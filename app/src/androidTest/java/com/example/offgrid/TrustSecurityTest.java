package com.example.offgrid;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TrustSecurityTest {
    private Context context;
    private String name;
    private MessageDatabase database;
    private MessageStore store;
    private CryptoManager alice;
    private CryptoManager bob;
    private final List<String> preferences = new ArrayList<>();

    @Before public void open() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "trust-test-" + UUID.randomUUID();
        reopen();
        alice = peer("alice"); bob = peer("bob");
    }

    private void reopen() throws Exception {
        if (database != null) database.close();
        database = Room.databaseBuilder(context, MessageDatabase.class, name).build();
        store = new MessageStore(database, new StorageCipher());
    }

    private CryptoManager peer(String label) {
        return new CryptoManager(new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String key, int mode) {
                String isolated = name + label + key;
                preferences.add(isolated);
                return context.getSharedPreferences(isolated, mode);
            }
        });
    }

    @After public void close() {
        database.close(); context.deleteDatabase(name);
        for (String key : preferences) context.deleteSharedPreferences(key);
    }

    @Test public void signedBroadcastAuthenticatesIdentityAndAllowsHopChanges() throws Exception {
        MeshMessage signed = alice.signPacket(MeshMessage.create(alice.getLocalId(), "Authentic broadcast"));
        assertTrue(bob.verifyPacket(signed));
        assertTrue(bob.verifyPacket(MeshMessage.fromBytes(signed.toBytes()).withDecrementedTtl()));
        assertFalse(bob.verifyPacket(MeshMessage.create(alice.getLocalId(), "Unsigned impersonation")));
        assertFalse(bob.verifyPacket(MeshMessage.fromJson(signed.toJson().put("txt", "Altered content"))));
        assertFalse(bob.verifyPacket(MeshMessage.fromJson(signed.toJson().put("sid", bob.getLocalId()))));
        assertFalse(bob.verifyPacket(MeshMessage.fromJson(signed.toJson().put("ts", signed.getTimestamp() + 1))));
        assertFalse(bob.verifyPacket(MeshMessage.fromJson(signed.toJson().put("v", 2))));
        assertFalse(bob.verifyPacket(signed.withAuthentication(bob.getPublicKeyBase64(), signed.getSignature())));
    }

    @Test public void discoveryImportAndQrDoNotAutomaticallyVerifyAPerson() throws Exception {
        assertTrue(bob.registerAnnouncedKey(alice.getLocalId(), alice.getPublicKeyBase64()));
        assertFalse(bob.isVerified(alice.getLocalId()));
        assertEquals(alice.getLocalId(), bob.importContact(alice.getContactString()));
        assertFalse(bob.isVerified(alice.getLocalId()));
        String fingerprint = bob.contactFingerprint(alice.getLocalId());
        assertEquals(alice.getOwnFingerprint(), fingerprint);
        assertFalse(bob.setVerified(alice.getLocalId(), "wrong fingerprint", true));
        assertTrue(bob.setVerified(alice.getLocalId(), fingerprint, true));
        assertTrue(peer("bob").isVerified(alice.getLocalId()));
        assertTrue(bob.setVerified(alice.getLocalId(), fingerprint, false));
        assertFalse(peer("bob").isVerified(alice.getLocalId()));
    }

    @Test public void qrRoundTripProducesTheExactContactAndNoTrustFlag() throws Exception {
        Bitmap bitmap = ContactQr.bitmap(alice.getContactString(), 512);
        int[] pixels = new int[512 * 512];
        bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512);
        String decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(512, 512, pixels)))).getText();
        assertEquals(alice.getContactString(), decoded);
        assertEquals(alice.getLocalId(), bob.importContact(decoded));
        assertFalse(bob.isVerified(alice.getLocalId()));
    }

    @Test public void persistentReplayLedgerSurvivesQueueExpiryAndDatabaseReopen() throws Exception {
        MeshMessage packet = alice.signPacket(MeshMessage.createKeyAnnounce(alice.getLocalId(),
                alice.getPublicKeyBase64(), 0));
        long now = System.currentTimeMillis();
        store.save(packet, null, false, now, null, now, now + 600_000, true);
        reopen();
        assertTrue(store.dao().pending().isEmpty());
        assertTrue(store.dao().history().isEmpty());
        assertEquals(1, store.dao().replayed(packet.getMessageId()));
        try {
            store.save(packet, packet, false, now, packet, now, now + 600_000, true);
            fail("Replay must roll back history and queue insertion");
        } catch (android.database.sqlite.SQLiteConstraintException expected) { }
        assertTrue(store.dao().history().isEmpty());
        assertTrue(store.dao().pending().isEmpty());
    }

    @Test public void clockRollbackDoesNotEraseEvidenceOrAdmitAnotherPacket() throws Exception {
        long now = System.currentTimeMillis();
        MeshMessage packet = alice.signPacket(MeshMessage.create(alice.getLocalId(), "Original"));
        store.save(packet, packet, false, now, null, now, now + 600_000, true);
        store.expire(now - PacketSecurity.CLOCK_SKEW_MS - 1);
        assertFalse(store.clockSafe(now - PacketSecurity.CLOCK_SKEW_MS - 1));
        assertEquals(1, store.dao().replayed(packet.getMessageId()));
        assertTrue(store.clockSafe(now));
        long rollback = now - PacketSecurity.CLOCK_SKEW_MS - 1;
        MeshMessage another = alice.signPacket(MeshMessage.fromJson(
                MeshMessage.create(alice.getLocalId(), "After rollback").toJson().put("ts", rollback)));
        try {
            store.save(another, another, false, rollback, null, rollback, rollback + 600_000, true);
            fail("Rollback must prevent packet acceptance");
        } catch (IllegalStateException expected) { }
        assertEquals(0, store.dao().replayed(another.getMessageId()));
    }

    @Test public void replayEvidenceCanExpireOnlyAfterTheAcceptanceWindow() throws Exception {
        long now = System.currentTimeMillis();
        MeshMessage packet = alice.signPacket(MeshMessage.create(alice.getLocalId(), "Original"));
        store.save(packet, packet, false, now, null, now, now + 600_000, true);
        long later = now + PacketSecurity.REPLAY_RETENTION_MS;
        store.expire(later);
        assertEquals(0, store.dao().replayed(packet.getMessageId()));
        assertFalse(PacketSecurity.fresh(packet, later));
        assertFalse(store.clockSafe(now));
    }

    @Test public void fullLedgerRejectsNewWorkRatherThanEvictingReplayEvidence() throws Exception {
        long now = System.currentTimeMillis();
        database.runInTransaction(() -> {
            for (int i = 0; i < PacketSecurity.MAX_REPLAY_ENTRIES; i++) {
                ReplayRecord row = new ReplayRecord(); row.id = "existing-" + i;
                row.expiresAtMs = now + PacketSecurity.REPLAY_RETENTION_MS;
                store.dao().insertReplay(row);
            }
        });
        MeshMessage packet = alice.signPacket(MeshMessage.create(alice.getLocalId(), "Full ledger"));
        try {
            store.save(packet, packet, false, now, packet, now, now + 600_000, true);
            fail("Full ledger must not evict existing evidence");
        } catch (IllegalStateException expected) { }
        assertEquals(PacketSecurity.MAX_REPLAY_ENTRIES, store.dao().replayCount());
        assertTrue(store.dao().history().isEmpty());
        assertTrue(store.dao().pending().isEmpty());
    }

    @Test public void failedOutboxWriteRollsBackReplayEvidenceAndAcceptance() throws Exception {
        database.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER reject_secure_pending BEFORE INSERT ON pending_messages "
                        + "BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END");
        MeshMessage packet = alice.signPacket(MeshMessage.create(alice.getLocalId(), "Must roll back"));
        long now = System.currentTimeMillis();
        try {
            store.save(packet, packet, false, now, packet, now, now + 600_000, true);
            fail("Failed storage must not burn the replay ID of an unaccepted message");
        } catch (android.database.sqlite.SQLiteException expected) { }
        assertEquals(0, store.dao().replayed(packet.getMessageId()));
        assertNull(store.dao().clock());
        assertTrue(store.dao().history().isEmpty());
        assertTrue(store.dao().pending().isEmpty());
    }
}
