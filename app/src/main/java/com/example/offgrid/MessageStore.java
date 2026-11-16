package com.example.offgrid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.Room;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Database operations run on the mesh thread or the history executor, never the UI thread. */
public final class MessageStore {
    public static final long DELIVERY_TIMEOUT_MS =
            com.example.offgrid.mesh.MeshConfig.PENDING_TTL_MS;
    private static volatile MessageStore instance;
    final MessageDatabase database;
    final StorageCipher cipher;
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<String> outgoingFailures = new MutableLiveData<>();

    public static MessageStore get(Context context) {
        if (instance == null) {
            synchronized (MessageStore.class) {
                if (instance == null) {
                    try {
                        instance =
                                new MessageStore(
                                        Room.databaseBuilder(
                                                        context.getApplicationContext(),
                                                        MessageDatabase.class,
                                                        "offgrid-messages.db")
                                                .addMigrations(
                                                        MessageDatabase.MIGRATION_1_2,
                                                        MessageDatabase.MIGRATION_2_3,
                                                        MessageDatabase.MIGRATION_3_4,
                                                        MessageDatabase.MIGRATION_4_5)
                                                .build(),
                                        new StorageCipher());
                    } catch (Exception e) {
                        throw new IllegalStateException("Encrypted message storage unavailable", e);
                    }
                }
            }
        }
        return instance;
    }

    MessageStore(MessageDatabase database, StorageCipher cipher) {
        this.database = database;
        this.cipher = cipher;
    }

    private volatile String visibleConversation;

    public void visibleConversation(String id) {
        visibleConversation = id;
    }

    public boolean isConversationVisible(String id) {
        return id != null && id.equals(visibleConversation);
    }

    /** Mark only the rows actually rendered, never newer arrivals racing the history load. */
    public void markRead(String conversation, List<String> ids) {
        historyExecutor.execute(() -> {
            if (isConversationVisible(conversation) && !ids.isEmpty())
                dao().markRead(conversation, ids);
        });
    }

    public MessageDao dao() {
        return database.messages();
    }

    public LiveData<String> outgoingFailures() {
        return outgoingFailures;
    }

    public void outgoingFailed(String id) {
        outgoingFailures.postValue(id);
    }

    public void refreshExpiration(Runnable onError) {
        historyExecutor.execute(() -> {
            try {
                expire(System.currentTimeMillis());
            } catch (RuntimeException e) {
                main.post(onError);
            }
        });
    }

    /** Atomically saves the display copy and relay packet before either is acknowledged locally. */
    public void save(
            MeshMessage wire,
            MeshMessage display,
            boolean outgoing,
            long displayTime,
            MeshMessage relay,
            long storedAt,
            long expiresAt)
            throws Exception {
        save(wire, display, outgoing, displayTime, relay, storedAt, expiresAt, false);
    }

    public void save(
            MeshMessage wire,
            MeshMessage display,
            boolean outgoing,
            long displayTime,
            MeshMessage relay,
            long storedAt,
            long expiresAt,
            boolean rememberInbound)
            throws Exception {
        ChatRecord chat = null;
        if (display != null) {
            chat = new ChatRecord();
            chat.id = wire.getMessageId();
            chat.conversationId =
                    wire.isPrivate()
                            ? (outgoing ? wire.getTargetId() : wire.getSenderId())
                            : "public";
            chat.outgoing = outgoing;
            chat.read = outgoing;
            chat.displayTimeMs = displayTime;
            chat.status = outgoing ? "QUEUED" : "RECEIVED";
            JSONObject body =
                    new JSONObject().put("wire", wire.toJson()).put("text", display.getText());
            if (!outgoing && relay != null && relay.isAcknowledgement()) {
                body.put("receipt", relay.toJson());
            }
            chat.content =
                    cipher.encrypt(
                            "history:" + chat.id, body.toString().getBytes(StandardCharsets.UTF_8));
        }
        PendingRecord pending = null;
        if (relay != null) {
            pending = new PendingRecord();
            pending.id = relay.getMessageId();
            pending.packet = cipher.encrypt("pending:" + pending.id, relay.toBytes());
            pending.storedAtMs = storedAt;
            pending.expiresAtMs = expiresAt;
        }
        ChatRecord finalChat = chat;
        PendingRecord finalPending = pending;
        database.runInTransaction(() -> {
            if (rememberInbound) rememberInbound(wire, storedAt);
            if (finalChat != null) dao().insertChat(finalChat);
            if (finalPending != null) dao().insertPending(finalPending);
        });
    }

