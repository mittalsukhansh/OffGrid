package com.example.offgrid.mesh.transport;

/** Ownership/adoption policy, independent of Android callback ordering or transport trust. */
public final class DirectGroupPolicy {
    private String restored;
    private boolean initialKnown, sawEmpty, pending, accepted, creator;
    private String owned = "";

    public DirectGroupPolicy(String restored) {
        this.restored = restored == null ? "" : restored;
    }

    public void empty() {
        initialKnown = true;
        sawEmpty = true;
        pending = false;
        accepted = false;
        owned = "";
        restored = "";
        creator = false;
    }

    public boolean canConnect() {
        return initialKnown && sawEmpty && owned.isEmpty() && !pending;
    }

    public boolean beginConnect() {
        if (!canConnect()) return false;
        pending = true;
        accepted = false;
        return true;
    }

    public void requestAccepted() {
        if (pending) accepted = true;
    }

    public void connectionFailed() {
        pending = false;
        accepted = false;
    }

    public boolean pending() {
        return pending;
    }

    public boolean observe(String fingerprint, boolean offgridMember) {
        boolean restore = !initialKnown && !restored.isEmpty() && restored.equals(fingerprint);
        initialKnown = true;
        restored = "";
        if (fingerprint == null || fingerprint.isEmpty()) return false;
        if (fingerprint.equals(owned)) return true;
        if (!owned.isEmpty()) return false;
        if (restore || (sawEmpty && ((pending && accepted) || offgridMember))) {
            creator = !restore && pending && accepted;
            owned = fingerprint;
            pending = false;
            return true;
        }
        return false;
    }

    public boolean mayDetach(String fingerprint) {
        return !owned.isEmpty() && owned.equals(fingerprint);
    }

    public boolean mayRemove(String fingerprint) {
        return creator && mayDetach(fingerprint);
    }

    public boolean ownsGroup() {
        return !owned.isEmpty();
    }

    public boolean creator() {
        return creator;
    }
}
