package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

public class CocAdvertisementTest {
    @Test
    public void dynamicPsmRoundTripsAndMalformedAdvertisementsAreRejected() {
        for (int psm = 128; psm <= 255; psm++)
            assertEquals(psm, CocAdvertisement.decode(CocAdvertisement.encode(psm)));
        assertEquals(-1, CocAdvertisement.decode(null));
        assertEquals(-1, CocAdvertisement.decode(new byte[] {0x4f, 1, 0}));
        assertEquals(-1, CocAdvertisement.decode(new byte[] {0x4f, 2, 0, (byte) 128}));
        assertEquals(-1, CocAdvertisement.decode(new byte[] {0x4f, 1, 0, 127}));
        assertEquals(-1, CocAdvertisement.decode(new byte[] {0x4f, 1, 1, 0}));
        assertThrows(IllegalArgumentException.class, () -> CocAdvertisement.encode(127));
        assertThrows(IllegalArgumentException.class, () -> CocAdvertisement.encode(256));
    }
}
