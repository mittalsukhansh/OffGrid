package com.example.offgrid;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe, bounded, time-expiring set of message ids that have already been processed.
 *
 * <p>Used to stop broadcast loops: every incoming message is passed through
 * {@link #markSeen(String)}, and only messages for which it returns {@code true} are
 * delivered and relayed.
 *
 * <p>Bounds:
 * <ul>
 *   <li><b>Size</b> – at most {@code maxEntries} ids are kept; the oldest are evicted first.</li>
 *   <li><b>Age</b> – ids older than {@code expiryMs} are forgotten. The expiry only has to be
 *       longer than the time a message can realistically circulate (TTL is capped, so minutes
 *       are plenty).</li>
 * </ul>
 *
 * <p>Timing uses {@link System#nanoTime()} (monotonic), so wall-clock changes or differing
 * phone clocks cannot affect expiry. The class has no Android dependencies and can be
 * unit-tested on the JVM.
 */
public final class MessageCache {

    public static final int DEFAULT_MAX_ENTRIES = 5_000;
    public static final long DEFAULT_EXPIRY_MS = TimeUnit.MINUTES.toMillis(15);

    private final int maxEntries;
    private final long expiryNanos;

    /**
     * Insertion-ordered: the eldest entry is always the oldest timestamp, because an entry is
     * only ever (re)inserted at "now". This lets purge/evict stop at the first live entry.
     */
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    public MessageCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_EXPIRY_MS);
    }

    public MessageCache(int maxEntries, long expiryMs) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        if (expiryMs <= 0) throw new IllegalArgumentException("expiryMs must be > 0");
        this.maxEntries = maxEntries;
        this.expiryNanos = TimeUnit.MILLISECONDS.toNanos(expiryMs);
    }

    /**
     * Atomically records {@code messageId} as seen.
     *
     * @return {@code true} if the id was NOT seen before (caller should process the message),
     *         {@code false} if it is a duplicate or the id is null/empty (caller must drop it)
     */
    public synchronized boolean markSeen(@Nullable String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return false;
        }
        long now = System.nanoTime();
        Long previous = seen.get(messageId);
        if (previous != null) {
            if (now - previous < expiryNanos) {
                return false; // duplicate
            }
            seen.remove(messageId); // expired: re-insert below so ordering stays chronological
        }
        seen.put(messageId, now);
        evictOverflow();
        return true;
    }

    /** @return true if the id is currently remembered (and not expired). Does not modify state. */
    public synchronized boolean hasSeen(@Nullable String messageId) {
        if (messageId == null) return false;
        Long ts = seen.get(messageId);
        return ts != null && System.nanoTime() - ts < expiryNanos;
    }

    /** Removes all expired entries. Call periodically from a maintenance task. */
    public synchronized int purgeExpired() {
        long now = System.nanoTime();
        int removed = 0;
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() < expiryNanos) {
                break; // everything after this is newer
            }
            it.remove();
            removed++;
        }
        return removed;
    }

    public synchronized int size() {
        return seen.size();
    }

    public synchronized void clear() {
        seen.clear();
    }

    private void evictOverflow() {
        if (seen.size() <= maxEntries) return;
        Iterator<String> it = seen.keySet().iterator();
        while (seen.size() > maxEntries && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}