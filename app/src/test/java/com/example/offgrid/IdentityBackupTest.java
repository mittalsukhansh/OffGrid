package com.example.offgrid;

import static org.junit.Assert.*;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

public class IdentityBackupTest {
    private final char[] password = "correct horse long phrase".toCharArray();

    @Test
    public void backupRoundTripAndRandomSalt() throws Exception {
        byte[] plain = "identity and contacts".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] first = IdentityBackup.encrypt(plain, password);
        assertFalse(Arrays.equals(first, IdentityBackup.encrypt(plain, password)));
        assertArrayEquals(plain, IdentityBackup.decrypt(first, password));
        first[first.length - 1] ^= 1;
        try {
            IdentityBackup.decrypt(first, password);
            fail("Tampering accepted");
        } catch (GeneralSecurityException expected) {
        }
    }

    @Test
    public void wrongPasswordAndUntrustedSizeFailClosed() throws Exception {
        byte[] backup = IdentityBackup.encrypt(new byte[] {1}, password);
        try {
            IdentityBackup.decrypt(backup, "incorrect long password".toCharArray());
            fail();
        } catch (GeneralSecurityException expected) {
        }
        try {
            IdentityBackup.decrypt(new byte[IdentityBackup.MAX_BYTES + 1], password);
            fail();
        } catch (GeneralSecurityException expected) {
        }
    }

    @Test
    public void rfc7914ScryptVector() {
        byte[] result =
                org.bouncycastle.crypto.generators.SCrypt.generate(
                        new byte[0], new byte[0], 16, 1, 1, 64);
        StringBuilder hex = new StringBuilder();
        for (byte b : result) hex.append(String.format("%02x", b & 255));
        assertEquals(
                "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
                hex.toString());
    }

    @Test
    public void minimumPassphraseIsEnforcedByTheBackupApi() throws Exception {
        char[] shortPhrase = "12345678901".toCharArray();
        assertThrows(
                GeneralSecurityException.class,
                () -> IdentityBackup.encrypt(new byte[] {1}, shortPhrase));
        byte[] encrypted = IdentityBackup.encrypt(new byte[] {1}, password);
        assertThrows(
                GeneralSecurityException.class,
                () -> IdentityBackup.decrypt(encrypted, shortPhrase));
    }
}
