package com.example.offgrid;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protocol-5 identity and private-message cryptography. The retained P-256 signing identity
 * determines the device ID; a separate wrapped P-256 agreement key receives private traffic. Each
 * encryption uses fresh ephemeral-static ECDH, purpose-separated HKDF and AES-256-GCM. The signed
 * envelope binds routing, sequence/epoch, both public keys and ciphertext. This construction does
 * not provide full forward secrecy after recipient agreement-key compromise. See PACKET_FORMAT.md
 * and SECURITY.md for canonical bytes and backup/restore limitations.
 */
public final class CryptoManager {

    private static final String TAG = "OffGridCrypto";

    public static final String CONTACT_PREFIX = "offgrid:";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String WRAP_KEY_ALIAS = "offgrid_identity_wrap_v1";
    private static final String PREFS_NAME = "offgrid_identity";
    private static final String PREF_PRIVATE_WRAPPED = "private_key_wrapped";
    private static final String PREF_PUBLIC = "public_key";
    private static final String PREF_CONTACTS = "contacts";

    private static final int B64_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    private static final int ID_BYTES = 16;
    private static final int PUBLIC_KEY_BYTES = 65; // 0x04 || X(32) || Y(32)
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_CONTACTS = 500;

    /** A person we can send private messages to. */
    public static final class Contact {
        @NonNull public final String id;

        /** True if added by the user (contact string); false if only heard on the mesh. */
        public final boolean addedManually;

        public final boolean verified;
        @NonNull public final String nickname;

        Contact(@NonNull String id, boolean addedManually, boolean verified, String nickname) {
            this.id = id;
            this.addedManually = addedManually;
            this.verified = verified;
            this.nickname = nickname;
        }
    }

    private static final class ContactRecord {
        final String keyB64;
        String agreementB64;
        boolean manual;
        boolean verified;
        String nickname = "";
        final long addedAt;

        ContactRecord(String keyB64, boolean manual, long addedAt) {
            this.keyB64 = keyB64;
            this.manual = manual;
            this.addedAt = addedAt;
        }
    }

    private static volatile CryptoManager instance;

