package com.example.offgrid;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Immutable data model for a single mesh chat message.
 *
 * <h3>TTL semantics</h3>
 * {@code ttl} is the number of hops the message may still travel <em>after</em> the receiving
 * node. A node that receives a message delivers it locally, then relays
 * {@link #withDecrementedTtl()} only if {@link #isRelayable()} is true (ttl &gt; 0 after the
 * decrement). A message created with ttl = 1 therefore reaches direct neighbours only.
 *
 * <h3>Wire format</h3>
 * Compact UTF-8 JSON, e.g.
 * <pre>{"v":1,"id":"...","sid":"...","txt":"hello","ttl":7,"ts":1760000000000}</pre>
 * The serialized form never exceeds {@link #MAX_SERIALIZED_BYTES} bytes, which lets the
 * transport reject oversized payloads without parsing them.
 */
public final class MeshMessage {

    public static final int PROTOCOL_VERSION = 1;

    /** Hops a freshly composed message may travel. */
    public static final int DEFAULT_TTL = 7;
    /** Hard upper bound accepted from the network (guards against hostile peers). */
    public static final int MAX_TTL = 15;

    public static final int MAX_TEXT_LENGTH = 280;
    public static final int MAX_ID_LENGTH = 64;
    /**
     * Upper bound for a serialized message. Worst case is 280 chars of control characters
     * (6 bytes each when JSON-escaped) plus two 64-char ids and field overhead, which stays
     * safely below this limit.
     */
    public static final int MAX_SERIALIZED_BYTES = 2048;

    private static final String K_VERSION = "v";
    private static final String K_ID = "id";
    private static final String K_SENDER = "sid";
    private static final String K_TEXT = "txt";
    private static final String K_TTL = "ttl";
    private static final String K_TIMESTAMP = "ts";

    private final String messageId;
    private final String senderId;
    private final String text;
    private final int ttl;
    private final long timestamp;

    /**
     * @throws IllegalArgumentException if any field is missing or out of range
     */
    public MeshMessage(@NonNull String messageId,
                       @NonNull String senderId,
                       @NonNull String text,
                       int ttl,
                       long timestamp) {
        this.messageId = requireId(messageId, "messageId");
        this.senderId = requireId(senderId, "senderId");
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        if (ttl < 0 || ttl > MAX_TTL) {
            throw new IllegalArgumentException("ttl must be within 0.." + MAX_TTL);
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive");
        }
        this.text = text;
        this.ttl = ttl;
        this.timestamp = timestamp;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** Creates a brand-new message with a random id and the current time. */
    @NonNull
    public static MeshMessage create(@NonNull String senderId, @NonNull String text, int ttl) {
        return new MeshMessage(UUID.randomUUID().toString(), senderId, text, ttl,
                System.currentTimeMillis());
    }

    @NonNull
    public static MeshMessage create(@NonNull String senderId, @NonNull String text) {
        return create(senderId, text, DEFAULT_TTL);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    @NonNull
    public String getMessageId() {
        return messageId;
    }

    @NonNull
    public String getSenderId() {
        return senderId;
    }

    @NonNull
    public String getText() {
        return text;
    }

    public int getTtl() {
        return ttl;
    }

    /** Wall-clock creation time (epoch millis) as reported by the sender's device. */
    public long getTimestamp() {
        return timestamp;
    }

    // ------------------------------------------------------------------
    // Relay helpers
    // ------------------------------------------------------------------

    /** @return a copy with ttl reduced by one (never below zero). */
    @NonNull
    public MeshMessage withDecrementedTtl() {
        return new MeshMessage(messageId, senderId, text, Math.max(0, ttl - 1), timestamp);
    }

    /** @return true if this message should still be forwarded to other peers. */
    public boolean isRelayable() {
        return ttl > 0;
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    @NonNull
    public JSONObject toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put(K_VERSION, PROTOCOL_VERSION);
            json.put(K_ID, messageId);
            json.put(K_SENDER, senderId);
            json.put(K_TEXT, text);
            json.put(K_TTL, ttl);
            json.put(K_TIMESTAMP, timestamp);
            return json;
        } catch (JSONException e) {
            // Only possible for non-finite numbers / null keys, neither of which we use.
            throw new IllegalStateException("Failed to serialize message", e);
        }
    }

    /**
     * @return UTF-8 JSON bytes ready to be sent over BLE
     * @throws IllegalStateException if the result exceeds {@link #MAX_SERIALIZED_BYTES}
     */
    @NonNull
    public byte[] toBytes() {
        byte[] bytes = toJson().toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SERIALIZED_BYTES) {
            throw new IllegalStateException("Serialized message too large: " + bytes.length);
        }
        return bytes;
    }

    /**
     * Parses and validates a message received from the network.
     *
     * @throws IllegalArgumentException if the data is not a valid message
     */
    @NonNull
    public static MeshMessage fromBytes(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        if (data.length > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("payload too large: " + data.length);
        }
        return fromJsonString(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * @throws IllegalArgumentException if the string is not a valid message
     */
    @NonNull
    public static MeshMessage fromJsonString(@NonNull String json) {
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            throw new IllegalArgumentException("malformed JSON: " + e.getMessage(), e);
        }
    }

    /**
     * @throws IllegalArgumentException if the object is not a valid message
     */
    @NonNull
    public static MeshMessage fromJson(@NonNull JSONObject json) {
        try {
            int version = json.optInt(K_VERSION, PROTOCOL_VERSION);
            if (version != PROTOCOL_VERSION) {
                throw new IllegalArgumentException("unsupported protocol version " + version);
            }
            return new MeshMessage(
                    json.getString(K_ID),
                    json.getString(K_SENDER),
                    json.getString(K_TEXT),
                    json.getInt(K_TTL),
                    json.getLong(K_TIMESTAMP));
        } catch (JSONException e) {
            throw new IllegalArgumentException("missing or invalid field: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Object
    // ------------------------------------------------------------------

    private static String requireId(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_ID_LENGTH + " characters");
        }
        return value;
    }

    /** Two messages are the same message if they share a messageId (ttl differs per hop). */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeshMessage)) return false;
        return messageId.equals(((MeshMessage) o).messageId);
    }

    @Override
    public int hashCode() {
        return messageId.hashCode();
    }

    /** Message text is intentionally omitted so it never leaks into logs. */
    @NonNull
    @Override
    public String toString() {
        return "MeshMessage{id=" + messageId
                + ", sender=" + senderId
                + ", ttl=" + ttl
                + ", textLength=" + text.length()
                + ", ts=" + timestamp + '}';
    }
}