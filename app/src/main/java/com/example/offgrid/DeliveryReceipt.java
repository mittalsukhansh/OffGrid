package com.example.offgrid;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Receipt plaintext is encrypted under a separate ECDH/HKDF purpose, never sent in clear. */
public final class DeliveryReceipt {
    private static final String PREFIX = "offgrid-receipt-v1|";
    public final String originalId;
    public final String fingerprint;

    private DeliveryReceipt(String originalId, String fingerprint) {
        this.originalId = originalId;
        this.fingerprint = fingerprint;
    }

    public static String payloadFor(MeshMessage original) {
        if (!original.isPrivate() || !original.isPayloadEncrypted()) {
            throw new IllegalArgumentException("Only encrypted private messages have receipts");
        }
        return PREFIX + original.getMessageId() + "|" + fingerprint(original);
    }

    public static DeliveryReceipt parse(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(PREFIX) || plaintext.length() > 150) {
            throw new IllegalArgumentException("Invalid receipt");
        }
        int separator = plaintext.lastIndexOf('|');
        String id = plaintext.substring(PREFIX.length(), Math.max(PREFIX.length(), separator));
        String hash = plaintext.substring(separator + 1);
        if (id.isEmpty() || id.length() > MeshMessage.MAX_ID_LENGTH || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid receipt reference");
        }
        return new DeliveryReceipt(id, hash);
    }

    /** Caller must authenticate/decrypt the ACK first. Never trust its cleartext envelope alone. */
    public boolean matches(MeshMessage acknowledgement, MeshMessage original) {
        return acknowledgement.isAcknowledgement() && original.isPrivate()
                && original.isPayloadEncrypted() && originalId.equals(original.getMessageId())
                && original.getTargetId().equals(acknowledgement.getSenderId())
                && original.getSenderId().equals(acknowledgement.getTargetId())
                && fingerprint.equals(fingerprint(original));
    }

    /** Length-delimited immutable wire fields; TTL is excluded because relays decrement it. */
    public static String fingerprint(MeshMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(message.getType());
            data.writeUTF(message.getMessageId());
            data.writeUTF(message.getSenderId());
            data.writeUTF(message.getTargetId() == null ? "" : message.getTargetId());
            data.writeUTF(message.getSenderKey() == null ? "" : message.getSenderKey());
            data.writeLong(message.getTimestamp());
            data.writeUTF(message.getText());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint message", e);
        }
    }
}
