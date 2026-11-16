package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class CombinedTransportTest {
    @Test
    public void wifiKeepsMeshActiveAfterBluetoothTurnsOffAndAddressesRouteIndependently() {
        Fake ble = new Fake(), wifi = new Fake();
        CombinedTransport combined = new CombinedTransport(ble, wifi);
        List<Boolean> states = new ArrayList<>();
        List<String> peers = new ArrayList<>();
        combined.start(
                new MeshTransport.Listener() {
                    @Override
                    public void onPacket(String peer, byte[] bytes) {
                        peers.add(peer);
                    }

                    @Override
                    public void onPeerSeen(String peer, int rssi) {
                        peers.add(peer);
                    }

                    @Override
                    public void onRadioState(boolean active) {
                        states.add(active);
                    }

                    @Override
                    public void onError(String error) {
                        fail(error);
                    }
                });
        ble.listener.onRadioState(true);
        wifi.listener.onRadioState(true);
        ble.listener.onRadioState(false);
        assertEquals(Arrays.asList(true), states);
        ble.listener.onPeerSeen("same-address", -50);
        wifi.listener.onPacket("same-address", new byte[] {1});
        assertEquals(Arrays.asList("ble:same-address", "wifi:same-address"), peers);
        MeshTransport.SendListener callbacks =
                new MeshTransport.SendListener() {
                    @Override
                    public boolean shouldSend(String id) {
                        return true;
                    }

                    @Override
                    public void onForwarded(String id) {}

                    @Override
                    public void onComplete(String failure, boolean incompatible) {}
                };
        combined.send("wifi:1.2.3.4:1234", Collections.emptyList(), callbacks);
        combined.send("ble:AA:BB:CC:DD:EE:FF", Collections.emptyList(), callbacks);
        assertEquals("1.2.3.4:1234", wifi.sent);
        assertEquals("AA:BB:CC:DD:EE:FF", ble.sent);
        wifi.listener.onRadioState(false);
        assertEquals(Arrays.asList(true, false), states);
        combined.cancelSends();
        assertTrue(ble.cancelled && wifi.cancelled);
    }

    private static class Fake implements MeshTransport {
        Listener listener;
        String sent;
        boolean cancelled;

        @Override
        public void start(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {}

        @Override
        public void maintain() {}

        @Override
        public void send(String peer, List<Packet> packets, SendListener listener) {
            sent = peer;
        }

        @Override
        public void cancelSends() {
            cancelled = true;
        }
    }
}
