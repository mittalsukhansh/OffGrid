package com.example.offgrid;

import static org.junit.Assert.*;

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

@RunWith(AndroidJUnit4.class)
public class TrustSecurityTest {
    private Context context;
    private String name;
    private MessageDatabase database;
    private MessageStore store;
    private CryptoManager alice;
    private CryptoManager bob;
    private final List<String> preferences = new ArrayList<>();

    @Before
    public void open() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "trust-test-" + UUID.randomUUID();
        reopen();
        alice = peer("alice");
        bob = peer("bob");
    }

    private void reopen() throws Exception {
        if (database != null) database.close();
        database = Room.databaseBuilder(context, MessageDatabase.class, name).build();
        store = new MessageStore(database, new StorageCipher());
    }

    private CryptoManager peer(String label) {
        return new CryptoManager(
                new ContextWrapper(context) {
                    @Override
                    public SharedPreferences getSharedPreferences(String key, int mode) {
                        String isolated = name + label + key;
                        preferences.add(isolated);
                        return context.getSharedPreferences(isolated, mode);
                    }
                });
    }

    @After
    public void close() {
        database.close();
        context.deleteDatabase(name);
        for (String key : preferences) context.deleteSharedPreferences(key);
    }

    @Test
    public void signedBroadcastAuthenticatesIdentityAndAllowsHopChanges() throws Exception {
        MeshMessage signed =
                alice.signPacket(MeshMessage.create(alice.getLocalId(), "Authentic broadcast"));
        assertTrue(bob.verifyPacket(signed));
        assertTrue(bob.verifyPacket(MeshMessage.fromBytes(signed.toBytes()).withDecrementedTtl()));
        assertFalse(
                bob.verifyPacket(MeshMessage.create(alice.getLocalId(), "Unsigned impersonation")));
        assertFalse(
                bob.verifyPacket(
                        MeshMessage.fromJson(signed.toJson().put("txt", "Altered content"))));
        assertFalse(
                bob.verifyPacket(
                        MeshMessage.fromJson(signed.toJson().put("sid", bob.getLocalId()))));
        assertFalse(
                bob.verifyPacket(
                        MeshMessage.fromJson(
                                signed.toJson().put("ts", signed.getTimestamp() + 1))));
        assertFalse(bob.verifyPacket(MeshMessage.fromJson(signed.toJson().put("v", 2))));
        assertFalse(
                bob.verifyPacket(
                        signed.withAuthentication(
                                bob.getPublicKeyBase64(), signed.getSignature())));
    }

    @Test
    public void discoveryImportAndQrDoNotAutomaticallyVerifyAPerson() throws Exception {
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

    @Test
    public void qrRoundTripProducesTheExactContactAndNoTrustFlag() throws Exception {
        Bitmap bitmap = ContactQr.bitmap(alice.getContactString(), 512);
        int[] pixels = new int[512 * 512];
        bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512);
        String decoded =
                new MultiFormatReader()
                        .decode(
                                new BinaryBitmap(
                                        new HybridBinarizer(
                                                new RGBLuminanceSource(512, 512, pixels))))
                        .getText();
        assertEquals(alice.getContactString(), decoded);
        assertEquals(alice.getLocalId(), bob.importContact(decoded));
        assertFalse(bob.isVerified(alice.getLocalId()));
    }

    @Test
    public void persistentReplayLedgerSurvivesQueueExpiryAndDatabaseReopen() throws Exception {
        MeshMessage packet =
                alice.signPacket(
                        MeshMessage.createKeyAnnounce(
                                alice.getLocalId(), alice.getPublicKeyBase64(), 0));
        long now = System.currentTimeMillis();
        store.save(packet, null, false, now, null, now, now + 600_000, true);
        reopen();
        assertTrue(store.dao().pending().isEmpty());
        assertTrue(store.dao().history().isEmpty());
        assertEquals(1, store.dao().replayed(packet.getMessageId()));
        try {
            store.save(packet, packet, false, now, packet, now, now + 600_000, true);
            fail("Replay must roll back history and queue insertion");
        } catch (IllegalStateException expected) {
        }
        assertTrue(store.dao().history().isEmpty());
        assertTrue(store.dao().pending().isEmpty());
    }

    @Test
    public void clockRollbackDoesNotStallSequenceAcceptance() throws Exception {
        long now = System.currentTimeMillis();
        MeshMessage packet =
                alice.signPacket(MeshMessage.create(alice.getLocalId(), "Before rollback"));
        store.save(packet, packet, false, now, null, now, now + 600000, true);
        long rollback = now - 86400000;
        store.expire(rollback);
        MeshMessage another =
                alice.signPacket(MeshMessage.create(alice.getLocalId(), "After rollback"));
        store.save(another, another, false, rollback, null, rollback, rollback + 600000, true);
        assertFalse(store.canAcceptSequence(packet));
        assertFalse(store.canAcceptSequence(another));
        assertEquals(2, store.dao().history().size());
    }

    @Test
    public void sequenceEvidenceNeverExpiresWithTheIdHint() throws Exception {
        long now = System.currentTimeMillis();
        MeshMessage packet = alice.signPacket(MeshMessage.create(alice.getLocalId(), "Original"));
        store.save(packet, packet, false, now, null, now, now + 600000, true);
        store.expire(now + 100 * PacketSecurity.REPLAY_RETENTION_MS);
        reopen();
        assertFalse(store.canAcceptSequence(packet));
        assertEquals(1, store.dao().sequenceCount());
    }

    @Test
    public void fullSequenceCapacityPreservesEvidenceAndRejectsNewEpochs() throws Exception {
        database.runInTransaction(() -> {
            for (int i = 0; i < 4096; i++) {
                SequenceRecord row = new SequenceRecord();
                row.id = "existing-" + i;
                store.dao().saveSequence(row);
            }
        });
        MeshMessage packet =
                alice.signPacket(MeshMessage.create(alice.getLocalId(), "Full ledger"));
        long now = System.currentTimeMillis();
        assertFalse(store.canAcceptSequence(packet));
        try {
            store.save(packet, packet, false, now, packet, now, now + 600000, true);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(4096, store.dao().sequenceCount());
        assertTrue(store.dao().history().isEmpty());
    }

    @Test
    public void separateAgreementKeysAndEphemeralEncryptionBindSignedMetadata() throws Exception {
        alice.importContact(bob.getContactString());
        bob.importContact(alice.getContactString());
        String id = MeshMessage.newId(alice.getLocalId());
        long now = System.currentTimeMillis();
        String first = alice.encrypt(id, bob.getLocalId(), now, "Ephemeral test");
        String second = alice.encrypt(id, bob.getLocalId(), now, "Ephemeral test");
        assertNotEquals(first.substring(0, 86), second.substring(0, 86));
        MeshMessage packet =
                alice.signPacket(
                        MeshMessage.createPrivate(
                                id,
                                alice.getLocalId(),
                                bob.getLocalId(),
                                first,
                                alice.getPublicKeyBase64(),
                                7,
                                now));
        assertNotEquals(packet.getSenderKey(), packet.getAgreementKey());
        assertEquals("Ephemeral test", bob.decrypt(packet));
        assertFalse(
                bob.verifyPacket(
                        MeshMessage.fromJson(
                                packet.toJson().put("seq", packet.getSequence() + 1))));
        assertFalse(
                bob.verifyPacket(
                        MeshMessage.fromJson(
                                packet.toJson().put("apk", bob.getPublicKeyBase64()))));
        MeshMessage afterRestart =
                peer("alice").signPacket(MeshMessage.create(alice.getLocalId(), "Restarted"));
        assertTrue(afterRestart.getSequence() > packet.getSequence());
        assertEquals(afterRestart.getEpoch(), packet.getEpoch());
    }

    @Test
    public void encryptedBackupRestoresKeysContactsAndStartsAFreshSequenceEpoch() throws Exception {
        alice.importContact(bob.getContactString());
        String fp = alice.contactFingerprint(bob.getLocalId());
        alice.setNickname(bob.getLocalId(), "Bob");
        alice.setVerified(bob.getLocalId(), fp, true);
        MeshMessage before =
                alice.signPacket(MeshMessage.create(alice.getLocalId(), "Before backup"));
        char[] password = "A long test backup phrase".toCharArray();
        byte[] encrypted = alice.exportIdentity(password);
        CryptoManager restored = peer("restored");
        restored.restoreIdentity(encrypted, password);
        assertEquals(alice.getLocalId(), restored.getLocalId());
        assertEquals(alice.getOwnFingerprint(), restored.getOwnFingerprint());
        assertEquals("Bob", restored.nickname(bob.getLocalId()));
        assertTrue(restored.isVerified(bob.getLocalId()));
        MeshMessage after =
                restored.signPacket(MeshMessage.create(restored.getLocalId(), "Restored"));
        assertNotEquals(before.getEpoch(), after.getEpoch());
        assertTrue(bob.verifyPacket(after));
        bob.importContact(restored.getContactString());
        String id = MeshMessage.newId(bob.getLocalId());
        long now = System.currentTimeMillis();
        MeshMessage privatePacket =
                bob.signPacket(
                        MeshMessage.createPrivate(
                                id,
                                bob.getLocalId(),
                                restored.getLocalId(),
                                bob.encrypt(id, restored.getLocalId(), now, "Restored reply"),
                                bob.getPublicKeyBase64(),
                                7,
                                now));
        assertEquals("Restored reply", restored.decrypt(privatePacket));
        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 1;
        try {
            restored.restoreIdentity(tampered, password);
            fail();
        } catch (java.security.GeneralSecurityException expected) {
        }
        assertEquals(alice.getLocalId(), restored.getLocalId());
    }

    @Test
    public void failedOutboxWriteRollsBackReplayEvidenceAndAcceptance() throws Exception {
        database.getOpenHelper()
                .getWritableDatabase()
                .execSQL(
                        "CREATE TRIGGER reject_secure_pending BEFORE INSERT ON pending_messages "
                                + "BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END");
        MeshMessage packet =
                alice.signPacket(MeshMessage.create(alice.getLocalId(), "Must roll back"));
        long now = System.currentTimeMillis();
        try {
            store.save(packet, packet, false, now, packet, now, now + 600_000, true);
            fail("Failed storage must not burn the replay ID of an unaccepted message");
        } catch (android.database.sqlite.SQLiteException expected) {
        }
        assertEquals(0, store.dao().replayed(packet.getMessageId()));
        assertNull(store.dao().clock());
        assertTrue(store.dao().history().isEmpty());
        assertTrue(store.dao().pending().isEmpty());
    }

    @Test
    public void unreadableStoredIdentityNeverSilentlyReplacesItsPublicIdentity() throws Exception {
        SharedPreferences saved = context.getSharedPreferences(name + "aliceoffgrid_identity", 0);
        String original = saved.getString("private_key_wrapped", null);
        assertNotNull(original);
        assertTrue(saved.edit().putString("private_key_wrapped", "AA").commit());
        try {
            peer("alice");
            fail("Corrupt identity was silently replaced");
        } catch (IllegalStateException expected) {
        }
        assertEquals(alice.getPublicKeyBase64(), saved.getString("public_key", null));
        assertEquals("AA", saved.getString("private_key_wrapped", null));
        assertTrue(saved.edit().putString("private_key_wrapped", original).commit());
        assertEquals(alice.getLocalId(), peer("alice").getLocalId());
    }
}
