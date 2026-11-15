package com.example.offgrid;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Three independent identities exercise real ECDH, GCM, Android Keystore, and Room. */
@RunWith(AndroidJUnit4.class)
public class AuthenticatedDeliveryTest {
    private Context context;
    private String name;
    private MessageDatabase database;
    private MessageStore store;
    private CryptoManager alice;
    private CryptoManager bob;
    private CryptoManager charlie;
    private MeshMessage original;
    private final List<String> preferences = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "receipt-test-" + UUID.randomUUID();
        database = Room.databaseBuilder(context, MessageDatabase.class, name).build();
        store = new MessageStore(database, new StorageCipher());
        alice = peer("alice"); bob = peer("bob"); charlie = peer("charlie");
        alice.importContact(bob.getContactString());
        alice.importContact(charlie.getContactString());
        bob.importContact(alice.getContactString());
        charlie.importContact(alice.getContactString());
        String id = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        original = MeshMessage.createPrivate(id, alice.getLocalId(), bob.getLocalId(),
                alice.encrypt(id, bob.getLocalId(), timestamp, "Private test message"),
                alice.getPublicKeyBase64(), 7, timestamp);
        store.save(original, original.withPlaintext("Private test message"), true, timestamp,
                original, timestamp, timestamp + MessageStore.DELIVERY_TIMEOUT_MS);
    }

    private CryptoManager peer(String label) {
        return new CryptoManager(new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String key, int mode) {
                String isolated = name + "-" + label + "-" + key;
                preferences.add(isolated);
                return context.getSharedPreferences(isolated, mode);
            }
        });
    }

    @After public void tearDown() {
        database.close();
        context.deleteDatabase(name);
        for (String key : preferences) context.deleteSharedPreferences(key);
    }

    private MeshMessage receipt() throws Exception {
        assertEquals("Private test message", bob.decrypt(original));
        return bob.createAcknowledgement(original);
    }

    @Test public void authenticatedReceiptMarksDeliveredAndSurvivesReopen() throws Exception {
        MeshMessage ack = receipt();
        assertEquals(original.getMessageId(), store.confirmDelivery(ack, alice.decryptAcknowledgement(ack)));
        assertTrue(store.dao().pending().isEmpty());
        database.close();
        database = Room.databaseBuilder(context, MessageDatabase.class, name).build();
        store = new MessageStore(database, new StorageCipher());
        assertEquals("DELIVERED", store.dao().chat(original.getMessageId()).status);
        assertEquals("Private test message", store.decodeChat(store.dao().history().get(0)).message.getText());
    }

    @Test public void authenticatedThirdPartyReceiptCannotConfirmBobsMessage() throws Exception {
        MeshMessage wrongRecipient = MeshMessage.createPrivate(original.getMessageId(), alice.getLocalId(),
                charlie.getLocalId(), original.getText(), alice.getPublicKeyBase64(), 7, original.getTimestamp());
        MeshMessage ack = charlie.createAcknowledgement(wrongRecipient);
        assertNull(store.confirmDelivery(ack, alice.decryptAcknowledgement(ack)));
        assertEquals("QUEUED", store.dao().chat(original.getMessageId()).status);
        assertEquals(1, store.dao().pending().size());
    }

    @Test public void privateCiphertextCannotBeRetypedAsAcknowledgement() throws Exception {
        String id = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        String privateCiphertext = bob.encrypt(id, alice.getLocalId(), timestamp,
                DeliveryReceipt.payloadFor(original));
        MeshMessage reflected = MeshMessage.createAcknowledgement(id, bob.getLocalId(), alice.getLocalId(),
                privateCiphertext, bob.getPublicKeyBase64(), timestamp);
        try { alice.decryptAcknowledgement(reflected); fail("Purpose separation must reject this"); }
        catch (GeneralSecurityException expected) { }
        assertEquals("QUEUED", store.dao().chat(original.getMessageId()).status);
    }

    @Test public void forgedEnvelopeAndTamperedCiphertextAreRejected() throws Exception {
        MeshMessage ack = receipt();
        MeshMessage forged = MeshMessage.createAcknowledgement(ack.getMessageId(), charlie.getLocalId(),
                alice.getLocalId(), ack.getText(), bob.getPublicKeyBase64(), ack.getTimestamp());
        try { alice.decryptAcknowledgement(forged); fail("Sender/key mismatch must be rejected"); }
        catch (GeneralSecurityException expected) { }
        String ciphertext = (ack.getText().charAt(0) == 'A' ? "B" : "A") + ack.getText().substring(1);
        MeshMessage tampered = MeshMessage.createAcknowledgement(ack.getMessageId(), bob.getLocalId(),
                alice.getLocalId(), ciphertext, bob.getPublicKeyBase64(), ack.getTimestamp());
        try { alice.decryptAcknowledgement(tampered); fail("Tampering must be rejected"); }
        catch (GeneralSecurityException expected) { }
    }

    @Test public void replayLateForwardCallbackAndExpirationCannotDowngradeDelivered() throws Exception {
        MeshMessage ack = receipt();
        String payload = alice.decryptAcknowledgement(ack);
        store.confirmDelivery(ack, payload);
        store.confirmDelivery(ack, payload);
        store.forwarded(original.getMessageId(), 2);
        store.expire(original.getTimestamp() + MessageStore.DELIVERY_TIMEOUT_MS);
        store.removePending(original.getMessageId(), "FAILED");
        assertEquals("DELIVERED", store.dao().chat(original.getMessageId()).status);
        assertTrue(store.dao().pending().isEmpty());
    }

    @Test public void unconfirmedForwardedMessageExpiresAndLateReceiptCanConfirmIt() throws Exception {
        MeshMessage ack = receipt();
        store.forwarded(original.getMessageId(), 1);
        store.expire(original.getTimestamp() + MessageStore.DELIVERY_TIMEOUT_MS);
        assertEquals("EXPIRED", store.dao().chat(original.getMessageId()).status);
        store.confirmDelivery(ack, alice.decryptAcknowledgement(ack));
        assertEquals("DELIVERED", store.dao().chat(original.getMessageId()).status);
    }

    @Test public void recipientAtomicallyCachesReceiptAndReusesItAfterRestart() throws Exception {
        MeshMessage ack = receipt();
        String recipientDatabase = name + "-recipient";
        MessageDatabase db = Room.databaseBuilder(context, MessageDatabase.class, recipientDatabase).build();
        try {
            MessageStore inbox = new MessageStore(db, new StorageCipher());
            long now = System.currentTimeMillis();
            inbox.save(original, original.withPlaintext("Private test message"), false, now, ack,
                    now, now + MessageStore.DELIVERY_TIMEOUT_MS);
            db.close();
            db = Room.databaseBuilder(context, MessageDatabase.class, recipientDatabase).build();
            inbox = new MessageStore(db, new StorageCipher());
            assertEquals(1, inbox.dao().history().size());
            assertEquals("RECEIVED", inbox.dao().history().get(0).status);
            assertArrayEquals(ack.toBytes(), inbox.decodePending(inbox.dao().pending().get(0)).toBytes());
            assertArrayEquals(ack.toBytes(), inbox.cachedReceipt(original.withDecrementedTtl(), now).toBytes());
            assertNull(inbox.cachedReceipt(original, ack.getTimestamp() + MessageStore.DELIVERY_TIMEOUT_MS));
        } finally {
            db.close();
            context.deleteDatabase(recipientDatabase);
        }
    }

    @Test public void queueFailureIsPersistedWithoutOverwritingDeliveredState() throws Exception {
        store.removePending(original.getMessageId(), "FAILED");
        database.close();
        database = Room.databaseBuilder(context, MessageDatabase.class, name).build();
        store = new MessageStore(database, new StorageCipher());
        assertEquals("FAILED", store.dao().chat(original.getMessageId()).status);
        assertTrue(store.dao().pending().isEmpty());
    }
}
