package com.example.offgrid.mesh.protocol;

import static org.junit.Assert.*;

import com.example.offgrid.MeshMessage;

import org.json.JSONObject;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class InventoryExchangeTest {
    private static final String A = "11111111111111111111111111111111",
            B = "22222222222222222222222222222222";
    private static final String KEY = new String(new char[87]).replace('\0', 'A');

    @Test
    public void unansweredRefreshCannotRepeatTheInitialForwardingPause() {
        AtomicLong time = new AtomicLong();
        InventoryExchange exchange =
                new InventoryExchange(
                        A,
                        time::get,
                        new InventoryExchange.Link() {
                            public MeshMessage packet(String target, String body) {
                                return MeshMessage.createInventory(A, target, body, KEY);
                            }

                            public void send(String peer, MeshMessage packet) {}

                            public List<String> ids(int cursor, int count) {
                                return Collections.emptyList();
                            }

                            public void authenticated(
                                    String peer, String identity, List<String> ids) {
                                fail("No reply was received");
                            }
                        });
        exchange.seen("ble:B");
        time.set(4999);
        assertFalse(exchange.ready("ble:B"));
        time.set(5000);
        assertTrue(exchange.ready("ble:B"));
        time.set(15000);
        exchange.seen("ble:B");
        assertTrue(exchange.ready("ble:B"));
        exchange.clear();
        exchange.seen("ble:B");
        assertFalse(
                "A new discovery lifecycle starts a bounded initial wait", exchange.ready("ble:B"));
    }

    @Test
    public void nonceResponseBindsOnlyItsEndpointAndCannotBeReplayed() throws Exception {
        AtomicLong time = new AtomicLong();
        List<MeshMessage> sent = new ArrayList<>();
        List<String> identified = new ArrayList<>();
        InventoryExchange exchange =
                new InventoryExchange(
                        A,
                        time::get,
                        new InventoryExchange.Link() {
                            public MeshMessage packet(String target, String body) {
                                return MeshMessage.createInventory(A, target, body, KEY);
                            }

                            public void send(String peer, MeshMessage packet) {
                                sent.add(packet);
                            }

                            public List<String> ids(int cursor, int count) {
                                return Collections.emptyList();
                            }

                            public void authenticated(
                                    String peer, String identity, List<String> ids) {
                                identified.add(peer + identity);
                            }
                        });
        exchange.seen("ble:B");
        assertFalse(exchange.ready("ble:B"));
        String nonce = new JSONObject(sent.get(0).getText()).getString("nonce");
        JSONObject body =
                new JSONObject()
                        .put("kind", "response")
                        .put("nonce", nonce)
                        .put("ids", new org.json.JSONArray());
        MeshMessage response = MeshMessage.createInventory(B, A, body.toString(), KEY);
        exchange.receive("wifi:B", response);
        assertTrue(identified.isEmpty());
        exchange.receive("ble:B", response);
        assertEquals(Collections.singletonList("ble:B" + B), identified);
        exchange.receive("ble:B", response);
        assertEquals(1, identified.size());
        time.set(16000);
        exchange.seen("ble:B");
        assertTrue("Authenticated refresh must not pause forwarding", exchange.ready("ble:B"));
        exchange.receive("ble:B", response);
        assertEquals(1, identified.size());
        time.set(22000);
        assertTrue(exchange.ready("ble:B"));
    }
}
