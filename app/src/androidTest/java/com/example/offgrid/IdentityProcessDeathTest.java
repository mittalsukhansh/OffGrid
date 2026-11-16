package com.example.offgrid;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Host-orchestrated real process death. Both methods skip in the ordinary test suite. */
@RunWith(AndroidJUnit4.class)
public class IdentityProcessDeathTest {
    private Context isolatedContext() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String token = InstrumentationRegistry.getArguments().getString("crashToken", "");
        assertTrue("Fresh host-generated token required", token.matches("[0-9a-f]{32}"));
        return new ContextWrapper(target) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences("crash-" + token + "-" + name, mode);
            }
        };
    }

    @Test
    public void createAndKill() {
        assumeTrue("kill".equals(InstrumentationRegistry.getArguments().getString("crashMode")));
        Context context = isolatedContext();
        assertTrue(context.getSharedPreferences("offgrid_identity", 0).getAll().isEmpty());
        CryptoManager crypto = new CryptoManager(context);
        Bundle proof = new Bundle();
        proof.putString("crashProtocol", Integer.toString(MeshMessage.PROTOCOL_VERSION));
        proof.putString("crashId", crypto.getLocalId());
        proof.putString("crashKey", crypto.getPublicKeyBase64());
        proof.putString("crashFingerprint", crypto.getOwnFingerprint());
        proof.putString(
                "crashEpoch",
                context.getSharedPreferences("offgrid_identity", 0).getString("send_epoch", ""));
        proof.putString("crashPid", Integer.toString(Process.myPid()));
        // Report only public data to the host. No fixture commit(), message signing, Activity
        // lifecycle stop, or graceful shutdown is allowed between construction and SIGKILL.
        InstrumentationRegistry.getInstrumentation().sendStatus(0, proof);
        Process.killProcess(Process.myPid());
    }

    @Test
    public void verifyAfterKill() throws Exception {
        assumeTrue("verify".equals(InstrumentationRegistry.getArguments().getString("crashMode")));
        Bundle expected = InstrumentationRegistry.getArguments();
        assertNotEquals(
                "A separate OS process is required",
                expected.getString("crashPid"),
                Integer.toString(Process.myPid()));
        Context context = isolatedContext();
        SharedPreferences saved = context.getSharedPreferences("offgrid_identity", 0);
        assertEquals(
                "Persisted signing public key",
                expected.getString("crashKey"),
                saved.getString("public_key", null));
        assertTrue("Persisted wrapped signing key", saved.contains("private_key_wrapped"));
        if (MeshMessage.PROTOCOL_VERSION >= 5) {
            assertTrue("Persisted agreement key", saved.contains("agreement_wrapped"));
            assertEquals(
                    "Persisted sender epoch",
                    expected.getString("crashEpoch"),
                    saved.getString("send_epoch", null));
        }
        CryptoManager crypto = new CryptoManager(context);
        assertEquals(
                "Identity must survive SIGKILL",
                expected.getString("crashId"),
                crypto.getLocalId());
        assertEquals(expected.getString("crashKey"), crypto.getPublicKeyBase64());
        assertEquals(expected.getString("crashFingerprint"), crypto.getOwnFingerprint());
        MeshMessage signed =
                crypto.signPacket(MeshMessage.create(crypto.getLocalId(), "Restart proof"));
        assertTrue("Reloaded private signing key must match", crypto.verifyPacket(signed));
        assertEquals(expected.getString("crashEpoch"), signed.toJson().optString("epoch", ""));
        Bundle proof = new Bundle();
        proof.putString("reopenedPid", Integer.toString(Process.myPid()));
        InstrumentationRegistry.getInstrumentation().sendStatus(0, proof);
    }
}
