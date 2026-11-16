package com.example.offgrid.mesh;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.example.offgrid.MeshMessage;
import com.example.offgrid.PrivateActions;
import com.example.offgrid.mesh.protocol.MeshProtocol;
import com.example.offgrid.mesh.relay.RelayScheduler;
import com.example.offgrid.mesh.storage.MeshStorage;
import com.example.offgrid.mesh.transport.MeshTransport;

import java.util.Random;

/**
 * Worker coordinator. The service owns Android lifecycle; components own their respective state.
 */
public final class MeshNode implements MeshTransport.Listener {
    public interface Events {
        void onStatus(String status);

        void onPeerCount(int count);

        void onReceived(MeshMessage message);

        void onError(String message);
    }

    private final PrivateActions actions;
    private final Handler worker;
    private final MeshTransport transport;
    private final MeshStorage storage;
    private final MeshProtocol protocol;
    private final RelayScheduler relay;
    private final com.example.offgrid.mesh.protocol.InventoryExchange inventory;
    private final Events events;
    private final Random random = new Random();
    private final Runnable maintenanceTask = this::maintain;
    private final Runnable mediaTask = this::pumpMedia;
    private final java.util.Map<String, RouteHint> routes = new java.util.LinkedHashMap<>();

    private static final class RouteHint {
        final String peer;
        final long seen;

        RouteHint(String peer, long seen) {
            this.peer = peer;
            this.seen = seen;
        }
    }

    private volatile boolean active, closed;
    private boolean ready, started;
    private long lastAnnouncement;
    private long receivedPackets, acceptedPackets;
    private final java.util.ArrayDeque<String> recentErrors = new java.util.ArrayDeque<>();

    public MeshNode(
            Handler worker,
            MeshTransport transport,
            MeshStorage storage,
            MeshProtocol protocol,
            PrivateActions actions,
            Events events) {
        this.actions = actions;
        actions.transferProfiles(target -> {
            RouteHint hint = routes.get(target);
            return hint != null && SystemClock.elapsedRealtime() - hint.seen < 15_000
                    ? transport.profile(hint.peer)
                    : com.example.offgrid.mesh.transport.TransferProfile.GATT;
        });
        this.worker = worker;
        this.transport = transport;
        this.storage = storage;
        this.protocol = protocol;
        this.events = events;
        relay =
                new RelayScheduler(
                        new RelayScheduler.Timer() {
                            @Override
                            public long elapsed() {
                                return SystemClock.elapsedRealtime();
                            }

                            @Override
                            public long wall() {
                                return System.currentTimeMillis();
                            }

                            @Override
                            public void schedule(Runnable task, long delay) {
                                worker.postDelayed(task, delay);
                            }

                            @Override
                            public void cancel(Runnable task) {
                                worker.removeCallbacks(task);
                            }
                        },
                        storage,
                        transport,
                        MeshMessage::toBytes,
                        new RelayScheduler.Listener() {
                            @Override
                            public void onPeerCount(int count) {
                                events.onPeerCount(count);
                            }

                            @Override
                            public void onError(String error) {
                                reportError(error);
                            }
                        });
        inventory =
                new com.example.offgrid.mesh.protocol.InventoryExchange(
                        protocol.localId(),
                        SystemClock::elapsedRealtime,
                        new com.example.offgrid.mesh.protocol.InventoryExchange.Link() {
                            public MeshMessage packet(String target, String body) throws Exception {
                                return protocol.inventory(target, body);
                            }

                            public java.util.List<String> ids(int cursor, int count) {
                                return relay.inventoryIds(cursor, count);
                            }

                            public void authenticated(
                                    String peer, String identity, java.util.List<String> ids) {
                                relay.inventory(peer, identity, ids);
                                relay.schedule(0);
                            }

                            public void send(String peer, MeshMessage packet) {
                                byte[] bytes = packet.toBytes();
                                if (bytes.length > transport.maxPacketBytes(peer)) return;
                                transport.send(
                                        peer,
                                        java.util.Collections.singletonList(
                                                new MeshTransport.Packet(
                                                        packet.getMessageId(), bytes)),
                                        new MeshTransport.SendListener() {
                                            public boolean shouldSend(String id) {
                                                return active && !closed;
                                            }

                                            public void onForwarded(String id) {}

                                            public void onComplete(
                                                    String failure, boolean incompatible) {}
                                        });
                            }
                        });
        relay.inventoryReady(inventory::ready);
        worker.post(this::restore);
    }