    public MeshMessage decodePending(PendingRecord row) throws Exception {
        MeshMessage message =
                MeshMessage.fromBytes(cipher.decrypt("pending:" + row.id, row.packet));
        if (!row.id.equals(message.getMessageId()))
            throw new IllegalStateException("Packet ID mismatch");
        return message;
    }

    public ChatAdapter.ChatEntry decodeChat(ChatRecord row) throws Exception {
        JSONObject body = historyBody(row);
        if (body.optBoolean("withdrawn", false)) {
            WithdrawalRecord withdrawal = dao().withdrawal(row.id);
            if (withdrawal == null) throw new IllegalStateException("Missing deletion marker");
            MeshMessage placeholder =
                    MeshMessage.createPrivate(
                                    row.id,
                                    withdrawal.senderId,
                                    withdrawal.targetId,
                                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                                    withdrawal.senderKey,
                                    0,
                                    withdrawal.timestamp)
                            .withPlaintext(
                                    withdrawal.outgoing
                                            ? (withdrawal.confirmed
                                                    ? "You unsent this message"
                                                    : "Unsend requested")
                                            : "Message unsent");
            return new ChatAdapter.ChatEntry(
                    placeholder, row.outgoing, row.displayTimeMs, row.status, "text", 0);
        }
        MeshMessage wire = MeshMessage.fromJson(body.getJSONObject("wire"));
        if (!row.id.equals(wire.getMessageId()))
            throw new IllegalStateException("Message ID mismatch");
        MeshMessage display = wire.isPrivate() ? wire.withPlaintext(body.getString("text")) : wire;
        return new ChatAdapter.ChatEntry(
                display, row.outgoing, row.displayTimeMs, row.status, row.kind, row.progress);
    }

    private JSONObject historyBody(ChatRecord row) throws Exception {
        return new JSONObject(
                new String(
                        cipher.decrypt("history:" + row.id, row.content), StandardCharsets.UTF_8));
    }

    /** Accepts only already-authenticated ACK plaintext, bound to the exact outgoing packet. */
    public String confirmDelivery(MeshMessage ack, String authenticatedPayload) throws Exception {
        return confirmDelivery(ack, authenticatedPayload, false, System.currentTimeMillis());
    }

    public String confirmDelivery(
            MeshMessage ack, String authenticatedPayload, boolean rememberInbound, long now)
            throws Exception {
        DeliveryReceipt receipt = DeliveryReceipt.parse(authenticatedPayload);
        ChatRecord row = dao().chat(receipt.originalId);
        if (row == null
                || !row.outgoing
                || row.status.startsWith("UNSENT")
                || row.status.startsWith("UNSEND")) return null;
        MeshMessage original = MeshMessage.fromJson(historyBody(row).getJSONObject("wire"));
        if (!receipt.matches(ack, original)) return null;
        database.runInTransaction(() -> {
            if (rememberInbound) rememberInbound(ack, now);
            dao().markDelivered(original.getMessageId());
            dao().deletePending(original.getMessageId());
            MediaRecord transfer = dao().media(original.getMessageId());
            if (transfer != null && transfer.outgoing && !transfer.state.equals("CANCELLED")) {
                transfer.state = "COMPLETE";
                dao().cancelMediaPackets(transfer.id);
                dao().deleteParts(transfer.id);
                dao().putMedia(transfer);
            }
        });
        return original.getMessageId();
    }

    /** A duplicate can requeue the same cached ACK without displaying the chat again. */
    public MeshMessage cachedReceipt(MeshMessage incoming, long now) throws Exception {
        ChatRecord row = dao().chat(incoming.getMessageId());
        if (row == null || row.outgoing) return null;
        JSONObject body = historyBody(row);
        MeshMessage original = MeshMessage.fromJson(body.getJSONObject("wire"));
        if (!DeliveryReceipt.fingerprint(incoming).equals(DeliveryReceipt.fingerprint(original))
                || !body.has("receipt")) return null;
        MeshMessage ack = MeshMessage.fromJson(body.getJSONObject("receipt"));
        return now - ack.getTimestamp() < DELIVERY_TIMEOUT_MS ? ack : null;
    }

    public interface HistoryCallback {
        void loaded(List<ChatAdapter.ChatEntry> entries, boolean unreadable);
    }

