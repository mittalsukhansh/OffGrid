package com.example.offgrid.mesh.relay;

import static org.junit.Assert.*;

import com.example.offgrid.MeshMessage;
import com.example.offgrid.mesh.storage.MeshStorage;
import com.example.offgrid.mesh.transport.MeshTransport;

import org.junit.Test;

import java.util.*;

/**
 * Deterministic production relay/codec simulator. Crypto and Android storage are separate device
 * tests.
 */
public class MeshSimulationTest {
    @Test
    public void partitionsLossReorderingAndRelayRestartPreserveBoundedDelivery() throws Exception {
        Network net = new Network(77331);
        Node a = net.add("A"), b = net.add("B"), c = net.add("C");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            MeshMessage message =
                    MeshMessage.create("11111111111111111111111111111111", "Message " + i, 3);
            ids.add(message.getMessageId());
            a.accept(message);
        }
        net.rounds(80, false, true); // A/B reachable, C partitioned; seeded losses/reordering.
        assertTrue(c.accepted.isEmpty());
        assertFalse(b.accepted.isEmpty());
        int before = b.accepted.size();
        b.restart(); // restore persisted queues and forwarding counts; retain replay IDs.
        assertEquals(before, b.accepted.size());
        net.loss = false;
        net.rounds(100, true, false);
        assertTrue(c.accepted.containsAll(ids));
        for (Node node : net.nodes.values()) {
            assertEquals(node.accepted.size(), node.deliveries);
            for (MeshStorage.Pending pending : node.pending.values())
                assertTrue(pending.forwardCount <= RelayScheduler.MAX_FORWARDS);
        }
        assertFalse(net.acDirect); // The graph never connected A to C.
    }

    private static final class Network {
        final Random random;
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final List<Runnable> inflight = new ArrayList<>();
        boolean joined, loss = true, acDirect;

        Network(long seed) {
            random = new Random(seed);
        }

        Node add(String name) {
            Node n = new Node(this, name);
            nodes.put(name, n);
            return n;
        }

        boolean linked(String from, String to) {
            return !from.equals(to)
                    && !((from.equals("A") && to.equals("C"))
                            || (from.equals("C") && to.equals("A")))
                    && (joined || (!from.equals("C") && !to.equals("C")));
        }

        void rounds(int count, boolean joined, boolean drop) {
            this.joined = joined;
            loss = drop;
            for (int step = 0; step < count; step++) {
                for (Node n : nodes.values()) {
                    for (String peer : nodes.keySet())
                        if (linked(n.name, peer)) n.relay.peerSeen(peer, -40);
                    n.relay.maintenance();
                    n.time.advance(1000);
                }
                Collections.shuffle(inflight, random);
                List<Runnable> batch = new ArrayList<>(inflight);
                inflight.clear();
                for (Runnable task : batch) task.run();
            }
        }
    }

    private static final class Node {
        final Network net;
        final String name;
        final RelaySchedulerTest.FakeTimer time = new RelaySchedulerTest.FakeTimer();
        final Map<String, MeshStorage.Pending> pending = new LinkedHashMap<>();
        final Set<String> accepted = new HashSet<>();
        int deliveries;
        RelayScheduler relay;

        Node(Network net, String name) {
            this.net = net;
            this.name = name;
            restart();
        }

        void restart() {
            if (relay != null) relay.clear();
            MeshStorage store =
                    new RelaySchedulerTest.FakeStorage() {
                        @Override
                        public void forwarded(String id, int count) {
                            MeshStorage.Pending old = pending.get(id);
                            if (old != null)
                                pending.put(
                                        id,
                                        new MeshStorage.Pending(
                                                old.message,
                                                old.storedAtMs,
                                                old.expiresAtMs,
                                                count));
                        }

                        @Override
                        public void discard(String id, String status) {
                            pending.remove(id);
                        }
                    };
            MeshTransport transport =
                    new MeshTransport() {
                        public void start(Listener listener) {}

                        public void stop() {}

                        public void maintain() {}

                        public void cancelSends() {}

                        public void send(String peer, List<Packet> packets, SendListener listener) {
                            net.inflight.add(() -> {
                                if (!net.linked(name, peer)
                                        || (net.loss && net.random.nextInt(4) == 0)) {
                                    listener.onComplete("partition/loss", false);
                                    return;
                                }
                                for (Packet packet : packets)
                                    if (listener.shouldSend(packet.id)) {
                                        Node target = net.nodes.get(peer);
                                        target.accept(MeshMessage.fromBytes(packet.bytes));
                                        target.relay.receivedFrom(name, packet.id);
                                        listener.onForwarded(packet.id);
                                    }
                                listener.onComplete(null, false);
                            });
                        }
                    };
            relay =
                    new RelayScheduler(
                            time,
                            store,
                            transport,
                            MeshMessage::toBytes,
                            new RelayScheduler.Listener() {
                                public void onPeerCount(int count) {}

                                public void onError(String message) {
                                    fail(message);
                                }
                            });
            for (MeshStorage.Pending row : pending.values()) relay.add(row);
            relay.start();
        }

        void accept(MeshMessage message) {
            if (!accepted.add(message.getMessageId())) return;
            deliveries++;
            MeshMessage forward = message.withDecrementedTtl();
            if (forward.isRelayable()) {
                MeshStorage.Pending row =
                        new MeshStorage.Pending(forward, time.wall(), time.wall() + 600000, 0);
                pending.put(message.getMessageId(), row);
                relay.add(row);
                relay.schedule(0);
            }
        }
    }
}
