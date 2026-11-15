package com.example.offgrid;

import android.content.Context;

import com.example.offgrid.mesh.protocol.MeshProtocol;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Authenticated private actions. Every public method is called on the mesh worker. */
public final class PrivateActions implements MeshProtocol.Extensions {
    final MessageStore store;
    final CryptoManager crypto;
    final Context context;
    final MediaTransfers media;

    public PrivateActions(Context context, MessageStore store, CryptoManager crypto) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.crypto = crypto;
        media = new MediaTransfers(this);
    }

    private MessageDao dao() {
        return store.dao();
    }

    byte[] seal(String scope, byte[] bytes) throws Exception {
        return store.cipher.encrypt(scope, bytes);
    }

    byte[] open(String scope, byte[] bytes) throws Exception {
        return store.cipher.decrypt(scope, bytes);
    }

    MeshMessage decode(String scope, byte[] bytes) throws Exception {
        return MeshMessage.fromBytes(open(scope, bytes));
    }

    void queue(MeshMessage packet, long now) throws Exception {
        store.save(packet, null, false, now, packet, now, now + MessageStore.DELIVERY_TIMEOUT_MS);
    }

    void accept(MeshMessage incoming, MeshMessage response, long now) throws Exception {
        store.save(
                incoming,
                null,
                false,
                now,
                response,
                now,
                now + MessageStore.DELIVERY_TIMEOUT_MS,
                true);
    }

    void transaction(Checked task) throws Exception {
        store.database.runInTransaction(
                (java.util.concurrent.Callable<Void>)
                        () -> {
                            task.run();
                            return null;
                        });
    }

    interface Checked {
        void run() throws Exception;
    }

    static MeshProtocol.Incoming result(MeshMessage wire, MeshMessage relay) {
        return new MeshProtocol.Incoming(wire, null, relay, null, null);
    }

    public void unsend(String id, long now) throws Exception {
        ChatRecord row = dao().chat(id);
        if (row == null
                || !row.outgoing
                || (dao().withdrawal(id) != null && dao().withdrawal(id).confirmed))
            throw new IllegalArgumentException("This message cannot be unsent");
        MeshMessage original = store.decodeChat(row).message;
        if (!original.isPrivate() || !original.getSenderId().equals(crypto.getLocalId()))
            throw new IllegalArgumentException("Only your private messages can be unsent");
        MeshMessage request =
                crypto.control(
                        original.getTargetId(),
                        new JSONObject()
                                .put("kind", "withdraw")
                                .put("mid", id)
                                .put("when", original.getTimestamp())
                                .toString());
        WithdrawalRecord marker = marker(original, request, true, now);
        transaction(() -> {
            WithdrawalRecord previous = dao().withdrawal(id);
            if (previous == null) requireMarkerSpace();
            else dao().deletePending(previous.requestId);
            queue(request, now);
            dao().putWithdrawal(marker);
            erase(id, "UNSEND_PENDING");
        });
        AttachmentFiles.remove(context, id);
    }

    private WithdrawalRecord marker(
            MeshMessage original, MeshMessage request, boolean outgoing, long now)
            throws Exception {
        WithdrawalRecord marker = new WithdrawalRecord();
        marker.id = original.getMessageId();
        marker.senderId = original.getSenderId();
        marker.targetId = original.getTargetId();
        marker.senderKey = original.getSenderKey();
        marker.timestamp = original.getTimestamp();
        marker.requestId = request.getMessageId();
        marker.request = seal("withdraw:" + marker.id, request.toBytes());
        marker.outgoing = outgoing;
        marker.createdAt = now;
        return marker;
    }

    private void requireMarkerSpace() {
        if (dao().withdrawalCount() >= 10_000)
            throw new IllegalStateException("Deletion marker storage is full");
    }

    private void erase(String id, String status) throws Exception {
        dao().deletePending(id);
        dao().cancelMediaPackets(id);
        dao().deleteParts(id);
        MediaRecord attachment = dao().media(id);
        if (attachment != null) {
            attachment.state = "CANCELLED";
            attachment.file = new byte[0];
            attachment.metadata = new byte[0];
            dao().putMedia(attachment);
        }
        dao().replaceWithdrawn(
                        id,
                        seal(
                                "history:" + id,
                                "{\"withdrawn\":true}".getBytes(StandardCharsets.UTF_8)),
                        status);
    }

    @Override
    public MeshProtocol.Incoming beforePrivate(MeshMessage packet, long now) throws Exception {
        WithdrawalRecord marker = dao().withdrawal(packet.getMessageId());
        if (marker != null) {
            if (!marker.senderId.equals(packet.getSenderId())
                    || !marker.targetId.equals(packet.getTargetId()))
                return MeshProtocol.Incoming.IGNORED;
            return MeshProtocol.Incoming
                    .IGNORED; // tombstone prevents delayed content from resurrecting
        }
        MediaRecord record = dao().media(packet.getMessageId());
        return record == null ? null : media.duplicateOffer(packet, record, now);
    }

    @Override
    public MeshProtocol.Incoming media(MeshMessage packet, String plaintext, long now)
            throws Exception {
        return media.acceptOffer(packet, plaintext, now);
    }

    @Override
    public MeshProtocol.Incoming control(MeshMessage packet, long now) throws Exception {
        String plaintext;
        try {
            plaintext = crypto.decryptControl(packet);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return MeshProtocol.Incoming.IGNORED;
        }
        JSONObject body;
        try {
            body = new JSONObject(plaintext);
        } catch (org.json.JSONException e) {
            return MeshProtocol.Incoming.IGNORED;
        }
        String kind = body.optString("kind");
        if (kind.equals("withdraw")) return receiveWithdrawal(packet, body, now);
        if (dao().replayed(packet.getMessageId()) > 0) return MeshProtocol.Incoming.IGNORED;
        if (kind.equals("withdraw_ack")) return confirmWithdrawal(packet, body, now);
        return media.control(packet, body, now);
    }

    private MeshProtocol.Incoming receiveWithdrawal(MeshMessage packet, JSONObject body, long now)
            throws Exception {
        String id = body.optString("mid");
        if (!id.matches("[0-9a-f]{64}") || !id.startsWith(packet.getSenderId()))
            return MeshProtocol.Incoming.IGNORED;
        WithdrawalRecord existing = dao().withdrawal(id);
        if (existing != null) {
            MeshMessage saved = decode("withdraw:" + id, existing.request);
            if (!existing.outgoing
                    && existing.response.length > 0
                    && Arrays.equals(
                            PacketSecurity.signingBytes(saved),
                            PacketSecurity.signingBytes(packet))) {
                MeshMessage reply = decode("withdraw-reply:" + id, existing.response);
                if (PacketSecurity.fresh(reply, now)) {
                    queue(reply, now);
                    return result(packet, reply);
                }
            }
            if (!existing.outgoing
                    && existing.senderId.equals(packet.getSenderId())
                    && existing.targetId.equals(packet.getTargetId())
                    && existing.timestamp == body.optLong("when")
                    && dao().replayed(packet.getMessageId()) == 0) {
                MeshMessage reply =
                        crypto.control(
                                packet.getSenderId(),
                                new JSONObject()
                                        .put("kind", "withdraw_ack")
                                        .put("mid", id)
                                        .put("request", packet.getMessageId())
                                        .toString());
                existing.requestId = packet.getMessageId();
                existing.request = seal("withdraw:" + id, packet.toBytes());
                existing.response = seal("withdraw-reply:" + id, reply.toBytes());
                transaction(() -> {
                    accept(packet, reply, now);
                    dao().putWithdrawal(existing);
                });
                return result(packet, reply);
            }
            return MeshProtocol.Incoming.IGNORED;
        }
        if (dao().replayed(packet.getMessageId()) > 0) return MeshProtocol.Incoming.IGNORED;
        ChatRecord row = dao().chat(id);
        MeshMessage original;
        if (row != null) {
            original = store.decodeChat(row).message;
            if (row.outgoing
                    || !original.isPrivate()
                    || !original.getSenderId().equals(packet.getSenderId())
                    || !original.getTargetId().equals(packet.getTargetId()))
                return MeshProtocol.Incoming.IGNORED;
        } else {
            long timestamp = body.optLong("when", 0);
            if (timestamp <= 0) return MeshProtocol.Incoming.IGNORED;
            original =
                    MeshMessage.createPrivate(
                            id,
                            packet.getSenderId(),
                            packet.getTargetId(),
                            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                            packet.getSenderKey(),
                            0,
                            timestamp);
        }
        MeshMessage response =
                crypto.control(
                        packet.getSenderId(),
                        new JSONObject()
                                .put("kind", "withdraw_ack")
                                .put("mid", id)
                                .put("request", packet.getMessageId())
                                .toString());
        WithdrawalRecord marker = marker(original, packet, false, now);
        marker.confirmed = true;
        marker.response = seal("withdraw-reply:" + id, response.toBytes());
        transaction(() -> {
            requireMarkerSpace();
            accept(packet, response, now);
            dao().putWithdrawal(marker);
            erase(id, "UNSENT");
        });
        AttachmentFiles.remove(context, id);
        return result(packet, response);
    }

    private MeshProtocol.Incoming confirmWithdrawal(MeshMessage packet, JSONObject body, long now)
            throws Exception {
        String id = body.optString("mid");
        WithdrawalRecord marker = dao().withdrawal(id);
        if (marker == null
                || !marker.outgoing
                || !marker.senderId.equals(packet.getTargetId())
                || !marker.targetId.equals(packet.getSenderId())
                || !marker.requestId.equals(body.optString("request"))) {
            return MeshProtocol.Incoming.IGNORED;
        }
        marker.confirmed = true;
        transaction(() -> {
            accept(packet, null, now);
            dao().putWithdrawal(marker);
            dao().deletePending(marker.requestId);
            erase(id, "UNSENT");
        });
        return new MeshProtocol.Incoming(packet, null, null, marker.requestId, null);
    }

    public void offer(String target, byte[] bytes, String mime, long now) throws Exception {
        media.offer(target, bytes, mime, now);
    }

    public void pump(long now) throws Exception {
        dao().expireWithdrawals(now - MessageStore.DELIVERY_TIMEOUT_MS);
        media.pump(now);
    }

    public byte[] attachment(String id) throws Exception {
        return media.file(id);
    }
}
