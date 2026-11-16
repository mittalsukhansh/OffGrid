package com.example.offgrid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.offgrid.mesh.MeshConfig;
import com.example.offgrid.mesh.MeshNode;
import com.example.offgrid.mesh.protocol.MeshProtocol;
import com.example.offgrid.mesh.storage.MeshStorage;
import com.example.offgrid.mesh.storage.RoomMeshStorage;
import com.example.offgrid.mesh.transport.BleTransport;
import com.example.offgrid.mesh.transport.CombinedTransport;
import com.example.offgrid.mesh.transport.LanTransport;
import com.example.offgrid.mesh.transport.TransportMetrics;
import com.example.offgrid.mesh.transport.WifiDirectTransport;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/** Android lifecycle, foreground notification and binding adapter for the composed mesh node. */
public class MeshService extends Service {
    private static final String TAG = "OffGridMesh";
    private static final String CHANNEL_ID = "offgrid_mesh";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_START = "com.example.offgrid.action.START_MESH";
    public static final String ACTION_STOP = "com.example.offgrid.action.STOP_MESH";
    public static final UUID SERVICE_UUID = MeshConfig.SERVICE_UUID;
    public static final UUID MESSAGE_CHAR_UUID = MeshConfig.MESSAGE_CHAR_UUID;

    public interface MeshListener {
        void onMessageReceived(@NonNull MeshMessage message);

        void onPeerCountChanged(int count);

        void onMeshError(@NonNull String description);
    }

    public class LocalBinder extends Binder {
        public MeshService getService() {
            return MeshService.this;
        }
    }

    public enum SendStatus {
        ACCEPTED,
        INVALID_MESSAGE,
        RECIPIENT_KEY_UNKNOWN,
        ENCRYPTION_FAILED
    }

    public static final class SendResult {
        @NonNull public final SendStatus status;
        @Nullable public final MeshMessage message;

        SendResult(SendStatus status, MeshMessage message) {
            this.status = status;
            this.message = message;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Set<MeshListener> listeners = new CopyOnWriteArraySet<>();
    private HandlerThread thread;
    private Handler worker;
    private Handler main;
    private MeshNode node;
    private LanTransport wifi;
    private WifiDirectTransport direct;
    private BleTransport ble;
    private TransportMetrics measurements;
    private volatile boolean destroyed;
    private volatile int peerCount;
    private String statusText = "Starting…";

    public static void start(@NonNull Context context) {
        ContextCompat.startForegroundService(
                context, new Intent(context, MeshService.class).setAction(ACTION_START));
    }

    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, MeshService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("offgrid-mesh");
        thread.start();
        worker = new Handler(thread.getLooper());
        main = new Handler(Looper.getMainLooper());
        CryptoManager crypto = CryptoManager.getInstance(this);
        MeshStorage storage = new RoomMeshStorage(MessageStore.get(this), crypto.getLocalId());
        FloodGuard flood = new FloodGuard(SystemClock::elapsedRealtime);
        PrivateActions actions = new PrivateActions(this, MessageStore.get(this), crypto);
        MeshProtocol protocol = new MeshProtocol(crypto, storage, flood);
        protocol.extensions(actions);
        measurements = new TransportMetrics(SystemClock::elapsedRealtime);
        ble = new BleTransport(this, worker, flood, measurements);
        ble.setCocEnabled(cocEnabled(this));
        wifi = new LanTransport(this, worker, flood, measurements);
        wifi.enabled(wifiEnabled(this));
        direct = new WifiDirectTransport(this, worker, flood, measurements);
        direct.enabled(directEnabled(this));
        node =
                new MeshNode(
                        worker,
                        new CombinedTransport(ble, wifi, direct),
                        storage,
                        protocol,
                        actions,
                        new MeshNode.Events() {
                            @Override
                            public void onStatus(String status) {
                                main.post(() -> setStatus(status));
                            }

                            @Override
                            public void onPeerCount(int count) {
                                main.post(() -> {
                                    if (destroyed) return;
                                    peerCount = count;
                                    for (MeshListener listener : listeners)
                                        listener.onPeerCountChanged(count);
                                    updateNotification();
                                });
                            }

                            @Override
                            public void onReceived(MeshMessage message) {
                                boolean notify =
                                        !message.isMedia()
                                                || "READY"
                                                        .equals(
                                                                MessageStore.get(MeshService.this)
                                                                        .dao()
                                                                        .chat(
                                                                                message
                                                                                        .getMessageId())
                                                                        .status);
                                main.post(() -> {
                                    if (destroyed) return;
                                    if (notify)
                                        ChatNotifications.received(MeshService.this, message);
                                    for (MeshListener listener : listeners)
                                        listener.onMessageReceived(message);
                                });
                            }

                            @Override
                            public void onError(String error) {
                                Log.w(TAG, error);
                                main.post(() -> {
                                    for (MeshListener listener : listeners)
                                        listener.onMeshError(error);
                                });
                            }
                        });
        createNotificationChannel();
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
        worker.post(node::start);
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
        listeners.clear();
        node.shutdown();
        // Keep the worker alive briefly for WifiP2pManager group-removal/channel callbacks.
        worker.postDelayed(thread::quitSafely, 5500);
        super.onDestroy();
    }

