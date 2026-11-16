package com.example.offgrid.mesh.protocol;

import com.example.offgrid.MeshMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, per-endpoint nonce handshake. Inventory never marks a message Delivered. */
public final class InventoryExchange {
    public interface Link {
        MeshMessage packet(String target, String body) throws Exception;

        void send(String peer, MeshMessage packet);

        List<String> ids(int cursor, int count);

        void authenticated(String peer, String identity, List<String> ids);
    }

    private static final int PAGE = 12, CAP = 200;
    private static final long INTERVAL = 15000, DEADLINE = 5000;
    private final String localId;
    private final LongSupplier clock;
    private final Link link;
    private final Map<String, State> states = new LinkedHashMap<>();
    private int cursor;

    private static final class State {
        long sent, replied;
        String challenge;
    }

    public InventoryExchange(String localId, LongSupplier clock, Link link) {
        this.localId = localId;
        this.clock = clock;
        this.link = link;
    }

    public void clear() {
        states.clear();
    }

    public void seen(String peer) {
        long now = clock.getAsLong();
        State state = states.get(peer);
        if (state == null) {
            if (states.size() >= CAP) states.remove(states.keySet().iterator().next());
            state = new State();
            state.sent = -INTERVAL;
            state.replied = -INTERVAL;
            states.put(peer, state);
        }
        if (now - state.sent < INTERVAL) return;
        state.sent = now;
        state.challenge = UUID.randomUUID().toString().replace("-", "");
        try {
            link.send(peer, link.packet(localId, body("request", state.challenge)));
        } catch (Exception ignored) {
            /* Normal relay remains available after a bounded delay. */
        }
    }

    public boolean ready(String peer) {
        State state = states.get(peer);
        return state == null
                || state.challenge == null
                || clock.getAsLong() - state.sent >= DEADLINE;
    }

    private String body(String kind, String nonce) throws Exception {
        JSONObject body = new JSONObject();
        body.put("kind", kind);
        body.put("nonce", nonce);
        body.put("ids", new JSONArray(link.ids(cursor, PAGE)));
        cursor = (cursor + PAGE) % 200;
        return body.toString();
    }

    /** Caller verifies the signature and ingress flood budget before this method. */
    public void receive(String peer, MeshMessage packet) {
        if (!packet.isInventory() || packet.getSenderId().equals(localId)) return;
        State state = states.get(peer);
        if (state == null) {
            seen(peer);
            state = states.get(peer);
        }
        try {
            JSONObject body = com.example.offgrid.JsonBounds.object(packet.getText(), 1152);
            String kind = body.getString("kind"), nonce = body.getString("nonce");
            if (!nonce.matches("[0-9a-f]{32}")) return;
            if ("request".equals(kind)) {
                if (!packet.getTargetId().equals(packet.getSenderId())
                        || clock.getAsLong() - state.replied < 1000) return;
                state.replied = clock.getAsLong();
                link.send(peer, link.packet(packet.getSenderId(), body("response", nonce)));
            } else if ("response".equals(kind)
                    && localId.equals(packet.getTargetId())
                    && nonce.equals(state.challenge)
                    && clock.getAsLong() - state.sent < DEADLINE) {
                JSONArray array = body.getJSONArray("ids");
                if (array.length() > PAGE) return;
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    String id = array.getString(i);
                    if (!id.matches("[0-9a-f]{64}")) return;
                    ids.add(id);
                }
                state.challenge = null;
                link.authenticated(peer, packet.getSenderId(), ids);
            }
        } catch (Exception ignored) {
            /* Malformed inventory never changes peer state. */
        }
    }
}
