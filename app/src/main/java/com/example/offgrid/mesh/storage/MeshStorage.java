package com.example.offgrid.mesh.storage;

import com.example.offgrid.MeshMessage;

import java.util.List;

/** Application storage operations; protocol and routing never access a Room DAO directly. */
public interface MeshStorage {
    final class Pending {
        public final MeshMessage message;
        public final long storedAtMs;
        public final long expiresAtMs;
        public final int forwardCount;

        public Pending(MeshMessage message, long storedAtMs, long expiresAtMs, int forwardCount) {
            this.message = message;
            this.storedAtMs = storedAtMs;
            this.expiresAtMs = expiresAtMs;
            this.forwardCount = forwardCount;
        }
    }

    List<Pending> pending() throws Exception;

    default List<String> pendingIds() throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Pending row : pending()) ids.add(row.message.getMessageId());
        return ids;
    }

    Pending pending(String id) throws Exception;

    boolean hasHistory(String id);

    boolean known(String id);

    boolean replayed(String id);

    default boolean canAcceptSequence(MeshMessage packet) {
        return true;
    }

    boolean clockSafe(long now);

    MeshMessage cachedReceipt(MeshMessage packet, long now) throws Exception;

    String confirmReceipt(MeshMessage ack, String plaintext, long now) throws Exception;

    void save(MeshMessage wire, MeshMessage display, boolean outgoing, MeshMessage relay, long now)
            throws Exception;

    void forwarded(String id, int count);

    void discard(String id, String status);

    void expire(long now);

    void outgoingFailed(String id);
}
