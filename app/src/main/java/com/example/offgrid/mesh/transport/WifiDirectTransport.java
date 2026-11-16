package com.example.offgrid.mesh.transport;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.*;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.example.offgrid.FloodGuard;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * AP-free DNS-SD peer discovery and explicit Android Wi-Fi Direct group pairing. Worker-confined.
 */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
public final class WifiDirectTransport implements MeshTransport {
    private static final String INSTANCE = "OffGrid", TYPE = "_offgrid._tcp";
    private final Context context;
    private final Handler worker;
    private final WifiP2pManager manager;
    private final DirectTcpLink link;
    private final SharedPreferences prefs;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private WifiP2pManager.Channel channel;
    private WifiP2pDnsSdServiceInfo service;
    private WifiP2pDnsSdServiceRequest request;
    private DirectGroupPolicy groups;
    private Listener listener;
    private boolean started, enabled, registered, requesting, scanning, connecting, removing;
    private long retryAt, nextDiscovery, queryAt, connectDeadline;
    private int generation, queryVersion;
    private String lease = "";
    private String status = "Off", groupFingerprint = "";

    public static final class Peer {
        public final String address, name;
        public final long seenAt;

        Peer(String address, String name, long seenAt) {
            this.address = address;
            this.name = cleanName(name);
            this.seenAt = seenAt;
        }

        public String label() {
            return name + " · " + address;
        }
    }

    public static final class Snapshot {
        public final String status;
        public final boolean enabled, supported, connected;
        public final List<Peer> peers;

        Snapshot(
                String status,
                boolean enabled,
                boolean supported,
                boolean connected,
                Collection<Peer> peers) {
            this.status = status;
            this.enabled = enabled;
            this.supported = supported;
            this.connected = connected;
            this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
        }
    }

    public WifiDirectTransport(
            Context context, Handler worker, FloodGuard flood, TransportMetrics metrics) {
        this.context = context.getApplicationContext();
        this.worker = worker;
        manager = context.getSystemService(WifiP2pManager.class);
        prefs = context.getSharedPreferences("offgrid-direct", Context.MODE_PRIVATE);
        link = new DirectTcpLink(worker, flood, metrics);
    }

    public static boolean supported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    public static String[] permissions() {
        return permissions(Build.VERSION.SDK_INT);
    }

