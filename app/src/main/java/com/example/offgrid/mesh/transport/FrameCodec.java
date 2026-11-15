package com.example.offgrid.mesh.transport;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Protocol-v3 GATT framing. Confined to the worker; no Android or database dependency. */
public final class FrameCodec {
    public static final int FIRST = 1;
    public static final int LAST = 2;
    public static final int MAX_PACKET_BYTES = 2048;
    public static final int MAX_FRAME_BYTES = 512;
    public static final int MAX_TRANSFERS = 16;
    public static final long TIMEOUT_MS = 12_000;
    private final Map<String, Assembly> incoming = new HashMap<>();

    public static List<byte[]> fragment(byte[] data, int mtu) {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Packet size out of range");
        }
        if (mtu < 23 || mtu > 517) throw new IllegalArgumentException("Invalid ATT MTU");
        // ATT value size is MTU-3, with an independent 512-byte characteristic limit.
        int chunk = Math.min(MAX_FRAME_BYTES, mtu - 3) - 1;
        List<byte[]> result = new ArrayList<>();
        for (int offset = 0; offset < data.length; offset += chunk) {
            int size = Math.min(chunk, data.length - offset);
            byte[] frame = new byte[size + 1];
            frame[0] =
                    (byte) ((offset == 0 ? FIRST : 0) | (offset + size == data.length ? LAST : 0));
            System.arraycopy(data, offset, frame, 1, size);
            result.add(frame);
        }
        return result;
    }

    /** Returns a complete packet or null. Bad input discards only this peer's assembly. */
    public byte[] accept(String peer, byte[] frame, long elapsedMs) {
        expire(elapsedMs);
        if (frame == null
                || frame.length < 2
                || frame.length > MAX_FRAME_BYTES
                || (frame[0] & ~3) != 0) {
            incoming.remove(peer);
            return null;
        }
        int flags = frame[0];
        Assembly assembly = incoming.get(peer);
        if ((flags & FIRST) != 0) {
            if (assembly == null && incoming.size() >= MAX_TRANSFERS) return null;
            assembly = new Assembly(elapsedMs);
            incoming.put(peer, assembly);
        }
        if (assembly == null) return null;
        if (assembly.bytes.size() + frame.length - 1 > MAX_PACKET_BYTES) {
            incoming.remove(peer);
            return null;
        }
        assembly.bytes.write(frame, 1, frame.length - 1);
        if ((flags & LAST) == 0) return null;
        incoming.remove(peer);
        return assembly.bytes.toByteArray();
    }

    public void expire(long elapsedMs) {
        Iterator<Assembly> iterator = incoming.values().iterator();
        while (iterator.hasNext()) {
            if (elapsedMs - iterator.next().startedAt >= TIMEOUT_MS) iterator.remove();
        }
    }

    public void disconnect(String peer) {
        incoming.remove(peer);
    }

    public void clear() {
        incoming.clear();
    }

    private static final class Assembly {
        final long startedAt;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Assembly(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