    private void restore() {
        try {
            long now = System.currentTimeMillis();
            storage.expire(now);
            for (MeshStorage.Pending pending : storage.pending()) {
                if (!protocol.validPending(pending.message, now)) {
                    storage.discard(pending.message.getMessageId(), "FAILED");
                    continue;
                }
                relay.add(pending);
                protocol.remember(pending.message.getMessageId());
            }
            ready = true;
        } catch (Exception e) {
            error("Saved messages could not be restored; original data was kept", e);
        }
    }

    /** Call on worker; restore was queued before any lifecycle command. */
    public void start() {
        if (closed || started) return;
        if (!ready) {
            reportError("Message storage unavailable; mesh was not started");
            return;
        }
        started = true;
        events.onStatus("Starting mesh");
        transport.start(this);
        worker.post(mediaTask);
        worker.postDelayed(maintenanceTask, MeshConfig.MAINTENANCE_MS);
    }

    public void shutdown() {
        closed = true;
        worker.post(() -> {
            started = false;
            active = false;
            worker.removeCallbacks(maintenanceTask);
            worker.removeCallbacks(mediaTask);
            routes.clear();
            inventory.clear();
            relay.clear();
            transport.stop();
            protocol.clear();
        });
    }

    public boolean active() {
        return active;
    }

    public String localId() {
        return protocol.localId();
    }

    public MeshMessage sendPublic(String text) {
        if (closed) return null;
        MeshProtocol.Outgoing outgoing = protocol.publicMessage(text);
        if (outgoing.status != MeshProtocol.SendStatus.ACCEPTED) return null;
        worker.post(() -> persistOutgoing(outgoing));
        return outgoing.display;
    }

    public MeshProtocol.Outgoing sendPrivate(String target, String text) {
        MeshProtocol.Outgoing outgoing =
                protocol.privateMessage(target, text, System.currentTimeMillis());
        if (outgoing.status == MeshProtocol.SendStatus.ACCEPTED)
            worker.post(() -> persistOutgoing(outgoing));
        return outgoing;
    }

    public void unsend(String id) {
        worker.post(() -> {
            try {
                actions.unsend(id, System.currentTimeMillis());
                refreshOutbox();
                relay.schedule(0);
            } catch (Exception e) {
                error("Could not request unsend: " + e.getMessage(), e);
            }
        });
    }

    public void sendMedia(String target, byte[] bytes, String mime) {
        worker.post(() -> {
            try {
                actions.offer(target, bytes, mime, System.currentTimeMillis());
                wakeMedia();
                refreshOutbox();
                relay.schedule(0);
            } catch (Exception e) {
                error("Could not send attachment: " + e.getMessage(), e);
            }
        });
    }

    private void refreshOutbox() throws Exception {
        java.util.List<String> ids = storage.pendingIds();
        relay.reconcile(ids);
        for (String id : ids) if (!relay.contains(id)) relay.add(storage.pending(id));
    }

    private void persistOutgoing(MeshProtocol.Outgoing outgoing) {
        if (closed || !ready) {
            storage.outgoingFailed(outgoing.wire.getMessageId());
            reportError("Message storage unavailable; message was not queued");
            return;
        }
        try {
            storage.save(
                    outgoing.wire,
                    outgoing.display,
                    true,
                    outgoing.wire,
                    System.currentTimeMillis());
            relay.add(storage.pending(outgoing.wire.getMessageId()));
            protocol.remember(outgoing.wire.getMessageId());
            relay.schedule(jitter());
        } catch (Exception e) {
            storage.outgoingFailed(outgoing.wire.getMessageId());
            error("Could not update saved messages; check message status before retrying", e);
        }
    }

    private void announce() {
        lastAnnouncement = SystemClock.elapsedRealtime();
        try {
            MeshMessage message = protocol.announcement();
            storage.save(message, null, false, message, System.currentTimeMillis());
            relay.add(storage.pending(message.getMessageId()));
            protocol.remember(message.getMessageId());
            relay.schedule(jitter());
        } catch (Exception e) {
            error("Could not save identity announcement", e);
        }
    }

    private void wakeMedia() {
        if (closed || !started) return;
        worker.removeCallbacks(mediaTask);
        worker.post(mediaTask);
    }

