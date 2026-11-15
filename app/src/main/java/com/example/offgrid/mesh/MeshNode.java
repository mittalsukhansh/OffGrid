package com.example.offgrid.mesh;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.example.offgrid.MeshMessage;
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

    private final Handler worker;
    private final MeshTransport transport;
    private final MeshStorage storage;
    private final MeshProtocol protocol;
    private final RelayScheduler relay;
    private final Events events;
    private final Random random = new Random();
    private final Runnable maintenanceTask = this::maintain;
    private volatile boolean active, closed;
    private boolean ready, started;
    private long lastAnnouncement;

    public MeshNode(
            Handler worker,
            MeshTransport transport,
            MeshStorage storage,
            MeshProtocol protocol,
            Events events) {
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
                                events.onError(error);
                            }
                        });
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
            events.onError("Message storage unavailable; mesh was not started");
            return;
        }
        started = true;
        events.onStatus("Starting mesh");
        transport.start(this);
        worker.postDelayed(maintenanceTask, MeshConfig.MAINTENANCE_MS);
    }

    public void shutdown() {
        closed = true;
        worker.post(() -> {
            started = false;
            active = false;
            worker.removeCallbacks(maintenanceTask);
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

    private void persistOutgoing(MeshProtocol.Outgoing outgoing) {
        if (closed || !ready) {
            storage.outgoingFailed(outgoing.wire.getMessageId());
            events.onError("Message storage unavailable; message was not queued");
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

    private void maintain() {
        if (closed || !started) return;
        try {
            storage.expire(System.currentTimeMillis());
        } catch (RuntimeException e) {
            events.onError("Could not refresh delivery deadlines");
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
        try {
            MeshProtocol.Incoming incoming =
                    protocol.receive(peer, packet, System.currentTimeMillis());
            if (incoming.error != null) events.onError(incoming.error);
            if (incoming.deliveredId != null) relay.remove(incoming.deliveredId);
            if (incoming.wire != null) relay.receivedFrom(peer, incoming.wire.getMessageId());
            if (incoming.relay != null) {
                relay.add(storage.pending(incoming.relay.getMessageId()));
                relay.schedule(jitter());
            }
            if (incoming.display != null) events.onReceived(incoming.display);
        } catch (Exception e) {
            error("Could not accept packet or update message storage", e);
        }
    }

    @Override
    public void onPeerSeen(String peer, int rssi) {
        if (active && !closed) relay.peerSeen(peer, rssi);
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
            events.onStatus("Waiting for Bluetooth");
        }
    }

    @Override
    public void onError(String message) {
        events.onStatus(message);
        events.onError(message);
    }

    private int jitter() {
        return 50 + random.nextInt(351);
    }

    private void error(String message, Exception failure) {
        Log.e("OffGridMesh", message, failure);
        events.onError(message);
    }
}
