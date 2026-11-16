package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

public class DirectGroupPolicyTest {
    @Test
    public void groupPresentAtStartupIsNeverClaimedEvenIfPeerAdvertisesOffgrid() {
        DirectGroupPolicy policy = new DirectGroupPolicy("");
        assertFalse(policy.observe("external", true));
        assertFalse(policy.mayRemove("external"));
        assertFalse(policy.mayDetach("external"));
        assertFalse(policy.canConnect());
    }

    @Test
    public void initiatedGroupCanBeRemovedOnlyWhileItsExactFingerprintMatches() {
        DirectGroupPolicy policy = new DirectGroupPolicy("");
        policy.empty();
        assertTrue(policy.beginConnect());
        policy.requestAccepted();
        assertTrue(policy.observe("ours", false));
        assertTrue(policy.mayRemove("ours"));
        assertFalse(policy.mayRemove("replacement"));
        assertFalse(policy.observe("replacement", true));
        policy.empty();
        assertFalse(policy.mayRemove("ours"));
    }

    @Test
    public void incomingOffgridGroupCanCarryDataButIsNotAutomaticallyRemoved() {
        DirectGroupPolicy policy = new DirectGroupPolicy("");
        policy.empty();
        assertFalse(policy.observe("incoming", false));
        assertTrue(policy.observe("incoming", true));
        assertTrue(policy.mayDetach("incoming"));
        assertFalse(policy.mayRemove("incoming"));
    }

    @Test
    public void restoreMatchesOnlyInitialSavedGroupWithoutAutomaticCleanup() {
        DirectGroupPolicy creator = new DirectGroupPolicy("saved");
        assertTrue(creator.observe("saved", false));
        assertFalse(creator.mayRemove("saved"));
        DirectGroupPolicy joined = new DirectGroupPolicy("saved");
        assertTrue(joined.observe("saved", false));
        assertFalse(joined.mayRemove("saved"));
        DirectGroupPolicy absent = new DirectGroupPolicy("saved");
        absent.empty();
        assertFalse(absent.observe("saved", false));
    }

    @Test
    public void failedConnectAndUnidentifiedGroupNeverGainCleanupAuthority() {
        DirectGroupPolicy policy = new DirectGroupPolicy("");
        assertFalse(policy.beginConnect());
        policy.empty();
        assertTrue(policy.beginConnect());
        policy.connectionFailed();
        assertFalse(policy.pending());
        assertFalse(policy.observe("unknown", false));
        assertFalse(policy.mayRemove("unknown"));
    }

    @Test
    public void pendingRequestWithoutPlatformAcceptanceCannotClaimAutomaticCleanup() {
        DirectGroupPolicy policy = new DirectGroupPolicy("");
        policy.empty();
        assertTrue(policy.beginConnect());
        assertFalse(policy.observe("early", false));
        assertTrue(policy.observe("early", true));
        assertFalse(policy.mayRemove("early"));
        policy.connectionFailed();
        assertFalse(policy.mayRemove("early"));
    }
}
