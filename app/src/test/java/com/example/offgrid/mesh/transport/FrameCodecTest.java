package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

public class FrameCodecTest {
    @Test
    public void roundTripEveryBoundaryAtDefaultPreferredAndMaximumMtu() {
        for (int mtu : new int[] {23, 247, 517}) {
            for (int length : new int[] {1, 19, 20, 243, 244, 511, 512, 1024, 2048}) {
                byte[] packet = new byte[length];
                new Random(length).nextBytes(packet);
                FrameCodec codec = new FrameCodec();
                List<byte[]> frames = FrameCodec.fragment(packet, mtu);
                for (int i = 0; i < frames.size(); i++) {
                    byte[] frame = frames.get(i);
                    assertTrue(frame.length <= Math.min(mtu - 3, 512));
                    assertEquals(i == 0, (frame[0] & FrameCodec.FIRST) != 0);
                    assertEquals(i == frames.size() - 1, (frame[0] & FrameCodec.LAST) != 0);
                    byte[] result = codec.accept("peer", frame, i);
                    if (i == frames.size() - 1) assertArrayEquals(packet, result);
                    else assertNull(result);
                }
            }
        }
    }

    @Test
    public void utf8IsReassembledBeforeTextDecoding() {
        String text = "Hello 🌍 नमस्ते café 👩‍💻";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        FrameCodec codec = new FrameCodec();
        byte[] result = null;
        for (byte[] frame : FrameCodec.fragment(bytes, 23)) result = codec.accept("peer", frame, 0);
        assertEquals(text, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    public void differentPeersCanInterleaveButCannotShareAnAssembly() {
        FrameCodec codec = new FrameCodec();
        assertNull(codec.accept("A", new byte[] {1, 10}, 0));
        assertNull(codec.accept("B", new byte[] {1, 20}, 0));
        assertArrayEquals(new byte[] {10, 11}, codec.accept("A", new byte[] {2, 11}, 1));
        assertArrayEquals(new byte[] {20, 21}, codec.accept("B", new byte[] {2, 21}, 1));
    }

    @Test
    public void newFirstFrameReplacesAnIncompletePacketAndOrphanContinuationIsIgnored() {
        FrameCodec codec = new FrameCodec();
        assertNull(codec.accept("peer", new byte[] {2, 9}, 0));
        codec.accept("peer", new byte[] {1, 10}, 1);
        codec.accept("peer", new byte[] {1, 20}, 2);
        assertArrayEquals(new byte[] {20, 21}, codec.accept("peer", new byte[] {2, 21}, 3));
        assertNull(codec.accept("peer", new byte[] {2, 21}, 4));
    }

    @Test
    public void malformedFramesResetOnlyTheOffendingPeer() {
        for (byte[] invalid : new byte[][] {null, {}, {1}, {4, 9}, new byte[513]}) {
            FrameCodec codec = new FrameCodec();
            codec.accept("A", new byte[] {1, 10}, 0);
            codec.accept("B", new byte[] {1, 20}, 0);
            assertNull(codec.accept("A", invalid, 1));
            assertNull(codec.accept("A", new byte[] {2, 11}, 2));
            assertArrayEquals(new byte[] {20, 21}, codec.accept("B", new byte[] {2, 21}, 2));
        }
    }

    @Test
    public void oversizePacketCannotGrowPastTheProtocolLimit() {
        FrameCodec codec = new FrameCodec();
        byte[] frame = new byte[512];
        frame[0] = 1;
        codec.accept("peer", frame, 0);
        frame[0] = 0;
        for (int i = 0; i < 4; i++) assertNull(codec.accept("peer", frame, 1));
        assertNull(codec.accept("peer", new byte[] {2, 7}, 2));
        assertArrayEquals(new byte[] {8}, codec.accept("peer", new byte[] {3, 8}, 3));
    }

    @Test
    public void deadlineIsFromFirstFrameAndIsNotExtendedBySlowContinuations() {
        FrameCodec codec = new FrameCodec();
        codec.accept("peer", new byte[] {1, 10}, 0);
        codec.accept("peer", new byte[] {0, 11}, 11_000);
        assertNull(codec.accept("peer", new byte[] {2, 12}, 12_000));
    }

    @Test
    public void capacityRecoversAfterExpiryAndDisconnect() {
        FrameCodec codec = new FrameCodec();
        for (int i = 0; i < 16; i++) codec.accept("peer" + i, new byte[] {1, 1}, 0);
        assertNull(codec.accept("extra", new byte[] {3, 2}, 1));
        codec.disconnect("peer0");
        assertArrayEquals(new byte[] {2}, codec.accept("extra", new byte[] {3, 2}, 2));
        codec.expire(12_000);
        assertArrayEquals(new byte[] {3}, codec.accept("new", new byte[] {3, 3}, 12_000));
        codec.clear();
        assertNull(codec.accept("peer1", new byte[] {2, 1}, 12_001));
    }

    @Test
    public void invalidOutgoingSizesAndMtuAreRejected() {
        for (byte[] data : new byte[][] {null, {}, new byte[2049]}) {
            try {
                FrameCodec.fragment(data, 23);
                fail("Invalid size accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        for (int mtu : new int[] {0, 22, 518}) {
            try {
                FrameCodec.fragment(new byte[] {1}, mtu);
                fail("Invalid MTU accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
    }
}
