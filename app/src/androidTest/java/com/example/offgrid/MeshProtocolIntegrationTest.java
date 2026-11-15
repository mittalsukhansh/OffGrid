package com.example.offgrid;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.offgrid.mesh.protocol.MeshProtocol;
import com.example.offgrid.mesh.storage.RoomMeshStorage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runs the production protocol/storage boundary for three nodes; does not simulate a BLE radio. */
@RunWith(AndroidJUnit4.class)
public class MeshProtocolIntegrationTest {
    private Context context;
    private String prefix;
    private Peer a, b, c;
    private final List<String> preferences = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "node-test-" + UUID.randomUUID();
        a = new Peer("A");
        b = new Peer("B");
        c = new Peer("C");
        a.crypto.importContact(c.crypto.getContactString());
        c.crypto.importContact(a.crypto.getContactString());
    }

    @After
    public void tearDown() {
        for (Peer peer : new Peer[] {a, b, c}) {
            if (peer != null) {
                peer.db.close();
                context.deleteDatabase(peer.name);
            }
        }
        for (String name : preferences) context.deleteSharedPreferences(name);
    }

    private MeshProtocol.Outgoing outgoing() throws Exception {
        MeshProtocol.Outgoing result =
                a.protocol.privateMessage(
                        c.crypto.getLocalId(), "Across the mesh", System.currentTimeMillis());
        assertEquals(MeshProtocol.SendStatus.ACCEPTED, result.status);
        a.storage.save(result.wire, result.display, true, result.wire, System.currentTimeMillis());
        return result;
    }

    @Test
    public void threeNodePrivateMessageAndReceiptTraverseProductionComponents() throws Exception {
        MeshProtocol.Outgoing sent = outgoing();
        MeshProtocol.Incoming atB = b.receive("A", sent.wire);
        assertNull(atB.display);
        assertEquals(sent.wire.getTtl() - 1, atB.relay.getTtl());
        assertTrue(atB.relay.isPayloadEncrypted());
        assertTrue(b.store.dao().history().isEmpty());
        MeshProtocol.Incoming atC = c.receive("B", atB.relay);
        assertEquals("Across the mesh", atC.display.getText());
        assertTrue(atC.relay.isAcknowledgement());
        assertEquals("RECEIVED", c.store.dao().history().get(0).status);
        MeshProtocol.Incoming backAtB = b.receive("C", atC.relay);
        assertNull(backAtB.display);
        assertTrue(backAtB.relay.isAcknowledgement());
        MeshProtocol.Incoming backAtA = a.receive("B", backAtB.relay);
        assertEquals(sent.wire.getMessageId(), backAtA.deliveredId);
        assertEquals("DELIVERED", a.store.dao().chat(sent.wire.getMessageId()).status);
        assertNull(a.store.dao().pendingById(sent.wire.getMessageId()));
    }

    @Test
    public void ttlZeroAndOneDeliverLocallyWithoutBeingRelayed() throws Exception {
        for (int ttl : new int[] {0, 1}) {
            MeshMessage packet =
                    a.crypto.signPacket(MeshMessage.create(a.crypto.getLocalId(), "Last hop", ttl));
            MeshProtocol.Incoming result = b.receive("A", packet);
            assertEquals("Last hop", result.display.getText());
            assertNull(result.relay);
        }
        assertTrue(b.store.dao().pending().isEmpty());
        assertEquals(2, b.store.dao().history().size());
    }

    @Test
    public void recipientRestartReusesReceiptWithoutDuplicatingHistoryOrForwardingBudget()
            throws Exception {
        MeshProtocol.Outgoing sent = outgoing();
        MeshProtocol.Incoming first = c.receive("A", sent.wire);
        c.store.forwarded(first.relay.getMessageId(), 3);
        c.reopen();
        MeshProtocol.Incoming duplicate = c.receive("A", sent.wire);
        assertNull(duplicate.display);
        assertArrayEquals(first.relay.toBytes(), duplicate.relay.toBytes());
        assertEquals(1, c.store.dao().history().size());
        assertEquals(3, c.store.dao().pendingById(first.relay.getMessageId()).forwardCount);
    }

    @Test
    public void relayRestartRejectsReplayEvenAfterItsPendingCopyIsGone() throws Exception {
        MeshProtocol.Outgoing sent = outgoing();
        b.receive("A", sent.wire);
        b.storage.discard(sent.wire.getMessageId(), "EXPIRED");
        b.reopen();
        assertTrue(b.store.dao().history().isEmpty());
        assertTrue(b.store.dao().pending().isEmpty());
        assertSame(MeshProtocol.Incoming.IGNORED, b.receive("A", sent.wire));
        assertTrue(b.store.dao().pending().isEmpty());
    }

    @Test
    public void unsignedMalformedAndTamperedPacketsCannotChangeHistory() throws Exception {
        MeshMessage unsigned = MeshMessage.create(a.crypto.getLocalId(), "Unsigned");
        assertSame(MeshProtocol.Incoming.IGNORED, b.receive("A", unsigned));
        MeshMessage signed = a.crypto.signPacket(unsigned);
        assertSame(
                MeshProtocol.Incoming.IGNORED,
                b.receive("A", MeshMessage.fromJson(signed.toJson().put("txt", "Impersonation"))));
        assertSame(
                MeshProtocol.Incoming.IGNORED,
                b.protocol.receive("A", new byte[] {'{', 'x'}, System.currentTimeMillis()));
        assertTrue(b.store.dao().history().isEmpty());
        assertEquals(0, b.store.dao().replayCount());
    }

    @Test
    public void rejectedStorageDoesNotExposeAMessageOrBurnItsReplayId() throws Exception {
        MeshProtocol.Outgoing sent = outgoing();
        c.db.getOpenHelper()
                .getWritableDatabase()
                .execSQL(
                        "CREATE TRIGGER fail_protocol_pending BEFORE INSERT ON pending_messages "
                                + "BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END");
        try {
            c.receive("A", sent.wire);
            fail("Write failure was swallowed");
        } catch (android.database.sqlite.SQLiteException expected) {
        }
        assertTrue(c.store.dao().history().isEmpty());
        assertEquals(0, c.store.dao().replayCount());
        c.db.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_protocol_pending");
        assertEquals("Across the mesh", c.receive("A", sent.wire).display.getText());
    }

    @Test
    public void packetTypesRoundTripAndMalformedBoundariesFailParsing() throws Exception {
        MeshProtocol.Outgoing sent = outgoing();
        MeshMessage ack = c.receive("A", sent.wire).relay;
        for (MeshMessage packet :
                new MeshMessage[] {
                    a.protocol.publicMessage("Public").wire,
                    sent.wire,
                    a.protocol.announcement(),
                    ack
                }) {
            MeshMessage parsed = MeshMessage.fromBytes(packet.toBytes());
            assertArrayEquals(packet.toBytes(), parsed.toBytes());
            assertTrue(b.crypto.verifyPacket(parsed));
        }
        for (byte[] invalid : new byte[][] {null, {}, {'{'}, new byte[2049]}) {
            try {
                MeshMessage.fromBytes(invalid);
                fail("Malformed bytes accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        for (int ttl : new int[] {-1, 16}) {
            try {
                MeshMessage.fromJson(sent.wire.toJson().put("ttl", ttl));
                fail("Invalid TTL accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            MeshMessage.fromJson(sent.wire.toJson().put("v", 99));
            fail("Unknown version accepted");
        } catch (IllegalArgumentException expected) {
        }
        try {
            MeshMessage.fromJson(sent.wire.toJson().put("typ", 99));
            fail("Unknown type accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private final class Peer {
        final String name;
        final CryptoManager crypto;
        MessageDatabase db;
        MessageStore store;
        RoomMeshStorage storage;
        MeshProtocol protocol;

        Peer(String label) throws Exception {
            name = prefix + label;
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
            reopen();
        }

        void reopen() throws Exception {
            if (db != null) db.close();
            db = Room.databaseBuilder(context, MessageDatabase.class, name).build();
            store = new MessageStore(db, new StorageCipher());
            storage = new RoomMeshStorage(store, crypto.getLocalId());
            protocol =
                    new MeshProtocol(crypto, storage, new FloodGuard(SystemClock::elapsedRealtime));
        }

        MeshProtocol.Incoming receive(String from, MeshMessage packet) throws Exception {
            return protocol.receive(from, packet.toBytes(), System.currentTimeMillis());
        }
    }
}
