package com.example.offgrid;

import android.content.Context;
import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises real SQLite and Android Keystore rather than mocked persistence. */
@RunWith(AndroidJUnit4.class)
public class MessageStoreTest {
    private Context context;
    private String databaseName;
    private MessageDatabase database;
    private MessageStore store;
    private StorageCipher cipher;
    private final String sender = "11111111111111111111111111111111";
    private final String recipient = "22222222222222222222222222222222";

    @Before public void open() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "persistence-test-" + UUID.randomUUID() + ".db";
        cipher = new StorageCipher();
        reopen();
    }

    private void reopen() {
        if (database != null) database.close();
        database = Room.databaseBuilder(context, MessageDatabase.class, databaseName).build();
        store = new MessageStore(database, cipher);
    }

    @After public void close() {
        database.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void historyAndQueueSurviveDatabaseAndKeyReload() throws Exception {
        MeshMessage message = MeshMessage.create(sender, "Hello after restart");
        store.save(message, message, true, 100, message, 100, 10_000);
        cipher = new StorageCipher();
        reopen();
        assertEquals("Hello after restart", store.decodeChat(store.dao().history().get(0)).message.getText());
        assertEquals(message.getMessageId(), store.decodePending(store.dao().pending().get(0)).getMessageId());
        assertEquals("QUEUED", store.dao().history().get(0).status);
    }

    @Test public void privateDisplayTextIsEncryptedAndNeverBecomesRelayPayload() throws Exception {
        String secret = "private plaintext 🔒";
        MeshMessage wire = MeshMessage.createPrivate(UUID.randomUUID().toString(), sender, recipient,
                repeat('A', 40), repeat('B', 87), 7, 123);
        store.save(wire, wire.withPlaintext(secret), true, 123, wire, 123, 10_000);
        ChatRecord row = store.dao().history().get(0);
        assertEquals(recipient, row.conversationId);
        assertFalse(new String(row.content, StandardCharsets.UTF_8).contains(secret));
        assertFalse(new String(store.dao().pending().get(0).packet, StandardCharsets.UTF_8).contains(secret));
        reopen();
        assertEquals(secret, store.decodeChat(store.dao().history().get(0)).message.getText());
        MeshMessage pending = store.decodePending(store.dao().pending().get(0));
        assertTrue(pending.isPayloadEncrypted());
        assertArrayEquals(wire.toBytes(), pending.toBytes());
    }

    @Test public void duplicateDoesNotResetProgressOrCreateAnotherHistoryRow() throws Exception {
        MeshMessage message = MeshMessage.create(sender, "Once");
        store.save(message, message, true, 100, message, 100, 10_000);
        store.forwarded(message.getMessageId(), 3);
        store.save(message, message, true, 200, message, 200, 20_000);
        reopen();
        assertEquals(1, store.dao().history().size());
        assertEquals(1, store.dao().pending().size());
        assertEquals(3, store.dao().pending().get(0).forwardCount);
        assertEquals(10_000, store.dao().pending().get(0).expiresAtMs);
        assertEquals("FORWARDED", store.dao().history().get(0).status);
    }

    @Test public void expirationRemovesQueueButKeepsHistoryAndStatus() throws Exception {
        MeshMessage message = MeshMessage.create(sender, "Offline");
        store.save(message, message, true, 100, message, 100, 200);
        store.expire(100 + MessageStore.DELIVERY_TIMEOUT_MS);
        reopen();
        assertTrue(store.dao().pending().isEmpty());
        assertEquals("EXPIRED", store.dao().history().get(0).status);
        assertEquals("Offline", store.decodeChat(store.dao().history().get(0)).message.getText());
    }

    @Test public void incomingHistoryAndQueueCommitTogether() throws Exception {
        MeshMessage incoming = MeshMessage.create(sender, "Received with screen closed");
        store.save(incoming, incoming, false, 100, incoming.withDecrementedTtl(), 100, 10_000);
        reopen();
        assertFalse(store.dao().history().get(0).outgoing);
        assertEquals("RECEIVED", store.dao().history().get(0).status);
        assertEquals(incoming.getTtl() - 1, store.decodePending(store.dao().pending().get(0)).getTtl());
    }

    @Test public void queueWriteFailureRollsBackHistory() throws Exception {
        database.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER reject_pending BEFORE INSERT ON pending_messages "
                        + "BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END");
        MeshMessage message = MeshMessage.create(sender, "Must not partially save");
        try {
            store.save(message, message, true, 100, message, 100, 10_000);
            fail("Queue write should fail");
        } catch (android.database.sqlite.SQLiteException expected) { }
        assertTrue(store.dao().history().isEmpty());
        assertTrue(store.dao().pending().isEmpty());
    }

    @Test public void tamperingOrMovingCiphertextToAnotherRowFailsAuthentication() throws Exception {
        byte[] encrypted = cipher.encrypt("history:first", "secret".getBytes(StandardCharsets.UTF_8));
        assertFalse(Arrays.equals(encrypted,
                cipher.encrypt("history:first", "secret".getBytes(StandardCharsets.UTF_8))));
        try {
            cipher.decrypt("history:second", encrypted);
            fail("Row substitution must fail authentication");
        } catch (java.security.GeneralSecurityException expected) { }
        encrypted[encrypted.length - 1] ^= 1;
        try {
            cipher.decrypt("history:first", encrypted);
            fail("Tampering must fail authentication");
        } catch (java.security.GeneralSecurityException expected) { }
    }

    private static String repeat(char c, int count) {
        char[] text = new char[count];
        Arrays.fill(text, c);
        return new String(text);
    }
}