    static String[] permissions(int sdk) {
        List<String> required = new ArrayList<>();
        if (sdk >= 33) required.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION);
            required.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (sdk >= 37) required.add(Manifest.permission.ACCESS_LOCAL_NETWORK);
        return required.toArray(new String[0]);
    }

    private boolean permissionsGranted() {
        for (String permission : permissions())
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private boolean locationReady() {
        LocationManager location = context.getSystemService(LocationManager.class);
        return location != null && LocationManagerCompat.isLocationEnabled(location);
    }

    private static String addressKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static String cleanName(String name) {
        if (name == null) return "Nearby phone";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < name.length() && clean.length() < 48; i++)
            if (!Character.isISOControl(name.charAt(i))
                    && Character.getType(name.charAt(i)) != Character.FORMAT)
                clean.append(name.charAt(i));
        return clean.toString().trim().isEmpty() ? "Nearby phone" : clean.toString().trim();
    }

    public Snapshot snapshot() {
        String detail =
                status + "\nData link: " + link.status() + " · endpoints " + link.peerCount();
        return new Snapshot(
                detail,
                enabled,
                supported(context),
                channel != null && !groupFingerprint.isEmpty(),
                peers.values());
    }

    public void enabled(boolean value) {
        if (enabled == value) {
            if (started) maintain();
            return;
        }
        enabled = value;
        if (!started) return;
        if (!enabled) teardown(true);
        maintain();
    }

    @Override
    public void start(Listener listener) {
        if (started) return;
        started = true;
        this.listener = listener;
        link.start(listener);
        maintain();
    }

    @Override
    public void maintain() {
        if (!started) return;
        if (!enabled) {
            status = "Off · enable Wi-Fi Direct pairing";
            return;
        }
        if (!supported(context) || manager == null) {
            status = "Wi-Fi Direct is unsupported on this phone";
            return;
        }
        if (!permissionsGranted()) {
            if (channel != null) teardown(false);
            status = "Wi-Fi Direct permission missing · enable it in Network";
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (channel == null) {
            if (now >= retryAt) initialize();
            return;
        }
        if (connecting && now >= connectDeadline) {
            connecting = false;
            groups.connectionFailed();
            manager.cancelConnect(
                    channel, action("Cancel timed-out pairing", generation, () -> {}));
            status = "Wi-Fi Direct pairing timed out · find peers and retry";
        }
        peers.values().removeIf(peer -> now - peer.seenAt > 120_000);
        if (now - queryAt >= 5000 || (requesting && now - queryAt > 10000)) inspect();
        link.maintain();
        if (!link.ready() && !connecting && now >= nextDiscovery) discover();
    }

    private void initialize() {
        long remaining = prefs.getLong("cleanupUntil", 0) - SystemClock.elapsedRealtime();
        if (remaining > 0 && remaining <= 5500) {
            status = "Finishing previous OffGrid group cleanup";
            return;
        }
        lease = UUID.randomUUID().toString();
        prefs.edit().putString("lease", lease).apply();
        int epoch = ++generation;
        groups = new DirectGroupPolicy(prefs.getString("group", ""));
        try {
            channel =
                    manager.initialize(
                            context,
                            worker.getLooper(),
                            () -> {
                                if (epoch != generation) return;
                                teardown(false);
                                retryAt = SystemClock.elapsedRealtime() + 15000;
                                status = "Wi-Fi Direct control channel lost; retrying";
                            });
            if (channel == null) throw new IllegalStateException("No Wi-Fi Direct channel");
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
            ContextCompat.registerReceiver(
                    context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
            // Only protected system actions; ignore extras and query WifiP2pManager for fresh
            // state.
            registered = true;
            manager.setDnsSdResponseListeners(
                    channel,
                    (instance, type, device) -> {
                        if (!current(epoch)
                                || !INSTANCE.equalsIgnoreCase(instance)
                                || type == null
                                || !(type.equalsIgnoreCase(TYPE)
                                        || type.toLowerCase(Locale.ROOT).startsWith(TYPE + ".")))
                            return;
                        if (device == null
                                || device.deviceAddress == null
                                || !device.deviceAddress.matches(
                                        "(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return;
                        if (peers.size() >= 64
                                && !peers.containsKey(
                                        device.deviceAddress.toLowerCase(Locale.ROOT))) return;
                        peers.put(
                                device.deviceAddress.toLowerCase(Locale.ROOT),
                                new Peer(
                                        device.deviceAddress.toLowerCase(Locale.ROOT),
                                        device.deviceName,
                                        SystemClock.elapsedRealtime()));
                        if (!link.ready() && !connecting)
                            status =
                                    "Found "
                                            + peers.size()
                                            + " OffGrid phone(s) · choose one to connect";
                    },
                    (name, record, device) -> {});
            service =
                    WifiP2pDnsSdServiceInfo.newInstance(
                            INSTANCE, TYPE, Collections.singletonMap("link", "1"));
            manager.addLocalService(
                    channel,
                    service,
                    action(
                            "Advertise OffGrid",
                            epoch,
                            () -> {
                                request = WifiP2pDnsSdServiceRequest.newInstance(TYPE);
                                manager.addServiceRequest(
                                        channel,
                                        request,
                                        action(
                                                "Request OffGrid discovery",
                                                epoch,
                                                () -> discover()));
                            }));
            status = "Wi-Fi Direct ready · checking group state";
            inspect();
        } catch (RuntimeException e) {
            teardown(false);
            retryAt = SystemClock.elapsedRealtime() + 15000;
            status = "Cannot initialize Wi-Fi Direct; check Wi-Fi and permissions";
        }
    }

    public void discover() {
        if (!started || !enabled || channel == null || request == null || !permissionsGranted())
            return;
        if (!locationReady()) {
            status =
                    "Turn on Android Location services for Wi-Fi Direct discovery (no GPS data is"
                            + " read)";
            nextDiscovery = SystemClock.elapsedRealtime() + 15000;
            return;
        }
        if (connecting || removing) return;
        int epoch = generation;
        nextDiscovery = SystemClock.elapsedRealtime() + 30000;
        try {
            manager.discoverServices(
                    channel,
                    action(
                            "Find OffGrid phones",
                            epoch,
                            () -> {
                                scanning = true;
                                if (!link.ready())
                                    status =
                                            "Searching nearby OffGrid phones · enable Direct on"
                                                    + " both";
                            }));
        } catch (RuntimeException e) {
            status = "Wi-Fi Direct discovery unavailable; check Wi-Fi/Location and permissions";
        }
    }

    public void connect(String address) {
        Peer selected = peers.get(addressKey(address));
        if (selected == null
                || SystemClock.elapsedRealtime() - selected.seenAt > 120000
                || channel == null
                || !enabled) {
            status = "Peer no longer available · find nearby phones again";
            return;
        }
        if (!permissionsGranted()) {
            status = "Wi-Fi Direct pairing permission missing";
            return;
        }
        int epoch = generation;
        try {
            manager.requestConnectionInfo(
                    channel,
                    info -> {
                        if (!current(epoch)) return;
                        if (info != null && info.groupFormed) {
                            status =
                                    "Already in a Wi-Fi Direct group; disconnect it before pairing"
                                            + " another phone";
                            inspect();
                            return;
                        }
                        groups.empty();
                        if (!groups.beginConnect()) return;
                        WifiP2pConfig config = new WifiP2pConfig();
                        config.deviceAddress = address;
                        config.wps.setup = WpsInfo.PBC;
                        connecting = true;
                        connectDeadline = SystemClock.elapsedRealtime() + 60000;
                        try {
                            manager.connect(
                                    channel,
                                    config,
                                    new WifiP2pManager.ActionListener() {
                                        @Override
                                        public void onSuccess() {
                                            if (!current(epoch)) return;
                                            groups.requestAccepted();
                                            inspect();
                                            status =
                                                    "Pairing · accept Android's connection"
                                                            + " prompt on the other phone";
                                        }

                                        @Override
                                        public void onFailure(int reason) {
                                            if (!current(epoch)) return;
                                            connecting = false;
                                            groups.connectionFailed();
                                            status =
                                                    "Wi-Fi Direct pairing failed: "
                                                            + reason(reason);
                                        }
                                    });
                        } catch (RuntimeException e) {
                            connecting = false;
                            groups.connectionFailed();
                            status = "Wi-Fi Direct pairing request failed";
                        }
                    });
        } catch (RuntimeException e) {
            connecting = false;
            groups.connectionFailed();
            status = "Wi-Fi Direct connect failed; check permissions";
        }
    }

    private void inspect() {
        if (channel == null || !permissionsGranted() || removing) return;
        long now = SystemClock.elapsedRealtime();
        if (requesting && now - queryAt < 10000) return;
        requesting = true;
        queryAt = now;
        int epoch = generation, query = ++queryVersion;
        try {
            manager.requestConnectionInfo(
                    channel,
                    info -> {
                        if (!current(epoch) || query != queryVersion) return;
                        if (info == null || !info.groupFormed) {
                            requesting = false;
                            groupFingerprint = "";
                            link.closeLink();
                            // Do not clear the pending connect while negotiation is in progress.
                            if (!connecting) {
                                groups.empty();
                                prefs.edit().remove("group").remove("creator").apply();
                            }
                            return;
                        }
                        try {
                            manager.requestGroupInfo(
                                    channel,
                                    group -> {
                                        if (!current(epoch) || query != queryVersion) return;
                                        requesting = false;
                                        if (group == null) {
                                            status = "Wi-Fi Direct group is forming";
                                            return;
                                        }
                                        applyGroup(info, group);
                                    });
                        } catch (RuntimeException e) {
                            requesting = false;
                            status = "Wi-Fi Direct group query failed; check permissions";
                        }
                    });
        } catch (RuntimeException e) {
            requesting = false;
            status = "Cannot query Wi-Fi Direct group";
        }
    }

    private void applyGroup(WifiP2pInfo info, WifiP2pGroup group) {
        String fingerprint = fingerprint(group);
        boolean known =
                group.getOwner() != null
                        && peers.containsKey(addressKey(group.getOwner().deviceAddress));
        for (WifiP2pDevice client : group.getClientList())
            known |= peers.containsKey(addressKey(client.deviceAddress));
        if (!groups.observe(fingerprint, known)) {
            groupFingerprint = "";
            link.closeLink();
            status =
                    "Existing or unidentified Wi-Fi Direct group left untouched · disconnect it in"
                            + " Android Wi-Fi settings first";
            return;
        }
        connecting = false;
        groupFingerprint = fingerprint;
        prefs.edit()
                .putString("group", fingerprint)
                .putBoolean("creator", groups.creator())
                .apply();
        try {
            NetworkInterface network = NetworkInterface.getByName(group.getInterface());
            if (network == null || info.groupOwnerAddress == null) {
                link.closeLink();
                status = "Waiting for P2P interface/address";
                return;
            }
            InterfaceAddress chosen = null;
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                if (address.getAddress() instanceof Inet4Address
                        && LanSubnet.contains(
                                address.getAddress(),
                                address.getNetworkPrefixLength(),
                                info.groupOwnerAddress)) {
                    if (info.isGroupOwner && !address.getAddress().equals(info.groupOwnerAddress))
                        continue;
                    chosen = address;
                    break;
                }
            }
            if (chosen == null) {
                link.closeLink();
                status = "Wi-Fi Direct needs its private IPv4 group interface";
                return;
            }
            link.bind(chosen.getAddress(), chosen.getNetworkPrefixLength(), info.groupOwnerAddress);
            status =
                    info.isGroupOwner
                            ? "Wi-Fi Direct group owner · waiting for/serving OffGrid clients"
                            : "Wi-Fi Direct client · connected without an access point";
            if (!link.ready()) status = link.status();
        } catch (SocketException | RuntimeException e) {
            link.closeLink();
            status = "Cannot bind the Wi-Fi Direct interface";
        }
    }

    public void disconnect() {
        disconnect(true);
    }

    private void disconnect(boolean explicit) {
        if (channel == null || groups == null || !permissionsGranted()) return;
        WifiP2pManager.Channel operation = channel;
        DirectGroupPolicy policy = groups;
        int epoch = generation;
        if (connecting) {
            connecting = false;
            policy.connectionFailed();
            manager.cancelConnect(
                    operation,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {}

                        @Override
                        public void onFailure(int reason) {}
                    });
        }
        try {
            manager.requestGroupInfo(
                    operation,
                    group -> {
                        if (epoch != generation || group == null) return;
                        String fingerprint = fingerprint(group);
                        if (!(explicit
                                ? policy.mayDetach(fingerprint)
                                : policy.mayRemove(fingerprint))) return;
                        removing = true;
                        link.closeLink();
                        try {
                            manager.removeGroup(
                                    operation,
                                    new WifiP2pManager.ActionListener() {
                                        @Override
                                        public void onSuccess() {
                                            if (epoch != generation) return;
                                            removing = false;
                                            policy.empty();
                                            groupFingerprint = "";
                                            prefs.edit().remove("group").remove("creator").apply();
                                            status = "Wi-Fi Direct disconnected";
                                        }

                                        @Override
                                        public void onFailure(int reason) {
                                            if (epoch != generation) return;
                                            removing = false;
                                            status =
                                                    "Could not remove OffGrid group: "
                                                            + reason(reason);
                                        }
                                    });
                        } catch (RuntimeException e) {
                            removing = false;
                            status = "Wi-Fi Direct disconnect request failed";
                        }
                    });
        } catch (RuntimeException e) {
            removing = false;
            status = "Wi-Fi Direct group query failed";
        }
    }

    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    worker.post(() -> {
                        if (started && enabled && channel != null) {
                            if (!requesting) queryAt = 0;
                            inspect();
                            maintain();
                        }
                    });
                }
            };

    private WifiP2pManager.ActionListener action(String operation, int epoch, Runnable success) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (!current(epoch)) return;
                try {
                    success.run();
                } catch (RuntimeException e) {
                    controlFailure(operation);
                }
            }

            @Override
            public void onFailure(int reason) {
                if (!current(epoch)) return;
                if (operation.equals("Advertise OffGrid")
                        || operation.equals("Request OffGrid discovery")) {
                    teardown(false);
                    retryAt = SystemClock.elapsedRealtime() + 15000;
                }
                status = operation + ": " + reason(reason);
            }
        };
    }

    private void controlFailure(String operation) {
        teardown(false);
        retryAt = SystemClock.elapsedRealtime() + 15000;
        status = operation + " failed; retrying control channel";
    }

    private static void closeChannel(WifiP2pManager.Channel channel) {
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                channel.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean current(int epoch) {
        return epoch == generation && started && enabled && channel != null;
    }

    private static String reason(int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) return "unsupported";
        if (reason == WifiP2pManager.BUSY) return "busy; retry after the current operation";
        if (reason == WifiP2pManager.NO_SERVICE_REQUESTS) return "discovery request unavailable";
        return "platform error " + reason + " (check Wi-Fi, permission and Location requirements)";
    }

    private static String fingerprint(WifiP2pGroup group) {
        try {
            String owner = group.getOwner() == null ? "" : group.getOwner().deviceAddress;
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (group.getNetworkName()
                                                    + "|"
                                                    + owner
                                                    + "|"
                                                    + group.getInterface())
                                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : hash) out.append(String.format(Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint group", e);
        }
    }

    private void teardown(boolean releaseOwned) {
        WifiP2pManager.Channel closing = channel;
        DirectGroupPolicy policy = groups;
        int cleanupEpoch = ++generation;
        String cleanupLease = lease;
        channel = null;
        requesting = false;
        scanning = false;
        removing = false;
        link.closeLink();
        peers.clear();
        if (registered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
        if (closing != null) {
            Runnable close =
                    () -> {
                        if (prefs.getString("lease", "").equals(cleanupLease))
                            prefs.edit().remove("cleanupUntil").apply();
                        closeChannel(closing);
                    };
            try {
                if (service != null) manager.removeLocalService(closing, service, null);
                if (request != null) manager.removeServiceRequest(closing, request, null);
                // Preserve groups present at startup and groups passively adopted after an incoming
                // connection.
                if (releaseOwned
                        && permissionsGranted()
                        && prefs.getString("lease", "").equals(cleanupLease)) {
                    if (policy != null && (policy.creator() || connecting))
                        prefs.edit()
                                .putLong("cleanupUntil", SystemClock.elapsedRealtime() + 5500)
                                .apply();
                    if (connecting) manager.cancelConnect(closing, null);
                    manager.requestGroupInfo(
                            closing,
                            group -> {
                                try {
                                    if (generation == cleanupEpoch
                                            && prefs.getString("lease", "").equals(cleanupLease)
                                            && (!started || !enabled)
                                            && group != null
                                            && policy != null
                                            && policy.mayRemove(fingerprint(group))) {
                                        String removed = fingerprint(group);
                                        manager.removeGroup(
                                                closing,
                                                new WifiP2pManager.ActionListener() {
                                                    @Override
                                                    public void onSuccess() {
                                                        if (generation == cleanupEpoch
                                                                && prefs.getString("lease", "")
                                                                        .equals(cleanupLease)
                                                                && prefs.getString("group", "")
                                                                        .equals(removed))
                                                            prefs.edit()
                                                                    .remove("group")
                                                                    .remove("creator")
                                                                    .apply();
                                                        close.run();
                                                    }

                                                    @Override
                                                    public void onFailure(int reason) {
                                                        close.run();
                                                    }
                                                });
                                    } else close.run();
                                } catch (RuntimeException ignored) {
                                    close.run();
                                }
                            });
                    worker.postDelayed(close, 5000);
                } else close.run();
            } catch (RuntimeException e) {
                close.run();
            }
        }
        connecting = false;
        service = null;
        request = null;
        groupFingerprint = "";
    }

    @Override
    public void stop() {
        started = false;
        teardown(true);
        status = "Stopped";
    }

    @Override
    public TransferProfile profile(String peer) {
        return link.profile(peer);
    }

    @Override
    public void send(String peer, List<Packet> packets, SendListener callbacks) {
        link.send(peer, packets, callbacks);
    }

    @Override
    public void cancelSends() {
        link.cancelSends();
    }
}