    public void loadHistory(List<ChatRecord> rows, HistoryCallback callback) {
        historyExecutor.execute(() -> {
            List<ChatAdapter.ChatEntry> entries = new ArrayList<>();
            boolean unreadable = false;
            for (ChatRecord row : rows) {
                try {
                    ChatAdapter.ChatEntry entry = decodeChat(row);
                    if (row.kind.equals("image")) {
                        MediaRecord record = dao().media(row.id);
                        if (record != null
                                && record.file.length > 0
                                && !record.state.equals("CANCELLED")) {
                            entry.thumbnail = MediaThumbnails.load(row.id, record.file, cipher);
                        }
                    }
                    entries.add(entry);
                } catch (Exception e) {
                    unreadable = true;
                } // keep original rows; never reset the database
            }
            Collections.reverse(entries);
            boolean failed = unreadable;
            main.post(() -> callback.loaded(entries, failed));
        });
    }

    public void forwarded(String id, int count) {
        database.runInTransaction(() -> {
            dao().setForwardCount(id, count);
            dao().markForwarded(id); // Never downgrade DELIVERED after a late GATT callback.
        });
    }

    public void removePending(String id, String status) {
        database.runInTransaction(() -> {
            dao().deletePending(id);
            // Queue eviction must not leave a message labelled as still pending.
            dao().finishQueued(id, status);
        });
    }

    public void expire(long now) {
        database.runInTransaction(() -> {
            // Never prune evidence after a wall-clock rollback.
            if (clockSafe(now)) {
                advanceClock(now);
                dao().pruneReplay(now);
            }
            dao().expireStatuses(now - DELIVERY_TIMEOUT_MS);
            dao().deleteExpired(now);
        });
    }

    public boolean clockSafe(long now) {
        SecurityClock clock = dao().clock();
        return clock == null || now >= clock.highWaterMs - PacketSecurity.CLOCK_SKEW_MS;
    }

    private void advanceClock(long now) {
        SecurityClock clock = dao().clock();
        if (clock == null) clock = new SecurityClock();
        clock.highWaterMs = Math.max(clock.highWaterMs, now);
        dao().saveClock(clock);
    }

    public boolean canAcceptSequence(MeshMessage packet) {
        if (packet.getEpoch() == null || packet.getSequence() < 1) return false;
        SequenceRecord state = dao().sequence(packet.getSenderId() + ":" + packet.getEpoch());
        return state == null
                ? dao().sequenceCount() < 4096
                : new SequenceWindow(state.highest, state.bits).accept(packet.getSequence());
    }

    /** Called only inside the same transaction as history/outbox/status changes. */
    private void rememberInbound(MeshMessage packet, long now) {
        if (packet.getProtocolVersion() >= 5) {
            if (packet.getEpoch() == null || packet.getSequence() < 1)
                throw new IllegalStateException("Missing replay sequence");
            String key = packet.getSenderId() + ":" + packet.getEpoch();
            SequenceRecord state = dao().sequence(key);
            if (state == null) {
                if (dao().sequenceCount() >= 4096)
                    throw new IllegalStateException(
                            "Replay identity capacity reached; evidence preserved");
                state = new SequenceRecord();
                state.id = key;
            }
            SequenceWindow window = new SequenceWindow(state.highest, state.bits);
            if (!window.accept(packet.getSequence()))
                throw new IllegalStateException("Sequence replay or outside window");
            state.highest = window.highest();
            state.bits = window.encode();
            dao().saveSequence(state);
            // ID hints accelerate duplicate control handling; permanent sequence state is
            // authoritative.
            dao().pruneReplay(now);
            ReplayRecord hint = new ReplayRecord();
            hint.id = packet.getMessageId();
            hint.expiresAtMs = now + PacketSecurity.REPLAY_RETENTION_MS;
            dao().insertReplay(hint);
            dao().trimReplayHints();
            return;
        }
        if (!PacketSecurity.fresh(packet, now) || !clockSafe(now)) {
            throw new IllegalStateException("Packet outside trusted time window");
        }
        dao().pruneReplay(now);
        if (dao().replayCount() >= PacketSecurity.MAX_REPLAY_ENTRIES) {
            throw new IllegalStateException("Replay ledger full; unexpired evidence was kept");
        }
        ReplayRecord row = new ReplayRecord();
        row.id = packet.getMessageId();
        row.expiresAtMs = now + PacketSecurity.REPLAY_RETENTION_MS;
        dao().insertReplay(row); // a duplicate aborts the entire acceptance transaction
        advanceClock(now);
    }
}
