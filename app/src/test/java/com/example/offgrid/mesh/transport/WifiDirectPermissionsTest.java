package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class WifiDirectPermissionsTest {
    @Test
    public void legacyDiscoveryNeedsLocationPermissionsWithoutBluetooth() {
        for (int sdk : new int[] {26, 30, 31, 32}) {
            List<String> required = Arrays.asList(WifiDirectTransport.permissions(sdk));
            assertEquals(2, required.size());
            assertTrue(required.contains("android.permission.ACCESS_FINE_LOCATION"));
            assertTrue(required.contains("android.permission.ACCESS_COARSE_LOCATION"));
            for (String permission : required) assertFalse(permission.contains("BLUETOOTH"));
        }
    }

    @Test
    public void modernDiscoveryUsesNearbyWifiAndAddsLocalNetworkAt37() {
        assertEquals(
                Collections.singletonList("android.permission.NEARBY_WIFI_DEVICES"),
                Arrays.asList(WifiDirectTransport.permissions(33)));
        assertEquals(
                Collections.singletonList("android.permission.NEARBY_WIFI_DEVICES"),
                Arrays.asList(WifiDirectTransport.permissions(36)));
        List<String> required = Arrays.asList(WifiDirectTransport.permissions(37));
        assertEquals(2, required.size());
        assertTrue(required.contains("android.permission.NEARBY_WIFI_DEVICES"));
        assertTrue(required.contains("android.permission.ACCESS_LOCAL_NETWORK"));
    }
}
