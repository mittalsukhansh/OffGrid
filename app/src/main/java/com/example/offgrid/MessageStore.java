package com.example.offgrid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.room.Room;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Database operations run on the mesh thread or the history executor, never the UI thread. */
public final class MessageStore {
    public static final long DELIVERY_TIMEOUT_MS = 10 * 60_000L;
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
                        instance = new MessageStore(Room.databaseBuilder(context.getApplicationContext(),
                                MessageDatabase.class, "offgrid-messages.db")
                                .addMigrations(MessageDatabase.MIGRATION_1_2).build(), new StorageCipher());
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

    public MessageDao dao() { return database.messages(); }
    public LiveData<String> outgoingFailures() { return outgoingFailures; }
    public void outgoingFailed(String id) { outgoingFailures.postValue(id); }

    public void refreshExpiration(Runnable onError) {
        historyExecutor.execute(() -> {
            try { expire(System.currentTimeMillis()); }
            catch (RuntimeException e) { main.post(onError); }
        });
    }

    /** Atomically saves the display copy and relay packet before either is acknowledged locally. */
    public void save(MeshMessage wire, MeshMessage display, boolean outgoing,
                     long displayTime, MeshMessage relay, long storedAt, long expiresAt) throws Exception {
        save(wire, display, outgoing, displayTime, relay, storedAt, expiresAt, false);
    }

    public void save(MeshMessage wire, MeshMessage display, boolean outgoing,
                     long displayTime, MeshMessage relay, long storedAt, long expiresAt,
                     boolean rememberInbound) throws Exception {
        ChatRecord chat = null;
        if (display != null) {
            chat = new ChatRecord();
            chat.id = wire.getMessageId();
            chat.conversationId = wire.isPrivate()
                    ? (outgoing ? wire.getTargetId() : wire.getSenderId()) : "public";
            chat.outgoing = outgoing;
            chat.displayTimeMs = displayTime;
            chat.status = outgoing ? "QUEUED" : "RECEIVED";
            JSONObject body = new JSONObject().put("wire", wire.toJson())
                    .put("text", display.getText());
            if (!outgoing && relay != null && relay.isAcknowledgement()) {
                body.put("receipt", relay.toJson());
            }
            chat.content = cipher.encrypt("history:" + chat.id,
                    body.toString().getBytes(StandardCharsets.UTF_8));
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
        MeshMessage message = MeshMessage.fromBytes(cipher.decrypt("pending:" + row.id, row.packet));
        if (!row.id.equals(message.getMessageId())) throw new IllegalStateException("Packet ID mismatch");
        return message;
    }

    public ChatAdapter.ChatEntry decodeChat(ChatRecord row) throws Exception {
        JSONObject body = historyBody(row);
        MeshMessage wire = MeshMessage.fromJson(body.getJSONObject("wire"));
        if (!row.id.equals(wire.getMessageId())) throw new IllegalStateException("Message ID mismatch");
        MeshMessage display = wire.isPrivate() ? wire.withPlaintext(body.getString("text")) : wire;
        return new ChatAdapter.ChatEntry(display, row.outgoing, row.displayTimeMs, row.status);
    }

    private JSONObject historyBody(ChatRecord row) throws Exception {
        return new JSONObject(new String(cipher.decrypt("history:" + row.id, row.content),
                StandardCharsets.UTF_8));
    }

    /** Accepts only already-authenticated ACK plaintext, bound to the exact outgoing packet. */
    public String confirmDelivery(MeshMessage ack, String authenticatedPayload) throws Exception {
        return confirmDelivery(ack, authenticatedPayload, false, System.currentTimeMillis());
    }

    public String confirmDelivery(MeshMessage ack, String authenticatedPayload,
                                  boolean rememberInbound, long now) throws Exception {
        DeliveryReceipt receipt = DeliveryReceipt.parse(authenticatedPayload);
        ChatRecord row = dao().chat(receipt.originalId);
        if (row == null || !row.outgoing) return null;
        MeshMessage original = MeshMessage.fromJson(historyBody(row).getJSONObject("wire"));
        if (!receipt.matches(ack, original)) return null;
        database.runInTransaction(() -> {
            if (rememberInbound) rememberInbound(ack, now);
            dao().markDelivered(original.getMessageId());
            dao().deletePending(original.getMessageId());
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
                try { entries.add(decodeChat(row)); }
                catch (Exception e) { unreadable = true; } // keep original rows; never reset the database
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

    /** Called only inside the same transaction as history/outbox/status changes. */
    private void rememberInbound(MeshMessage packet, long now) {
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
