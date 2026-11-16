package com.example.offgrid.mesh.transport;

/** Conservative initial limits, not measured radio speeds. Canonical resume blocks stay 640 B. */
public enum TransferProfile {
    GATT(640, 2500, 4),
    COC(2560, 500, 16),
    WIFI(7680, 200, 48),
    WIFI_DIRECT(7680, 200, 48);
    public final int chunkBytes, intervalMs, windowBlocks;

    TransferProfile(int chunkBytes, int intervalMs, int windowBlocks) {
        this.chunkBytes = chunkBytes;
        this.intervalMs = intervalMs;
        this.windowBlocks = windowBlocks;
    }

    public static TransferProfile ingress(String peer) {
        if (peer.startsWith("direct:")) return WIFI_DIRECT;
        if (peer.startsWith("wifi:")) return WIFI;
        if (peer.startsWith("ble:coc:")) return COC;
        return GATT;
    }
}
