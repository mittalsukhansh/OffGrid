package com.example.offgrid.mesh.transport;

import static com.example.offgrid.mesh.MeshConfig.MAINTENANCE_MS;
import static com.example.offgrid.mesh.MeshConfig.MESSAGE_CHAR_UUID;
import static com.example.offgrid.mesh.MeshConfig.SERVICE_UUID;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
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
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.example.offgrid.FloodGuard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android BLE discovery/server adapter. Routing and message acceptance live outside this class. */
@SuppressLint("MissingPermission")
public final class BleTransport implements MeshTransport {
    private static final String TAG = "OffGridMesh";
    private static final long MAX_SCAN_BACKOFF_MS = 60_000;
    private static final long MAX_ADVERTISE_BACKOFF_MS = 60_000;
    private static final long MAINTENANCE_INTERVAL_MS = MAINTENANCE_MS;
    private final Context context;
    private final Handler meshHandler;
    private final FloodGuard floodGuard;
    private final FrameCodec codec = new FrameCodec();
    // Includes finished sessions awaiting delayed close, so stop() cannot leak GATT handles.
    private final Set<GattOutboundSession> liveSessions = new HashSet<>();
    private Listener listener;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private volatile BluetoothGattServer gattServer;
    private volatile boolean meshActive;
    private boolean requested,
            registered,
            serverReady,
            scanning,
            advertising,
            advertisingUnsupported;
    private Boolean reportedRadioState;
    private long scanBackoffMs, nextScanAttemptAt, advertiseBackoffMs, nextAdvertiseAttemptAt;
    private String lastProblem;

    public BleTransport(Context context, Handler worker, FloodGuard flood) {
        this.context = context.getApplicationContext();
        meshHandler = worker;
        floodGuard = flood;
    }

    public String diagnostics() {
        return "Bluetooth: "
                + (meshActive ? "active" : "unavailable")
                + "\nScanning: "
                + scanning
                + " · advertising: "
                + advertising
                + "\nGATT server ready: "
                + serverReady
                + (lastProblem == null ? "" : "\nBluetooth issue: " + lastProblem);
    }

