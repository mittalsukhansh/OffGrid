package com.example.offgrid;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in release-to-release test. Never runs in ordinary debug tests. */
@RunWith(AndroidJUnit4.class)
public class UpgradeContinuityTest {
    @Test
    public void seed() throws Exception {
        assumeTrue("seed".equals(InstrumentationRegistry.getArguments().getString("upgrade")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CryptoManager crypto = CryptoManager.getInstance(context);
        SharedPreferences marker = context.getSharedPreferences("upgrade-evidence", 0);
        CryptoManager friend =
                new CryptoManager(
                        new android.content.ContextWrapper(context) {
                            @Override
                            public SharedPreferences getSharedPreferences(String name, int mode) {
                                return context.getSharedPreferences("upgrade-friend-" + name, mode);
                            }
                        });
        String friendId = crypto.importContact(friend.getContactString());
        assertNotNull(friendId);
        assertTrue(crypto.setNickname(friendId, "Upgrade friend"));
        assertTrue(crypto.setVerified(friendId, crypto.contactFingerprint(friendId), true));
        assertTrue(marker.edit().putString("friend", friendId).commit());
        MeshMessage packet =
                crypto.signPacket(
                        MeshMessage.create(crypto.getLocalId(), "Upgrade continuity sentinel"));
        MessageStore.get(context)
                .save(
                        packet,
                        packet,
                        true,
                        packet.getTimestamp(),
                        null,
                        packet.getTimestamp(),
                        packet.getTimestamp() + 600000);
        assertTrue(
                marker.edit()
                        .putString("identity", crypto.getLocalId())
                        .putString("publicKey", crypto.getPublicKeyBase64())
                        .putString("message", packet.getMessageId())
                        .putString("fingerprint", crypto.getOwnFingerprint())
                        .putInt("fixtureVersion", 2)
                        .putString("friendFingerprint", crypto.contactFingerprint(friendId))
                        .putBoolean(
                                "friendUsesAgreementKey",
                                friend.getContactString().startsWith("offgrid5:"))
                        .commit());
        assertTrue(context.getSharedPreferences("offgrid_identity", 0).edit().commit());
        assertTrue(
                context.getSharedPreferences("upgrade-friend-offgrid_identity", 0).edit().commit());
        assertEquals(
                "Upgrade continuity sentinel",
                MessageStore.get(context)
                        .decodeChat(MessageStore.get(context).dao().chat(packet.getMessageId()))
                        .message
                        .getText());
    }

    @Test
    public void verify() throws Exception {
        assumeTrue("verify".equals(InstrumentationRegistry.getArguments().getString("upgrade")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences marker = context.getSharedPreferences("upgrade-evidence", 0);
        assertNotNull(marker.getString("identity", null));
        SharedPreferences identity = context.getSharedPreferences("offgrid_identity", 0);
        assertEquals(
                "Stored public identity must survive before key loading",
                marker.getString("publicKey", null),
                identity.getString("public_key", null));
        assertTrue(
                "Wrapped identity must survive before key loading",
                identity.contains("private_key_wrapped"));
        CryptoManager crypto = CryptoManager.getInstance(context);
        assertEquals(marker.getString("identity", null), crypto.getLocalId());
        assertEquals(marker.getString("publicKey", null), crypto.getPublicKeyBase64());
        String friend = marker.getString("friend", null);
        assertEquals("Upgrade friend", crypto.nickname(friend));
        assertTrue(crypto.getContacts().stream().anyMatch(c -> c.id.equals(friend)));
        assertEquals("Use a matching baseline fixture", 2, marker.getInt("fixtureVersion", 0));
        if (marker.getBoolean("friendUsesAgreementKey", false)) {
            // An ordinary protocol-5 update must preserve the verified two-key binding.
            assertEquals(
                    marker.getString("friendFingerprint", null), crypto.contactFingerprint(friend));
            assertTrue("Unchanged verified binding must survive update", crypto.isVerified(friend));
        } else if (MeshMessage.PROTOCOL_VERSION >= 5) {
            // Protocol-4 verification covered one key and cannot verify a new agreement key.
            assertFalse(
                    "Legacy single-key trust requires reverification", crypto.isVerified(friend));
        } else {
            assertTrue(crypto.isVerified(friend));
        }
        String id = marker.getString("message", null);
        assertEquals(
                "Upgrade continuity sentinel",
                MessageStore.get(context)
                        .decodeChat(MessageStore.get(context).dao().chat(id))
                        .message
                        .getText());
    }
}
