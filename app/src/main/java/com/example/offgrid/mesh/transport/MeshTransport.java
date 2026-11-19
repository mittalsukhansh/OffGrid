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
        /** Worker callback: cancel closes only this native connection, never every peer. */
        // Call before opening a native connection and before each packet write. The scheduler
        // reserves forwarding capacity here; shouldSend alone does not reserve capacity.
        default void onAttempt(String id, Runnable cancel) {}

        default boolean isCancelled() {
            return false;
        }

        boolean shouldSend(String id);

        void onForwarded(String id);

        void onComplete(String failure, boolean incompatible);
    }

    void start(Listener listener);

    void stop();

    void maintain();

    void send(String peer, List<Packet> packets, SendListener listener);

    void cancelSends();

    default TransferProfile profile(String peer) {
        return TransferProfile.GATT;
    }

    default int maxPacketBytes(String peer) {
        return profile(peer) == TransferProfile.GATT ? 2048 : LanFrames.MAX_PACKET_BYTES;
    }
}
