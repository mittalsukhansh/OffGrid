package com.example.offgrid.mesh.relay;

import com.example.offgrid.MeshMessage;
import com.example.offgrid.mesh.MeshConfig;
import com.example.offgrid.mesh.storage.MeshStorage;
import com.example.offgrid.mesh.transport.MeshTransport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Worker-confined routing policy. No Android Bluetooth, Service, Handler or Room API. */
public final class RelayScheduler {
    public static final int MAX_CONNECTIONS = 3;
    public static final long SEND_TURN_MS = 3_000;
    public static final int MAX_BATCH = 20;
    public static final int MAX_FORWARDS = 6;
    public static final int MAX_FORWARDS_PER_PEER = 3;
    public static final int MAX_QUEUE = 200;
    public static final long PEER_FRESH_MS = 10_000;
    public static final long PEER_TIMEOUT_MS = 30_000;
    private static final int MAX_PEERS = 200;
    private static final int MAX_PEER_IDS = 500;
    public static final long INVENTORY_HINT_MS = 5_000;
    public static final long RETRY_BASE_MS = 30_000;
    public static final long RETRY_MAX_MS = 120_000;

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
    private final Map<String, Runnable> attemptChecks = new HashMap<>();
    private long opportunityCounter, scheduledAt;
    private final Map<String, Map<String, Hint>> identityHints = new LinkedHashMap<>();
    private final LinkedHashMap<String, Buffered> queue = new LinkedHashMap<>();
    private final Runnable connectPass = this::connect;
    private final java.util.ArrayDeque<String> recentFailures = new java.util.ArrayDeque<>();
    private java.util.function.Predicate<String> inventoryReady = peer -> true;

