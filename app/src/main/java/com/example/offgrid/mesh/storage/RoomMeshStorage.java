package com.example.offgrid.mesh.storage;

import com.example.offgrid.MeshMessage;
import com.example.offgrid.MessageStore;
import com.example.offgrid.PendingRecord;

import java.util.ArrayList;
import java.util.List;

/** Adapts encrypted Room records and existing atomic transactions to mesh use cases. */
public final class RoomMeshStorage implements MeshStorage {
    private final MessageStore store;
    private final String localId;

    public RoomMeshStorage(MessageStore store, String localId) {
        this.store = store;
        this.localId = localId;
    }

    @Override
    public void replayTrust(java.util.function.ToIntFunction<String> trust, Object guard) {
        store.replayTrust(trust, guard);
    }

    private Pending decode(PendingRecord row) throws Exception {
        return row == null
                ? null
                : new Pending(
                        store.decodePending(row),
                        row.storedAtMs,
                        row.expiresAtMs,
                        row.forwardCount);
    }

    @Override
    public List<Pending> pending() throws Exception {
        List<Pending> result = new ArrayList<>();
        for (PendingRecord row : store.dao().pending()) result.add(decode(row));
        return result;
    }

    @Override
    public List<String> pendingIds() {
        return store.dao().pendingIds();
    }

    @Override
    public Pending pending(String id) throws Exception {
        return decode(store.dao().pendingById(id));
    }

    @Override
    public boolean hasHistory(String id) {
        return store.dao().containsChat(id) > 0;
    }

    @Override
    public boolean known(String id) {
        return hasHistory(id) || store.dao().containsPending(id) > 0;
    }

    @Override
    public boolean replayed(String id) {
        return store.dao().replayed(id) > 0;
    }

    @Override
    public boolean canAcceptSequence(MeshMessage packet) {
        return store.canAcceptSequence(packet);
    }

    @Override
    public boolean clockSafe(long now) {
        return store.clockSafe(now);
    }

    @Override
    public MeshMessage cachedReceipt(MeshMessage packet, long now) throws Exception {
        return store.cachedReceipt(packet, now);
    }

    @Override
    public String confirmReceipt(MeshMessage ack, String plaintext, long now) throws Exception {
        return store.confirmDelivery(ack, plaintext, true, now);
    }

    @Override
    public void save(
            MeshMessage wire, MeshMessage display, boolean outgoing, MeshMessage relay, long now)
            throws Exception {
        store.save(
                wire,
                display,
                outgoing,
                outgoing ? wire.getTimestamp() : now,
                relay,
                now,
                now + MessageStore.DELIVERY_TIMEOUT_MS,
                !localId.equals(wire.getSenderId()));
    }

    @Override
    public void forwarded(String id, int count) {
        store.forwarded(id, count);
    }

    @Override
    public void discard(String id, String status) {
        store.removePending(id, status);
    }

    @Override
    public void expire(long now) {
        store.expire(now);
    }

    @Override
    public void outgoingFailed(String id) {
        store.outgoingFailed(id);
    }
}
