package com.example.offgrid.mesh.transport;

import java.util.List;

/** Byte transport boundary. Every listener callback runs on the mesh worker. */
public interface MeshTransport {
    final class Packet {
        public final String id;
        public final byte[] bytes;

        public Packet(String id, byte[] bytes) {
            this.id = id;
            this.bytes = bytes;
        }
    }

    interface Listener {
        void onPacket(String peer, byte[] packet);

        void onPeerSeen(String peer, int rssi);

        void onRadioState(boolean active);

        void onError(String description);
    }

    interface SendListener {
        boolean shouldSend(String id);

        void onForwarded(String id);

        void onComplete(String failure, boolean incompatible);
    }

    void start(Listener listener);

    void stop();

    void maintain();

    void send(String peer, List<Packet> packets, SendListener listener);

    void cancelSends();
}