    @NonNull
    public static CryptoManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (CryptoManager.class) {
                if (instance == null) {
                    instance = new CryptoManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, ContactRecord> contacts = new LinkedHashMap<>(); // guarded by this

    private PrivateKey agreementPrivate;
    private String agreementPublic;
    private long nextSequence, reservedUntil;
    private String sendEpoch;
    private String contactBundle;
    private PrivateKey privateKey;
    private byte[] publicKeyBytes;
    private String publicKeyB64;
    private String localId;

    CryptoManager(Context appContext) {
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            loadOrCreateIdentity();
            loadAgreementIdentity();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialise device identity", e);
        }
        loadContacts();
    }

    // ------------------------------------------------------------------
    // Public API: identity
    // ------------------------------------------------------------------

    /** This device's ID (32 lowercase hex characters). */
    @NonNull
    public String getLocalId() {
        return localId;
    }

    /** This device's public key, Base64url (87 chars). */
    @NonNull
    public String getPublicKeyBase64() {
        return publicKeyB64;
    }

    /** A string others can import to message this device privately. */
    @NonNull
    public synchronized String getContactString() {
        if (contactBundle != null) return contactBundle;
        try {
            String pair = publicKeyB64 + "." + agreementPublic;
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey, secureRandom);
            signer.update(("offgrid-contact-v5|" + pair).getBytes(StandardCharsets.UTF_8));
            return contactBundle =
                    "offgrid5:" + pair + "." + Base64.encodeToString(signer.sign(), B64_FLAGS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isValidId(@Nullable String id) {
        return id != null && id.length() == ID_BYTES * 2 && id.matches("[0-9a-f]+");
    }

    /** Groups an ID for display, e.g. "A1B2-C3D4-E5F6-0718" for hexChars = 16. */
    @NonNull
    public static String formatId(@NonNull String id, int hexChars) {
        String upper = id.toUpperCase(java.util.Locale.ROOT);
        int n = Math.min(hexChars, upper.length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i += 4) {
            if (i > 0) sb.append('-');
            sb.append(upper, i, Math.min(i + 4, n));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Public API: contacts
    // ------------------------------------------------------------------

    /**
     * Imports a contact string produced by {@link #getContactString()} on another device.
     *
     * @return the contact's ID, or null if the string is not a valid contact
     */
    @Nullable
    public String importContact(@Nullable String contactString) {
        if (contactString == null || contactString.length() > 400) return null;
        if (contactString.trim().startsWith("offgrid5:")) return importBundle(contactString.trim());
        String s = contactString.trim();
        if (!s.regionMatches(true, 0, CONTACT_PREFIX, 0, CONTACT_PREFIX.length())) return null;
        String keyB64 = s.substring(CONTACT_PREFIX.length()).trim();
        if (keyB64.length() != 87) return null;
        try {
            byte[] raw = Base64.decode(keyB64, B64_FLAGS);
            String id = deriveId(raw);
            return registerKey(id, keyB64, true) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Records a public key heard on the mesh (key announcement or inside a private message).
     *
     * @return true if the key is valid for that ID (so the packet is worth relaying)
     */
    public boolean registerAnnouncedKey(@NonNull String id, @NonNull String keyB64) {
        return registerKey(id, keyB64, false);
    }

    public synchronized boolean hasKeyFor(@NonNull String id) {
        return contacts.containsKey(id) && contacts.get(id).agreementB64 != null;
    }

    /** Manually added contacts first, then others, most recent first. */
    @NonNull
    public synchronized List<Contact> getContacts() {
        List<Map.Entry<String, ContactRecord>> entries = new ArrayList<>(contacts.entrySet());
        Collections.sort(
                entries,
                (a, b) -> {
                    if (a.getValue().manual != b.getValue().manual)
                        return a.getValue().manual ? -1 : 1;
                    return Long.compare(b.getValue().addedAt, a.getValue().addedAt);
                });
        List<Contact> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, ContactRecord> e : entries) {
            out.add(
                    new Contact(
                            e.getKey(),
                            e.getValue().manual,
                            e.getValue().verified,
                            e.getValue().nickname));
        }
        return out;
    }

    public static String normalizeNickname(String nickname) {
        if (nickname == null) return "";
        if (nickname.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Nicknames cannot contain control characters");
        }
        String normalized = nickname.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 40 || normalized.matches(".*[\\p{Cc}].*")) {
            throw new IllegalArgumentException(
                    "Use at most 40 characters without control characters");
        }
        return normalized;
    }

    public synchronized boolean setNickname(String id, String nickname) {
        ContactRecord record = contacts.get(id);
        if (record == null) return false;
        record.nickname = normalizeNickname(nickname);
        record.manual = true;
        persistContacts();
        return true;
    }

    public synchronized String nickname(String id) {
        ContactRecord record = contacts.get(id);
        return record == null ? "" : record.nickname;
    }

    public synchronized boolean nicknameInUse(String id, String nickname) {
        String name = normalizeNickname(nickname);
        if (name.isEmpty()) return false;
        for (Map.Entry<String, ContactRecord> entry : contacts.entrySet()) {
            if (!entry.getKey().equals(id) && entry.getValue().nickname.equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    public synchronized String contactLabel(String id) {
        String name = nickname(id);
        return (name.isEmpty() ? "" : name + " · ") + formatId(id, 8);
    }

    public synchronized boolean isVerified(String id) {
        ContactRecord record = contacts.get(id);
        return record != null && record.verified;
    }

    public String getOwnFingerprint() {
        return fullFingerprint(
                (publicKeyB64 + "." + agreementPublic).getBytes(StandardCharsets.US_ASCII));
    }

    public synchronized String contactFingerprint(String id) {
        ContactRecord record = contacts.get(id);
        return record == null
                ? null
                : (record.agreementB64 == null
                        ? fullFingerprint(Base64.decode(record.keyB64, B64_FLAGS))
                        : fullFingerprint(
                                (record.keyB64 + "." + record.agreementB64)
                                        .getBytes(StandardCharsets.US_ASCII)));
    }

    /** Import/discovery never verifies a person; explicit fingerprint comparison is required. */
    public synchronized boolean setVerified(
            String id, String comparedFingerprint, boolean verified) {
        ContactRecord record = contacts.get(id);
        if (record == null || !contactFingerprint(id).equals(comparedFingerprint)) return false;
        record.verified = verified;
        if (verified) record.manual = true;
        persistContacts();
        return true;
    }

    private static String fullFingerprint(byte[] key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder result = new StringBuilder();
            for (byte b : hash) {
                result.append(Character.forDigit((b >>> 4) & 15, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signs all immutable packet fields; forwarding may only change TTL. */
    public MeshMessage signPacket(MeshMessage message) throws GeneralSecurityException {
        if (message.getProtocolVersion() != MeshMessage.PROTOCOL_VERSION) {
            throw new GeneralSecurityException("Legacy packet must be recreated before sending");
        }
        if (!localId.equals(message.getSenderId()))
            throw new GeneralSecurityException("Not our identity");
        if (message.isPrivate() && !message.isPayloadEncrypted()) {
            throw new GeneralSecurityException("Cannot sign a display-only plaintext copy");
        }
        MeshMessage keyed =
                message.withAuthentication(publicKeyB64, null)
                        .withSequence(currentEpoch(), allocateSequence(), agreementPublic);
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey, secureRandom);
        signer.update(PacketSecurity.signingBytes(keyed));
        return keyed.withAuthentication(
                publicKeyB64, Base64.encodeToString(signer.sign(), B64_FLAGS));
    }

    public boolean verifyPacket(MeshMessage packet) {
        if (packet.getProtocolVersion() != MeshMessage.PROTOCOL_VERSION
                || packet.getSignature() == null
                || packet.getSenderKey() == null
                || packet.getAgreementKey() == null
                || packet.getEpoch() == null
                || packet.getSequence() < 1) return false;
        try {
            byte[] raw = Base64.decode(packet.getSenderKey(), B64_FLAGS);
            if (!deriveId(raw).equals(packet.getSenderId())) return false;
            decodePublicKey(Base64.decode(packet.getAgreementKey(), B64_FLAGS));
            java.security.Signature verifier =
                    java.security.Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(decodePublicKey(raw));
            verifier.update(PacketSecurity.signingBytes(packet));
            return verifier.verify(Base64.decode(packet.getSignature(), B64_FLAGS));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Public API: encryption
    // ------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} for {@code targetId}.
     *
     * @return Base64url(ephemeral public point || iv || ciphertext || tag) to put in a private
     *     {@link MeshMessage}
     * @throws IllegalArgumentException if the plaintext is empty or too large
     * @throws GeneralSecurityException if we have no key for the target or encryption fails
     */
    @NonNull
    public String encrypt(
            @NonNull String messageId,
            @NonNull String targetId,
            long timestamp,
            @NonNull String plaintext)
            throws GeneralSecurityException {
        return encryptForPurpose(messageId, targetId, timestamp, plaintext, "chat");
    }

    public MeshMessage createAcknowledgement(MeshMessage original) throws GeneralSecurityException {
        if (!original.isPrivate() || !localId.equals(original.getTargetId())) {
            throw new GeneralSecurityException("Cannot acknowledge another device's message");
        }
        String id = MeshMessage.newId(localId);
        long timestamp = System.currentTimeMillis();
        String payload = DeliveryReceipt.payloadFor(original);
        return signPacket(
                MeshMessage.createAcknowledgement(
                        id,
                        localId,
                        original.getSenderId(),
                        encryptForPurpose(id, original.getSenderId(), timestamp, payload, "ack"),
                        publicKeyB64,
                        timestamp));
    }

    public MeshMessage control(String targetId, String plaintext) throws GeneralSecurityException {
        String id = MeshMessage.newId(localId);
        long now = System.currentTimeMillis();
        return signPacket(
                MeshMessage.createControl(
                        id,
                        localId,
                        targetId,
                        encryptForPurpose(id, targetId, now, plaintext, "control"),
                        publicKeyB64,
                        now));
    }

    public String decryptControl(MeshMessage message) throws GeneralSecurityException {
        return decryptForPurpose(message, "control");
    }

    private String encryptForPurpose(
            String messageId, String targetId, long timestamp, String plaintext, String purpose)
            throws GeneralSecurityException {
        byte[] pt = plaintext.getBytes(StandardCharsets.UTF_8);
        if (pt.length == 0
                || pt.length
                        > ("control".equals(purpose)
                                ? MeshMessage.MAX_CONTROL_PLAINTEXT_BYTES
                                : MeshMessage.MAX_PRIVATE_PLAINTEXT_BYTES)) {
            throw new IllegalArgumentException("plaintext size out of range");
        }
        ECPublicKey targetKey = getContactKey(targetId);
        KeyPair ephemeral = newEcPair();
        byte[] ephemeralPublic = encodePublicKey((ECPublicKey) ephemeral.getPublic());
        byte[] key = deriveKey(ephemeral.getPrivate(), targetKey, localId, targetId, purpose);
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(messageId, localId, targetId, timestamp));
            byte[] ct = cipher.doFinal(pt);

            byte[] out = new byte[PUBLIC_KEY_BYTES + iv.length + ct.length];
            System.arraycopy(ephemeralPublic, 0, out, 0, PUBLIC_KEY_BYTES);
            System.arraycopy(iv, 0, out, PUBLIC_KEY_BYTES, iv.length);
            System.arraycopy(ct, 0, out, PUBLIC_KEY_BYTES + iv.length, ct.length);
            return Base64.encodeToString(out, B64_FLAGS);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Decrypts a private message addressed to this device. On success the sender's key is
     * remembered, so the user can reply.
     *
     * @throws GeneralSecurityException if the message is not for us, was tampered with, or the
     *     embedded sender key does not match the sender ID
     */
    @NonNull
    public String decrypt(@NonNull MeshMessage message) throws GeneralSecurityException {
        return decryptForPurpose(message, "chat");
    }

    public String decryptAcknowledgement(MeshMessage message) throws GeneralSecurityException {
        return decryptForPurpose(message, "ack");
    }

    private String decryptForPurpose(MeshMessage message, String purpose)
            throws GeneralSecurityException {
        if (!("control".equals(purpose)
                        ? message.isControl()
                        : "ack".equals(purpose) ? message.isAcknowledgement() : message.isPrivate())
                || !message.isPayloadEncrypted()
                || !localId.equals(message.getTargetId())) {
            throw new GeneralSecurityException("not a private message for this device");
        }
        String senderKeyB64 = message.getSenderKey();
        if (senderKeyB64 == null) throw new GeneralSecurityException("missing sender key");

        byte[] senderKeyRaw;
        byte[] data;
        try {
            senderKeyRaw = Base64.decode(senderKeyB64, B64_FLAGS);
            data = Base64.decode(message.getText(), B64_FLAGS);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("bad encoding", e);
        }
        // The ID is the hash of the key, so this proves the key belongs to the claimed sender.
        if (!deriveId(senderKeyRaw).equals(message.getSenderId())) {
            throw new GeneralSecurityException("sender key does not match sender id");
        }
        if (data.length < PUBLIC_KEY_BYTES + GCM_IV_BYTES + GCM_TAG_BITS / 8 + 1) {
            throw new GeneralSecurityException("ciphertext too short");
        }

        ECPublicKey ephemeral = decodePublicKey(Arrays.copyOfRange(data, 0, PUBLIC_KEY_BYTES));
        byte[] key =
                deriveKey(agreementPrivate, ephemeral, message.getSenderId(), localId, purpose);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, data, PUBLIC_KEY_BYTES, GCM_IV_BYTES));
            cipher.updateAAD(
                    aad(
                            message.getMessageId(),
                            message.getSenderId(),
                            localId,
                            message.getTimestamp()));
            byte[] pt =
                    cipher.doFinal(
                            data,
                            PUBLIC_KEY_BYTES + GCM_IV_BYTES,
                            data.length - PUBLIC_KEY_BYTES - GCM_IV_BYTES);
            int limit =
                    "control".equals(purpose)
                            ? MeshMessage.MAX_CONTROL_PLAINTEXT_BYTES
                            : MeshMessage.MAX_PRIVATE_PLAINTEXT_BYTES;
            if (pt.length == 0 || pt.length > limit)
                throw new GeneralSecurityException("Plaintext size out of range");

            registerKey(message.getSenderId(), senderKeyB64, false);
            return new String(pt, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Identity and contacts only. Message bodies/files and replay evidence are not exported. */
    public synchronized byte[] exportIdentity(char[] password) throws Exception {
        JSONObject payload = new JSONObject();
        byte[] signing = privateKey.getEncoded(), agreement = agreementPrivate.getEncoded();
        byte[] plain = null;
        try {
            payload.put("version", 1);
            payload.put("signingPrivate", Base64.encodeToString(signing, B64_FLAGS));
            payload.put("signingPublic", publicKeyB64);
            payload.put("agreementPrivate", Base64.encodeToString(agreement, B64_FLAGS));
            payload.put("agreementPublic", agreementPublic);
            payload.put("contacts", new JSONObject(prefs.getString(PREF_CONTACTS, "{}")));
            plain = payload.toString().getBytes(StandardCharsets.UTF_8);
            return IdentityBackup.encrypt(plain, password);
        } finally {
            Arrays.fill(signing, (byte) 0);
            Arrays.fill(agreement, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * Caller must stop mesh and require empty history before replacing an installation identity.
     */
    public synchronized void restoreIdentity(byte[] encrypted, char[] password) throws Exception {
        byte[] plain = IdentityBackup.decrypt(encrypted, password);
        byte[] signing = null, agreement = null;
        try {
            JSONObject payload =
                    JsonBounds.object(
                            new String(plain, StandardCharsets.UTF_8), IdentityBackup.MAX_BYTES);
            if (payload.getInt("version") != 1)
                throw new GeneralSecurityException("Unsupported identity version");
            signing = Base64.decode(payload.getString("signingPrivate"), B64_FLAGS);
            agreement = Base64.decode(payload.getString("agreementPrivate"), B64_FLAGS);
            String signingPublic = payload.getString("signingPublic");
            String newAgreementPublic = payload.getString("agreementPublic");
            PrivateKey newSigning = validatePair(signing, signingPublic);
            PrivateKey newAgreement = validatePair(agreement, newAgreementPublic);
            JSONObject savedContacts = payload.getJSONObject("contacts");
            if (savedContacts.length() > MAX_CONTACTS)
                throw new GeneralSecurityException("Too many contacts");
            Iterator<String> ids = savedContacts.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject contact = savedContacts.getJSONObject(id);
                byte[] raw = Base64.decode(contact.getString("k"), B64_FLAGS);
                if (!deriveId(raw).equals(id))
                    throw new GeneralSecurityException("Invalid contact identity");
                decodePublicKey(raw);
                if (!contact.isNull("agreement"))
                    decodePublicKey(Base64.decode(contact.getString("agreement"), B64_FLAGS));
                contact.put("nickname", normalizeNickname(contact.optString("nickname", "")));
            }
            String epoch = java.util.UUID.randomUUID().toString().replace("-", "");
            if (!prefs.edit()
                    .putString(
                            PREF_PRIVATE_WRAPPED, Base64.encodeToString(wrap(signing), B64_FLAGS))
                    .putString(PREF_PUBLIC, signingPublic)
                    .putString(
                            "agreement_wrapped", Base64.encodeToString(wrap(agreement), B64_FLAGS))
                    .putString("agreement_public", newAgreementPublic)
                    .putString(PREF_CONTACTS, savedContacts.toString())
                    .putString("send_epoch", epoch)
                    .putLong("sequence_reserved", 0)
                    .commit())
                throw new GeneralSecurityException("Could not save restored identity");
            setIdentity(newSigning, Base64.decode(signingPublic, B64_FLAGS));
            agreementPrivate = newAgreement;
            agreementPublic = newAgreementPublic;
            sendEpoch = epoch;
            nextSequence = reservedUntil = 0;
            contactBundle = null;
            contacts.clear();
            loadContacts();
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (signing != null) Arrays.fill(signing, (byte) 0);
            if (agreement != null) Arrays.fill(agreement, (byte) 0);
        }
    }

    private PrivateKey validatePair(byte[] privateBytes, String publicB64)
            throws GeneralSecurityException {
        PrivateKey key =
                KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        ECPublicKey pub = decodePublicKey(Base64.decode(publicB64, B64_FLAGS));
        byte[] challenge = new byte[32];
        secureRandom.nextBytes(challenge);
        // Mathematical pair check only; agreement keys are never used to sign network traffic.
        java.security.Signature proof = java.security.Signature.getInstance("SHA256withECDSA");
        proof.initSign(key);
        proof.update(challenge);
        byte[] signature = proof.sign();
        proof.initVerify(pub);
        proof.update(challenge);
        if (!proof.verify(signature))
            throw new GeneralSecurityException("Mismatched identity keys");
        return key;
    }

    // ------------------------------------------------------------------
    // Key derivation
    // ------------------------------------------------------------------

    private byte[] deriveKey(
            PrivateKey ownKey, ECPublicKey peer, String senderId, String targetId, String purpose)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ownKey);
        agreement.doPhase(peer, true);
        byte[] shared = agreement.generateSecret();
        try {
            byte[] info =
                    (("control".equals(purpose)
                                            ? "offgrid-control-v5|"
                                            : "ack".equals(purpose)
                                                    ? "offgrid-ack-v5|"
                                                    : "offgrid-e2ee-v5|")
                                    + senderId
                                    + ">"
                                    + targetId)
                            .getBytes(StandardCharsets.UTF_8);
            return hkdfSha256(shared, info);
        } finally {
            Arrays.fill(shared, (byte) 0);
        }
    }

    /** HKDF (RFC 5869) with a zero salt, producing one 32-byte block. */
    private static byte[] hkdfSha256(byte[] ikm, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 0x01);
        byte[] okm = mac.doFinal();
        Arrays.fill(prk, (byte) 0);
        return okm;
    }

    private static byte[] aad(String messageId, String senderId, String targetId, long timestamp) {
        return (messageId + "|" + senderId + "|" + targetId + "|" + timestamp)
                .getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    private synchronized boolean registerKey(String claimedId, String keyB64, boolean manual) {
        if (!isValidId(claimedId) || claimedId.equals(localId)) return false;
        try {
            byte[] raw = Base64.decode(keyB64, B64_FLAGS);
            if (!deriveId(raw).equals(claimedId)) return false; // key does not belong to this ID
            decodePublicKey(raw); // must be a valid curve point
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }

        ContactRecord existing = contacts.get(claimedId);
        if (existing != null) {
            if (manual && !existing.manual) {
                existing.manual = true;
                persistContacts();
            }
            return true;
        }
        contacts.put(claimedId, new ContactRecord(keyB64, manual, System.currentTimeMillis()));
        trimContacts();
        persistContacts();
        return true;
    }

    private synchronized String importBundle(String bundle) {
        try {
            String[] parts = bundle.substring("offgrid5:".length()).split("\\.", -1);
            if (parts.length != 3
                    || parts[0].length() != 87
                    || parts[1].length() != 87
                    || parts[2].length() > 104) return null;
            ECPublicKey signing = decodePublicKey(Base64.decode(parts[0], B64_FLAGS));
            decodePublicKey(Base64.decode(parts[1], B64_FLAGS));
            java.security.Signature verify = java.security.Signature.getInstance("SHA256withECDSA");
            verify.initVerify(signing);
            verify.update(
                    ("offgrid-contact-v5|" + parts[0] + "." + parts[1])
                            .getBytes(StandardCharsets.UTF_8));
            if (!verify.verify(Base64.decode(parts[2], B64_FLAGS))) return null;
            String id = deriveId(Base64.decode(parts[0], B64_FLAGS));
            return registerAgreement(id, parts[0], parts[1], true) ? id : null;
        } catch (GeneralSecurityException | IllegalArgumentException bad) {
            return null;
        }
    }

    /** Only a verified packet may bind a new agreement key to a signing identity. */
    public boolean registerPacketKeys(MeshMessage packet) {
        return verifyPacket(packet)
                && registerAgreement(
                        packet.getSenderId(),
                        packet.getSenderKey(),
                        packet.getAgreementKey(),
                        false);
    }

    private synchronized boolean registerAgreement(
            String id, String signing, String agreement, boolean manual) {
        try {
            decodePublicKey(Base64.decode(agreement, B64_FLAGS));
        } catch (GeneralSecurityException | IllegalArgumentException bad) {
            return false;
        }
        if (!registerKey(id, signing, manual)) return false;
        ContactRecord record = contacts.get(id);
        if (!agreement.equals(record.agreementB64)) {
            record.agreementB64 = agreement;
            record.verified = false;
            persistContacts();
        }
        return true;
    }

    private static KeyPair newEcPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private void loadAgreementIdentity() throws GeneralSecurityException {
        String stored = prefs.getString("agreement_wrapped", null);
        agreementPublic = prefs.getString("agreement_public", null);
        if (stored != null && agreementPublic != null) {
            byte[] plain = unwrap(Base64.decode(stored, B64_FLAGS));
            try {
                agreementPrivate =
                        KeyFactory.getInstance("EC")
                                .generatePrivate(new PKCS8EncodedKeySpec(plain));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
            decodePublicKey(Base64.decode(agreementPublic, B64_FLAGS));
        } else {
            KeyPair pair = newEcPair();
            agreementPrivate = pair.getPrivate();
            agreementPublic =
                    Base64.encodeToString(
                            encodePublicKey((ECPublicKey) pair.getPublic()), B64_FLAGS);
            byte[] plain = agreementPrivate.getEncoded();
            try {
                if (!prefs.edit()
                        .putString(
                                "agreement_wrapped", Base64.encodeToString(wrap(plain), B64_FLAGS))
                        .putString("agreement_public", agreementPublic)
                        .commit())
                    throw new GeneralSecurityException("Could not persist agreement key");
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        }
        sendEpoch = prefs.getString("send_epoch", null);
        if (sendEpoch == null) {
            sendEpoch = java.util.UUID.randomUUID().toString().replace("-", "");
            if (!prefs.edit()
                    .putString("send_epoch", sendEpoch)
                    .putLong("sequence_reserved", 0)
                    .commit())
                throw new GeneralSecurityException("Could not persist sequence epoch");
        }
        nextSequence = reservedUntil = prefs.getLong("sequence_reserved", 0);
    }

    private synchronized String currentEpoch() {
        return sendEpoch;
    }

    private synchronized long allocateSequence() throws GeneralSecurityException {
        // Reserve before use; a process crash may leave gaps but cannot reuse a signed sequence.
        if (nextSequence == reservedUntil) {
            if (reservedUntil > Long.MAX_VALUE - 64)
                throw new GeneralSecurityException("Sequence exhausted");
            long persisted = prefs.getLong("sequence_reserved", 0);
            nextSequence = Math.max(nextSequence, persisted);
            if (nextSequence > Long.MAX_VALUE - 64)
                throw new GeneralSecurityException("Sequence exhausted");
            reservedUntil = nextSequence + 64;
            if (!prefs.edit().putLong("sequence_reserved", reservedUntil).commit()) {
                reservedUntil = nextSequence;
                throw new GeneralSecurityException("Could not reserve sequence block");
            }
        }
        return ++nextSequence;
    }

    private synchronized ECPublicKey getContactKey(String id) throws GeneralSecurityException {
        ContactRecord record = contacts.get(id);
        if (record == null) throw new GeneralSecurityException("no public key for " + id);
        try {
            if (record.agreementB64 == null)
                throw new GeneralSecurityException("Contact needs a new QR exchange");
            return decodePublicKey(Base64.decode(record.agreementB64, B64_FLAGS));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("stored key is corrupt", e);
        }
    }

    /** Drops the oldest mesh-discovered contacts once over the cap; manual ones are kept. */
    private void trimContacts() {
        Iterator<Map.Entry<String, ContactRecord>> it = contacts.entrySet().iterator();
        while (contacts.size() > MAX_CONTACTS && it.hasNext()) {
            if (!it.next().getValue().manual) it.remove();
        }
    }

    private void loadContacts() {
        String json = prefs.getString(PREF_CONTACTS, null);
        if (json == null) return;
        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> ids = root.keys();
            synchronized (this) {
                while (ids.hasNext()) {
                    String id = ids.next();
                    JSONObject o = root.getJSONObject(id);
                    String key = o.getString("k");
                    byte[] raw = Base64.decode(key, B64_FLAGS);
                    if (!isValidId(id) || !deriveId(raw).equals(id)) continue;
                    decodePublicKey(raw);
                    ContactRecord record =
                            new ContactRecord(key, o.optBoolean("m", false), o.optLong("t", 0L));
                    record.agreementB64 = o.optString("agreement", null);
                    if (record.agreementB64 != null)
                        decodePublicKey(Base64.decode(record.agreementB64, B64_FLAGS));
                    record.verified =
                            record.agreementB64 != null && o.optBoolean("verified", false);
                    record.nickname = normalizeNickname(o.optString("nickname", ""));
                    contacts.put(id, record);
                }
            }
        } catch (JSONException | GeneralSecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Contacts unreadable; starting empty", e);
        }
    }

    private void persistContacts() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ContactRecord> e : contacts.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("k", e.getValue().keyB64);
                o.put("agreement", e.getValue().agreementB64);
                o.put("m", e.getValue().manual);
                o.put("verified", e.getValue().verified);
                o.put("nickname", e.getValue().nickname);
                o.put("t", e.getValue().addedAt);
                root.put(e.getKey(), o);
            }
            prefs.edit().putString(PREF_CONTACTS, root.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to persist contacts", e);
        }
    }

    // ------------------------------------------------------------------
    // Identity storage (private key wrapped by an Android Keystore AES key)
    // ------------------------------------------------------------------

    private void loadOrCreateIdentity() throws GeneralSecurityException {
        String wrapped = prefs.getString(PREF_PRIVATE_WRAPPED, null);
        String pubB64 = prefs.getString(PREF_PUBLIC, null);
        if (wrapped != null && pubB64 != null) {
            try {
                byte[] pkcs8 = unwrap(Base64.decode(wrapped, B64_FLAGS));
                PrivateKey priv =
                        KeyFactory.getInstance("EC")
                                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                Arrays.fill(pkcs8, (byte) 0);
                byte[] pub = Base64.decode(pubB64, B64_FLAGS);
                decodePublicKey(pub); // sanity check
                setIdentity(priv, pub);
                return;
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // e.g. Keystore key lost after a restore; a new identity is the only option.
                Log.w(TAG, "Stored identity unreadable; generating a new one", e);
            }
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] pub = encodePublicKey((ECPublicKey) pair.getPublic());

        byte[] pkcs8 = pair.getPrivate().getEncoded();
        String wrappedNew = Base64.encodeToString(wrap(pkcs8), B64_FLAGS);
        Arrays.fill(pkcs8, (byte) 0);

        prefs.edit()
                .putString(PREF_PRIVATE_WRAPPED, wrappedNew)
                .putString(PREF_PUBLIC, Base64.encodeToString(pub, B64_FLAGS))
                .apply();
        setIdentity(pair.getPrivate(), pub);
    }

    private void setIdentity(PrivateKey priv, byte[] pub) throws GeneralSecurityException {
        privateKey = priv;
        publicKeyBytes = pub;
        publicKeyB64 = Base64.encodeToString(pub, B64_FLAGS);
        localId = deriveId(pub);
    }

    private static SecretKey getOrCreateWrapKey() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(WRAP_KEY_ALIAS)) {
                KeyGenerator kg =
                        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                kg.init(
                        new KeyGenParameterSpec.Builder(
                                        WRAP_KEY_ALIAS,
                                        KeyProperties.PURPOSE_ENCRYPT
                                                | KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build());
                return kg.generateKey();
            }
            return (SecretKey) ks.getKey(WRAP_KEY_ALIAS, null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static byte[] wrap(byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey()); // Keystore picks the IV
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] unwrap(byte[] data) throws GeneralSecurityException {
        if (data.length <= GCM_IV_BYTES)
            throw new GeneralSecurityException("wrapped key too short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateWrapKey(),
                new GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES));
        return cipher.doFinal(data, GCM_IV_BYTES, data.length - GCM_IV_BYTES);
    }

    // ------------------------------------------------------------------
    // EC helpers
    // ------------------------------------------------------------------

    /** First 16 bytes of SHA-256(public key) as lowercase hex. */
    @NonNull
    static String deriveId(@NonNull byte[] publicKey) throws IllegalArgumentException {
        if (publicKey.length != PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("public key must be " + PUBLIC_KEY_BYTES + " bytes");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(publicKey);
            StringBuilder sb = new StringBuilder(ID_BYTES * 2);
            for (int i = 0; i < ID_BYTES; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final class P256Holder {
        static final ECParameterSpec SPEC = load();

        private static ECParameterSpec load() {
            try {
                AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec("secp256r1"));
                return params.getParameterSpec(ECParameterSpec.class);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("P-256 unavailable", e);
            }
        }
    }

    private static byte[] encodePublicKey(ECPublicKey key) {
        ECPoint w = key.getW();
        byte[] out = new byte[PUBLIC_KEY_BYTES];
        out[0] = 0x04;
        System.arraycopy(toFixed32(w.getAffineX()), 0, out, 1, 32);
        System.arraycopy(toFixed32(w.getAffineY()), 0, out, 33, 32);
        return out;
    }

    private static byte[] toFixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) { // leading sign byte
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    /** Parses an uncompressed P-256 point and verifies it lies on the curve. */
    private static ECPublicKey decodePublicKey(byte[] raw) throws GeneralSecurityException {
        if (raw.length != PUBLIC_KEY_BYTES || raw[0] != 0x04) {
            throw new InvalidKeyException("not an uncompressed P-256 point");
        }
        ECPoint point =
                new ECPoint(
                        new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                        new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        ECParameterSpec spec = P256Holder.SPEC;
        EllipticCurve curve = spec.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            throw new InvalidKeyException("coordinate out of range");
        }
        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        if (!lhs.equals(rhs)) {
            throw new InvalidKeyException("point is not on the curve");
        }
        return (ECPublicKey)
                KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }
}
