package com.example.offgrid;

import static org.junit.Assert.*;

import org.junit.Test;

public class NicknamePolicyTest {
    @Test
    public void nicknamesNormalizeWhitespaceAndCanBeCleared() {
        assertEquals("Alex Smith", CryptoManager.normalizeNickname("  Alex   Smith  "));
        assertEquals("", CryptoManager.normalizeNickname(null));
        assertEquals("", CryptoManager.normalizeNickname("  "));
        assertEquals("Chris", CryptoManager.normalizeNickname("Chris"));
    }

    @Test
    public void oversizedAndControlCharacterLabelsAreRejected() {
        for (String value : new String[] {"a".repeat(41), "name" + (char) 1}) {
            try {
                CryptoManager.normalizeNickname(value);
                fail("Invalid nickname accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
    }
}
