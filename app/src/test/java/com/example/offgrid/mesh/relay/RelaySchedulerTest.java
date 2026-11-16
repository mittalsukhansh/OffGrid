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

    private static final class FakeTimer implements RelayScheduler.Timer {
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

    private static final class FakeStorage implements MeshStorage {
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
