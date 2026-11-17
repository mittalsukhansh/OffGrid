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

    @Test
    public void legacyFileRemainsReadable() throws Exception {
        byte[] vector;
        try (java.io.InputStream in = getClass().getResourceAsStream("/identity-backup-v1.b64")) {
            assertNotNull(in);
            vector =
                    java.util.Base64.getMimeDecoder()
                            .decode(
                                    new String(
                                            in.readAllBytes(),
                                            java.nio.charset.StandardCharsets.US_ASCII));
        }
        assertEquals(
                "legacy identity payload",
                new String(
                        IdentityBackup.decrypt(vector, password),
                        java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void v2CostsAreExplicitAuthenticatedAndBounded() throws Exception {
        byte[] encrypted = IdentityBackup.encrypt(new byte[] {7}, password);
        assertEquals(0x4f474232, java.nio.ByteBuffer.wrap(encrypted).getInt());
        assertEquals(15, encrypted[5]);
        assertEquals(8, encrypted[7]);
        assertEquals(1, encrypted[8]);
        for (int[] mutation :
                new int[][] {{4, 2}, {5, 14}, {5, 18}, {6, 127}, {7, 9}, {8, 2}, {9, 1}}) {
            byte[] bad = encrypted.clone();
            bad[mutation[0]] = (byte) mutation[1];
            assertThrows(
                    GeneralSecurityException.class, () -> IdentityBackup.decrypt(bad, password));
        }
        byte[] changedCost = encrypted.clone();
        changedCost[5] = 16;
        assertThrows(
                GeneralSecurityException.class,
                () -> IdentityBackup.decrypt(changedCost, password));
        byte[] stronger = IdentityBackup.encrypt(new byte[] {7}, password, 17);
        assertEquals(17, stronger[5]);
        assertArrayEquals(new byte[] {7}, IdentityBackup.decrypt(stronger, password));
    }
}
