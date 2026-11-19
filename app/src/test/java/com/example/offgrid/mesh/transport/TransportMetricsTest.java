package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class TransportMetricsTest {
    @Test
    public void repeatedResetDoesNotReviveCompletedSessionsOrBreakCancellation() {
        AtomicLong clock = new AtomicLong();
        TransportMetrics metrics = new TransportMetrics(clock::get);
        MeshTransport.SendListener sent =
                metrics.sending(
                        TransferProfile.WIFI,
                        Collections.emptyList(),
                        new MeshTransport.SendListener() {
                            public boolean shouldSend(String id) {
                                return true;
                            }

                            public void onForwarded(String id) {}

                            public void onComplete(String failure, boolean incompatible) {}
                        });
        clock.set(100);
        metrics.reset();
        clock.set(200);
        metrics.reset();
        clock.set(250);
        sent.onComplete("failure", false);
        String wifi = metrics.snapshot().split("\n\nWIFI:")[1].split("\n\n")[0];
        assertTrue(wifi.contains("Sessions 1/1 · failures 1"));
        assertTrue(wifi.contains("last session 50 ms"));
        metrics.reset();
        assertTrue(
                metrics.snapshot().split("\n\nWIFI:")[1].split("\n\n")[0].contains("Sessions 0/0"));
        int[] closed = {0};
        sent.onAttempt("late", () -> closed[0]++);
        assertEquals(1, closed[0]);
        assertTrue(sent.isCancelled());
    }

    @Test
    public void resetDuringActiveTransferKeepsLaterAcknowledgedBytesAndRebasesDuration() {
        AtomicLong time = new AtomicLong(1000);
        TransportMetrics metrics = new TransportMetrics(time::get);
        MeshTransport.SendListener sent =
                metrics.sending(
                        TransferProfile.COC,
                        Arrays.asList(
                                new MeshTransport.Packet("before", new byte[1200]),
                                new MeshTransport.Packet("after", new byte[600])),
                        new MeshTransport.SendListener() {
                            public boolean shouldSend(String id) {
                                return true;
                            }

                            public void onForwarded(String id) {}

                            public void onComplete(String failure, boolean incompatible) {}
                        });
        sent.onForwarded("before");
        time.set(3000);
        metrics.reset();
        sent.onForwarded("before"); // duplicate completion must not be counted after reset
        sent.onForwarded("after");
        time.set(3500);
        sent.onComplete(null, false);
        String coc = metrics.snapshot().split("\n\nCOC:")[1].split("\n\n")[0];
        assertTrue(coc.contains("TX 600 B / 1 packets"));
        assertTrue(coc.contains("Sessions 1/1 · failures 0"));
        assertTrue(coc.contains("last session 500 ms"));
    }

    @Test
    public void cancellationHandleSurvivesMetricsAndIntentionalYieldIsNotAFailure() {
        TransportMetrics metrics = new TransportMetrics(() -> 0L);
        int[] closed = {0};
        boolean[] cancelled = {false};
        MeshTransport.SendListener measured =
                metrics.sending(
                        TransferProfile.COC,
                        Collections.singletonList(new MeshTransport.Packet("packet", new byte[10])),
                        new MeshTransport.SendListener() {
                            public void onAttempt(String id, Runnable close) {
                                cancelled[0] = true;
                                close.run();
                            }

                            public boolean isCancelled() {
                                return cancelled[0];
                            }

                            public boolean shouldSend(String id) {
                                return !cancelled[0];
                            }

                            public void onForwarded(String id) {
                                fail("No packet was acknowledged");
                            }

                            public void onComplete(String failure, boolean incompatible) {}
                        });
        measured.onAttempt("packet", () -> closed[0]++);
        assertEquals(1, closed[0]);
        assertTrue(measured.isCancelled());
        measured.onComplete("socket closed", false);
        String coc = metrics.snapshot().split("\n\nCOC:")[1].split("\n\n")[0];
        assertTrue(coc.contains("Sessions 1/1 · failures 0"));
    }

    @Test
    public void countsWireBytesOnceAndRecordsFailureAndElapsedTime() {
        AtomicLong time = new AtomicLong(1000);
        TransportMetrics metrics = new TransportMetrics(time::get);
        MeshTransport.SendListener delegate =
                new MeshTransport.SendListener() {
                    @Override
                    public boolean shouldSend(String id) {
                        return true;
                    }

                    @Override
                    public void onForwarded(String id) {}

                    @Override
                    public void onComplete(String error, boolean incompatible) {}
                };
        MeshTransport.SendListener sent =
                metrics.sending(
                        TransferProfile.COC,
                        Arrays.asList(new MeshTransport.Packet("one", new byte[1200])),
                        delegate);
        sent.onForwarded("one");
        sent.onForwarded("one");
        metrics.received(TransferProfile.COC, 600);
        time.addAndGet(2000);
        sent.onComplete("failed", false);
        sent.onComplete(null, false);
        String text = metrics.snapshot();
        assertTrue(text.contains("COC: TX 1200 B / 1 packets · RX 600 B / 1 packets"));
        assertTrue(text.contains("last session 2000 ms"));
        assertTrue(text.contains("Sessions 1/1 · failures 1"));
        metrics.reset();
        assertTrue(metrics.snapshot().contains("COC: TX 0 B / 0 packets"));
    }

    @Test
    public void profilesKeepResumeAlignmentAndSeparatePacketLimits() {
        assertEquals(640, TransferProfile.GATT.chunkBytes);
        assertEquals(2560, TransferProfile.COC.chunkBytes);
        assertEquals(7680, TransferProfile.WIFI.chunkBytes);
        for (TransferProfile p : TransferProfile.values()) {
            assertEquals(0, p.chunkBytes % 640);
            assertTrue(p.windowBlocks >= p.chunkBytes / 640);
        }
        assertEquals(TransferProfile.WIFI, TransferProfile.ingress("wifi:192.168.1.2"));
        assertEquals(TransferProfile.COC, TransferProfile.ingress("ble:coc:AA:BB"));
    }
}
