package com.example.offgrid;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Cheap bounded token buckets run before allocation/crypto and again after identity verification.
 */
public final class FloodGuard {
    private final LongSupplier clock;
    private final Bucket globalFrames = new Bucket(2400, 1200);
    private final Bucket globalPackets = new Bucket(60, 3);
    private final Bucket streamPackets = new Bucket(40, 20);
    private final Map<String, Bucket> streamPeers = bounded(200);
    private final Map<String, Bucket> streamSenders = bounded(1000);
    private final Map<String, Bucket> frames = bounded(200);
    private final Map<String, Bucket> peers = bounded(200);
    private final Map<String, Bucket> senders = bounded(1000);

    public FloodGuard(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized boolean allowFrame(String address) {
        long now = clock.getAsLong();
        return globalFrames.take(now)
                && frames.computeIfAbsent(address, k -> new Bucket(240, 120)).take(now);
    }

    public synchronized boolean allowPacket(String address) {
        long now = clock.getAsLong();
        return globalPackets.take(now)
                && peers.computeIfAbsent(address, k -> new Bucket(30, 1.5)).take(now);
    }

    public synchronized boolean allowPacket(String address, boolean stream) {
        if (!stream) return allowPacket(address);
        long now = clock.getAsLong();
        return streamPackets.take(now)
                && streamPeers.computeIfAbsent(address, k -> new Bucket(20, 10)).take(now);
    }

    public synchronized boolean allowSender(String identity, boolean streamControl) {
        if (!streamControl) return allowSender(identity);
        return streamSenders
                .computeIfAbsent(identity, k -> new Bucket(20, 10))
                .take(clock.getAsLong());
    }

    public synchronized boolean allowSender(String identity) {
        return senders.computeIfAbsent(identity, k -> new Bucket(12, 0.5)).take(clock.getAsLong());
    }

    private static Map<String, Bucket> bounded(int cap) {
        return new LinkedHashMap<String, Bucket>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > cap;
            }
        };
    }

    private static final class Bucket {
        final double capacity;
        final double rate;
        double tokens;
        long last = -1;

        Bucket(double capacity, double perSecond) {
            this.capacity = capacity;
            this.rate = perSecond;
            tokens = capacity;
        }

        boolean take(long now) {
            if (last >= 0)
                tokens = Math.min(capacity, tokens + Math.max(0, now - last) * rate / 1000);
            last = Math.max(last, now);
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }
}
