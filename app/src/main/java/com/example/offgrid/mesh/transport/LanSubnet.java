package com.example.offgrid.mesh.transport;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Restrict LAN dialing/admission to private IPv4 peers on the selected interface subnet. */
public final class LanSubnet {
    private LanSubnet() {}

    public static boolean contains(InetAddress local, int prefixLength, InetAddress peer) {
        if (!(local instanceof Inet4Address)
                || !(peer instanceof Inet4Address)
                || !local.isSiteLocalAddress()
                || !peer.isSiteLocalAddress()
                || prefixLength < 8
                || prefixLength > 32) return false;
        byte[] a = local.getAddress(), b = peer.getAddress();
        int bits = prefixLength;
        for (int i = 0; i < 4; i++) {
            int mask = bits >= 8 ? 255 : bits <= 0 ? 0 : (255 << (8 - bits)) & 255;
            if ((a[i] & mask) != (b[i] & mask)) return false;
            bits -= 8;
        }
        return true;
    }
}
