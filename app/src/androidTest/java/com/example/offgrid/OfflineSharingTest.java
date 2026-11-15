package com.example.offgrid;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.offgrid.mesh.protocol.MeshProtocol;
import com.example.offgrid.mesh.storage.RoomMeshStorage;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real crypto and SQLite; transport is direct logical-node delivery, not a Bluetooth test. */
@RunWith(AndroidJUnit4.class)
public class OfflineSharingTest {
    Context context;
    String prefix;
    Peer a, b, c;
    final AtomicLong elapsed = new AtomicLong(1000);
    final List<String> preferences = new ArrayList<>();
    final Set<String> deliveredPackets = new HashSet<>();

    @Before
    public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "share-" + UUID.randomUUID();
        a = new Peer("A");
        b = new Peer("B");
        c = new Peer("C");
        for (Peer p : new Peer[] {a, b, c})
            for (Peer other : new Peer[] {a, b, c})
                if (p != other) p.crypto.importContact(other.crypto.getContactString());
    }

    @After
    public void cleanup() {
        for (Peer p : new Peer[] {a, b, c})
            if (p != null) {
                p.db.close();
                context.deleteDatabase(p.name);
            }
        for (String key : preferences) context.deleteSharedPreferences(key);
    }

    MeshMessage text() throws Exception {
        MeshProtocol.Outgoing sent =
                a.protocol.privateMessage(
                        c.crypto.getLocalId(),
                        "Remove this private text",
                        System.currentTimeMillis());
        a.storage.save(sent.wire, sent.display, true, sent.wire, System.currentTimeMillis());
        return sent.wire;
    }

    MeshMessage queued(Peer peer, String id) throws Exception {
        return peer.store.decodePending(peer.store.dao().pendingById(id));
    }

    @Test
    public void nicknamesAreLocalCanDuplicateAndDoNotChangeIdentityOrTrust() throws Exception {
        String id = c.crypto.getLocalId(), fingerprint = a.crypto.contactFingerprint(id);
        a.crypto.setVerified(id, fingerprint, true);
        a.crypto.setNickname(id, "Chris");
        a.crypto.setNickname(b.crypto.getLocalId(), "Chris");
        assertTrue(a.crypto.nicknameInUse(id, "Chris"));
        assertTrue(a.crypto.isVerified(id));
        a.reloadIdentity();
        assertEquals("Chris", a.crypto.nickname(id));
        assertTrue(a.crypto.isVerified(id));
        assertEquals("", c.crypto.nickname(a.crypto.getLocalId()));
        assertTrue(a.crypto.contactLabel(id).contains(CryptoManager.formatId(id, 8)));
    }

    @Test
    public void authenticatedUnsendErasesBothAppCopiesAndConfirmsWithoutResurrection()
            throws Exception {
        MeshMessage wire = text();
        c.receive(a, wire);
        a.actions.unsend(wire.getMessageId(), System.currentTimeMillis());
        WithdrawalRecord marker = a.store.dao().withdrawal(wire.getMessageId());
        assertEquals("UNSEND_PENDING", a.store.dao().chat(wire.getMessageId()).status);
        MeshProtocol.Incoming response = c.receive(a, queued(a, marker.requestId));
        assertNotNull(response.relay);
        a.receive(c, response.relay);
        assertEquals("UNSENT", a.store.dao().chat(wire.getMessageId()).status);
        assertEquals(
                "Message unsent",
                c.store.decodeChat(c.store.dao().chat(wire.getMessageId())).message.getText());
        c.reopen();
        assertSame(MeshProtocol.Incoming.IGNORED, c.receive(a, wire));
        assertEquals(1, c.store.dao().history().size());
        assertFalse(
                new String(
                                c.store.cipher.decrypt(
                                        "history:" + wire.getMessageId(),
                                        c.store.dao().chat(wire.getMessageId()).content),
                                java.nio.charset.StandardCharsets.UTF_8)
                        .contains(wire.getText()));
    }

    @Test
    public void withdrawalBeforeOriginalAndRetryAreSafe() throws Exception {
        MeshMessage wire = text();
        a.actions.unsend(wire.getMessageId(), System.currentTimeMillis());
        WithdrawalRecord first = a.store.dao().withdrawal(wire.getMessageId());
        MeshMessage request = queued(a, first.requestId);
        c.receive(a, request);
        assertSame(MeshProtocol.Incoming.IGNORED, c.receive(a, wire));
        assertTrue(c.store.dao().history().isEmpty());
        a.actions.unsend(wire.getMessageId(), System.currentTimeMillis());
        WithdrawalRecord retry = a.store.dao().withdrawal(wire.getMessageId());
        assertNotEquals(first.requestId, retry.requestId);
        MeshProtocol.Incoming confirmed = c.receive(a, queued(a, retry.requestId));
        assertNotNull(confirmed.relay);
        a.receive(c, confirmed.relay);
        assertTrue(a.store.dao().withdrawal(wire.getMessageId()).confirmed);
    }

    @Test
    public void anotherAuthorCannotWithdrawOrConfirmSomeoneElsesMessage() throws Exception {
        MeshMessage wire = text();
        c.receive(a, wire);
        MeshMessage forged =
                b.crypto.control(
                        c.crypto.getLocalId(),
                        new JSONObject()
                                .put("kind", "withdraw")
                                .put("mid", wire.getMessageId())
                                .put("when", wire.getTimestamp())
                                .toString());
        assertSame(MeshProtocol.Incoming.IGNORED, c.receive(b, forged));
        assertNull(c.store.dao().withdrawal(wire.getMessageId()));
        a.actions.unsend(wire.getMessageId(), System.currentTimeMillis());
        WithdrawalRecord marker = a.store.dao().withdrawal(wire.getMessageId());
        MeshMessage fakeAck =
                b.crypto.control(
                        a.crypto.getLocalId(),
                        new JSONObject()
                                .put("kind", "withdraw_ack")
                                .put("mid", wire.getMessageId())
                                .put("request", marker.requestId)
                                .toString());
        assertSame(MeshProtocol.Incoming.IGNORED, a.receive(b, fakeAck));
        assertFalse(a.store.dao().withdrawal(wire.getMessageId()).confirmed);
    }

    byte[] photo() {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff3488cc);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    MediaRecord offer(byte[] file, String mime) throws Exception {
        a.actions.offer(c.crypto.getLocalId(), file, mime, System.currentTimeMillis());
        MediaRecord record = a.store.dao().activeMedia().get(0);
        MeshProtocol.Incoming received = c.receive(a, queued(a, record.id));
        assertNotNull(received.relay);
        a.receive(c, received.relay);
        return record;
    }

    void drain(Peer from, Peer to) throws Exception {
        // Deliver each signed packet only once per pass; persistent replay protection handles later
        // repeats.
        List<PendingRecord> rows = from.store.dao().pending();
        for (PendingRecord row : rows) {
            MeshMessage packet = from.store.decodePending(row);
            if (!deliveredPackets.add(from.label + ":" + to.label + ":" + packet.getMessageId()))
                continue;
            MeshProtocol.Incoming result = to.receive(from, packet);
            if (result.relay != null) from.receive(to, result.relay);
        }
    }

    @Test
    public void reorderedDuplicateChunksAndPartialProgressSurviveRestart() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[128 * 128];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | random.nextInt(0x1000000);
        bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 128);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output);
        bitmap.recycle();
        byte[] image = output.toByteArray();
        MediaRecord record = offer(image, "image/jpeg");
        assertTrue(record.chunkCount > 4);
        for (int index : new int[] {2, 0, 2, 1}) {
            byte[] part =
                    Arrays.copyOfRange(
                            image,
                            index * MediaTransfers.CHUNK,
                            Math.min(image.length, (index + 1) * MediaTransfers.CHUNK));
            MeshMessage chunk =
                    a.crypto.control(
                            c.crypto.getLocalId(),
                            new JSONObject()
                                    .put("kind", "media_chunk")
                                    .put("mid", record.id)
                                    .put("index", index)
                                    .put(
                                            "data",
                                            Base64.getUrlEncoder()
                                                    .withoutPadding()
                                                    .encodeToString(part))
                                    .toString());
            MeshProtocol.Incoming response = c.receive(a, chunk);
            if (response.relay != null) a.receive(c, response.relay);
        }
        assertEquals(3, c.store.dao().parts(record.id).size());
        c.reopen();
        assertEquals(3, BitSet.valueOf(c.store.dao().media(record.id).received).cardinality());
        for (int n = 0; n < 80 && !a.store.dao().chat(record.id).status.equals("DELIVERED"); n++) {
            elapsed.addAndGet(5000);
            a.actions.pump(System.currentTimeMillis());
            drain(a, c);
        }
        assertEquals("DELIVERED", a.store.dao().chat(record.id).status);
        assertArrayEquals(image, c.actions.attachment(record.id));
    }

    @Test
    public void imageChunksCompleteOnlyAfterIntegrityValidationAndSurviveRestart()
            throws Exception {
        byte[] image = photo();
        MediaRecord record = offer(image, "image/jpeg");
        assertEquals("TRANSFERRING", a.store.dao().chat(record.id).status);
        c.reopen();
        for (int n = 0; n < 30 && !a.store.dao().chat(record.id).status.equals("DELIVERED"); n++) {
            elapsed.addAndGet(5000);
            a.actions.pump(System.currentTimeMillis());
            drain(a, c);
        }
        assertEquals("DELIVERED", a.store.dao().chat(record.id).status);
        assertEquals("READY", c.store.dao().chat(record.id).status);
        assertArrayEquals(image, c.actions.attachment(record.id));
        MeshMessage late =
                c.crypto.control(
                        a.crypto.getLocalId(),
                        new JSONObject()
                                .put("kind", "media_status")
                                .put("mid", record.id)
                                .put("bits", "")
                                .toString());
        a.receive(c, late);
        assertEquals("DELIVERED", a.store.dao().chat(record.id).status);
    }

    @Test
    public void corruptChunkNeverProducesDeliveredAndUnsendCancelsAttachment() throws Exception {
        byte[] image = photo();
        MediaRecord record = offer(image, "image/jpeg");
        for (int i = 0; i < record.chunkCount; i++) {
            byte[] part =
                    Arrays.copyOfRange(
                            image,
                            i * MediaTransfers.CHUNK,
                            Math.min(image.length, (i + 1) * MediaTransfers.CHUNK));
            part[0] ^= 1;
            MeshMessage chunk =
                    a.crypto.control(
                            c.crypto.getLocalId(),
                            new JSONObject()
                                    .put("kind", "media_chunk")
                                    .put("mid", record.id)
                                    .put("index", i)
                                    .put(
                                            "data",
                                            Base64.getUrlEncoder()
                                                    .withoutPadding()
                                                    .encodeToString(part))
                                    .toString());
            MeshProtocol.Incoming response = c.receive(a, chunk);
            if (response.relay != null) a.receive(c, response.relay);
        }
        assertEquals("FAILED", c.store.dao().media(record.id).state);
        assertNotEquals("DELIVERED", a.store.dao().chat(record.id).status);
        a.actions.unsend(record.id, System.currentTimeMillis());
        WithdrawalRecord marker = a.store.dao().withdrawal(record.id);
        c.receive(a, queued(a, marker.requestId));
        assertEquals(0, c.store.dao().media(record.id).file.length);
        try {
            c.actions.attachment(record.id);
            fail("Withdrawn attachment remained available");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void mediaChunkSizesLimitsAndVideoDurationAreEnforced() throws Exception {
        try {
            a.actions.offer(
                    c.crypto.getLocalId(),
                    new byte[MediaTransfers.IMAGE_MAX + 1],
                    "image/jpeg",
                    System.currentTimeMillis());
            fail("Oversized image accepted");
        } catch (IllegalArgumentException expected) {
        }
        byte[] clip = asset("tiny-video.mp4");
        AttachmentPreparer.validate(clip, "video/mp4");
        byte[] tooLong = asset("too-long-video.mp4");
        try {
            AttachmentPreparer.validate(tooLong, "video/mp4");
            fail("Long video accepted");
        } catch (IllegalArgumentException expected) {
        }
        MediaRecord record = offer(clip, "video/mp4");
        for (int n = 0; n < 40 && !a.store.dao().chat(record.id).status.equals("DELIVERED"); n++) {
            elapsed.addAndGet(5000);
            a.actions.pump(System.currentTimeMillis());
            drain(a, c);
        }
        assertEquals("DELIVERED", a.store.dao().chat(record.id).status);
        assertArrayEquals(clip, c.actions.attachment(record.id));
    }

    byte[] asset(String name) throws Exception {
        try (java.io.InputStream input =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @Test
    public void chatCiphertextCannotBeReflectedIntoAControlCommand() throws Exception {
        String id = MeshMessage.newId(a.crypto.getLocalId());
        long now = System.currentTimeMillis();
        String body =
                new JSONObject().put("kind", "withdraw").put("mid", id).put("when", now).toString();
        MeshMessage reflected =
                a.crypto.signPacket(
                        MeshMessage.createControl(
                                id,
                                a.crypto.getLocalId(),
                                c.crypto.getLocalId(),
                                a.crypto.encrypt(id, c.crypto.getLocalId(), now, body),
                                a.crypto.getPublicKeyBase64(),
                                now));
        assertSame(MeshProtocol.Incoming.IGNORED, c.receive(a, reflected));
        assertNull(c.store.dao().withdrawal(id));
        assertTrue(c.store.dao().history().isEmpty());
    }

    private final class Peer {
        final String name, label;
        CryptoManager crypto;
        MessageDatabase db;
        MessageStore store;
        RoomMeshStorage storage;
        PrivateActions actions;
        MeshProtocol protocol;

        Peer(String label) throws Exception {
            this.label = label;
            name = prefix + label;
            reloadIdentity();
            reopen();
        }

        void reloadIdentity() {
            crypto =
                    new CryptoManager(
                            new ContextWrapper(context) {
                                @Override
                                public SharedPreferences getSharedPreferences(
                                        String key, int mode) {
                                    String scoped = name + key;
                                    preferences.add(scoped);
                                    return context.getSharedPreferences(scoped, mode);
                                }
                            });
        }

        void reopen() throws Exception {
            if (db != null) db.close();
            db = Room.databaseBuilder(context, MessageDatabase.class, name).build();
            store = new MessageStore(db, new StorageCipher());
            storage = new RoomMeshStorage(store, crypto.getLocalId());
            actions = new PrivateActions(context, store, crypto);
            protocol = new MeshProtocol(crypto, storage, new FloodGuard(elapsed::get));
            protocol.extensions(actions);
        }

        MeshProtocol.Incoming receive(Peer peer, MeshMessage packet) throws Exception {
            return protocol.receive(peer.label, packet.toBytes(), System.currentTimeMillis());
        }
    }
}
