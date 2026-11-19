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
        dao().deleteTransferPackets(id);
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
            body = JsonBounds.object(plaintext, MeshMessage.MAX_CONTROL_PLAINTEXT_BYTES);
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

    public enum TransferCommand {
        PAUSE,
        RESUME,
        CANCEL,
        SEND_AGAIN
    }

    public void transferCommand(String id, TransferCommand command, long now) throws Exception {
        media.command(id, command, now);
    }

    public java.util.List<TransferCommand> transferCommands(String id) {
        MediaRecord row = dao().media(id);
        ChatRecord chat = dao().chat(id);
        if (row == null
                || chat == null
                || !row.outgoing
                || !row.senderId.equals(crypto.getLocalId())
                || chat.status.equals("DELIVERED")
                || row.state.equals("COMPLETE")
                || dao().withdrawal(id) != null) return java.util.Collections.emptyList();
        if (row.state.equals("CANCELLED") || row.state.equals("FAILED"))
            return row.file.length > 0
                    ? java.util.Collections.singletonList(TransferCommand.SEND_AGAIN)
                    : java.util.Collections.emptyList();
        return java.util.Arrays.asList(
                row.paused ? TransferCommand.RESUME : TransferCommand.PAUSE,
                TransferCommand.CANCEL);
    }

    public String deliveryDetails(String id) {
        ChatRecord chat = dao().chat(id);
        if (chat == null) return "Message is unavailable.";
        String summary;
        switch (chat.status) {
            case "DELIVERED":
                summary =
                        "Delivered: the recipient authenticated and saved this message; this is not"
                                + " a read receipt.";
                break;
            case "FORWARDED":
                summary = "Forwarded to a nearby peer. Recipient delivery is unconfirmed.";
                break;
            case "EXPIRED":
                summary =
                        "Delivery deadline expired without confirmation. The recipient may still"
                                + " have a copy.";
                break;
            case "EVICTED":
                summary =
                        "Removed from the local relay queue because it was full. Delivery is"
                                + " unconfirmed.";
                break;
            case "FAILED":
                summary = "Sending failed or the transfer expired. Delivery is unconfirmed.";
                break;
            case "PAUSED":
                summary = "Attachment sending is paused on this phone.";
                break;
            case "CANCELLED":
                summary =
                        "Local attachment sending was cancelled; already relayed copies are not"
                                + " recalled.";
                break;
            case "UNSEND_PENDING":
                summary = "Waiting for the recipient to acknowledge the unsend request.";
                break;
            case "UNSEND_EXPIRED":
                summary = "Unsend request expired without recipient confirmation.";
                break;
            case "UNSENT":
                summary =
                        "Unsent in the participating apps; screenshots and exports cannot be"
                                + " recalled.";
                break;
            case "TRANSFERRING":
                summary = "Sending attachment blocks. Waiting for authenticated completion.";
                break;
            case "RECEIVING":
                summary = "Receiving and validating attachment blocks.";
                break;
            case "READY":
                summary = "Attachment validated and saved on this phone.";
                break;
            case "RECEIVED":
                summary = "Message saved on this phone.";
                break;
            default:
                summary = "Queued locally. No recipient delivery confirmation yet.";
        }
        return summary + (!chat.kind.equals("text") ? "\n\n" + media.details(id) : "");
    }

    public boolean deliveryNeedsRelay(String id) {
        ChatRecord row = dao().chat(id);
        return row != null
                && row.outgoing
                && (row.status.equals("QUEUED")
                        || row.status.equals("FORWARDED")
                        || row.status.equals("TRANSFERRING")
                        || row.status.equals("UNSEND_PENDING"));
    }

    public java.util.List<String> deliveryPacketIds(String id) {
        WithdrawalRecord withdrawal = dao().withdrawal(id);
        if (withdrawal != null) return java.util.Collections.singletonList(withdrawal.requestId);
        java.util.List<String> ids = dao().transferPacketIds(id);
        return ids.isEmpty() ? java.util.Collections.singletonList(id) : ids;
    }

    public java.util.List<String> transferPacketIds(String id) {
        return dao().transferPacketIds(id);
    }

    public void offer(String target, byte[] bytes, String mime, long now) throws Exception {
        media.offer(target, bytes, mime, now);
    }

    private long lastWithdrawalSweep;

    public void pump(long now) throws Exception {
        pump(now, now);
    }

    public void transferProfiles(
            java.util.function.Function<String, com.example.offgrid.mesh.transport.TransferProfile>
                    profiles) {
        media.profiles = profiles;
    }

    public void pump(long now, long elapsed) throws Exception {
        if (now - lastWithdrawalSweep >= 5000) {
            dao().expireWithdrawals(now - MessageStore.DELIVERY_TIMEOUT_MS);
            lastWithdrawalSweep = now;
        }
        media.pump(now, elapsed);
    }

    public long pumpDelay() {
        return media.pumpDelay();
    }

    public String transferDiagnostics(long now) {
        StringBuilder out =
                new StringBuilder(
                        "Active media payload progress (signed receiver bitmap; not Delivered):");
        for (MediaRecord row : dao().activeMedia()) {
            java.util.BitSet bits = java.util.BitSet.valueOf(row.received);
            long bytes = (long) bits.cardinality() * 640;
            if (bits.get(row.chunkCount - 1)) bytes -= (long) row.chunkCount * 640 - row.byteCount;
            long age = Math.max(1, now - row.createdAt);
            out.append("\n")
                    .append(row.outgoing ? "TX " : "RX ")
                    .append(row.id.substring(0, 8))
                    .append(" · ")
                    .append(row.state)
                    .append(" · ")
                    .append(bytes)
                    .append('/')
                    .append(row.byteCount)
                    .append(" payload B · wall-age average ")
                    .append(bytes * 1000 / age)
                    .append(" B/s");
        }
        return out.toString();
    }

    public byte[] attachment(String id) throws Exception {
        return media.file(id);
    }
}
