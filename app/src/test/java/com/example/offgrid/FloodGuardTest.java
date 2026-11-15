package com.example.offgrid;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class FloodGuardTest {
    @Test public void peerBurstIsLimitedAndRefillsWithoutSleeping() {
        AtomicLong now = new AtomicLong(1000);
        FloodGuard guard = new FloodGuard(now::get);
        for (int i = 0; i < 30; i++) assertTrue(guard.allowPacket("peer"));
        assertFalse(guard.allowPacket("peer"));
        now.addAndGet(1000);
        assertTrue(guard.allowPacket("peer"));
    }

    @Test public void rotatingBluetoothAddressesCannotBypassTheGlobalBudget() {
        FloodGuard guard = new FloodGuard(() -> 1000);
        for (int i = 0; i < 60; i++) assertTrue(guard.allowPacket("peer" + i));
        assertFalse(guard.allowPacket("new-peer"));
    }

    @Test public void senderBudgetFollowsAuthenticatedIdentityAcrossRelays() {
        FloodGuard guard = new FloodGuard(() -> 1000);
        for (int i = 0; i < 12; i++) assertTrue(guard.allowSender("sender"));
        assertFalse(guard.allowSender("sender"));
        assertTrue(guard.allowSender("another-sender"));
    }

    @Test public void frameFloodAndMonotonicClockRollbackCannotMintTokens() {
        AtomicLong now = new AtomicLong(1000);
        FloodGuard guard = new FloodGuard(now::get);
        for (int i = 0; i < 240; i++) assertTrue(guard.allowFrame("peer"));
        assertFalse(guard.allowFrame("peer"));
        now.set(0);
        assertFalse(guard.allowFrame("peer"));
        now.set(2000);
        assertTrue(guard.allowFrame("peer"));
    }
}
