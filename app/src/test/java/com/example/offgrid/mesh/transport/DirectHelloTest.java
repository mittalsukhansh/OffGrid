package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.*;

public class DirectHelloTest {
    @Test
    public void discoveryHelloRoundTripsWithoutAnyIpOrContactIdentityInTheFrame() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DirectHello.write(new DataOutputStream(bytes));
        assertEquals(8, bytes.size());
        DirectHello.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(DirectHello.PORT > 1024 && DirectHello.PORT < 65536);
    }

    @Test
    public void wrongMagicVersionAndTruncatedHelloAreRejected() throws Exception {
        for (int[] values : new int[][] {{0, 1}, {DirectHello.MAGIC, 2}}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(values[0]);
            out.writeInt(values[1]);
            assertThrows(
                    IOException.class,
                    () ->
                            DirectHello.read(
                                    new DataInputStream(
                                            new ByteArrayInputStream(bytes.toByteArray()))));
        }
        assertThrows(
                EOFException.class,
                () -> DirectHello.read(new DataInputStream(new ByteArrayInputStream(new byte[3]))));
    }
}
