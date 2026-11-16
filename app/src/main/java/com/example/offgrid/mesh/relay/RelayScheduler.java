package com.example.offgrid.mesh.relay;

import com.example.offgrid.MeshMessage;
import com.example.offgrid.mesh.MeshConfig;
import com.example.offgrid.mesh.storage.MeshStorage;
import com.example.offgrid.mesh.transport.MeshTransport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Worker-confined routing policy. No Android Bluetooth, Service, Handler or Room API. */
public final class RelayScheduler {
    public static final int MAX_CONNECTIONS = 3;
    public static final int MAX_BATCH = 20;
    public static final int MAX_FORWARDS = 6;
    public static final int MAX_QUEUE = 200;
    public static final long PEER_FRESH_MS = 10_000;
    public static final long PEER_TIMEOUT_MS = 30_000;
    private static final int MAX_PEERS = 200;
    private static final int MAX_PEER_IDS = 500;

    public interface Timer {
        long elapsed();

        long wall();

        void schedule(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    public interface Listener {
        void onPeerCount(int count);

        void onError(String message);
    }

    private final Timer timer;
    private final MeshStorage storage;
    private final MeshTransport transport;
    private final Listener listener;
    private final Function<MeshMessage, byte[]> encoder;
    private final Map<String, Peer> peers = new HashMap<>();
    private final Map<String, Object> sessions = new HashMap<>();
    private final LinkedHashMap<String, Buffered> queue = new LinkedHashMap<>();
    private final Runnable connectPass = this::connect;
    private final java.util.ArrayDeque<String> recentFailures = new java.util.ArrayDeque<>();
    private boolean active;
    private boolean scheduled;
    private int publishedPeerCount;

    public RelayScheduler(
            Timer timer,
            MeshStorage storage,
            MeshTransport transport,
            Function<MeshMessage, byte[]> encoder,
            Listener listener) {
        this.timer = timer;
        this.storage = storage;
        this.transport = transport;
        this.listener = listener;
        this.encoder = encoder;
    }

    public void start() {
        active = true;
        schedule(0);
    }

    public void pause() {
        active = false;
        scheduled = false;
        timer.cancel(connectPass);
        sessions.clear();
        transport.cancelSends();
        peers.clear();
        publishPeerCount();
    }

    public void clear() {
        pause();
        queue.clear();
    }

    public void add(MeshStorage.Pending pending) {
        if (pending == null || queue.containsKey(pending.message.getMessageId())) return;
        long remaining = Math.min(MeshConfig.PENDING_TTL_MS, pending.expiresAtMs - timer.wall());
        if (remaining <= 0) return;
        queue.put(
                pending.message.getMessageId(),
                new Buffered(pending, timer.elapsed() - (MeshConfig.PENDING_TTL_MS - remaining)));
        trim();
    }

    public void remove(String id) {
        queue.remove(id);
    }

    public boolean contains(String id) {
        return queue.containsKey(id);
    }

    public void reconcile(java.util.Collection<String> persistedIds) {
        queue.keySet().retainAll(new java.util.HashSet<>(persistedIds));
    }

    public boolean eligible(String id) {
        Buffered buffered = queue.get(id);
        return buffered != null
                && timer.elapsed() - buffered.storedAt < MeshConfig.PENDING_TTL_MS
                && buffered.forwardCount < MAX_FORWARDS;
    }

    public void peerSeen(String address, int rssi) {
        Peer peer = peers.get(address);
        if (peer == null) {
            if (peers.size() >= MAX_PEERS) evictStalest();
            if (peers.size() >= MAX_PEERS) return;
            peer = new Peer(address);
            peers.put(address, peer);
            publishPeerCount();
        }
        peer.lastSeen = timer.elapsed();
        peer.rssi = rssi;
        if (!queue.isEmpty() && !sessions.containsKey(address)) schedule(0);
    }

    public void receivedFrom(String peer, String id) {
        Peer source = peers.get(peer);
        if (source != null) source.remember(id);
    }

    public void maintenance() {
        long now = timer.elapsed();
        peers.entrySet()
                .removeIf(entry ->
                        now - entry.getValue().lastSeen > PEER_TIMEOUT_MS
                                && !sessions.containsKey(entry.getKey()));
        publishPeerCount();
        Iterator<Map.Entry<String, Buffered>> iterator = queue.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Buffered> entry = iterator.next();
            if (now - entry.getValue().storedAt < MeshConfig.PENDING_TTL_MS) continue;
            try {
                storage.discard(entry.getKey(), "EXPIRED");
                iterator.remove();
            } catch (RuntimeException e) {
                listener.onError("Could not expire a stored message");
            }
        }
        schedule(0);
    }

    public void schedule(long delayMs) {
        if (!active || scheduled) return;
        scheduled = true;
        timer.schedule(connectPass, delayMs);
    }

    private void trim() {
        while (queue.size() > MAX_QUEUE) {
            String victim = null;
            for (Map.Entry<String, Buffered> entry : queue.entrySet()) {
                if (victim == null) victim = entry.getKey();
                if (entry.getValue().message.isKeyAnnounce()) {
                    victim = entry.getKey();
                    break;
                }
            }
            storage.discard(victim, "FAILED");
            queue.remove(victim);
        }
    }

