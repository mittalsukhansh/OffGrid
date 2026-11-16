package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.net.InetAddress;

public class LanSubnetTest {
    @Test
    public void admitsOnlyPrivateIpv4PeersOnInterfaceSubnet() throws Exception {
        InetAddress local = InetAddress.getByName("192.168.12.2");
        assertTrue(LanSubnet.contains(local, 24, InetAddress.getByName("192.168.12.99")));
        assertFalse(LanSubnet.contains(local, 24, InetAddress.getByName("192.168.13.99")));
        assertFalse(LanSubnet.contains(local, 24, InetAddress.getByName("8.8.8.8")));
        assertFalse(LanSubnet.contains(local, 0, InetAddress.getByName("8.8.8.8")));
        assertFalse(LanSubnet.contains(local, 24, InetAddress.getByName("127.0.0.1")));
        assertFalse(LanSubnet.contains(local, 24, InetAddress.getByName("224.0.0.1")));
        assertFalse(LanSubnet.contains(local, 24, InetAddress.getByName("::1")));
        assertFalse(LanSubnet.contains(local, 24, null));
        assertTrue(
                LanSubnet.contains(
                        InetAddress.getByName("10.7.1.2"), 16, InetAddress.getByName("10.7.2.3")));
        assertFalse(
                LanSubnet.contains(
                        InetAddress.getByName("10.7.1.2"), 16, InetAddress.getByName("10.8.2.3")));
        assertTrue(
                LanSubnet.contains(
                        InetAddress.getByName("172.20.1.2"),
                        12,
                        InetAddress.getByName("172.31.2.3")));
    }
}
