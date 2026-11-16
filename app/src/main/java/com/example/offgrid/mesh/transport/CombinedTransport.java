package com.example.offgrid.mesh.transport;

import java.util.*;

/**
 * Independent transport lifecycle and namespaced peer addresses; contacts remain key identities.
 */
public final class CombinedTransport implements MeshTransport {
    private final Map<String, MeshTransport> children = new LinkedHashMap<>();
    private final Set<String> active = new HashSet<>();
    private Listener listener;
    private Boolean reported;
    private boolean running;

    public CombinedTransport(MeshTransport ble, MeshTransport wifi) {
        this(ble, wifi, null);
    }

    public CombinedTransport(MeshTransport ble, MeshTransport wifi, MeshTransport direct) {
        children.put("ble:", ble);
        children.put("wifi:", wifi);
        if (direct != null) children.put("direct:", direct);
    }

    @Override
    public void start(Listener listener) {
        if (running) return;
        this.listener = listener;
        running = true;
        reported = null;
        active.clear();
        for (Map.Entry<String, MeshTransport> child : children.entrySet())
            child.getValue().start(child(child.getKey()));
    }

    private Listener child(String prefix) {
        return new Listener() {
            @Override
            public void onPacket(String peer, byte[] packet) {
                if (running) listener.onPacket(prefix + peer, packet);
            }

            @Override
            public void onPeerSeen(String peer, int rssi) {
                if (running) listener.onPeerSeen(prefix + peer, rssi);
            }

            @Override
            public void onRadioState(boolean enabled) {
                if (!running) return;
                if (enabled) active.add(prefix);
                else active.remove(prefix);
                boolean any = !active.isEmpty();
                if (reported == null || reported != any) {
                    reported = any;
                    listener.onRadioState(any);
                }
            }

            @Override
            public void onError(String error) {
                if (running) listener.onError(prefix + " " + error);
            }
        };
    }

    @Override
    public void send(String peer, List<Packet> packets, SendListener callbacks) {
        if (running)
            for (Map.Entry<String, MeshTransport> child : children.entrySet())
                if (peer.startsWith(child.getKey())) {
                    child.getValue()
                            .send(peer.substring(child.getKey().length()), packets, callbacks);
                    return;
                }
        callbacks.onComplete("Transport unavailable", false);
    }

    @Override
    public TransferProfile profile(String peer) {
        if (running)
            for (Map.Entry<String, MeshTransport> child : children.entrySet())
                if (peer.startsWith(child.getKey()))
                    return child.getValue().profile(peer.substring(child.getKey().length()));
        return TransferProfile.GATT;
    }

    @Override
    public void maintain() {
        for (MeshTransport child : children.values()) child.maintain();
    }

    @Override
    public void cancelSends() {
        for (MeshTransport child : children.values()) child.cancelSends();
    }

    @Override
    public void stop() {
        running = false;
        for (MeshTransport child : children.values()) child.stop();
        active.clear();
        reported = false;
    }
}
