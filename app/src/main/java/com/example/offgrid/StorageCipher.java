package com.example.offgrid;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Independent, non-exportable device key. Never falls back to plaintext storage. */
public final class StorageCipher {
    private static final String ALIAS = "offgrid_message_storage_v1";
    private final SecretKey key;

    public StorageCipher() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        key = (SecretKey) store.getKey(ALIAS, null);
    }

    public byte[] encrypt(String context, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] result = new byte[1 + 12 + encrypted.length];
        result[0] = 1; // storage format version
        System.arraycopy(cipher.getIV(), 0, result, 1, 12);
        System.arraycopy(encrypted, 0, result, 13, encrypted.length);
        return result;
    }

    public byte[] decrypt(String context, byte[] encrypted) throws GeneralSecurityException {
        if (encrypted.length < 29 || encrypted[0] != 1) {
            throw new GeneralSecurityException("Unsupported or corrupt stored message");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(encrypted, 13, encrypted.length - 13);
    }
}