    private boolean enterForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else startForeground(NOTIFICATION_ID, buildNotification());
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to enter foreground", e);
            return false;
        }
    }

    public void addListener(@NonNull MeshListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull MeshListener listener) {
        listeners.remove(listener);
    }

    public static boolean wifiEnabled(Context context) {
        return context.getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .getBoolean("wifi", false);
    }

    public void setWifiEnabled(boolean value) {
        getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .edit()
                .putBoolean("wifi", value)
                .apply();
        worker.post(() -> wifi.enabled(value));
    }

    public static boolean directEnabled(Context context) {
        return context.getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .getBoolean("direct", false);
    }

    public static boolean hasWifiTransportEnabled(Context context) {
        return wifiEnabled(context) || directEnabled(context);
    }

    public void setDirectEnabled(boolean enabled) {
        getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .edit()
                .putBoolean("direct", enabled)
                .apply();
        worker.post(() -> direct.enabled(enabled));
    }

    public void discoverDirectPeers() {
        worker.post(direct::discover);
    }

    public void connectDirectPeer(String address) {
        worker.post(() -> direct.connect(address));
    }

    public void disconnectDirectGroup() {
        worker.post(direct::disconnect);
    }

    public void directSnapshot(java.util.function.Consumer<WifiDirectTransport.Snapshot> callback) {
        worker.post(() -> {
            WifiDirectTransport.Snapshot snapshot = direct.snapshot();
            main.post(() -> {
                if (!destroyed) callback.accept(snapshot);
            });
        });
    }

    public static boolean cocEnabled(Context context) {
        return context.getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .getBoolean("coc", true);
    }

    public void setCocEnabled(boolean enabled) {
        getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .edit()
                .putBoolean("coc", enabled)
                .apply();
        worker.post(() -> ble.setCocEnabled(enabled));
    }

    public void resetMeasurements() {
        worker.post(measurements::reset);
    }

    public void diagnostics(java.util.function.Consumer<String> callback) {
        node.diagnostics(detail -> {
            String snapshot =
                    ble.diagnostics()
                            + "\nWi-Fi: "
                            + wifi.status()
                            + "\nWi-Fi discovered endpoints: "
                            + wifi.peerCount()
                            + "\n\nWi-Fi Direct (no access point): "
                            + direct.snapshot().status
                            + "\n\n"
                            + detail
                            + "\n\n"
                            + measurements.snapshot()
                            + "\n\n"
                            + "Endpoints are transport addresses, not verified contacts. One phone"
                            + " may appear on both transports. Forwarded means transferred to a"
                            + " peer; only an authenticated recipient acknowledgement means"
                            + " Delivered.\n\n"
                            + "Wi-Fi Direct forms a phone-to-phone group without a router. Pair in"
                            + " Network > Wi-Fi Direct. Same-LAN Wi-Fi remains an optional path and"
                            + " requires a shared network. Device labels are not verified contact"
                            + " identities. Media keeps its existing size limits; pacing follows"
                            + " the last authenticated direct-ingress hint and recipient range"
                            + " capability.";
            main.post(() -> {
                if (!destroyed) callback.accept(snapshot);
            });
        });
    }

    public int getPeerCount() {
        return peerCount;
    }

    public boolean isMeshActive() {
        return node.active();
    }

    @NonNull
    public String getLocalSenderId() {
        return node.localId();
    }

    @Nullable
    public MeshMessage sendMessage(@NonNull String text) {
        return node.sendPublic(text);
    }

    @NonNull
    public SendResult sendPrivateMessage(@NonNull String target, @NonNull String text) {
        if (destroyed) return new SendResult(SendStatus.INVALID_MESSAGE, null);
        MeshProtocol.Outgoing outgoing = node.sendPrivate(target, text);
        return new SendResult(SendStatus.valueOf(outgoing.status.name()), outgoing.display);
    }

    public void unsend(String id) {
        if (!destroyed) node.unsend(id);
    }

    public void sendMedia(String target, byte[] bytes, String mime) {
        if (!destroyed) node.sendMedia(target, bytes, mime);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID, "OffGrid mesh", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the offline mesh running in the background");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        int immutable = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent =
                launch != null ? PendingIntent.getActivity(this, 0, launch, immutable) : null;

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
