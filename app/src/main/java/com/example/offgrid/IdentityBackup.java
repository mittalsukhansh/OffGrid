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

/**
 * OGB1: fixed scrypt parameters, salt(16), IV(12), AES-256-GCM payload. Header is authenticated.
 */
public final class IdentityBackup {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int HEADER = 32;
    private static final int MAGIC = 0x4f474231;

    private IdentityBackup() {}

    public static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException {
        if (plain.length == 0 || plain.length > MAX_BYTES - HEADER - 16)
            throw new GeneralSecurityException("Invalid backup size");
        byte[] header = new byte[HEADER];
        new SecureRandom().nextBytes(header);
        ByteBuffer.wrap(header).putInt(MAGIC);
        return crypt(true, plain, header, password);
    }

    public static byte[] decrypt(byte[] backup, char[] password) throws GeneralSecurityException {
        if (backup.length < HEADER + 17
                || backup.length > MAX_BYTES
                || ByteBuffer.wrap(backup).getInt() != MAGIC)
            throw new GeneralSecurityException("Unsupported or oversized backup");
        return crypt(
                false,
                Arrays.copyOfRange(backup, HEADER, backup.length),
                Arrays.copyOf(backup, HEADER),
                password);
    }

    private static byte[] crypt(boolean encrypt, byte[] data, byte[] header, char[] password)
            throws GeneralSecurityException {
        if (password.length < 12 || password.length > 1024)
            throw new GeneralSecurityException("Use a passphrase of 12–1024 characters");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] pw = new byte[encoded.remaining()];
        encoded.get(pw);
        byte[] key = null;
        try {
            // RFC 7914 scrypt: 32 MiB core memory, fixed N/r/p, never parsed from untrusted input.
            key = SCrypt.generate(pw, Arrays.copyOfRange(header, 4, 20), 32768, 8, 1, 32);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, header, 20, 12));
            cipher.updateAAD(header);
            byte[] result = cipher.doFinal(data);
            if (!encrypt) return result;
            byte[] out = Arrays.copyOf(header, HEADER + result.length);
            System.arraycopy(result, 0, out, HEADER, result.length);
            return out;
        } finally {
            Arrays.fill(pw, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
