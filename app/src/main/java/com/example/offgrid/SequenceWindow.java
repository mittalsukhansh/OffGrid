package com.example.offgrid;

import java.util.BitSet;

/** Durable, clock-independent replay window. No eviction or timestamp reset can erase evidence. */
public final class SequenceWindow {
    public static final int WIDTH = 4096;
    private long highest;
    private BitSet seen;

    public SequenceWindow(long highest, byte[] encoded) {
        if (highest < 0 || encoded.length > WIDTH / 8)
            throw new IllegalArgumentException("Invalid replay state");
        this.highest = highest;
        seen = BitSet.valueOf(encoded);
    }

    public boolean accept(long sequence) {
        if (sequence < 1) return false;
        if (sequence > highest) {
            long shift = sequence - highest;
            BitSet next = new BitSet(WIDTH);
            if (shift < WIDTH) {
                for (int i = seen.nextSetBit(0);
                        i >= 0 && i + shift < WIDTH;
                        i = seen.nextSetBit(i + 1)) next.set((int) (i + shift));
            }
            highest = sequence;
            seen = next;
        }
        long offset = highest - sequence;
        if (offset >= WIDTH || seen.get((int) offset)) return false;
        seen.set((int) offset);
        return true;
    }

    public long highest() {
        return highest;
    }

    public byte[] encode() {
        return seen.toByteArray();
    }
}
