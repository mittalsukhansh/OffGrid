package com.example.offgrid;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Publicly specified, length-delimited signing format. Mutable relay TTL is excluded. */
public final class PacketSecurity {
    public static final long MAX_AGE_MS = 10 * 60_000L;
    public static final long CLOCK_SKEW_MS = 2 * 60_000L;
    public static final long REPLAY_RETENTION_MS = 30 * 60_000L;
    public static final int MAX_REPLAY_ENTRIES = 10_000;

    public static boolean fresh(MeshMessage packet, long now) {
        return packet.getTimestamp() >= now - MAX_AGE_MS
                && packet.getTimestamp() <= now + CLOCK_SKEW_MS;
    }

    public static boolean namespaced(MeshMessage packet) {
        return packet.getProtocolVersion() == MeshMessage.PROTOCOL_VERSION
                && packet.getSenderId().matches("[0-9a-f]{32}")
                && packet.getMessageId().matches("[0-9a-f]{64}")
                && packet.getMessageId().startsWith(packet.getSenderId());
    }

    public static byte[] signingBytes(MeshMessage packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeUTF("offgrid-signed-packet-v1");
            data.writeInt(packet.getProtocolVersion());
            data.writeInt(packet.getType());
            data.writeUTF(packet.getMessageId());
            data.writeUTF(packet.getSenderId());
            data.writeUTF(packet.getTargetId() == null ? "" : packet.getTargetId());
            data.writeUTF(packet.getSenderKey() == null ? "" : packet.getSenderKey());
            data.writeLong(packet.getTimestamp());
            data.writeUTF(packet.getText());
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
