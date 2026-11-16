package com.example.offgrid.mesh.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Unauthenticated link discovery, never a cryptographic contact or delivery receipt. */
public final class DirectHello {
    public static final int MAGIC = 0x4f475031; // OGP1
    public static final int VERSION = 1;
    public static final int PORT = 39997;

    private DirectHello() {}

    public static void write(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.flush();
    }

    public static void read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != VERSION)
            throw new IOException("Unsupported Direct hello");
    }
}
