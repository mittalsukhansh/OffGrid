package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable mesh packet.
 *
 * <h3>Types</h3>
 *
 * <ul>
 *   <li>{@link #TYPE_PUBLIC} - plaintext group chat. {@code targetId} is null (or {@link
 *       #BROADCAST}); everyone displays it.
 *   <li>{@link #TYPE_PRIVATE} - addressed to one device. On the wire {@code text} is an AES-GCM
 *       ciphertext (Base64url) that only the target can decrypt. Every node relays it, but only the
 *       node whose ID equals {@code targetId} ever decrypts or displays it.
 *   <li>{@link #TYPE_KEY_ANNOUNCE} - carries the sender's public key in {@code text} so others can
 *       encrypt to them. The sender ID must be the hash of that key.
 * </ul>
 *
 * <h3>Plaintext view</h3>
 *
 * After the target decrypts a private message, {@link #withPlaintext(String)} yields a copy with
 * {@link #isPayloadEncrypted()} == false for the UI. Such a copy refuses to serialize, so plaintext
 * can never be put back on the air by accident.
 *
 * <h3>TTL semantics</h3>
 *
 * A node delivers a message locally, then relays {@link #withDecrementedTtl()} only while {@link
 * #isRelayable()} (ttl &gt; 0 after the decrement).
 *
 * <h3>Wire format (protocol version 3)</h3>
 *
 * <pre>
 * {"v":2,"id":"..","sid":"..","typ":1,"tid":"..","txt":"..","spk":"..","ttl":7,"ts":1760000000000}
 * </pre>
 *
 * {@code tid} and {@code spk} (sender public key) appear only on private messages. The target ID is
 * visible to relays (they need it to know the packet is not for them); only the content is
 * confidential.
 */
public final class MeshMessage {

    public static final int PROTOCOL_VERSION = 4;

    public static final int TYPE_PUBLIC = 0;
    public static final int TYPE_PRIVATE = 1;
    public static final int TYPE_KEY_ANNOUNCE = 2;
    public static final int TYPE_ACK = 3;
    public static final int TYPE_CONTROL = 4;
    public static final int TYPE_MEDIA = 5;

    /** Optional explicit marker for "everyone"; equivalent to a null target. */
    public static final String BROADCAST = "BROADCAST";

    public static final int DEFAULT_TTL = 7;
    public static final int MAX_TTL = 15;

    public static final int MAX_TEXT_LENGTH = 280;
    public static final int MAX_ID_LENGTH = 64;

    /** Worst case: 280 characters of 4-byte UTF-8 sequences. */
    public static final int MAX_PRIVATE_PLAINTEXT_BYTES = MAX_TEXT_LENGTH * 4;

    /** iv(12) + 1120 + tag(16) = 1148 bytes -> 1531 Base64 chars; 1600 leaves headroom. */
    public static final int MAX_CIPHERTEXT_LENGTH = 1600;

    /** Worst-case private message is ~1.8 KB (ciphertext + 87-char key + ids), which fits. */
    public static final int MAX_SERIALIZED_BYTES = 2048;

    private static final Pattern TARGET_ID = Pattern.compile("[0-9a-f]{32}");

    /** 65-byte uncompressed P-256 point as unpadded Base64url. */
    private static final Pattern PUBLIC_KEY = Pattern.compile("[A-Za-z0-9_-]{87}");

    /** iv(12) + at least 1 byte + tag(16) = 29 bytes -> at least 39 Base64 chars. */
    private static final Pattern CIPHERTEXT =
            Pattern.compile("[A-Za-z0-9_-]{39," + MAX_CIPHERTEXT_LENGTH + "}");

    private static final String K_VERSION = "v";
    private static final String K_ID = "id";
    private static final String K_SENDER = "sid";
    private static final String K_TYPE = "typ";
    private static final String K_TARGET = "tid";
    private static final String K_TEXT = "txt";
    private static final String K_SENDER_KEY = "spk";
    private static final String K_TTL = "ttl";
    private static final String K_TIMESTAMP = "ts";

    private final int wireVersion;
    private final String signature;
    private final String messageId;
    private final String senderId;
    private final String targetId; // null unless TYPE_PRIVATE
    private final int type;
    private final String text; // plaintext, ciphertext, or public key (see type)
    private final String senderKey; // only TYPE_PRIVATE
    private final boolean payloadEncrypted;
    private final int ttl;
    private final long timestamp;

    private MeshMessage(
            String messageId,
            String senderId,
            @Nullable String targetId,
            int type,
            String text,
            @Nullable String senderKey,
            boolean payloadEncrypted,
            int ttl,
            long timestamp) {
        this(
                messageId,
                senderId,
                targetId,
                type,
                text,
                senderKey,
                payloadEncrypted,
                ttl,
                timestamp,
                null);
    }

    private MeshMessage(
            String messageId,
            String senderId,
            @Nullable String targetId,
            int type,
            String text,
            @Nullable String senderKey,
            boolean payloadEncrypted,
            int ttl,
            long timestamp,
            @Nullable String signature) {
        this(
                messageId,
                senderId,
                targetId,
                type,
                text,
                senderKey,
                payloadEncrypted,
                ttl,
                timestamp,
                signature,
                PROTOCOL_VERSION);
    }

    private MeshMessage(
            String messageId,
            String senderId,
            @Nullable String targetId,
            int type,
            String text,
            @Nullable String senderKey,
            boolean payloadEncrypted,
            int ttl,
            long timestamp,
            @Nullable String signature,
            int wireVersion) {
        this.wireVersion = wireVersion;
        if (signature != null && !signature.matches("[A-Za-z0-9_-]{8,104}")) {
            throw new IllegalArgumentException("Invalid signature encoding");
        }
        this.signature = signature;
        this.messageId = requireId(messageId, "messageId");
        this.senderId = requireId(senderId, "senderId");
        if (ttl < 0 || ttl > MAX_TTL) {
            throw new IllegalArgumentException("ttl must be within 0.." + MAX_TTL);
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive");
        }

        switch (type) {
            case TYPE_PUBLIC:
                if (targetId != null && !BROADCAST.equals(targetId)) {
                    throw new IllegalArgumentException("public message cannot have a target");
                }
                if ((senderKey != null && !PUBLIC_KEY.matcher(senderKey).matches())
                        || payloadEncrypted) {
                    throw new IllegalArgumentException("public message cannot be encrypted");
                }
                requireChatText(text);
                targetId = null;
                break;

            case TYPE_PRIVATE:
            case TYPE_ACK:
            case TYPE_CONTROL:
            case TYPE_MEDIA:
                if (targetId == null || !TARGET_ID.matcher(targetId).matches()) {
                    throw new IllegalArgumentException("private message needs a valid targetId");
                }
                if (senderKey == null || !PUBLIC_KEY.matcher(senderKey).matches()) {
                    throw new IllegalArgumentException("private message needs a valid sender key");
                }
                if (payloadEncrypted) {
                    if (text == null || !CIPHERTEXT.matcher(text).matches()) {
                        throw new IllegalArgumentException("invalid ciphertext");
                    }
                } else {
                    requireChatText(text);
                }
                break;

            case TYPE_KEY_ANNOUNCE:
                if (targetId != null
                        || payloadEncrypted
                        || (senderKey != null && !senderKey.equals(text))) {
                    throw new IllegalArgumentException("invalid key announcement");
                }
                if (text == null || !PUBLIC_KEY.matcher(text).matches()) {
                    throw new IllegalArgumentException("invalid public key");
                }
                break;

            default:
                throw new IllegalArgumentException("unknown message type " + type);
        }

        this.targetId = targetId;
        this.type = type;
        this.text = text;
        this.senderKey = senderKey;
        this.payloadEncrypted = payloadEncrypted;
        this.ttl = ttl;
        this.timestamp = timestamp;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** New public (group) message. */
    @NonNull
    public static MeshMessage create(@NonNull String senderId, @NonNull String text, int ttl) {
        return new MeshMessage(
                newId(senderId),
                senderId,
                null,
                TYPE_PUBLIC,
                text,
                null,
                false,
                ttl,
                System.currentTimeMillis());
    }

    @NonNull
    public static MeshMessage create(@NonNull String senderId, @NonNull String text) {
        return create(senderId, text, DEFAULT_TTL);
    }

    /**
     * Wraps an already-encrypted payload for {@code targetId}. The messageId and timestamp are
     * supplied by the caller because they are authenticated by the encryption (AAD).
     */
    @NonNull
    public static MeshMessage createPrivate(
            @NonNull String messageId,
            @NonNull String senderId,
            @NonNull String targetId,
            @NonNull String ciphertext,
            @NonNull String senderPublicKey,
            int ttl,
            long timestamp) {
        return new MeshMessage(
                messageId,
                senderId,
                targetId,
                TYPE_PRIVATE,
                ciphertext,
                senderPublicKey,
                true,
                ttl,
                timestamp);
    }

    /** Announces {@code publicKey} (Base64url) as belonging to {@code senderId}. */
    @NonNull
    public static MeshMessage createKeyAnnounce(
            @NonNull String senderId, @NonNull String publicKey, int ttl) {
        return new MeshMessage(
                newId(senderId),
                senderId,
                null,
                TYPE_KEY_ANNOUNCE,
                publicKey,
                null,
                false,
                ttl,
                System.currentTimeMillis());
    }

    public static MeshMessage createAcknowledgement(
            String id,
            String senderId,
            String targetId,
            String ciphertext,
            String senderKey,
            long timestamp) {
        return new MeshMessage(
                id,
                senderId,
                targetId,
                TYPE_ACK,
                ciphertext,
                senderKey,
                true,
                DEFAULT_TTL,
                timestamp);
    }

    /** Sender-prefixed IDs prevent an authenticated stranger from occupying another sender's ID. */
    public static String newId(String senderId) {
        if (senderId == null || !senderId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid sender identity");
        }
        return senderId + UUID.randomUUID().toString().replace("-", "");
    }

    public int getProtocolVersion() {
        return wireVersion;
    }

    public String getSignature() {
        return signature;
    }

    public MeshMessage withAuthentication(String key, String signature) {
        return new MeshMessage(
                messageId,
                senderId,
                targetId,
                type,
                text,
                key,
                payloadEncrypted,
                ttl,
                timestamp,
                signature,
                wireVersion);
    }

    public static MeshMessage createControl(
            String id,
            String senderId,
            String targetId,
            String ciphertext,
            String senderKey,
            long timestamp) {
        return new MeshMessage(
                id,
                senderId,
                targetId,
                TYPE_CONTROL,
                ciphertext,
                senderKey,
                true,
                DEFAULT_TTL,
                timestamp);
    }

    public static MeshMessage createMedia(
            String id,
            String senderId,
            String targetId,
            String ciphertext,
            String senderKey,
            long timestamp) {
        return new MeshMessage(
                id,
                senderId,
                targetId,
                TYPE_MEDIA,
                ciphertext,
                senderKey,
                true,
                DEFAULT_TTL,
                timestamp);
    }

    public boolean isMedia() {
        return type == TYPE_MEDIA;
    }

    public boolean isControl() {
        return type == TYPE_CONTROL;
    }

    public boolean isAcknowledgement() {
        return type == TYPE_ACK;
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

    /** The intended recipient, or null for public messages. */
    @Nullable
    public String getTargetId() {
        return targetId;
    }

    public int getType() {
        return type;
    }

    public boolean isPublic() {
        return type == TYPE_PUBLIC;
    }

    public boolean isPrivate() {
        return type == TYPE_PRIVATE || type == TYPE_MEDIA;
    }

    public boolean isKeyAnnounce() {
        return type == TYPE_KEY_ANNOUNCE;
    }

    /**
     * Chat text for public messages and for decrypted private messages. For an encrypted private
     * message this is ciphertext, and for a key announcement it is the public key.
     */
    @NonNull
    public String getText() {
        return text;
    }

    /** True if {@link #getText()} is still ciphertext. */
    public boolean isPayloadEncrypted() {
        return payloadEncrypted;
    }

    /** Sender's public key (Base64url); only present on private messages. */
    @Nullable
    public String getSenderKey() {
        return senderKey;
    }

    public int getTtl() {
        return ttl;
    }

    /** Wall-clock creation time (epoch millis) as reported by the sender's device. */
    public long getTimestamp() {
        return timestamp;
    }

    // ------------------------------------------------------------------
    // Derived copies
    // ------------------------------------------------------------------

    /**
     * @return a copy with ttl reduced by one (never below zero).
     */
    @NonNull
    public MeshMessage withDecrementedTtl() {
        return new MeshMessage(
                messageId,
                senderId,
                targetId,
                type,
                text,
                senderKey,
                payloadEncrypted,
                Math.max(0, ttl - 1),
                timestamp,
                signature,
                wireVersion);
    }

    /**
     * @return a display copy of this private message carrying {@code plaintext}. It cannot be
     *     serialized.
     * @throws IllegalStateException if this is not an encrypted private message
     * @throws IllegalArgumentException if the plaintext is empty or too long
     */
    @NonNull
    public MeshMessage withPlaintext(@NonNull String plaintext) {
        if (!isPrivate() || !payloadEncrypted) {
            throw new IllegalStateException("not an encrypted private message");
        }
        return new MeshMessage(
                messageId,
                senderId,
                targetId,
                type,
                plaintext,
                senderKey,
                false,
                ttl,
                timestamp,
                signature,
                wireVersion);
    }

    /**
     * @return true if this message should still be forwarded to other peers.
     */
    public boolean isRelayable() {
        return ttl > 0;
    }

    public static boolean isValidTargetId(@Nullable String id) {
        return id != null && TARGET_ID.matcher(id).matches();
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    /**
     * @throws IllegalStateException if this is a decrypted private message (never transmit
     *     plaintext of a private message)
     */
    @NonNull
    public JSONObject toJson() {
        if (isPrivate() && !payloadEncrypted) {
            throw new IllegalStateException("refusing to serialize a plaintext private message");
        }
        try {
            JSONObject json = new JSONObject();
            json.put(K_VERSION, wireVersion);
            json.put(K_ID, messageId);
            json.put(K_SENDER, senderId);
            json.put(K_TYPE, type);
            if (targetId != null) json.put(K_TARGET, targetId);
            json.put(K_TEXT, text);
            if (senderKey != null) json.put(K_SENDER_KEY, senderKey);
            if (signature != null) json.put("sig", signature);
            json.put(K_TTL, ttl);
            json.put(K_TIMESTAMP, timestamp);
            return json;
        } catch (JSONException e) {
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
            if (version != PROTOCOL_VERSION && version != 2 && version != 3) {
                throw new IllegalArgumentException("unsupported protocol version " + version);
            }
            int type = json.optInt(K_TYPE, TYPE_PUBLIC);
            return new MeshMessage(
                    json.getString(K_ID),
                    json.getString(K_SENDER),
                    json.isNull(K_TARGET) ? null : json.getString(K_TARGET),
                    type,
                    json.getString(K_TEXT),
                    json.isNull(K_SENDER_KEY) ? null : json.getString(K_SENDER_KEY),
                    type == TYPE_PRIVATE
                            || type == TYPE_ACK
                            || type == TYPE_CONTROL
                            || type == TYPE_MEDIA,
                    json.getInt(K_TTL),
                    json.getLong(K_TIMESTAMP),
                    json.isNull("sig") ? null : json.getString("sig"),
                    version);
        } catch (JSONException e) {
            throw new IllegalArgumentException("missing or invalid field: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Validation / Object
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

    private static void requireChatText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text exceeds " + MAX_TEXT_LENGTH + " characters");
        }
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

    /** Content is intentionally omitted so it never leaks into logs. */
    @NonNull
    @Override
    public String toString() {
        return "MeshMessage{id="
                + messageId
                + ", type="
                + type
                + ", sender="
                + senderId
                + ", target="
                + targetId
                + ", ttl="
                + ttl
                + ", textLength="
                + text.length()
                + ", ts="
                + timestamp
                + '}';
    }
}
