package com.example.offgrid;

import static org.junit.Assert.*;

import com.example.offgrid.mesh.transport.LanFrames;

import org.junit.Test;

import java.io.*;
import java.util.Random;

/** Seeded bounded mutation smoke fuzzing of production parsers, not exhaustive coverage. */
public class PacketFuzzTest {
    @Test(timeout = 15000)
    public void malformedPacketsAndFramesStayBounded() throws Exception {
        Random random = new Random(0x0ff611d);
        byte[] seed = MeshMessage.create("11111111111111111111111111111111", "Fuzz seed").toBytes();
        for (int i = 0; i < 10000; i++) {
            byte[] data;
            if ((i & 1) == 0) {
                data = new byte[random.nextInt(4096)];
                random.nextBytes(data);
            } else {
                data = seed.clone();
                for (int j = 0; j < 1 + random.nextInt(8); j++)
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
            }
            try {
                MeshMessage parsed = MeshMessage.fromBytes(data);
                assertTrue(parsed.toBytes().length <= MeshMessage.MAX_CONTROL_SERIALIZED_BYTES);
                assertTrue(parsed.getTtl() >= 0 && parsed.getTtl() <= 15);
            } catch (IllegalArgumentException expected) {
            }
            try {
                LanFrames.readHeader(input(data));
            } catch (IOException expected) {
            }
            try {
                assertTrue(LanFrames.readPacket(input(data)).length <= LanFrames.MAX_PACKET_BYTES);
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void deeplyNestedJsonIsRejectedBeforeRecursiveParsing() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8000; i++) text.append('[');
        assertThrows(
                IllegalArgumentException.class, () -> MeshMessage.fromJsonString(text.toString()));
    }

    private static DataInputStream input(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }
}
