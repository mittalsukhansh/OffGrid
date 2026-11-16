package com.example.offgrid.mesh.transport;

import java.util.*;
import java.util.function.LongSupplier;

/** Worker-confined measurements. Confirmed wire bytes are not authenticated delivered payload. */
public final class TransportMetrics {
    private final LongSupplier clock;
    private final EnumMap<TransferProfile, Counter> counters = new EnumMap<>(TransferProfile.class);
    private long resetAt;

    public TransportMetrics(LongSupplier clock) {
        this.clock = clock;
        reset();
    }

    public void reset() {
        counters.clear();
        for (TransferProfile profile : TransferProfile.values())
            counters.put(profile, new Counter());
        resetAt = clock.getAsLong();
    }

    public void received(TransferProfile profile, int bytes) {
        Counter c = counters.get(profile);
        c.rx += bytes;
        c.rxPackets++;
    }

    public MeshTransport.SendListener sending(
            TransferProfile profile,
            List<MeshTransport.Packet> packets,
            MeshTransport.SendListener delegate) {
        Counter c = counters.get(profile);
        c.sessions++;
        long start = clock.getAsLong();
        Map<String, Integer> sizes = new HashMap<>();
        for (MeshTransport.Packet packet : packets) sizes.put(packet.id, packet.bytes.length);
        return new MeshTransport.SendListener() {
            boolean ended;

            @Override
            public boolean shouldSend(String id) {
                return !ended && delegate.shouldSend(id);
            }

            @Override
            public void onForwarded(String id) {
                if (ended) return;
                Integer bytes = sizes.remove(id);
                if (bytes != null) {
                    c.tx += bytes;
                    c.txPackets++;
                }
                delegate.onForwarded(id);
            }

            @Override
            public void onComplete(String failure, boolean incompatible) {
                if (ended) return;
                ended = true;
                c.completed++;
                c.lastMs = Math.max(0, clock.getAsLong() - start);
                c.sessionMs += c.lastMs;
                if (failure != null) c.failures++;
                delegate.onComplete(failure, incompatible);
            }
        };
    }

    public String snapshot() {
        long elapsed = Math.max(1, clock.getAsLong() - resetAt);
        StringBuilder out =
                new StringBuilder("Transport measurements · since reset ")
                        .append(elapsed / 1000)
                        .append("s");
        for (TransferProfile p : TransferProfile.values()) {
            Counter c = counters.get(p);
            out.append("\n\n")
                    .append(p)
                    .append(": TX ")
                    .append(c.tx)
                    .append(" B / ")
                    .append(c.txPackets)
                    .append(" packets · RX ")
                    .append(c.rx)
                    .append(" B / ")
                    .append(c.rxPackets)
                    .append(" packets\nTX wall average ")
                    .append(c.tx * 1000 / elapsed)
                    .append(" B/s · last session ")
                    .append(c.lastMs)
                    .append(" ms\nSessions ")
                    .append(c.completed)
                    .append('/')
                    .append(c.sessions)
                    .append(" · failures ")
                    .append(c.failures)
                    .append("\nPolicy: up to ")
                    .append(p.chunkBytes)
                    .append(" payload B every ")
                    .append(p.intervalMs)
                    .append(" ms");
        }
        return out.append(
                        "\n\n"
                            + "Wire-byte counters include encrypted envelopes, relays and retries;"
                            + " they are not unique media goodput or Delivered receipts. Wall"
                            + " averages include idle time. Reset before each controlled run."
                            + " Counters reset on service restart.")
                .toString();
    }

    private static final class Counter {
        long tx, rx, txPackets, rxPackets, sessions, completed, failures, lastMs, sessionMs;
    }
}
