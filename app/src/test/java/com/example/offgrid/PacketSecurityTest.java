package com.example.offgrid;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PacketSecurityTest {
    private final String sender = "11111111111111111111111111111111";

    @Test public void namespacedIdsCannotOccupyAnotherIdentitysNamespace() {
        MeshMessage message = MeshMessage.create(sender, "Hello");
        assertTrue(PacketSecurity.namespaced(message));
        MeshMessage different = MeshMessage.createPrivate(message.getMessageId(),
                "22222222222222222222222222222222", sender, repeat('A', 40), repeat('B', 87), 7, 1);
        assertFalse(PacketSecurity.namespaced(different));
    }

    @Test public void freshnessRejectsOldAndFuturePackets() {
        MeshMessage message = MeshMessage.create(sender, "Hello");
        long time = message.getTimestamp();
        assertTrue(PacketSecurity.fresh(message, time));
        assertFalse(PacketSecurity.fresh(message, time + PacketSecurity.MAX_AGE_MS + 1));
        assertFalse(PacketSecurity.fresh(message, time - PacketSecurity.CLOCK_SKEW_MS - 1));
    }

    @Test public void signingBytesBindContentButPermitRelayHopChanges() {
        MeshMessage message = MeshMessage.create(sender, "Hello");
        assertArrayEquals(PacketSecurity.signingBytes(message),
                PacketSecurity.signingBytes(message.withDecrementedTtl()));
        assertFalse(Arrays.equals(PacketSecurity.signingBytes(message),
                PacketSecurity.signingBytes(MeshMessage.create(sender, "Changed"))));
    }

    private static String repeat(char c, int count) {
        char[] text = new char[count]; Arrays.fill(text, c); return new String(text);
    }
}
