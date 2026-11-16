package com.example.offgrid.mesh.transport;

/** Four service-data bytes in the BLE scan response, leaving the primary UUID filter intact. */
public final class CocAdvertisement {
    private CocAdvertisement() {}

    public static byte[] encode(int psm) {
        if (psm < 128 || psm > 255) throw new IllegalArgumentException("Invalid dynamic LE PSM");
        return new byte[] {0x4f, 1, (byte) (psm >>> 8), (byte) psm};
    }

    public static int decode(byte[] value) {
        if (value == null || value.length != 4 || value[0] != 0x4f || value[1] != 1) return -1;
        int psm = ((value[2] & 255) << 8) | (value[3] & 255);
        return psm >= 128 && psm <= 255 ? psm : -1;
    }
}
