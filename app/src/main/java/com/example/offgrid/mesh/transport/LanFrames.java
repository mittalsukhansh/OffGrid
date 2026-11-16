package com.example.offgrid.mesh.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Length-prefixed LAN envelope. This is framing, never a delivery receipt or trust handshake. */
public final class LanFrames {
    public static final int MAGIC = 0x4f474c31; // OGL1
    public static final int MAX_BATCH = 20;
    public static final int MAX_PACKET_BYTES = 32768;

    private LanFrames() {}

    public static void writeHeader(DataOutputStream out, int count) throws IOException {
        if (count < 1 || count > MAX_BATCH) throw new IOException("Invalid LAN batch size");
        out.writeInt(MAGIC);
        out.writeInt(count);
        out.flush();
    }

    public static int readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Incompatible LAN framing");
        int count = in.readInt();
        if (count < 1 || count > MAX_BATCH) throw new IOException("Invalid LAN batch size");
        return count;
    }

    public static byte[] readPacket(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_PACKET_BYTES)
            throw new IOException("Invalid LAN packet size");
        byte[] packet = new byte[length];
        in.readFully(packet);
        return packet;
    }

    public static void writePacket(DataOutputStream out, byte[] packet) throws IOException {
        if (packet == null || packet.length < 1 || packet.length > MAX_PACKET_BYTES)
            throw new IOException("Invalid LAN packet size");
        out.writeInt(packet.length);
        out.write(packet);
        out.flush();
    }
}