    public void inventoryReady(java.util.function.Predicate<String> ready) {
        inventoryReady = ready;
    }

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
        for (Runnable check : attemptChecks.values()) timer.cancel(check);
        attemptChecks.clear();
        sessions.clear();
        transport.cancelSends();
        peers.clear();
        publishPeerCount();
    }

    public void clear() {
        pause();
        queue.clear();
        identityHints.clear();
    }

    public void add(MeshStorage.Pending pending) {
        if (pending == null || queue.containsKey(pending.message.getMessageId())) return;
        long remaining = Math.min(MeshConfig.PENDING_TTL_MS, pending.expiresAtMs - timer.wall());
        if (remaining <= 0) return;
        queue.put(
                pending.message.getMessageId(),
                new Buffered(pending, timer.elapsed() - (MeshConfig.PENDING_TTL_MS - remaining)));
        trim();
        checkAttempts();
    }

    public void remove(String id) {
        queue.remove(id);
        checkAttempts();
    }

    public boolean contains(String id) {
        return queue.containsKey(id);
    }

    public void reconcile(java.util.Collection<String> persistedIds) {
        queue.keySet().retainAll(new java.util.HashSet<>(persistedIds));
        checkAttempts();
    }

    private void checkAttempts() {
        for (Runnable check : new ArrayList<>(attemptChecks.values())) check.run();
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
        if (source != null) source.hint(id, timer.elapsed() + RETRY_BASE_MS);
    }

    /** IDs are hints from a challenge-authenticated endpoint; never delivery receipts. */
    public void inventory(String address, String identity, java.util.Collection<String> ids) {
        Peer peer = peers.get(address);
        if (peer == null) return;
        if (peer.identity != null && !identity.equals(peer.identity)) peer.hints.clear();
        peer.identity = identity;
        pruneIdentityHints();
        Map<String, Hint> remembered = identityHints.get(identity);
        boolean acceptClaims = remembered != null || identityHints.size() < MAX_PEERS;
        Map<String, Hint> shared =
                remembered == null ? new LinkedHashMap<>() : new LinkedHashMap<>(remembered);
        for (Peer sibling : peers.values())
            if (identity.equals(sibling.identity)) {
                for (Map.Entry<String, Hint> entry : sibling.hints.entrySet()) {
                    Hint previous = shared.get(entry.getKey()), next = entry.getValue();
                    if (previous == null
                            || next.forwards > previous.forwards
                            || (next.forwards == previous.forwards
                                    && next.retryAt < previous.retryAt))
                        shared.put(entry.getKey(), next);
                }
            }
        // A peer cannot fill the hint table with invented IDs or renew a known claim forever.
        shared.keySet().removeIf(id -> !queue.containsKey(id));
        for (String id : ids)
            if (acceptClaims && queue.containsKey(id))
                shared.putIfAbsent(id, new Hint(timer.elapsed() + INVENTORY_HINT_MS, 0));
        if (acceptClaims) identityHints.put(identity, shared);
        for (Peer sibling : peers.values())
            if (identity.equals(sibling.identity)) {
                sibling.hints.clear();
                sibling.hints.putAll(shared);
            }
        publishPeerCount();
    }

    private void pruneIdentityHints() {
        identityHints
                .values()
                .removeIf(hints -> {
                    hints.keySet().removeIf(id -> !queue.containsKey(id));
                    return hints.isEmpty();
                });
    }

    public java.util.List<String> inventoryIds(int cursor, int limit) {
        java.util.List<String> all = new java.util.ArrayList<>(queue.keySet());
        java.util.List<String> page = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(limit, all.size()); i++)
            page.add(all.get((cursor + i) % all.size()));
        return page;
    }

    private String logical(Peer p) {
        return p.identity == null ? p.address : "identity:" + p.identity;
    }

    public void maintenance() {
        long now = timer.elapsed();
        pruneIdentityHints();
        for (Peer peer : peers.values()) peer.hints.keySet().removeIf(id -> !queue.containsKey(id));
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
        if (!active) return;
        long due = timer.elapsed() + Math.max(0, delayMs);
        if (scheduled && scheduledAt <= due) return;
        if (scheduled) timer.cancel(connectPass);
        scheduled = true;
        scheduledAt = due;
        timer.schedule(connectPass, Math.max(0, delayMs));
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

    private boolean readyFor(Peer peer, String id) {
        if (!eligible(id) || !peer.ready(id, timer.elapsed())) return false;
        Hint hint = peer.hints.get(id);
        int attempts = hint == null ? 0 : hint.forwards;
        // Never spend the final global attempt retrying an already-served neighbor.
        // Keep an opportunity for a new relay that appears after a partition.
        return attempts < MAX_FORWARDS_PER_PEER
                && (attempts == 0 || queue.get(id).forwardCount < MAX_FORWARDS - 1);
    }

    private static int priority(MeshMessage packet) {
        if (packet.isAcknowledgement()) return 0;
        if (packet.getType() == MeshMessage.TYPE_PRIVATE
                || packet.getType() == MeshMessage.TYPE_PUBLIC) return 1;
        if (packet.getType() == MeshMessage.TYPE_KEY_ANNOUNCE) return 3;
        return 2;
    }

    private List<MeshTransport.Packet> pendingFor(Peer peer, int limit, List<Buffered> ordered) {
        List<MeshTransport.Packet> result = new ArrayList<>();
        if (peer.address.startsWith("ble:coc:")
                && transport.profile(peer.address)
                        != com.example.offgrid.mesh.transport.TransferProfile.COC) return result;
        boolean waitingForInventory = !inventoryReady.test(peer.address);
        for (Buffered buffered : ordered) {
            // Inventory is an optimization, not packet authentication. Do not hold chat/receipts
            // for the initial handshake; signature, encryption and admission checks still apply.
            if (waitingForInventory && priority(buffered.message) > 1) continue;
            if (result.size() >= limit) break;
            String id = buffered.message.getMessageId();
            if (readyFor(peer, id)) {
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
        List<Buffered> ordered = new ArrayList<>(queue.values());
        ordered.sort(
                java.util.Comparator.<Buffered>comparingInt(buffer -> priority(buffer.message))
                        .thenComparingLong(buffer -> buffer.lastOpportunity));
        List<Peer> candidates = new ArrayList<>();
        for (Peer peer : peers.values()) {
            if (!sessions.containsKey(peer.address)
                    && now - peer.lastSeen <= PEER_FRESH_MS
                    && now >= peer.backoffUntil
                    && !pendingFor(peer, 1, ordered).isEmpty()) candidates.add(peer);
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
            boolean sameIdentityBusy = false;
            for (String endpoint : sessions.keySet()) {
                Peer other = peers.get(endpoint);
                if (other != null && logical(peer).equals(logical(other))) sameIdentityBusy = true;
            }
            if (sameIdentityBusy) continue;
            List<MeshTransport.Packet> batch = pendingFor(peer, MAX_BATCH, ordered);
            if (batch.isEmpty()) continue;
            Object session = new Object();
            sessions.put(peer.address, session);
            MeshTransport.SendListener callbacks =
                    new MeshTransport.SendListener() {
                        int forwarded;
                        String attempting;
                        long attemptStarted;
                        boolean yielded;
                        Runnable cancelAttempt;
                        final Runnable turnDeadline = this::checkTurn;

                        private void finishTurn() {
                            timer.cancel(turnDeadline);
                            if (attemptChecks.get(peer.address) == turnDeadline)
                                attemptChecks.remove(peer.address);
                            attempting = null;
                        }

                        private void checkTurn() {
                            timer.cancel(turnDeadline);
                            if (!current() || yielded || attempting == null) return;
                            long remaining = SEND_TURN_MS - (timer.elapsed() - attemptStarted);
                            boolean removed = !queue.containsKey(attempting);
                            boolean waitingText = false;
                            for (Buffered other : queue.values())
                                if (!other.message.getMessageId().equals(attempting)
                                        && priority(other.message) <= 1
                                        && readyFor(peer, other.message.getMessageId())) {
                                    waitingText = true;
                                    break;
                                }
                            if (removed || (remaining <= 0 && waitingText)) {
                                yielded = true;
                                if (!removed) {
                                    Hint previous = peer.hints.get(attempting);
                                    peer.hints.put(
                                            attempting,
                                            new Hint(
                                                    timer.elapsed() + SEND_TURN_MS,
                                                    previous == null ? 0 : previous.forwards));
                                }
                                Runnable close = cancelAttempt;
                                finishTurn();
                                close.run();
                            } else if (remaining > 0) timer.schedule(turnDeadline, remaining);
                            // No polling when alone: add/reconcile/remove checks wake this guard.
                        }

                        @Override
                        public void onAttempt(String id, Runnable cancel) {
                            if (!current() || yielded) {
                                cancel.run();
                                return;
                            }
                            cancelAttempt = cancel;
                            if (!id.equals(attempting)) {
                                finishTurn();
                                attempting = id;
                                attemptStarted = timer.elapsed();
                                Buffered buffered = queue.get(id);
                                if (buffered != null)
                                    buffered.lastOpportunity = ++opportunityCounter;
                            }
                            attemptChecks.put(peer.address, turnDeadline);
                            checkTurn();
                        }

                        @Override
                        public boolean isCancelled() {
                            return !current() || yielded;
                        }

                        private boolean current() {
                            return active && sessions.get(peer.address) == session;
                        }

                        @Override
                        public boolean shouldSend(String id) {
                            return current() && !yielded && readyFor(peer, id);
                        }

                        @Override
                        public void onForwarded(String id) {
                            if (!current() || yielded) return;
                            if (id.equals(attempting)) finishTurn();
                            forwarded++;
                            Hint previous = peer.hints.get(id);
                            int attempt = previous == null ? 1 : previous.forwards + 1;
                            long retry =
                                    Math.min(
                                            RETRY_MAX_MS,
                                            RETRY_BASE_MS << Math.min(attempt - 1, 2));
                            Hint sent = new Hint(timer.elapsed() + retry, attempt);
                            peer.hints.put(id, sent);
                            if (peer.identity != null && identityHints.containsKey(peer.identity))
                                identityHints.get(peer.identity).put(id, sent);
                            if (peer.identity != null)
                                for (Peer sibling : peers.values())
                                    if (peer.identity.equals(sibling.identity))
                                        sibling.hints.put(id, sent);
                            peer.trimHints();
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
                            finishTurn();
                            if (!current()) return;
                            sessions.remove(peer.address);
                            if (failure != null && !yielded) {
                                if (recentFailures.size() == 8) recentFailures.removeFirst();
                                recentFailures.addLast(peer.address + ": " + failure);
                            }
                            if (yielded || failure == null || forwarded > 0) {
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
                            schedule(yielded ? 0 : 100);
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
        text.append(
                "Automatic relay: on while mesh is running\n"
                        + "Nearby peers are direct neighbors, not all reachable contacts.\n");
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
                .append("\nLogical peers (unverified endpoints counted separately): ")
                .append(publishedPeerCount)
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
        Set<String> unique = new java.util.HashSet<>();
        for (Peer peer : peers.values()) unique.add(logical(peer));
        if (publishedPeerCount == unique.size()) return;
        publishedPeerCount = unique.size();
        listener.onPeerCount(publishedPeerCount);
    }

    private static final class Buffered {
        final MeshMessage message;
        final long storedAt;
        int forwardCount;
        long lastOpportunity;

        Buffered(MeshStorage.Pending pending, long storedAt) {
            message = pending.message;
            forwardCount = pending.forwardCount;
            this.storedAt = storedAt;
        }
    }

    private static final class Hint {
        final long retryAt;
        final int forwards;

        Hint(long retryAt, int forwards) {
            this.retryAt = retryAt;
            this.forwards = forwards;
        }
    }

    private static final class Peer {
        final String address;
        String identity;
        long lastSeen, backoffUntil;
        int rssi, failures;
        final Map<String, Hint> hints = new LinkedHashMap<>();

        Peer(String address) {
            this.address = address;
        }

        boolean ready(String id, long now) {
            Hint hint = hints.get(id);
            return hint == null || now >= hint.retryAt;
        }

        void hint(String id, long until) {
            hints.putIfAbsent(id, new Hint(until, 0));
            trimHints();
        }

        void trimHints() {
            while (hints.size() > MAX_PEER_IDS) hints.remove(hints.keySet().iterator().next());
        }
    }
}
