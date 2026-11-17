package com.example.offgrid;

import org.bouncycastle.crypto.generators.SCrypt;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, authenticated, bounded scrypt/AES-GCM backup. Reads OGB1; writes OGB2. */
public final class IdentityBackup {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int DEFAULT_LOG_N = 15;
    private static final int V1 = 0x4f474231, V2 = 0x4f474232;
    private static final int HEADER_V1 = 32, HEADER_V2 = 40;

    private IdentityBackup() {}

    public static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException {
        return encrypt(plain, password, DEFAULT_LOG_N);
    }

    /** logN=15..17 permits future measured cost increases without changing the file format. */
    public static byte[] encrypt(byte[] plain, char[] password, int logN)
            throws GeneralSecurityException {
        parameters(logN, 8, 1);
        if (plain.length == 0 || plain.length > MAX_BYTES - HEADER_V2 - 16)
            throw new GeneralSecurityException("Invalid backup size");
        byte[] header = new byte[HEADER_V2];
        new SecureRandom().nextBytes(header);
        ByteBuffer.wrap(header)
                .putInt(V2)
                .put((byte) 1)
                .put((byte) logN)
                .putShort((short) 8)
                .put((byte) 1)
                .put(new byte[3]);
        return crypt(true, plain, header, password, logN, 12);
    }

    public static byte[] decrypt(byte[] backup, char[] password) throws GeneralSecurityException {
        if (backup.length < HEADER_V1 + 17 || backup.length > MAX_BYTES)
            throw new GeneralSecurityException("Unsupported or oversized backup");
        int magic = ByteBuffer.wrap(backup).getInt(), length, logN, saltOffset;
        if (magic == V1) {
            length = HEADER_V1;
            logN = 15;
            saltOffset = 4;
        } else if (magic == V2) {
            length = HEADER_V2;
            saltOffset = 12;
            if (backup.length < length + 17
                    || backup[4] != 1
                    || backup[9] != 0
                    || backup[10] != 0
                    || backup[11] != 0)
                throw new GeneralSecurityException("Unsupported backup header");
            logN = backup[5] & 255;
            int r = ByteBuffer.wrap(backup, 6, 2).getShort() & 65535;
            parameters(logN, r, backup[8] & 255);
        } else throw new GeneralSecurityException("Unsupported backup version");
        return crypt(
                false,
                Arrays.copyOfRange(backup, length, backup.length),
                Arrays.copyOf(backup, length),
                password,
                logN,
                saltOffset);
    }

    private static void parameters(int logN, int r, int p) throws GeneralSecurityException {
        // Reject untrusted costs before KDF allocation. 32..128 MiB core memory, plus overhead.
        if (logN < 15 || logN > 17 || r != 8 || p != 1)
            throw new GeneralSecurityException("Unsupported or unsafe scrypt cost");
    }

    private static byte[] crypt(
            boolean encrypt, byte[] data, byte[] header, char[] password, int logN, int saltOffset)
            throws GeneralSecurityException {
        if (password.length < 12 || password.length > 1024)
            throw new GeneralSecurityException("Use a passphrase of 12–1024 characters");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] pw = new byte[encoded.remaining()];
        encoded.get(pw);
        byte[] key = null;
        try {
            key =
                    SCrypt.generate(
                            pw,
                            Arrays.copyOfRange(header, saltOffset, saltOffset + 16),
                            1 << logN,
                            8,
                            1,
                            32);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, header, saltOffset + 16, 12));
            cipher.updateAAD(header);
            byte[] result = cipher.doFinal(data);
            if (!encrypt) return result;
            byte[] out = Arrays.copyOf(header, header.length + result.length);
            System.arraycopy(result, 0, out, header.length, result.length);
            return out;
        } finally {
            Arrays.fill(pw, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
