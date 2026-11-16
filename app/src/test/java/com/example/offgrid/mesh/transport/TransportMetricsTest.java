package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class TransportMetricsTest {
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
