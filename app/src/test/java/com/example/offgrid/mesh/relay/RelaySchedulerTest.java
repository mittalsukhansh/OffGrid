package com.example.offgrid.mesh.relay;

import static org.junit.Assert.*;

import com.example.offgrid.MeshMessage;
import com.example.offgrid.mesh.MeshConfig;
import com.example.offgrid.mesh.storage.MeshStorage;
import com.example.offgrid.mesh.transport.MeshTransport;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RelaySchedulerTest {
    private final String sender = "11111111111111111111111111111111";
    private FakeTimer time;
    private FakeStorage storage;
    private FakeTransport transport;
    private RelayScheduler scheduler;

    @Before
    public void setUp() {
        time = new FakeTimer();
        storage = new FakeStorage();
        transport = new FakeTransport();
        scheduler =
                new RelayScheduler(
                        time,
                        storage,
                        transport,
                        packet -> packet.getMessageId().getBytes(StandardCharsets.UTF_8),
                        new RelayScheduler.Listener() {
                            @Override
                            public void onPeerCount(int count) {}

                            @Override
                            public void onError(String message) {
                                fail(message);
                            }
                        });
    }

    private MeshMessage queued(int forwards) {
        MeshMessage message = MeshMessage.create(sender, "Test");
        scheduler.add(
                new MeshStorage.Pending(
                        message, time.wall(), time.wall() + MeshConfig.PENDING_TTL_MS, forwards));
        return message;
    }

    private void peer(String name, int signal) {
        scheduler.peerSeen(name, signal);
    }

    @Test
    public void chatAndReceiptsBypassInitialInventoryButBulkDoesNot() {
        MeshMessage announce = MeshMessage.createKeyAnnounce(sender, "A".repeat(87), 4);
        MeshMessage text =
                MeshMessage.createPrivate(
                        MeshMessage.newId(sender),
                        sender,
                        "2".repeat(32),
                        "A".repeat(40),
                        "A".repeat(87),
                        7,
                        time.wall());
        MeshMessage receipt =
                MeshMessage.createAcknowledgement(
                        MeshMessage.newId(sender),
                        sender,
                        "2".repeat(32),
                        "A".repeat(40),
                        "A".repeat(87),
                        time.wall());
        MeshMessage control =
                MeshMessage.createControl(
                        MeshMessage.newId(sender),
                        sender,
                        "2".repeat(32),
                        "A".repeat(40),
                        "A".repeat(87),
                        time.wall());
        for (MeshMessage packet : Arrays.asList(announce, control, text, receipt))
            scheduler.add(
                    new MeshStorage.Pending(
                            packet, time.wall(), time.wall() + MeshConfig.PENDING_TTL_MS, 0));
        scheduler.inventoryReady(peer -> false);
        peer("wifi:phone", -40);
        scheduler.start();
        time.runDue();
        Call call = transport.calls.get(0);
        assertEquals(
                Arrays.asList(receipt.getMessageId(), text.getMessageId()),
                Arrays.asList(call.packets.get(0).id, call.packets.get(1).id));
        assertEquals(2, call.packets.size());
        for (MeshTransport.Packet packet : call.packets) call.listener.onForwarded(packet.id);
        call.listener.onComplete(null, false);
        scheduler.inventoryReady(peer -> true);
        scheduler.schedule(0);
        time.advance(100);
        Call bulk = transport.calls.get(1);
        assertEquals(control.getMessageId(), bulk.packets.get(0).id);
        assertEquals(announce.getMessageId(), bulk.packets.get(1).id);
    }

    @Test
    public void repeatedSendsLeaveBudgetForAThirdPhoneAfterLongPartition() {
        MeshMessage packet = queued(0);
        scheduler.start();
        for (int i = 0; i < 7; i++) {
            peer("ble:coc:old-neighbor", -10);
            scheduler.inventory("ble:coc:old-neighbor", sender, Collections.emptyList());
            time.runDue();
            for (Call call : new ArrayList<>(transport.calls)) {
                if (call.listener.shouldSend(packet.getMessageId())) {
                    call.listener.onForwarded(packet.getMessageId());
                    call.listener.onComplete(null, false);
                }
            }
            time.advance(60_000);
        }
        assertEquals(Integer.valueOf(3), storage.progress.get(packet.getMessageId()));
        assertTrue(scheduler.eligible(packet.getMessageId()));
        peer("ble:coc:new-third-phone", -40);
        scheduler.inventory(
                "ble:coc:new-third-phone",
                "22222222222222222222222222222222",
                Collections.emptyList());
        time.runDue();
        Call next = transport.calls.get(transport.calls.size() - 1);
        assertEquals("ble:coc:new-third-phone", next.peer);
        assertTrue(next.listener.shouldSend(packet.getMessageId()));
    }

    @Test
    public void strongestPeersAreChosenAndConcurrentSessionsAreCapped() {
        queued(0);
        for (int i = 0; i < 8; i++) peer("peer" + i, -100 + i);
        scheduler.start();
        time.runDue();
        assertEquals(3, transport.calls.size());
        assertEquals("peer7", transport.calls.get(0).peer);
        assertEquals("peer6", transport.calls.get(1).peer);
        assertEquals("peer5", transport.calls.get(2).peer);
    }

    @Test
    public void batchesAreBoundedAndReceivedPacketsAreNotEchoedBackToTheirSource() {
        MeshMessage sourcePacket = queued(0);
        for (int i = 0; i < 25; i++) queued(0);
        peer("source", -20);
        scheduler.receivedFrom("source", sourcePacket.getMessageId());
        scheduler.start();
        time.runDue();
        Call call = transport.calls.get(0);
        assertEquals(20, call.packets.size());
        assertFalse(
                call.packets.stream()
                        .anyMatch(packet -> packet.id.equals(sourcePacket.getMessageId())));
    }

    @Test
    public void forwardingCountSurvivesRestoreAndStopsAtItsLimit() {
        MeshMessage almostDone = queued(5);
        queued(6);
        peer("peer", -30);
        scheduler.start();
        time.runDue();
        Call call = transport.calls.get(0);
        assertEquals(1, call.packets.size());
        call.listener.onForwarded(almostDone.getMessageId());
        assertEquals(Integer.valueOf(6), storage.progress.get(almostDone.getMessageId()));
        assertFalse(scheduler.eligible(almostDone.getMessageId()));
    }

    @Test
    public void receiptCancellationAndLateCallbackFromAnOldSessionAreSafe() {
        MeshMessage message = queued(0);
        peer("peer", -30);
        scheduler.start();
        time.runDue();
        Call old = transport.calls.get(0);
        scheduler.remove(message.getMessageId());
        assertFalse(old.listener.shouldSend(message.getMessageId()));
        old.listener.onForwarded(message.getMessageId());
        assertFalse(storage.progress.containsKey(message.getMessageId()));
        scheduler.pause();
        queued(0);
        peer("peer", -30);
        scheduler.start();
        time.runDue();
        old.listener.onComplete("late failure", false);
        int count = transport.calls.size();
        scheduler.schedule(0);
        time.runDue();
        assertEquals(
                count, transport.calls.size()); // stale callback did not free the new session slot
    }

    @Test
    public void failureBackoffBlocksImmediateRetryAndAllowsAHealthyRetryLater() {
        queued(0);
        peer("peer", -30);
        scheduler.start();
        time.runDue();
        transport.calls.get(0).listener.onComplete("disconnected", false);
        assertTrue(scheduler.diagnostics().contains("peer: disconnected"));
        assertTrue(scheduler.diagnostics().contains("retry in 4s"));
        time.advance(3999);
        scheduler.maintenance();
        time.runDue();
        assertEquals(1, transport.calls.size());
        time.advance(1);
        scheduler.maintenance();
        time.runDue();
        assertEquals(2, transport.calls.size());
    }

    @Test
    public void expiryUsesThePersistedDeadlineAndDoesNotRestartOnRecovery() {
        MeshMessage message = MeshMessage.create(sender, "Old queue");
        scheduler.add(
                new MeshStorage.Pending(
                        message,
                        time.wall() - MeshConfig.PENDING_TTL_MS + 100,
                        time.wall() + 100,
                        0));
        assertTrue(scheduler.eligible(message.getMessageId()));
        time.advance(100);
        scheduler.maintenance();
        assertFalse(scheduler.eligible(message.getMessageId()));
        assertEquals("EXPIRED", storage.discarded.get(message.getMessageId()));
    }

    @Test
    public void fullQueueEvictsAnAnnouncementBeforeChatAndPausingPreservesPackets() {
        char[] key = new char[87];
        Arrays.fill(key, 'B');
        MeshMessage announcement = MeshMessage.createKeyAnnounce(sender, new String(key), 4);
        scheduler.add(
                new MeshStorage.Pending(
                        announcement, time.wall(), time.wall() + MeshConfig.PENDING_TTL_MS, 0));
        MeshMessage first = queued(0);
        for (int i = 0; i < 199; i++) queued(0);
        assertEquals("FAILED", storage.discarded.get(announcement.getMessageId()));
        scheduler.start();
        scheduler.pause();
        assertTrue(scheduler.eligible(first.getMessageId()));
        assertEquals(1, transport.cancellations);
    }

    @Test
    public void stalePeersAreNotConnectedAndCanBeRediscovered() {
        queued(0);
        peer("peer", -30);
        time.advance(RelayScheduler.PEER_FRESH_MS + 1);
        scheduler.start();
        time.runDue();
        assertTrue(transport.calls.isEmpty());
        peer("peer", -30);
        time.runDue();
        assertEquals(1, transport.calls.size());
    }

    @Test
    public void bulkPacketsSkipGattAndWifiHasPriorityOverStrongerRadioSignal() {
        scheduler =
                new RelayScheduler(
                        time,
                        storage,
                        transport,
                        packet -> new byte[6000],
                        new RelayScheduler.Listener() {
                            @Override
                            public void onPeerCount(int count) {}

                            @Override
                            public void onError(String error) {
                                fail(error);
                            }
                        });
        queued(0);
        peer("ble:strong", -10);
        peer("wifi:weak", -100);
        scheduler.start();
        time.runDue();
        assertEquals(1, transport.calls.size());
        assertEquals("wifi:weak", transport.calls.get(0).peer);
    }

    @Test
    public void inventorySuppressesKnownIdsAcrossAuthenticatedRadioAliases() {
        MeshMessage known = queued(0);
        MeshMessage missing = queued(0);
        peer("ble:phone", -20);
        peer("wifi:phone", -40);
        scheduler.inventory(
                "ble:phone", sender, java.util.Collections.singletonList(known.getMessageId()));
        scheduler.inventory("wifi:phone", sender, java.util.Collections.emptyList());
        scheduler.start();
        time.runDue();
        assertEquals(1, transport.calls.size());
        assertEquals("wifi:phone", transport.calls.get(0).peer);
        assertEquals(missing.getMessageId(), transport.calls.get(0).packets.get(0).id);
        transport.calls.get(0).listener.onForwarded(missing.getMessageId());
        transport.calls.get(0).listener.onComplete(null, false);
        time.advance(100);
        assertEquals(1, transport.calls.size());
    }

    @Test
    public void repeatedInventoriesCannotPostponeForwardingForever() {
        MeshMessage packet = queued(0);
        peer("wifi:liar", -20);
        scheduler.inventory("wifi:liar", sender, Collections.singletonList(packet.getMessageId()));
        scheduler.start();
        time.runDue();
        assertTrue(transport.calls.isEmpty());
        time.advance(RelayScheduler.INVENTORY_HINT_MS - 1);
        scheduler.inventory("wifi:liar", sender, Collections.singletonList(packet.getMessageId()));
        scheduler.schedule(0);
        time.runDue();
        assertTrue(transport.calls.isEmpty());
        time.advance(1);
        peer("wifi:liar", -20);
        time.runDue();
        assertEquals(1, transport.calls.size());
        Call call = transport.calls.get(0);
        call.listener.onForwarded(packet.getMessageId());
        call.listener.onComplete(null, false);
        time.advance(RelayScheduler.RETRY_BASE_MS);
        scheduler.inventory("wifi:liar", sender, Collections.singletonList(packet.getMessageId()));
        peer("wifi:liar", -20);
        time.runDue();
        assertEquals(2, transport.calls.size());
        scheduler.remove(packet.getMessageId());
        assertFalse(transport.calls.get(1).listener.shouldSend(packet.getMessageId()));
    }

    @Test
    public void newRadioAliasCannotRenewExpiredInventorySuppression() {
        MeshMessage packet = queued(0);
        peer("ble:liar", -10);
        scheduler.inventory("ble:liar", sender, Collections.singletonList(packet.getMessageId()));
        scheduler.start();
        time.advance(RelayScheduler.INVENTORY_HINT_MS);
        peer("wifi:new", -20);
        scheduler.inventory("wifi:new", sender, Collections.singletonList(packet.getMessageId()));
        time.runDue();
        assertFalse(transport.calls.isEmpty());
    }

    @Test
    public void disconnectedIdentityCannotRestartSuppressionWithANewEndpoint() {
        MeshMessage packet = queued(0);
        peer("ble:old", -20);
        scheduler.inventory("ble:old", sender, Collections.singletonList(packet.getMessageId()));
        time.advance(RelayScheduler.PEER_TIMEOUT_MS + 1);
        scheduler.maintenance();
        peer("wifi:returning", -20);
        scheduler.inventory(
                "wifi:returning", sender, Collections.singletonList(packet.getMessageId()));
        scheduler.start();
        time.runDue();
        assertEquals(1, transport.calls.size());
        assertEquals("wifi:returning", transport.calls.get(0).peer);
    }

    static final class FakeTimer implements RelayScheduler.Timer {
        long elapsed;
        final long wallBase = System.currentTimeMillis();
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();

        @Override
        public long elapsed() {
            return elapsed;
        }

        @Override
        public long wall() {
            return wallBase + elapsed;
        }

        @Override
        public void schedule(Runnable task, long delay) {
            tasks.put(task, elapsed + delay);
        }

        @Override
        public void cancel(Runnable task) {
            tasks.remove(task);
        }

        void advance(long delta) {
            elapsed += delta;
            runDue();
        }

        void runDue() {
            while (true) {
                Runnable due = null;
                for (Map.Entry<Runnable, Long> task : tasks.entrySet()) {
                    if (task.getValue() <= elapsed) {
                        due = task.getKey();
                        break;
                    }
                }
                if (due == null) return;
                tasks.remove(due);
                due.run();
            }
        }
    }

    private static final class Call {
        final String peer;
        final List<MeshTransport.Packet> packets;
        final MeshTransport.SendListener listener;

        Call(String peer, List<MeshTransport.Packet> packets, MeshTransport.SendListener listener) {
            this.peer = peer;
            this.packets = packets;
            this.listener = listener;
        }
    }

    private static final class FakeTransport implements MeshTransport {
        final List<Call> calls = new ArrayList<>();
        int cancellations;

        @Override
        public com.example.offgrid.mesh.transport.TransferProfile profile(String peer) {
            if (peer.startsWith("ble:coc:"))
                return com.example.offgrid.mesh.transport.TransferProfile.COC;
            return peer.startsWith("wifi:")
                    ? com.example.offgrid.mesh.transport.TransferProfile.WIFI
                    : com.example.offgrid.mesh.transport.TransferProfile.GATT;
        }

        @Override
        public void start(Listener listener) {}

        @Override
        public void stop() {}

        @Override
        public void maintain() {}

        @Override
        public void send(String peer, List<Packet> packets, SendListener listener) {
            calls.add(new Call(peer, packets, listener));
        }

        @Override
        public void cancelSends() {
            cancellations++;
        }
    }

    static class FakeStorage implements MeshStorage {
        final Map<String, Integer> progress = new HashMap<>();
        final Map<String, String> discarded = new HashMap<>();

        @Override
        public void forwarded(String id, int count) {
            progress.put(id, count);
        }

        @Override
        public void discard(String id, String status) {
            discarded.put(id, status);
        }

        @Override
        public List<Pending> pending() {
            return Collections.emptyList();
        }

        @Override
        public Pending pending(String id) {
            return null;
        }

        @Override
        public boolean hasHistory(String id) {
            return false;
        }

        @Override
        public boolean known(String id) {
            return false;
        }

        @Override
        public boolean replayed(String id) {
            return false;
        }

        @Override
        public boolean clockSafe(long now) {
            return true;
        }

        @Override
        public MeshMessage cachedReceipt(MeshMessage packet, long now) {
            return null;
        }

        @Override
        public String confirmReceipt(MeshMessage ack, String plaintext, long now) {
            return null;
        }

        @Override
        public void save(
                MeshMessage wire,
                MeshMessage display,
                boolean outgoing,
                MeshMessage relay,
                long now) {}

        @Override
        public void expire(long now) {}

        @Override
        public void outgoingFailed(String id) {}
    }
}