    @Override
    public void start(Listener listener) {
        if (requested) return;
        this.listener = listener;
        requested = true;
        reportedRadioState = null;
        ContextCompat.registerReceiver(
                context,
                radioReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        startRadios();
    }

    @Override
    public void stop() {
        requested = false;
        stopRadios();
        if (registered) {
            try {
                context.unregisterReceiver(radioReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
    }

    private void radioState(boolean value) {
        meshActive = value;
        if (reportedRadioState == null || reportedRadioState != value) {
            reportedRadioState = value;
            listener.onRadioState(value);
        }
    }

    private void startRadios() {
        if (!requested || meshActive) return;
        if (!hasBlePermissions()) {
            reportError("Bluetooth permissions are missing");
            return;
        }
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null
                || !context.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            reportError("This device does not support Bluetooth Low Energy");
            return;
        }
        if (!adapter.isEnabled()) {
            radioState(false);
            return;
        }
        lastProblem = null;
        scanBackoffMs = nextScanAttemptAt = advertiseBackoffMs = nextAdvertiseAttemptAt = 0;
        advertisingUnsupported = !adapter.isMultipleAdvertisementSupported();
        // Set active before openGattServer's asynchronous callbacks, as in the original service.
        meshActive = true;
        if (isLocationRequiredAndOff())
            reportError("Turn on Location services for Bluetooth scanning");
        if (advertisingUnsupported)
            reportError("This device cannot advertise; running in scan-only mode");
        openGattServer();
        startScanning();
        radioState(true);
    }

    private void stopRadios() {
        meshActive = false;
        stopScanning();
        stopAdvertising();
        cancelSends();
        closeGattServer();
        codec.clear();
        if (listener != null) radioState(false);
    }

    @Override
    public void maintain() {
        if (!requested) return;
        codec.expire(SystemClock.elapsedRealtime());
        if (!meshActive) {
            startRadios();
            return;
        }
        if (serverReady && !advertising) startAdvertising();
        if (!scanning) startScanning();
    }

    @Override
    public void send(String peer, List<Packet> packets, SendListener listener) {
        if (!meshActive || adapter == null || !adapter.isEnabled()) {
            listener.onComplete("Bluetooth unavailable", false);
            return;
        }
        GattOutboundSession session =
                new GattOutboundSession(
                        context,
                        meshHandler,
                        adapter.getRemoteDevice(peer),
                        peer,
                        packets,
                        listener,
                        liveSessions::remove);
        liveSessions.add(session);
        session.start();
    }

    @Override
    public void cancelSends() {
        for (GattOutboundSession session : new ArrayList<>(liveSessions)) session.abort();
        liveSessions.clear();
    }

    private final BroadcastReceiver radioReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    int state =
                            intent.getIntExtra(
                                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    runOnMesh(() -> {
                        if (!requested) return;
                        if (state == BluetoothAdapter.STATE_ON) startRadios();
                        else if (state == BluetoothAdapter.STATE_OFF
                                || state == BluetoothAdapter.STATE_TURNING_OFF) stopRadios();
                    });
                }
            };

    private void openGattServer() {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        try {
            gattServer = manager.openGattServer(context, gattServerCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "openGattServer failed", e);
        }
        if (gattServer == null) {
            reportError("Could not open the Bluetooth GATT server");
            return;
        }
        BluetoothGattService service =
                new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(
                new BluetoothGattCharacteristic(
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
                public void onConnectionStateChange(
                        BluetoothDevice device, int status, int newState) {
                    if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        final String address = device.getAddress();
                        runOnMesh(() -> codec.disconnect(address)); // drop half-received data
                    }
                }

                @Override
                public void onCharacteristicWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattCharacteristic characteristic,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {
                    boolean valid =
                            MESSAGE_CHAR_UUID.equals(characteristic.getUuid())
                                    && !preparedWrite
                                    && offset == 0
                                    && value != null
                                    && value.length > 1
                                    && value.length <= 512
                                    && meshActive
                                    && floodGuard.allowFrame(device.getAddress());

                    BluetoothGattServer server = gattServer;
                    if (responseNeeded && server != null) {
                        try {
                            server.sendResponse(
                                    device,
                                    requestId,
                                    valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE,
                                    offset,
                                    null);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "sendResponse failed", e);
                        }
                    }
                    if (valid) {
                        final String address = device.getAddress();
                        final byte[] frame = value.clone();
                        meshHandler.post(() -> acceptFrame(address, frame));
                    }
                }
            };

    private void startAdvertising() {
        if (advertising || advertisingUnsupported || adapter == null || !serverReady) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextAdvertiseAttemptAt) return;

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            scheduleAdvertiseBackoff(now);
            return;
        }
        AdvertiseSettings settings =
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();
        // 16-byte UUID + headers fits easily in the 31-byte legacy payload.
        AdvertiseData data =
                new AdvertiseData.Builder()
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
        advertiseBackoffMs =
                Math.min(
                        MAX_ADVERTISE_BACKOFF_MS,
                        Math.max(MAINTENANCE_INTERVAL_MS, advertiseBackoffMs * 2));
        nextAdvertiseAttemptAt = now + advertiseBackoffMs;
    }

    private final AdvertiseCallback advertiseCallback =
            new AdvertiseCallback() {
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
                                reportError(
                                        "Bluetooth advertising is not supported on this device");
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
        ScanFilter filter =
                new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
        ScanSettings settings =
                new ScanSettings.Builder()
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
        scanBackoffMs =
                Math.min(MAX_SCAN_BACKOFF_MS, Math.max(MAINTENANCE_INTERVAL_MS, scanBackoffMs * 2));
        nextScanAttemptAt = now + scanBackoffMs;
    }

    private final ScanCallback scanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    final BluetoothDevice device = result.getDevice();
                    final int rssi = result.getRssi();
                    runOnMesh(() -> {
                        if (!meshActive) return;
                        scanBackoffMs = 0;
                        listener.onPeerSeen(device.getAddress(), rssi);
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

    private void acceptFrame(String peer, byte[] frame) {
        if (!meshActive) return;
        byte[] packet = codec.accept(peer, frame, SystemClock.elapsedRealtime());
        if (packet != null) listener.onPacket(peer, packet);
    }

    private void runOnMesh(Runnable task) {
        if (Looper.myLooper() == meshHandler.getLooper()) task.run();
        else meshHandler.post(task);
    }

    private void reportError(String error) {
        if (requested && !error.equals(lastProblem)) {
            lastProblem = error;
            listener.onError(error);
        }
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
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationRequiredAndOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false;
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return manager == null || !LocationManagerCompat.isLocationEnabled(manager);
    }
}