    private void pumpMedia() {
        if (closed || !started) return;
        try {
            actions.pump(System.currentTimeMillis(), SystemClock.elapsedRealtime());
            refreshOutbox();
            relay.schedule(0);
        } catch (Exception e) {
            error("Could not advance attachment transfers", e);
        }
        worker.postDelayed(mediaTask, actions.pumpDelay());
    }

    private void maintain() {
        if (closed || !started) return;
        try {
            storage.expire(System.currentTimeMillis());
        } catch (RuntimeException e) {
            reportError("Could not refresh delivery deadlines");
        }
        try {
            refreshOutbox();
        } catch (Exception e) {
            error("Could not resume attachment transfers", e);
        }
        relay.maintenance();
        protocol.maintenance();
        transport.maintain();
        if (active
                && SystemClock.elapsedRealtime() - lastAnnouncement
                        >= MeshConfig.KEY_ANNOUNCE_INTERVAL_MS) announce();
        worker.postDelayed(maintenanceTask, MeshConfig.MAINTENANCE_MS);
    }

    @Override
    public void onPacket(String peer, byte[] packet) {
        if (closed || !active) return;
        receivedPackets++;
        try {
            MeshProtocol.Incoming incoming =
                    protocol.receive(peer, packet, System.currentTimeMillis());
            if (incoming.wire != null && incoming.wire.isInventory()) {
                inventory.receive(peer, incoming.wire);
                return;
            }
            if (incoming.wire != null) {
                acceptedPackets++;
                // TTL is only a performance hint, not an authenticated route proof.
                if (incoming.wire.getTtl() == MeshMessage.DEFAULT_TTL
                        && localId().equals(incoming.wire.getTargetId())) {
                    if (routes.size() >= 256) routes.remove(routes.keySet().iterator().next());
                    routes.put(
                            incoming.wire.getSenderId(),
                            new RouteHint(peer, SystemClock.elapsedRealtime()));
                }
                if (incoming.wire.isControl() || incoming.wire.isMedia()) wakeMedia();
            }
            if (incoming.error != null) reportError(incoming.error);
            if (incoming.deliveredId != null) relay.remove(incoming.deliveredId);
            if (incoming.wire != null) relay.receivedFrom(peer, incoming.wire.getMessageId());
            if (incoming.relay != null) {
                relay.add(storage.pending(incoming.relay.getMessageId()));
                relay.schedule(jitter());
            }
            if (incoming.wire != null) refreshOutbox();
            if (incoming.display != null) events.onReceived(incoming.display);
        } catch (Exception e) {
            error("Could not accept packet or update message storage", e);
        }
    }

    @Override
    public void onPeerSeen(String peer, int rssi) {
        if (active && !closed) {
            relay.peerSeen(peer, rssi);
            inventory.seen(peer);
            worker.postDelayed(
                    () -> {
                        if (active && !closed) relay.schedule(0);
                    },
                    5000);
        }
    }

    @Override
    public void onRadioState(boolean enabled) {
        active = enabled && started && !closed;
        if (!started || closed) return;
        if (active) {
            relay.start();
            announce();
            events.onStatus("Mesh active");
        } else {
            relay.pause();
            events.onStatus("Waiting for Bluetooth or local Wi-Fi");
        }
    }

    @Override
    public void onError(String message) {
        events.onStatus(message);
        reportError(message);
    }

    public void diagnostics(java.util.function.Consumer<String> callback) {
        worker.post(() -> {
            StringBuilder text = new StringBuilder(relay.diagnostics());
            text.append("\n\nPackets received this run: ")
                    .append(receivedPackets)
                    .append("\nAccepted packet events: ")
                    .append(acceptedPackets)
                    .append("\nIgnored packet events: ")
                    .append(receivedPackets - acceptedPackets)
                    .append("\n(Ignored includes duplicates, limits and invalid packets.)");
            text.append("\n\n").append(actions.transferDiagnostics(System.currentTimeMillis()));
            if (!recentErrors.isEmpty()) {
                text.append("\n\nRecent errors (this run):");
                for (String error : recentErrors) text.append("\n").append(error);
            }
            callback.accept(text.toString());
        });
    }

    private void reportError(String message) {
        if (recentErrors.size() >= 8) recentErrors.removeFirst();
        recentErrors.addLast(
                android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                        + " "
                        + message);
        events.onError(message);
    }

    private int jitter() {
        return 50 + random.nextInt(351);
    }

    private void error(String message, Exception failure) {
        Log.e("OffGridMesh", message, failure);
        reportError(message);
    }
}
