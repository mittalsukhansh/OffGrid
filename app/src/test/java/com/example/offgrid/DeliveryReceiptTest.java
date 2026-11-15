package com.example.offgrid;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Pure JVM tests exercise immutable packet binding without Android stubs or mocked crypto. */
public class DeliveryReceiptTest {
    private final String alice = "11111111111111111111111111111111";
    private final String bob = "22222222222222222222222222222222";
    private final String charlie = "33333333333333333333333333333333";

    private MeshMessage original(String id, String target, String ciphertext) {
        return MeshMessage.createPrivate(id, alice, target, ciphertext, repeat('B', 87), 7, 123);
    }

    private MeshMessage ack(String sender, String target) {
        return MeshMessage.createAcknowledgement("receipt-id", sender, target,
                repeat('C', 40), repeat('D', 87), 456);
    }

    @Test public void receiptMatchesExactPacketAndSurvivesHopChanges() {
        MeshMessage original = original("original-id", bob, repeat('A', 40));
        DeliveryReceipt receipt = DeliveryReceipt.parse(DeliveryReceipt.payloadFor(original));
        assertTrue(receipt.matches(ack(bob, alice), original));
        assertTrue(receipt.matches(ack(bob, alice), original.withDecrementedTtl()));
    }

    @Test public void anotherPeersReceiptCannotConfirmDelivery() {
        MeshMessage original = original("original-id", bob, repeat('A', 40));
        DeliveryReceipt receipt = DeliveryReceipt.parse(DeliveryReceipt.payloadFor(original));
        assertFalse(receipt.matches(ack(charlie, alice), original));
        assertFalse(receipt.matches(ack(bob, charlie), original));
    }

    @Test public void replayCannotConfirmDifferentContentIdOrRecipient() {
        MeshMessage original = original("original-id", bob, repeat('A', 40));
        DeliveryReceipt receipt = DeliveryReceipt.parse(DeliveryReceipt.payloadFor(original));
        assertFalse(receipt.matches(ack(bob, alice), original("another-id", bob, repeat('A', 40))));
        assertFalse(receipt.matches(ack(bob, alice), original("original-id", bob, repeat('Z', 40))));
        assertFalse(receipt.matches(ack(bob, alice), original("original-id", charlie, repeat('A', 40))));
    }

    @Test public void delimiterInMessageIdIsUnambiguous() {
        MeshMessage original = original("original|id", bob, repeat('A', 40));
        DeliveryReceipt receipt = DeliveryReceipt.parse(DeliveryReceipt.payloadFor(original));
        assertEquals("original|id", receipt.originalId);
        assertTrue(receipt.matches(ack(bob, alice), original));
    }

    @Test public void malformedReferencesAreRejected() {
        for (String payload : new String[]{"", "original-id", "offgrid-receipt-v1|", 
                "offgrid-receipt-v1||" + repeat('a', 64),
                "offgrid-receipt-v1|id|" + repeat('z', 64)}) {
            try { DeliveryReceipt.parse(payload); fail("Malformed receipt accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void broadcastsDoNotHaveAnIntendedRecipientReceipt() {
        DeliveryReceipt.payloadFor(MeshMessage.create(alice, "Public message"));
    }

    private static String repeat(char value, int count) {
        char[] text = new char[count];
        Arrays.fill(text, value);
        return new String(text);
    }
}
