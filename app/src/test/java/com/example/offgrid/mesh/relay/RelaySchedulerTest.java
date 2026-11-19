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
    public void failedAndPausedAttemptsReleaseUnusedReservations() {
        MeshMessage packet = queued(5);
        peer("peer-a", -10);
        peer("peer-b", -20);
        scheduler.start();
        time.runDue();
        Call first = transport.calls.get(0), second = transport.calls.get(1);
        first.listener.onAttempt(packet.getMessageId(), () -> {});
        assertFalse(second.listener.shouldSend(packet.getMessageId()));
        first.listener.onComplete("real failure", false);
        assertTrue(second.listener.shouldSend(packet.getMessageId()));
        second.listener.onAttempt(packet.getMessageId(), () -> {});
        scheduler.pause();
        peer("peer-c", -30);
        scheduler.start();
        time.runDue();
        Call resumed = transport.calls.get(2);
        assertTrue(resumed.listener.shouldSend(packet.getMessageId()));
        first.listener.onForwarded(packet.getMessageId());
        assertFalse(storage.progress.containsKey(packet.getMessageId()));
        resumed.listener.onAttempt(packet.getMessageId(), () -> {});
        resumed.listener.onForwarded(packet.getMessageId());
        assertEquals(Integer.valueOf(6), storage.progress.get(packet.getMessageId()));
    }

    @Test
    public void reservedRetrySurvivesLaterSlotAcknowledgingFirstAndInventoryHints() {
        MeshMessage packet = queued(3);
        peer("peer-a", -10);
        scheduler.start();
        time.runDue();
        Call initial = transport.calls.get(0);
        initial.listener.onForwarded(packet.getMessageId());
        initial.listener.onComplete(null, false);
        time.advance(30_000);
        peer("peer-a", -10);
        peer("peer-b", -20);
        time.runDue();
        Call retry = transport.calls.get(1), fresh = transport.calls.get(2);
        retry.listener.onAttempt(packet.getMessageId(), () -> fail("Reserved retry was cancelled"));
        fresh.listener.onAttempt(
                packet.getMessageId(), () -> fail("Last fresh-neighbor slot was cancelled"));
        scheduler.inventory(
                "peer-b", "2".repeat(32), Collections.singletonList(packet.getMessageId()));
        assertTrue(fresh.listener.shouldSend(packet.getMessageId()));
        fresh.listener.onForwarded(packet.getMessageId());
        scheduler.inventory("peer-a", sender, Collections.singletonList(packet.getMessageId()));
        assertTrue(retry.listener.shouldSend(packet.getMessageId()));
        retry.listener.onForwarded(packet.getMessageId());
        assertEquals(Integer.valueOf(6), storage.progress.get(packet.getMessageId()));
        retry.listener.onForwarded(packet.getMessageId());
        assertEquals(Integer.valueOf(6), storage.progress.get(packet.getMessageId()));
    }

    @Test
    public void aggregateDiagnosticsAndResetNeverExposeIdsOrCancelAReservation() {
        MeshMessage packet = queued(5);
        peer("private-radio-address", -20);
        scheduler.start();
        time.runDue();
        Call active = transport.calls.get(0);
        active.listener.onAttempt(
                packet.getMessageId(), () -> fail("Reset cancelled a live attempt"));
        scheduler.resetMeasurements();
        String report = scheduler.measurements();
        assertTrue(report.contains("Active forward reservations: 1"));
        assertTrue(report.contains("Queued packets: 1"));
        assertFalse(report.contains(packet.getMessageId()));
        assertFalse(report.contains(sender));
        assertFalse(report.contains("private-radio-address"));
        assertTrue(active.listener.shouldSend(packet.getMessageId()));
    }

    @Test
    public void parallelAttemptsShareTheFinalForwardingSlotBeforeAnyAcknowledgement() {
        MeshMessage packet = queued(5);
        peer("peer-a", -10);
        peer("peer-b", -20);
        peer("peer-c", -30);
        scheduler.start();
        time.runDue();
        assertEquals(3, transport.calls.size());
        for (Call call : transport.calls)
            call.listener.onAttempt(
                    packet.getMessageId(), () -> call.listener.onComplete("closed", false));
        assertTrue(transport.calls.get(0).listener.shouldSend(packet.getMessageId()));
        assertFalse(
                "The last slot is already reserved",
                transport.calls.get(1).listener.shouldSend(packet.getMessageId()));
        assertFalse(transport.calls.get(2).listener.shouldSend(packet.getMessageId()));
        for (Call call : transport.calls) call.listener.onForwarded(packet.getMessageId());
        assertEquals(Integer.valueOf(6), storage.progress.get(packet.getMessageId()));
    }

    @Test
    public void twoSlowPacketsCanFinishAfterEachGetsItsFirstTurn() {
        MeshMessage first = queued(0);
        peer("wifi:phone", -20);
        scheduler.start();
        time.runDue();
        Call blocked = transport.calls.get(0);
        blocked.listener.onAttempt(
                first.getMessageId(), () -> blocked.listener.onComplete("closed", false));
        MeshMessage second = queued(0);
        time.advance(3000);
        Call next = transport.calls.get(1);
        int[] repeatedAbort = {0};
        next.listener.onAttempt(
                second.getMessageId(),
                () -> {
                    repeatedAbort[0]++;
                    next.listener.onComplete("closed", false);
                });
        time.advance(3000);
        assertEquals(
                "Already attempted packets must not cause endless three-second restarts",
                0,
                repeatedAbort[0]);
        time.advance(1000);
        next.listener.onForwarded(second.getMessageId());
        next.listener.onComplete(null, false);
        time.advance(100);
        Call retry = transport.calls.get(2);
        assertEquals(first.getMessageId(), retry.packets.get(0).id);
        retry.listener.onAttempt(first.getMessageId(), () -> fail("No untried text is waiting"));
        retry.listener.onForwarded(first.getMessageId());
        retry.listener.onComplete(null, false);
        assertEquals(Integer.valueOf(1), storage.progress.get(first.getMessageId()));
        assertEquals(Integer.valueOf(1), storage.progress.get(second.getMessageId()));
    }

    @Test
    public void alternateRadioCannotPutYieldedReceiptAheadOfWaitingText() {
        MeshMessage receipt =
                MeshMessage.createAcknowledgement(
                        MeshMessage.newId(sender),
                        sender,
                        "2".repeat(32),
                        "A".repeat(40),
                        "A".repeat(87),
                        time.wall());
        scheduler.add(
                new MeshStorage.Pending(
                        receipt, time.wall(), time.wall() + MeshConfig.PENDING_TTL_MS, 0));
        peer("ble:coc:primary", -20);
        scheduler.inventory("ble:coc:primary", sender, Collections.emptyList());
        scheduler.start();
        time.runDue();
        Call stalled = transport.calls.get(0);
        stalled.listener.onAttempt(
                receipt.getMessageId(), () -> stalled.listener.onComplete("closed", false));
        MeshMessage next = queued(0);
        time.advance(2999);
        peer("wifi:alias", -30);
        scheduler.inventory("wifi:alias", sender, Collections.emptyList());
        time.runDue();
        assertEquals(1, transport.calls.size());
        time.advance(1);
        Call replacement = transport.calls.get(1);
        assertEquals("wifi:alias", replacement.peer);
        assertEquals(next.getMessageId(), replacement.packets.get(0).id);
        assertFalse(replacement.listener.shouldSend(receipt.getMessageId()));
    }

    @Test
    public void fallbackReplacesCancelHandleWithoutRestartingThreeSecondWindow() {
        MeshMessage first = queued(0);
        peer("wifi:phone", -20);
        scheduler.start();
        time.runDue();
        Call call = transport.calls.get(0);
        int[] closed = {0, 0};
        call.listener.onAttempt(first.getMessageId(), () -> closed[0]++);
        time.advance(2500);
        call.listener.onAttempt(
                first.getMessageId(),
                () -> {
                    closed[1]++;
                    call.listener.onComplete("closed", false);
                });
        queued(0);
        time.advance(500);
        assertEquals(0, closed[0]);
        assertEquals(1, closed[1]);
    }

    @Test
    public void stalledAttemptYieldsAtThreeSecondsAndKeepsOldPacketQueued() {
        MeshMessage first = queued(0);
        peer("wifi:phone", -20);
        scheduler.start();
        time.runDue();
        Call stalled = transport.calls.get(0);
        int[] closed = {0};
        stalled.listener.onAttempt(
                first.getMessageId(),
                () -> {
                    closed[0]++;
                    stalled.listener.onComplete("socket closed", false);
                });
        MeshMessage next = queued(0);
        time.advance(2999);
        assertEquals(0, closed[0]);
        time.advance(1);
        assertEquals(1, closed[0]);
        assertTrue(scheduler.contains(first.getMessageId()));
        assertFalse(storage.progress.containsKey(first.getMessageId()));
        Call replacement = transport.calls.get(1);
        assertEquals(next.getMessageId(), replacement.packets.get(0).id);
        assertTrue(stalled.listener.isCancelled());
        stalled.listener.onForwarded(first.getMessageId());
        assertFalse(storage.progress.containsKey(first.getMessageId()));
        assertFalse(scheduler.diagnostics().contains("socket closed"));
    }

    @Test
    public void slowSingleMessageIsNotAbortedButNewTextGetsATurnAfterItsWindow() {
        MeshMessage first = queued(0);
        peer("wifi:phone", -20);
        scheduler.start();
        time.runDue();
        Call stalled = transport.calls.get(0);
        int[] closed = {0};
        stalled.listener.onAttempt(
                first.getMessageId(),
                () -> {
                    closed[0]++;
                    stalled.listener.onComplete("closed", false);
                });
        time.advance(10_000);
        assertEquals(0, closed[0]);
        MeshMessage next = queued(0);
        time.runDue();
        assertEquals(1, closed[0]);
        assertEquals(next.getMessageId(), transport.calls.get(1).packets.get(0).id);
    }

    @Test
    public void unsendReconciliationCancelsActiveOriginalImmediately() {
        MeshMessage first = queued(0);
        peer("wifi:phone", -20);
        scheduler.start();
        time.runDue();
        Call stalled = transport.calls.get(0);
        int[] closed = {0};
        stalled.listener.onAttempt(
                first.getMessageId(),
                () -> {
                    closed[0]++;
                    stalled.listener.onComplete("closed", false);
                });
        MeshMessage next = queued(0);
        scheduler.reconcile(Collections.singletonList(next.getMessageId()));
        time.runDue();
        assertEquals(1, closed[0]);
        assertFalse(scheduler.contains(first.getMessageId()));
        assertEquals(next.getMessageId(), transport.calls.get(1).packets.get(0).id);
        assertFalse(stalled.listener.shouldSend(first.getMessageId()));
    }

    @Test
    public void packetProgressGetsANewWindowAndPauseClearsDeadline() {
        MeshMessage first = queued(0), second = queued(0);
        peer("wifi:phone", -20);
        scheduler.start();
        time.runDue();
        Call call = transport.calls.get(0);
        int[] closed = {0};
        call.listener.onAttempt(first.getMessageId(), () -> closed[0]++);
        time.advance(2900);
        call.listener.onForwarded(first.getMessageId());
        call.listener.onAttempt(second.getMessageId(), () -> closed[0]++);
        queued(0);
        time.advance(100);
        assertEquals(0, closed[0]);
        scheduler.pause();
        time.advance(10_000);
        assertEquals(0, closed[0]);
        assertTrue(call.listener.isCancelled());
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
