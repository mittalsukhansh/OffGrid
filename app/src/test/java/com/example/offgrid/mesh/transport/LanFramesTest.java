package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

public class LanFramesTest {
    @Test
    public void realTcpCarriesMaximumBatchWithoutBluetoothFragmentation() throws Exception {
        byte[] payload = new byte[FrameCodec.MAX_PACKET_BYTES];
        Arrays.fill(payload, (byte) 0xa5);
        ExecutorService io = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Future<Integer> received =
                    io.submit(() -> {
                        try (Socket peer = server.accept()) {
                            peer.setSoTimeout(5000);
                            DataInputStream in = new DataInputStream(peer.getInputStream());
                            DataOutputStream out = new DataOutputStream(peer.getOutputStream());
                            int count = LanFrames.readHeader(in);
                            for (int i = 0; i < count; i++) {
                                assertArrayEquals(payload, LanFrames.readPacket(in));
                                out.writeByte(1);
                                out.flush();
                            }
                            return count;
                        }
                    });
            try (Socket client =
                    new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                DataInputStream in = new DataInputStream(client.getInputStream());
                LanFrames.writeHeader(out, 20);
                for (int i = 0; i < 20; i++) {
                    LanFrames.writePacket(out, payload);
                    assertEquals(1, in.readUnsignedByte());
                }
            }
            assertEquals(Integer.valueOf(20), received.get(5, TimeUnit.SECONDS));
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    public void rejectsUnboundedAllocationsEmptyFramesAndTruncatedPackets() throws Exception {
        for (int length : new int[] {-1, 0, 2049, Integer.MAX_VALUE}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeInt(length);
            assertThrows(IOException.class, () -> LanFrames.readPacket(input(bytes.toByteArray())));
        }
        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        new DataOutputStream(truncated).writeInt(2048);
        assertThrows(
                EOFException.class, () -> LanFrames.readPacket(input(truncated.toByteArray())));
        assertThrows(
                IOException.class,
                () ->
                        LanFrames.writePacket(
                                new DataOutputStream(new ByteArrayOutputStream()), new byte[2049]));
    }

    @Test
    public void rejectsWrongProtocolAndOversizedBatchBeforePackets() throws Exception {
        for (int[] header :
                new int[][] {
                    {0, 1}, {LanFrames.MAGIC, 0}, {LanFrames.MAGIC, 21}, {LanFrames.MAGIC, -1}
                }) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(header[0]);
            out.writeInt(header[1]);
            assertThrows(IOException.class, () -> LanFrames.readHeader(input(bytes.toByteArray())));
        }
    }

    private DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
