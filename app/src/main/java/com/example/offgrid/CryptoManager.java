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
 * Device identity, contact keys and end-to-end encryption for private mesh messages.
 *
 * <h3>Identity</h3>
 * Each install owns a long-term P-256 key pair. The <b>device ID</b> is the first 16 bytes of
 * SHA-256(public key) as 32 hex characters. Because the ID is derived from the key, nobody can
 * publish a different key for somebody else's ID: a key is accepted for an ID only if its hash
 * matches. If you obtain someone's ID out-of-band (in person, QR, another app), a key heard
 * over the mesh for that ID is therefore automatically authentic.
 *
 * <p>The private key never leaves the device unencrypted: it is wrapped with an AES-256-GCM key
 * stored in the Android Keystore before being saved.
 *
 * <h3>Encryption (per message)</h3>
 * <pre>
 *   shared = ECDH(myPrivate, theirPublic)
 *   key    = HKDF-SHA256(shared, info = "offgrid-e2ee-v1|" + senderId + ">" + targetId)
 *   wire   = Base64url( iv(12) || AES-256-GCM(key, iv, plaintext, aad) )
 *   aad    = messageId | senderId | targetId | timestamp
 * </pre>
 * The key is direction-specific, and the AAD binds the ciphertext to the packet's routing
 * fields, so a relay cannot re-address or re-label a message without breaking authentication.
 * Relays never need (and never have) any key.
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

        Contact(@NonNull String id, boolean addedManually) {
            this.id = id;
            this.addedManually = addedManually;
        }
    }

    private static final class ContactRecord {
        final String keyB64;
        boolean manual;
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

    private PrivateKey privateKey;
    private byte[] publicKeyBytes;
    private String publicKeyB64;
    private String localId;

    private CryptoManager(Context appContext) {
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            loadOrCreateIdentity();
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
    public String getContactString() {
        return CONTACT_PREFIX + publicKeyB64;
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
        if (contactString == null) return null;
        String s = contactString.trim();
        if (!s.regionMatches(true, 0, CONTACT_PREFIX, 0, CONTACT_PREFIX.length())) return null;
        String keyB64 = s.substring(CONTACT_PREFIX.length()).trim();
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
        return contacts.containsKey(id);
    }

    /** Manually added contacts first, then others, most recent first. */
    @NonNull
    public synchronized List<Contact> getContacts() {
        List<Map.Entry<String, ContactRecord>> entries = new ArrayList<>(contacts.entrySet());
        Collections.sort(entries, (a, b) -> {
            if (a.getValue().manual != b.getValue().manual) return a.getValue().manual ? -1 : 1;
            return Long.compare(b.getValue().addedAt, a.getValue().addedAt);
        });
        List<Contact> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, ContactRecord> e : entries) {
            out.add(new Contact(e.getKey(), e.getValue().manual));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Public API: encryption
    // ------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} for {@code targetId}.
     *
     * @return Base64url(iv || ciphertext || tag) to put in a private {@link MeshMessage}
     * @throws IllegalArgumentException  if the plaintext is empty or too large
     * @throws GeneralSecurityException  if we have no key for the target or encryption fails
     */
    @NonNull
    public String encrypt(@NonNull String messageId, @NonNull String targetId, long timestamp,
                          @NonNull String plaintext) throws GeneralSecurityException {
        byte[] pt = plaintext.getBytes(StandardCharsets.UTF_8);
        if (pt.length == 0 || pt.length > MeshMessage.MAX_PRIVATE_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("plaintext size out of range");
        }
        ECPublicKey targetKey = getContactKey(targetId);
        byte[] key = deriveKey(targetKey, localId, targetId);
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(messageId, localId, targetId, timestamp));
            byte[] ct = cipher.doFinal(pt);

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
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
     *                                  embedded sender key does not match the sender ID
     */
    @NonNull
    public String decrypt(@NonNull MeshMessage message) throws GeneralSecurityException {
        if (!message.isPrivate() || !message.isPayloadEncrypted()
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
        if (data.length < GCM_IV_BYTES + GCM_TAG_BITS / 8 + 1) {
            throw new GeneralSecurityException("ciphertext too short");
        }

        ECPublicKey senderKey = decodePublicKey(senderKeyRaw);
        byte[] key = deriveKey(senderKey, message.getSenderId(), localId);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES));
            cipher.updateAAD(aad(message.getMessageId(), message.getSenderId(), localId,
                    message.getTimestamp()));
            byte[] pt = cipher.doFinal(data, GCM_IV_BYTES, data.length - GCM_IV_BYTES);

            registerKey(message.getSenderId(), senderKeyB64, false);
            return new String(pt, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // ------------------------------------------------------------------
    // Key derivation
    // ------------------------------------------------------------------

    private byte[] deriveKey(ECPublicKey peer, String senderId, String targetId)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(peer, true);
        byte[] shared = agreement.generateSecret();
        try {
            byte[] info = ("offgrid-e2ee-v1|" + senderId + ">" + targetId)
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
            if (!deriveId(raw).equals(claimedId)) return false;   // key does not belong to this ID
            decodePublicKey(raw);                                 // must be a valid curve point
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

    private synchronized ECPublicKey getContactKey(String id) throws GeneralSecurityException {
        ContactRecord record = contacts.get(id);
        if (record == null) throw new GeneralSecurityException("no public key for " + id);
        try {
            return decodePublicKey(Base64.decode(record.keyB64, B64_FLAGS));
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
                    contacts.put(id, new ContactRecord(
                            o.getString("k"), o.optBoolean("m", false), o.optLong("t", 0L)));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Contacts unreadable; starting empty", e);
        }
    }

    private void persistContacts() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ContactRecord> e : contacts.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("k", e.getValue().keyB64);
                o.put("m", e.getValue().manual);
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
                PrivateKey priv = KeyFactory.getInstance("EC")
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
                KeyGenerator kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                kg.init(new KeyGenParameterSpec.Builder(WRAP_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
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
        if (data.length <= GCM_IV_BYTES) throw new GeneralSecurityException("wrapped key too short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(),
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
        ECPoint point = new ECPoint(
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
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(point, spec));
    }
}