    private List<MeshTransport.Packet> pendingFor(Peer peer, int limit) {
        List<MeshTransport.Packet> result = new ArrayList<>();
        if (peer.address.startsWith("ble:coc:")
                && transport.profile(peer.address)
                        != com.example.offgrid.mesh.transport.TransferProfile.COC) return result;
        for (Buffered buffered : queue.values()) {
            if (result.size() >= limit) break;
            String id = buffered.message.getMessageId();
            if (eligible(id) && !peer.deliveredIds.contains(id)) {
                byte[] bytes = encoder.apply(buffered.message);
                if (bytes.length <= transport.maxPacketBytes(peer.address))
                    result.add(new MeshTransport.Packet(id, bytes));
            }
        }
        return result;
    }

    private void connect() {
        scheduled = false;
        if (!active || queue.isEmpty() || sessions.size() >= MAX_CONNECTIONS) return;
        long now = timer.elapsed();
        List<Peer> candidates = new ArrayList<>();
        for (Peer peer : peers.values()) {
            if (!sessions.containsKey(peer.address)
                    && now - peer.lastSeen <= PEER_FRESH_MS
                    && now >= peer.backoffUntil
                    && !pendingFor(peer, 1).isEmpty()) candidates.add(peer);
        }
        candidates.sort((a, b) -> {
            int profile =
                    Integer.compare(
                            transport.profile(b.address).ordinal(),
                            transport.profile(a.address).ordinal());
            return profile != 0 ? profile : Integer.compare(b.rssi, a.rssi);
        });
        for (Peer peer : candidates) {
            if (sessions.size() >= MAX_CONNECTIONS) break;
            List<MeshTransport.Packet> batch = pendingFor(peer, MAX_BATCH);
            if (batch.isEmpty()) continue;
            Object session = new Object();
            sessions.put(peer.address, session);
            MeshTransport.SendListener callbacks =
                    new MeshTransport.SendListener() {
                        int forwarded;

                        private boolean current() {
                            return active && sessions.get(peer.address) == session;
                        }

                        @Override
                        public boolean shouldSend(String id) {
                            return current() && eligible(id);
                        }

                        @Override
                        public void onForwarded(String id) {
                            if (!current()) return;
                            forwarded++;
                            peer.remember(id);
                            Buffered buffered = queue.get(id);
                            if (buffered != null) {
                                try {
                                    storage.forwarded(id, buffered.forwardCount + 1);
                                    buffered.forwardCount++;
                                } catch (RuntimeException e) {
                                    listener.onError("Could not save forwarding progress");
                                }
                            }
                        }

                        @Override
                        public void onComplete(String failure, boolean incompatible) {
                            if (!current()) return;
                            sessions.remove(peer.address);
                            if (failure != null) {
                                if (recentFailures.size() == 8) recentFailures.removeFirst();
                                recentFailures.addLast(peer.address + ": " + failure);
                            }
                            if (failure == null || forwarded > 0) {
                                peer.failures = 0;
                                peer.backoffUntil = 0;
                            } else if (incompatible)
                                peer.backoffUntil = timer.elapsed() + 5 * 60_000L;
                            else {
                                peer.failures++;
                                peer.backoffUntil =
                                        timer.elapsed()
                                                + Math.min(
                                                        60_000L,
                                                        2_000L << Math.min(peer.failures, 5));
                            }
                            schedule(100);
                        }
                    };
            try {
                transport.send(peer.address, batch, callbacks);
            } catch (RuntimeException e) {
                callbacks.onComplete("Transport rejected session", false);
            }
        }
    }

    public String diagnostics() {
        StringBuilder text = new StringBuilder();
        text.append("Relay queue: ")
                .append(queue.size())
                .append(" packets\n")
                .append("Active outbound sessions: ")
                .append(sessions.size())
                .append(" / ")
                .append(MAX_CONNECTIONS)
                .append("\n")
                .append("Discovered transport endpoints: ")
                .append(peers.size())
                .append("\n");
        long now = timer.elapsed();
        for (Peer peer : peers.values()) {
            text.append("\n")
                    .append(peer.address)
                    .append(" · seen ")
                    .append(Math.max(0, now - peer.lastSeen) / 1000)
                    .append("s ago");
            if (!peer.address.startsWith("wifi:"))
                text.append(" · ").append(peer.rssi).append(" dBm");
            text.append(" · failures ").append(peer.failures);
            if (peer.backoffUntil > now)
                text.append(" · retry in ")
                        .append((peer.backoffUntil - now + 999) / 1000)
                        .append("s");
        }
        if (!recentFailures.isEmpty()) {
            text.append("\n\nRecent connection failures:");
            for (String failure : recentFailures) text.append("\n").append(failure);
        }
        return text.toString();
    }

    private void evictStalest() {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (Peer peer : peers.values()) {
            if (!sessions.containsKey(peer.address) && peer.lastSeen < oldest) {
                oldest = peer.lastSeen;
                victim = peer.address;
            }
        }
        if (victim != null) peers.remove(victim);
    }

    private void publishPeerCount() {
        if (publishedPeerCount == peers.size()) return;
        publishedPeerCount = peers.size();
        listener.onPeerCount(publishedPeerCount);
    }

    private static final class Buffered {
        final MeshMessage message;
        final long storedAt;
        int forwardCount;

        Buffered(MeshStorage.Pending pending, long storedAt) {
            message = pending.message;
            forwardCount = pending.forwardCount;
            this.storedAt = storedAt;
        }
    }

    private static final class Peer {
        final String address;
        long lastSeen, backoffUntil;
        int rssi, failures;
        final Set<String> deliveredIds = new LinkedHashSet<>();

        Peer(String address) {
            this.address = address;
        }

        void remember(String id) {
            deliveredIds.add(id);
            if (deliveredIds.size() > MAX_PEER_IDS) {
                Iterator<String> iterator = deliveredIds.iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }
}
