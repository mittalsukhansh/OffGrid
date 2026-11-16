package com.example.offgrid.mesh.transport;

import java.util.List;

/** Namespaced peer addresses keep radio discovery state independent of cryptographic identity. */
public final class CombinedTransport implements MeshTransport {
    private final MeshTransport ble, wifi;
    private Listener listener;
    private boolean bleActive, wifiActive;
    private Boolean reported;

    public CombinedTransport(MeshTransport ble, MeshTransport wifi) {
        this.ble = ble;
        this.wifi = wifi;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        reported = null;
        ble.start(child("ble:"));
        wifi.start(child("wifi:"));
    }

    private Listener child(String prefix) {
        return new Listener() {
            @Override
            public void onPacket(String peer, byte[] packet) {
                listener.onPacket(prefix + peer, packet);
            }

            @Override
            public void onPeerSeen(String peer, int rssi) {
                listener.onPeerSeen(prefix + peer, rssi);
            }

            @Override
            public void onRadioState(boolean active) {
                if (prefix.equals("ble:")) bleActive = active;
                else wifiActive = active;
                boolean any = bleActive || wifiActive;
                if (reported == null || reported != any) {
                    reported = any;
                    listener.onRadioState(any);
                }
            }

            @Override
            public void onError(String error) {
                listener.onError(prefix + " " + error);
            }
        };
    }

    @Override
    public void send(String peer, List<Packet> packets, SendListener callbacks) {
        if (peer.startsWith("wifi:")) wifi.send(peer.substring(5), packets, callbacks);
        else if (peer.startsWith("ble:")) ble.send(peer.substring(4), packets, callbacks);
        else callbacks.onComplete("Unknown transport", true);
    }

    @Override
    public TransferProfile profile(String peer) {
        if (peer.startsWith("wifi:")) return wifi.profile(peer.substring(5));
        if (peer.startsWith("ble:")) return ble.profile(peer.substring(4));
        return TransferProfile.GATT;
    }

    @Override
    public void maintain() {
        ble.maintain();
        wifi.maintain();
    }

    @Override
    public void cancelSends() {
        ble.cancelSends();
        wifi.cancelSends();
    }

    @Override
    public void stop() {
        ble.stop();
        wifi.stop();
        bleActive = wifiActive = false;
    }
}
