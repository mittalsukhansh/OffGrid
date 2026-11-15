package com.example.offgrid;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Foreground service that runs the OffGrid BLE mesh.
 *
 * <h3>How the mesh works</h3>
 * BLE advertisements are limited to 31 bytes, far too small for a chat message, so
 * advertising is used for <em>discovery only</em> and messages travel over GATT:
 * <ol>
 *   <li>Every node advertises {@link #SERVICE_UUID} and hosts a GATT server with one writable
 *       characteristic ({@link #MESSAGE_CHAR_UUID}).</li>
 *   <li>Every node scans for that UUID to find peers.</li>
 *   <li>When a peer is nearby and we hold messages it has not received, we connect as a GATT
 *       client, write the messages (chunked to the negotiated MTU), and disconnect.</li>
 *   <li>The receiving node reassembles the frames, drops duplicates via {@link MessageCache},
 *       delivers the message to the UI, decrements the TTL and stores it for relaying to its
 *       own neighbours (store-and-forward, so it also reaches peers that arrive later).</li>
 * </ol>
 * To avoid broadcast storms in crowded venues relaying uses random jitter, a cap on concurrent
 * outbound connections, and a per-message fan-out limit.
 *
 * <h3>Private messages</h3>
 * Every node floods every packet, but a {@link MeshMessage#TYPE_PRIVATE} packet carries an
 * AES-GCM ciphertext that only its target can decrypt (see {@link CryptoManager}). Non-target
 * nodes relay it untouched and never display it. Nodes also announce their public key
 * periodically so others can encrypt to them.
 *
 * <h3>Usage</h3>
 * <pre>
 * // 1. After BLE permissions are granted (BlePermissionHelper):
 * MeshService.start(context);
 *
 * // 2. Bind from the Activity to send/receive:
 * bindService(new Intent(this, MeshService.class), conn, 0);
 * // in onServiceConnected: mesh = ((MeshService.LocalBinder) binder).getService();
 * //                        mesh.addListener(listener);
 * mesh.sendMessage("hello");
 *
 * // 3. Stop:
 * MeshService.stop(context);
 * </pre>
 *
 * <h3>Threading</h3>
 * All mesh state is confined to a single {@link HandlerThread}; BLE callbacks hop onto it.
 * {@link MeshListener} callbacks are delivered on the main thread.
 */
@SuppressLint("MissingPermission") // Permissions are verified in hasBlePermissions() before use.
public class MeshService extends Service {

    private static final String TAG = "OffGridMesh";

    // ------------------------------------------------------------------
    // Public constants / API
    // ------------------------------------------------------------------

    public static final String ACTION_START = "com.example.offgrid.action.START_MESH";
    public static final String ACTION_STOP = "com.example.offgrid.action.STOP_MESH";

    /** Advertised to identify OffGrid nodes. Change only if you also change it on all peers. */
    public static final UUID SERVICE_UUID =
            UUID.fromString("b8f1a2c4-6d3e-4f70-9a15-2e8c7d4b1f93");
    /** Writable characteristic that receives framed messages. */
    public static final UUID MESSAGE_CHAR_UUID =
            UUID.fromString("b8f1a2c5-6d3e-4f70-9a15-2e8c7d4b1f93");

    /** Receives mesh events on the main thread. */
    public interface MeshListener {
        /** A new (non-duplicate) message arrived from the mesh. */
        void onMessageReceived(@NonNull MeshMessage message);

        /** Number of OffGrid peers currently in range changed. */
        void onPeerCountChanged(int peerCount);

        /** A recoverable problem the UI may want to surface (e.g. Location is off). */
        void onMeshError(@NonNull String description);

    }

    public class LocalBinder extends Binder {
        public MeshService getService() {
            return MeshService.this;
        }
    }

    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, MeshService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, MeshService.class));
    }

    // ------------------------------------------------------------------
    // Tuning
    // ------------------------------------------------------------------

    private static final String CHANNEL_ID = "offgrid_mesh";
    private static final int NOTIFICATION_ID = 1001;

    private static final int MAX_CONCURRENT_OUTBOUND = 3;      // BLE stacks handle ~7 links total
    private static final int MAX_MESSAGES_PER_SESSION = 20;
    private static final int MAX_FORWARDS_PER_MESSAGE = 6;     // gossip fan-out per node
    private static final int RELAY_BUFFER_MAX = 200;
    private static final long RELAY_BUFFER_TTL_MS = 10 * 60_000L;
    private static final int MAX_PEERS = 200;
    private static final int MAX_REMEMBERED_IDS_PER_PEER = 500;
    private static final long PEER_TIMEOUT_MS = 30_000L;       // forget peers unseen this long
    private static final long PEER_FRESH_MS = 10_000L;         // only connect to recently seen peers
    private static final long MAINTENANCE_INTERVAL_MS = 5_000L;
    private static final long SESSION_STEP_TIMEOUT_MS = 12_000L;
    private static final long INCOMPATIBLE_BACKOFF_MS = 5 * 60_000L;
    private static final int PREFERRED_MTU = 247;
    private static final int RELAY_JITTER_MIN_MS = 50;
    private static final int RELAY_JITTER_MAX_MS = 400;
    private static final int MAX_INBOUND_BUFFERS = 16;
    private static final long MAX_SCAN_BACKOFF_MS = 60_000L;
    private static final long MAX_ADVERTISE_BACKOFF_MS = 60_000L;
    /** Public-key announcements are low priority and flood the whole mesh: keep them rare. */
    private static final long KEY_ANNOUNCE_INTERVAL_MS = 10 * 60_000L;
    private static final int KEY_ANNOUNCE_TTL = 4;

    // Framing: [flags:1][payload...]. One message may span several frames.
    private static final int ATT_OVERHEAD = 3;
    private static final int FRAME_FIRST = 0x01;
    private static final int FRAME_LAST = 0x02;

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final IBinder binder = new LocalBinder();
    private final Set<MeshListener> listeners = new CopyOnWriteArraySet<>();
    private final MessageCache seenCache = new MessageCache();
    private final Random random = new Random();

    private HandlerThread meshThread;
    private Handler meshHandler;
    private Handler mainHandler;

    private String localSenderId;
    private CryptoManager crypto;
    private MessageStore store;
    private boolean storageReady;
    private long lastKeyAnnounceMs;   // mesh thread only
    private volatile int peerCount;
    private volatile boolean meshActive;
    private volatile boolean destroyed;
    private volatile String statusText = "Starting…";

    // ---- Everything below is touched ONLY on the mesh thread (except gattServer reads) ----
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private volatile BluetoothGattServer gattServer;

    private boolean serverReady;
    private boolean scanning;
    private boolean advertising;
    private boolean advertisingUnsupported;
    private long scanBackoffMs;
    private long nextScanAttemptAt;
    private long advertiseBackoffMs;
    private long nextAdvertiseAttemptAt;
    private boolean connectPassScheduled;

    private final Map<String, Peer> peers = new HashMap<>();
    private final Map<String, OutboundSession> sessions = new HashMap<>();
    private final Map<String, ByteArrayOutputStream> inboundBuffers = new HashMap<>();
    private final LinkedHashMap<String, BufferedMessage> relayBuffer = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        meshThread = new HandlerThread("offgrid-mesh");
        meshThread.start();
        meshHandler = new Handler(meshThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        crypto = CryptoManager.getInstance(this);   // first run generates the identity key
        localSenderId = crypto.getLocalId();
        store = MessageStore.get(this);
        meshHandler.post(this::restorePending);

        createNotificationChannel();
        ContextCompat.registerReceiver(this, bluetoothStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        meshHandler.post(this::startMesh);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        meshHandler.post(() -> {
            stopMesh();
            relayBuffer.clear();
            seenCache.clear();
        });
        meshThread.quitSafely();
        listeners.clear();
        super.onDestroy();
    }

    private boolean enterForeground() {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            // SecurityException: Nearby-devices permissions missing (Android 14+).
            // ForegroundServiceStartNotAllowedException: started from a restricted background state.
            Log.e(TAG, "Unable to enter foreground; request BLE permissions before starting", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Public API (callable from any thread)
    // ------------------------------------------------------------------

    public void addListener(@NonNull MeshListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull MeshListener listener) {
        listeners.remove(listener);
    }

    public int getPeerCount() {
        return peerCount;
    }

    public boolean isMeshActive() {
        return meshActive;
    }

    @NonNull
    public String getLocalSenderId() {
        return localSenderId;
    }

    /**
     * Queues a new message for the mesh. The message is stored and delivered to peers as they
     * come into range (also later, if nobody is nearby right now).
     *
     * @return the created message (so the UI can show it immediately), or null if the text is
     *         empty or too long
     */
    @Nullable
    public MeshMessage sendMessage(@NonNull String text) {
        final MeshMessage message;
        try {
            message = MeshMessage.create(localSenderId, text, MeshMessage.DEFAULT_TTL);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Rejected outgoing message: " + e.getMessage());
            return null;
        }
        meshHandler.post(() -> {
            if (persistAndBuffer(message, message, true, message)) {
                seenCache.markSeen(message.getMessageId());
                scheduleConnectPass(nextJitterMs());
            }
        });
        return message;
    }

    public enum SendStatus {
        SENT,
        /** Bad recipient ID, sending to yourself, or empty/too long text. */
        INVALID_MESSAGE,
        /** We do not have the recipient's public key yet. */
        RECIPIENT_KEY_UNKNOWN,
        ENCRYPTION_FAILED
    }

    public static final class SendResult {
        @NonNull public final SendStatus status;
        /** Plaintext view of the sent message for the local UI; non-null only when SENT. */
        @Nullable public final MeshMessage message;

        SendResult(@NonNull SendStatus status, @Nullable MeshMessage message) {
            this.status = status;
            this.message = message;
        }
    }

    /**
     * Encrypts {@code text} for {@code targetId} and floods it through the mesh. Every node
     * relays the packet, but only the target can read it.
     */
    @NonNull
    public SendResult sendPrivateMessage(@NonNull String targetId, @NonNull String text) {
        if (!MeshMessage.isValidTargetId(targetId) || targetId.equals(localSenderId)
                || text.trim().isEmpty() || text.length() > MeshMessage.MAX_TEXT_LENGTH) {
            return new SendResult(SendStatus.INVALID_MESSAGE, null);
        }
        if (!crypto.hasKeyFor(targetId)) {
            return new SendResult(SendStatus.RECIPIENT_KEY_UNKNOWN, null);
        }
        try {
            String messageId = UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            String ciphertext = crypto.encrypt(messageId, targetId, timestamp, text);
            final MeshMessage wire = MeshMessage.createPrivate(messageId, localSenderId, targetId,
                    ciphertext, crypto.getPublicKeyBase64(), MeshMessage.DEFAULT_TTL, timestamp);
            final MeshMessage view = wire.withPlaintext(text);
            meshHandler.post(() -> {
                if (persistAndBuffer(wire, view, true, wire)) {
                    seenCache.markSeen(wire.getMessageId());
                    scheduleConnectPass(nextJitterMs());
                }
            });
            return new SendResult(SendStatus.SENT, view);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Rejected private message: " + e.getMessage());
            return new SendResult(SendStatus.INVALID_MESSAGE, null);
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "Encryption failed", e);
            return new SendResult(SendStatus.ENCRYPTION_FAILED, null);
        }
    }

    // ------------------------------------------------------------------
    // Mesh start / stop (mesh thread)
    // ------------------------------------------------------------------

    private void startMesh() {
        if (meshActive || destroyed) return;
        if (!storageReady) {
            reportError("Message storage unavailable; mesh was not started");
            return;
        }

        if (!hasBlePermissions()) {
            setStatus("Bluetooth permissions required");
            reportError("Bluetooth permissions are missing");
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            setStatus("BLE not supported");
            reportError("This device does not support Bluetooth Low Energy");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Waiting for Bluetooth");
            return; // bluetoothStateReceiver restarts us when Bluetooth turns on
        }

        meshActive = true;
        scanBackoffMs = 0;
        nextScanAttemptAt = 0;
        advertiseBackoffMs = 0;
        nextAdvertiseAttemptAt = 0;

        if (isLocationRequiredAndOff()) {
            reportError("Turn on Location services; Android needs it for Bluetooth scanning");
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            advertisingUnsupported = true;
            reportError("This device cannot advertise; running in scan-only mode");
        }

        openGattServer();
        startScanning();
        meshHandler.postDelayed(maintenanceRunnable, MAINTENANCE_INTERVAL_MS);
        announceKey();
        setStatus("Mesh active");
        Log.i(TAG, "Mesh started");
    }

    private void stopMesh() {
        meshActive = false;
        meshHandler.removeCallbacks(maintenanceRunnable);
        meshHandler.removeCallbacks(connectPassRunnable);
        connectPassScheduled = false;

        stopScanning();
        stopAdvertising();
        for (OutboundSession session : new ArrayList<>(sessions.values())) {
            session.abort();
        }
        sessions.clear();
        closeGattServer();
        inboundBuffers.clear();
        peers.clear();
        updatePeerCount();
        Log.i(TAG, "Mesh stopped");
    }

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                meshHandler.post(MeshService.this::startMesh);
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF
                    || state == BluetoothAdapter.STATE_OFF) {
                meshHandler.post(() -> {
                    stopMesh();
                    setStatus("Waiting for Bluetooth");
                });
            }
        }
    };

    private final Runnable maintenanceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!meshActive) return;
            long now = SystemClock.elapsedRealtime();

            // Forget peers that left (but never one we are mid-session with).
            boolean peersChanged = false;
            Iterator<Map.Entry<String, Peer>> pit = peers.entrySet().iterator();
            while (pit.hasNext()) {
                Map.Entry<String, Peer> entry = pit.next();
                if (now - entry.getValue().lastSeenMs > PEER_TIMEOUT_MS
                        && !sessions.containsKey(entry.getKey())) {
                    pit.remove();
                    peersChanged = true;
                }
            }
            if (peersChanged) updatePeerCount();

            // Expire old relay entries and seen-ids.
            Iterator<BufferedMessage> bit = relayBuffer.values().iterator();
            while (bit.hasNext()) {
                BufferedMessage buffered = bit.next();
                if (now - buffered.storedAtMs >= RELAY_BUFFER_TTL_MS) {
                    try {
                        store.removePending(buffered.message.getMessageId(), "EXPIRED");
                        bit.remove();
                    } catch (RuntimeException e) {
                        reportError("Could not expire a stored message");
                    }
                }
            }
            seenCache.purgeExpired();
            if (now - lastKeyAnnounceMs >= KEY_ANNOUNCE_INTERVAL_MS) announceKey();

            // Self-healing: restart radios that failed or were silently stopped.
            if (serverReady && !advertising) startAdvertising();
            if (!scanning) startScanning();

            // Retry peers whose back-off elapsed.
            scheduleConnectPass(0);

            meshHandler.postDelayed(this, MAINTENANCE_INTERVAL_MS);
        }
    };

    // ------------------------------------------------------------------
    // GATT server (receiving)
    // ------------------------------------------------------------------

    private void openGattServer() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        try {
            gattServer = manager.openGattServer(this, gattServerCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "openGattServer failed", e);
        }
        if (gattServer == null) {
            reportError("Could not open the Bluetooth GATT server");
            return;
        }
        BluetoothGattService service = new BluetoothGattService(
                SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(
                MESSAGE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE));
        if (!gattServer.addService(service)) {
            reportError("Could not register the mesh GATT service");
        }
        // Advertising starts from onServiceAdded().
    }

    private void closeGattServer() {
        BluetoothGattServer server = gattServer;
        gattServer = null;
        serverReady = false;
        if (server != null) {
            try {
                server.clearServices();
                server.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Error closing GATT server", e);
            }
        }
    }

    private final BluetoothGattServerCallback gattServerCallback =
            new BluetoothGattServerCallback() {

                @Override
                public void onServiceAdded(int status, BluetoothGattService service) {
                    runOnMesh(() -> {
                        if (!meshActive) return;
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            serverReady = true;
                            startAdvertising();
                        } else {
                            reportError("GATT service registration failed (status " + status + ")");
                        }
                    });
                }

                @Override
                public void onConnectionStateChange(BluetoothDevice device, int status,
                                                    int newState) {
                    if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        final String address = device.getAddress();
                        runOnMesh(() -> inboundBuffers.remove(address)); // drop half-received data
                    }
                }

                @Override
                public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                         BluetoothGattCharacteristic characteristic,
                                                         boolean preparedWrite,
                                                         boolean responseNeeded, int offset,
                                                         byte[] value) {
                    boolean valid = MESSAGE_CHAR_UUID.equals(characteristic.getUuid())
                            && !preparedWrite
                            && offset == 0
                            && value != null
                            && value.length > 1;

                    BluetoothGattServer server = gattServer;
                    if (responseNeeded && server != null) {
                        try {
                            server.sendResponse(device, requestId,
                                    valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE,
                                    offset, null);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "sendResponse failed", e);
                        }
                    }
                    if (valid) {
                        final String address = device.getAddress();
                        final byte[] frame = value.clone();
                        meshHandler.post(() -> handleInboundFrame(address, frame));
                    }
                }
            };

    /** Reassembles framed writes into complete messages. */
    private void handleInboundFrame(String address, byte[] frame) {
        if (!meshActive) return;

        int flags = frame[0] & 0xFF;
        boolean first = (flags & FRAME_FIRST) != 0;
        boolean last = (flags & FRAME_LAST) != 0;
        int payloadLength = frame.length - 1;

        ByteArrayOutputStream buffer;
        if (first) {
            if (!inboundBuffers.containsKey(address)
                    && inboundBuffers.size() >= MAX_INBOUND_BUFFERS) {
                Log.w(TAG, "Too many concurrent inbound transfers; dropping");
                return;
            }
            buffer = new ByteArrayOutputStream(Math.max(64, payloadLength));
            inboundBuffers.put(address, buffer);
        } else {
            buffer = inboundBuffers.get(address);
            if (buffer == null) return; // continuation without a start frame
        }

        if (buffer.size() + payloadLength > MeshMessage.MAX_SERIALIZED_BYTES) {
            inboundBuffers.remove(address);
            Log.w(TAG, "Inbound message too large; dropped");
            return;
        }
        buffer.write(frame, 1, payloadLength);

        if (last) {
            inboundBuffers.remove(address);
            handleInboundMessage(address, buffer.toByteArray());
        }
    }

    private void handleInboundMessage(String sourceAddress, byte[] bytes) {
        final MeshMessage incoming;
        try {
            incoming = MeshMessage.fromBytes(bytes);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Dropped malformed message: " + e.getMessage());
            return;
        }

        if (seenCache.hasSeen(incoming.getMessageId())
                || incoming.getSenderId().equals(localSenderId)) return;
        try {
            if (store.dao().containsChat(incoming.getMessageId()) > 0
                    || store.dao().containsPending(incoming.getMessageId()) > 0) return;
            MeshMessage display = null;
            switch (incoming.getType()) {
                case MeshMessage.TYPE_PUBLIC:
                    display = incoming;
                    break;
                case MeshMessage.TYPE_KEY_ANNOUNCE:
                    if (!crypto.registerAnnouncedKey(incoming.getSenderId(), incoming.getText())) return;
                    break;
                case MeshMessage.TYPE_PRIVATE:
                    if (localSenderId.equals(incoming.getTargetId())) {
                        display = incoming.withPlaintext(crypto.decrypt(incoming));
                    }
                    break;
                default: return;
            }
            MeshMessage outgoing = incoming.withDecrementedTtl();
            if (!persistAndBuffer(incoming, display, false,
                    outgoing.isRelayable() ? outgoing : null)) return;
            seenCache.markSeen(incoming.getMessageId());
            Peer source = peers.get(sourceAddress);
            if (source != null) source.remember(incoming.getMessageId());
            if (display != null) dispatchReceived(display);
            if (outgoing.isRelayable()) scheduleConnectPass(nextJitterMs());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Dropped invalid or undecryptable packet", e);
        } catch (RuntimeException e) {
            reportError("Could not read message storage; packet was not accepted");
        }
    }

    /** Floods our public key so peers can send us private messages. */
    private void announceKey() {
        lastKeyAnnounceMs = SystemClock.elapsedRealtime();
        MeshMessage announce = MeshMessage.createKeyAnnounce(
                localSenderId, crypto.getPublicKeyBase64(), KEY_ANNOUNCE_TTL);
        if (persistAndBuffer(announce, null, false, announce)) {
            seenCache.markSeen(announce.getMessageId());
            scheduleConnectPass(nextJitterMs());
        }
    }

    // ------------------------------------------------------------------
    // Advertising
    // ------------------------------------------------------------------

    private void startAdvertising() {
        if (advertising || advertisingUnsupported || adapter == null || !serverReady) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextAdvertiseAttemptAt) return;

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            scheduleAdvertiseBackoff(now);
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        // 16-byte UUID + headers fits easily in the 31-byte legacy payload.
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        try {
            advertising = true; // optimistic; reverted in onStartFailure
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (RuntimeException e) {
            advertising = false;
            Log.w(TAG, "startAdvertising threw", e);
            scheduleAdvertiseBackoff(now);
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "stopAdvertising failed", e);
            }
        }
        advertising = false;
    }

    private void scheduleAdvertiseBackoff(long now) {
        advertiseBackoffMs = Math.min(MAX_ADVERTISE_BACKOFF_MS,
                Math.max(MAINTENANCE_INTERVAL_MS, advertiseBackoffMs * 2));
        nextAdvertiseAttemptAt = now + advertiseBackoffMs;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            runOnMesh(() -> {
                advertiseBackoffMs = 0;
                Log.i(TAG, "Advertising started");
            });
        }

        @Override
        public void onStartFailure(int errorCode) {
            runOnMesh(() -> {
                switch (errorCode) {
                    case ADVERTISE_FAILED_ALREADY_STARTED:
                        advertising = true;
                        return;
                    case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                        advertising = false;
                        advertisingUnsupported = true;
                        reportError("Bluetooth advertising is not supported on this device");
                        return;
                    default: // TOO_MANY_ADVERTISERS, INTERNAL_ERROR, DATA_TOO_LARGE
                        advertising = false;
                        scheduleAdvertiseBackoff(SystemClock.elapsedRealtime());
                        Log.w(TAG, "Advertising failed, code " + errorCode);
                }
            });
        }
    };

    // ------------------------------------------------------------------
    // Scanning / peer discovery
    // ------------------------------------------------------------------

    private void startScanning() {
        if (scanning || adapter == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextScanAttemptAt) return;

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;

        // A service-UUID filter is mandatory for scanning with the screen off.
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "startScan threw", e);
            scheduleScanBackoff(now);
        }
    }

    private void stopScanning() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "stopScan failed", e);
            }
        }
        scanning = false;
    }

    private void scheduleScanBackoff(long now) {
        scanBackoffMs = Math.min(MAX_SCAN_BACKOFF_MS,
                Math.max(MAINTENANCE_INTERVAL_MS, scanBackoffMs * 2));
        nextScanAttemptAt = now + scanBackoffMs;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            final BluetoothDevice device = result.getDevice();
            final int rssi = result.getRssi();
            runOnMesh(() -> {
                if (!meshActive) return;
                scanBackoffMs = 0;
                onPeerSeen(device, rssi);
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnMesh(() -> {
                scanning = false;
                scheduleScanBackoff(SystemClock.elapsedRealtime());
                reportError("Bluetooth scan failed (code " + errorCode + "); retrying");
            });
        }
    };

    private void onPeerSeen(BluetoothDevice device, int rssi) {
        String address = device.getAddress();
        long now = SystemClock.elapsedRealtime();

        Peer peer = peers.get(address);
        if (peer == null) {
            if (peers.size() >= MAX_PEERS) evictStalestPeer();
            peer = new Peer(device);
            peers.put(address, peer);
            updatePeerCount();
        }
        peer.device = device;
        peer.lastSeenMs = now;
        peer.rssi = rssi;

        if (!relayBuffer.isEmpty() && !sessions.containsKey(address)) {
            scheduleConnectPass(0);
        }
    }

    private void evictStalestPeer() {
        String stalest = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Peer> e : peers.entrySet()) {
            if (sessions.containsKey(e.getKey())) continue;
            if (e.getValue().lastSeenMs < oldest) {
                oldest = e.getValue().lastSeenMs;
                stalest = e.getKey();
            }
        }
        if (stalest != null) peers.remove(stalest);
    }

    private void updatePeerCount() {
        int count = peers.size();
        if (count == peerCount) return;
        peerCount = count;
        mainHandler.post(() -> {
            for (MeshListener l : listeners) l.onPeerCountChanged(count);
        });
        updateNotification();
    }

    // ------------------------------------------------------------------
    // Relay buffer + outbound connection scheduling
    // ------------------------------------------------------------------

    private void restorePending() {
        try {
            long wallNow = System.currentTimeMillis();
            long elapsedNow = SystemClock.elapsedRealtime();
            store.expire(wallNow);
            for (PendingRecord row : store.dao().pending()) {
                MeshMessage message = store.decodePending(row);
                long remaining = Math.min(RELAY_BUFFER_TTL_MS, row.expiresAtMs - wallNow);
                if (remaining <= 0) continue;
                BufferedMessage buffered = new BufferedMessage(message,
                        elapsedNow - (RELAY_BUFFER_TTL_MS - remaining));
                buffered.forwardCount = row.forwardCount;
                relayBuffer.put(message.getMessageId(), buffered);
                seenCache.markSeen(message.getMessageId());
            }
            trimRelayBuffer();
            storageReady = true;
        } catch (Exception e) {
            Log.e(TAG, "Could not restore pending messages", e);
            reportError("Saved messages could not be restored; original data was kept");
        }
    }

    private boolean persistAndBuffer(MeshMessage wire, @Nullable MeshMessage display,
                                     boolean outgoing, @Nullable MeshMessage relay) {
        if (!storageReady) {
            reportError("Message storage unavailable; message was not queued");
            if (outgoing) store.outgoingFailed(wire.getMessageId());
            return false;
        }
        try {
            long now = System.currentTimeMillis();
            store.save(wire, display, outgoing, outgoing ? wire.getTimestamp() : now,
                    relay, now, now + RELAY_BUFFER_TTL_MS);
            if (relay != null) {
                relayBuffer.put(relay.getMessageId(),
                        new BufferedMessage(relay, SystemClock.elapsedRealtime()));
                trimRelayBuffer();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not persist message", e);
            reportError("Could not update saved messages; check message status before retrying");
            if (outgoing) store.outgoingFailed(wire.getMessageId());
            return false;
        }
    }

    private void trimRelayBuffer() {
        // When full, evict a key announcement first (cheap to resend), else the oldest entry.
        while (relayBuffer.size() > RELAY_BUFFER_MAX) {
            String victim = null;
            for (Map.Entry<String, BufferedMessage> e : relayBuffer.entrySet()) {
                if (victim == null) victim = e.getKey();
                if (e.getValue().message.isKeyAnnounce()) {
                    victim = e.getKey();
                    break;
                }
            }
            if (victim == null) break;
            store.removePending(victim, "EVICTED");
            relayBuffer.remove(victim);
        }
    }

    private List<MeshMessage> collectPending(Peer peer, int limit) {
        List<MeshMessage> out = new ArrayList<>();
        for (BufferedMessage b : relayBuffer.values()) {
            if (out.size() >= limit) break;
            if (SystemClock.elapsedRealtime() - b.storedAtMs >= RELAY_BUFFER_TTL_MS) continue;
            if (b.forwardCount >= MAX_FORWARDS_PER_MESSAGE) continue;
            if (peer.deliveredIds.contains(b.message.getMessageId())) continue;
            out.add(b.message);
        }
        return out;
    }

    private void scheduleConnectPass(long delayMs) {
        if (!meshActive || connectPassScheduled) return;
        connectPassScheduled = true;
        meshHandler.postDelayed(connectPassRunnable, delayMs);
    }

    private final Runnable connectPassRunnable = this::runConnectPass;

    private void runConnectPass() {
        connectPassScheduled = false;
        if (!meshActive || adapter == null || !adapter.isEnabled()) return;
        if (relayBuffer.isEmpty() || sessions.size() >= MAX_CONCURRENT_OUTBOUND) return;

        long now = SystemClock.elapsedRealtime();
        List<Peer> candidates = new ArrayList<>();
        for (Map.Entry<String, Peer> entry : peers.entrySet()) {
            Peer p = entry.getValue();
            if (sessions.containsKey(entry.getKey())) continue;
            if (now - p.lastSeenMs > PEER_FRESH_MS) continue;
            if (now < p.backoffUntilMs) continue;
            if (collectPending(p, 1).isEmpty()) continue;
            candidates.add(p);
        }
        // Strongest signal first: closest peers give the most reliable links.
        Collections.sort(candidates, (a, b) -> Integer.compare(b.rssi, a.rssi));

        for (Peer p : candidates) {
            if (sessions.size() >= MAX_CONCURRENT_OUTBOUND) break;
            List<MeshMessage> batch = collectPending(p, MAX_MESSAGES_PER_SESSION);
            if (batch.isEmpty()) continue;
            OutboundSession session = new OutboundSession(p, batch);
            sessions.put(p.device.getAddress(), session);
            session.start();
        }
    }

    /**
     * One GATT client connection to a peer: connect, negotiate MTU, discover the mesh service,
     * write each pending message as a sequence of frames, then disconnect.
     */
    private final class OutboundSession extends BluetoothGattCallback {

        private final Peer peer;
        private final List<MeshMessage> batch;
        private final String address;

        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic characteristic;
        private int mtu = 23;
        private int messageIndex;
        private int delivered;
        private List<byte[]> frames;
        private int frameIndex;
        private boolean finished;
        private boolean incompatible;

        private final Runnable timeoutRunnable = () -> finish("timeout");
        private final Runnable closeRunnable = this::closeGattNow;

        OutboundSession(Peer peer, List<MeshMessage> batch) {
            this.peer = peer;
            this.batch = batch;
            this.address = peer.device.getAddress();
        }

        void start() {
            armTimeout();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    gatt = peer.device.connectGatt(MeshService.this, false, this,
                            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK,
                            meshHandler);
                } else {
                    gatt = peer.device.connectGatt(MeshService.this, false, this,
                            BluetoothDevice.TRANSPORT_LE);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "connectGatt threw", e);
            }
            if (gatt == null) finish("connectGatt failed");
        }

        /** Tear down without bookkeeping (used when the whole mesh stops). */
        void abort() {
            finished = true;
            meshHandler.removeCallbacks(timeoutRunnable);
            closeGattNow();
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            runOnMesh(() -> {
                if (gatt == null) gatt = g;
                if (finished) {
                    if (newState == BluetoothGatt.STATE_DISCONNECTED) closeGattNow();
                    return;
                }
                if (newState == BluetoothGatt.STATE_CONNECTED
                        && status == BluetoothGatt.GATT_SUCCESS) {
                    armTimeout();
                    if (!g.requestMtu(PREFERRED_MTU)) discoverServices(g);
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED
                        || status != BluetoothGatt.GATT_SUCCESS) {
                    finish(status != BluetoothGatt.GATT_SUCCESS
                            ? "gatt status " + status : "disconnected");
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            runOnMesh(() -> {
                if (finished) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
                armTimeout();
                discoverServices(g);
            });
        }

        private void discoverServices(BluetoothGatt g) {
            if (!g.discoverServices()) finish("discoverServices failed");
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            runOnMesh(() -> {
                if (finished) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("service discovery failed: " + status);
                    return;
                }
                BluetoothGattService service = g.getService(SERVICE_UUID);
                characteristic = service != null
                        ? service.getCharacteristic(MESSAGE_CHAR_UUID) : null;
                if (characteristic == null) {
                    incompatible = true;
                    finish("peer has no OffGrid service");
                    return;
                }
                startNextMessage();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c,
                                          int status) {
            runOnMesh(() -> {
                if (finished) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("write failed: " + status);
                    return;
                }
                frameIndex++;
                if (frameIndex < frames.size()) {
                    writeCurrentFrame();
                } else {
                    onMessageDelivered(batch.get(messageIndex));
                    messageIndex++;
                    startNextMessage();
                }
            });
        }

        private void startNextMessage() {
            if (messageIndex >= batch.size()) {
                finish(null);
                return;
            }
            frames = buildFrames(batch.get(messageIndex).toBytes(), mtu);
            frameIndex = 0;
            writeCurrentFrame();
        }

        private void writeCurrentFrame() {
            armTimeout();
            if (!writeFrame(frames.get(frameIndex))) finish("write rejected");
        }

        @SuppressWarnings("deprecation")
        private boolean writeFrame(byte[] frame) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return gatt.writeCharacteristic(characteristic, frame,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                            == BluetoothStatusCodes.SUCCESS;
                }
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(frame);
                return gatt.writeCharacteristic(characteristic);
            } catch (RuntimeException e) {
                Log.w(TAG, "writeCharacteristic threw", e);
                return false;
            }
        }

        private void onMessageDelivered(MeshMessage message) {
            delivered++;
            peer.remember(message.getMessageId());
            BufferedMessage buffered = relayBuffer.get(message.getMessageId());
            if (buffered != null) {
                try {
                    store.forwarded(message.getMessageId(), buffered.forwardCount + 1);
                    buffered.forwardCount++;
                } catch (RuntimeException e) {
                    reportError("Could not save forwarding progress");
                }
            }
        }

        private void armTimeout() {
            meshHandler.removeCallbacks(timeoutRunnable);
            meshHandler.postDelayed(timeoutRunnable, SESSION_STEP_TIMEOUT_MS);
        }

        /** @param failure null on success, otherwise a short reason. */
        private void finish(@Nullable String failure) {
            if (finished) return;
            finished = true;
            meshHandler.removeCallbacks(timeoutRunnable);
            if (sessions.get(address) == this) sessions.remove(address);

            long now = SystemClock.elapsedRealtime();
            if (failure == null || delivered > 0) {
                peer.failures = 0;
                peer.backoffUntilMs = 0;
            } else if (incompatible) {
                peer.backoffUntilMs = now + INCOMPATIBLE_BACKOFF_MS;
            } else {
                peer.failures++;
                peer.backoffUntilMs = now
                        + Math.min(60_000L, 2_000L << Math.min(peer.failures, 5));
            }
            Log.d(TAG, "Session with " + address + " ended: "
                    + (failure == null ? "ok" : failure) + ", delivered=" + delivered);

            // Graceful close: disconnect now, release the client after the callback or 500 ms.
            BluetoothGatt g = gatt;
            if (g != null) {
                try {
                    g.disconnect();
                } catch (RuntimeException e) {
                    Log.w(TAG, "disconnect failed", e);
                }
                meshHandler.postDelayed(closeRunnable, 500);
            }
            scheduleConnectPass(100); // a connection slot just freed up
        }

        private void closeGattNow() {
            meshHandler.removeCallbacks(closeRunnable);
            BluetoothGatt g = gatt;
            gatt = null;
            if (g != null) {
                try {
                    g.disconnect();
                } catch (RuntimeException ignored) {
                    // already disconnected
                }
                try {
                    g.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "close failed", e);
                }
            }
        }
    }

    private static List<byte[]> buildFrames(byte[] data, int mtu) {
        int chunk = Math.max(1, mtu - ATT_OVERHEAD - 1);
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < data.length; offset += chunk) {
            int length = Math.min(chunk, data.length - offset);
            int flags = 0;
            if (offset == 0) flags |= FRAME_FIRST;
            if (offset + length >= data.length) flags |= FRAME_LAST;
            byte[] frame = new byte[length + 1];
            frame[0] = (byte) flags;
            System.arraycopy(data, offset, frame, 1, length);
            frames.add(frame);
        }
        return frames;
    }

    // ------------------------------------------------------------------
    // Model classes
    // ------------------------------------------------------------------

    private static final class Peer {
        BluetoothDevice device;
        long lastSeenMs;
        int rssi;
        int failures;
        long backoffUntilMs;
        /** Ids we already sent to (or received from) this peer; bounded, oldest evicted first. */
        final LinkedHashSet<String> deliveredIds = new LinkedHashSet<>();

        Peer(BluetoothDevice device) {
            this.device = device;
        }

        void remember(String messageId) {
            deliveredIds.add(messageId);
            if (deliveredIds.size() > MAX_REMEMBERED_IDS_PER_PEER) {
                Iterator<String> it = deliveredIds.iterator();
                it.next();
                it.remove();
            }
        }
    }

    private static final class BufferedMessage {
        final MeshMessage message; // already in outgoing form (ttl = value to send)
        final long storedAtMs;
        int forwardCount;

        BufferedMessage(MeshMessage message, long storedAtMs) {
            this.message = message;
            this.storedAtMs = storedAtMs;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void runOnMesh(Runnable r) {
        if (Looper.myLooper() == meshHandler.getLooper()) {
            r.run();
        } else {
            meshHandler.post(r);
        }
    }

    private int nextJitterMs() {
        return RELAY_JITTER_MIN_MS
                + random.nextInt(RELAY_JITTER_MAX_MS - RELAY_JITTER_MIN_MS + 1);
    }

    private void dispatchReceived(MeshMessage message) {
        mainHandler.post(() -> {
            for (MeshListener l : listeners) l.onMessageReceived(message);
        });
    }

    private void reportError(String description) {
        Log.w(TAG, description);
        mainHandler.post(() -> {
            for (MeshListener l : listeners) l.onMeshError(description);
        });
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return granted(Manifest.permission.BLUETOOTH_SCAN)
                    && granted(Manifest.permission.BLUETOOTH_ADVERTISE)
                    && granted(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 6-11 returns no scan results while the Location toggle is off. */
    private boolean isLocationRequiredAndOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm == null || !LocationManagerCompat.isLocationEnabled(lm);
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "OffGrid mesh", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the offline mesh running in the background");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        int immutable = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = launch != null
                ? PendingIntent.getActivity(this, 0, launch, immutable) : null;

        Intent stopIntent = new Intent(this, MeshService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent, immutable);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // replace with your icon
                .setContentTitle("OffGrid mesh")
                .setContentText(statusText + " · " + peerCount + " nearby")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, "Stop", stopPending)
                .build();
    }

    private void setStatus(String status) {
        statusText = status;
        updateNotification();
    }

    private void updateNotification() {
        if (destroyed) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }
}
