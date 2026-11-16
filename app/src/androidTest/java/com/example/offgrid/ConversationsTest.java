package com.example.offgrid;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.*;
import org.junit.runner.RunWith;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ConversationsTest {
    Context context;
    MessageDatabase db;
    MessageStore store;
    String name;
    final String alice = "11111111111111111111111111111111";
    final String bob = "22222222222222222222222222222222";
    final String me = "33333333333333333333333333333333";

    @Before
    public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "conversations-" + UUID.randomUUID();
        reopen();
    }

    void reopen() throws Exception {
        if (db != null) db.close();
        db = Room.databaseBuilder(context, MessageDatabase.class, name).build();
        store = new MessageStore(db, new StorageCipher());
    }

    @After
    public void cleanup() {
        db.close();
        context.deleteDatabase(name);
    }

    MeshMessage incoming(String sender, long time) throws Exception {
        MeshMessage wire =
                MeshMessage.createPrivate(
                        MeshMessage.newId(sender),
                        sender,
                        me,
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                        7,
                        time);
        store.save(
                wire,
                wire.withPlaintext("Private chat with " + sender),
                false,
                time,
                null,
                time,
                time + 600000);
        return wire;
    }

    @Test
    public void historiesAreSeparateAndUnreadCountsSurviveRestartAndSelectiveRead()
            throws Exception {
        MeshMessage first = incoming(alice, 100),
                second = incoming(alice, 200),
                other = incoming(bob, 300);
        MeshMessage broadcast = MeshMessage.create(bob, "Public message");
        store.save(broadcast, broadcast, false, 400, null, 400, 600000);
        List<ChatRecord> aliceRows = value(store.dao().observeConversation(alice));
        assertEquals(2, aliceRows.size());
        assertEquals(second.getMessageId(), aliceRows.get(0).id);
        for (ChatRecord row : aliceRows) assertEquals(alice, row.conversationId);
        assertEquals(2, unread(alice));
        assertEquals(1, unread(bob));
        assertEquals(1, unread("public"));
        // Opening Alice cannot consume Bob's unread message, even if its ID is supplied.
        store.dao().markRead(alice, Arrays.asList(first.getMessageId(), other.getMessageId()));
        // A new arrival racing the rendered snapshot must stay unread.
        incoming(alice, 500);
        reopen();
        assertEquals(2, unread(alice));
        assertEquals(1, unread(bob));
        assertEquals(1, unread("public"));
        assertTrue(store.dao().chat(first.getMessageId()).read);
        assertFalse(store.dao().chat(other.getMessageId()).read);
        assertEquals(alice, store.dao().conversations().get(0).conversationId);
    }

    @Test
    public void outgoingWithdrawnAndIncompleteMediaDoNotInflateUnreadCounts() throws Exception {
        MeshMessage outgoing =
                MeshMessage.createPrivate(
                        MeshMessage.newId(me),
                        me,
                        alice,
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                        7,
                        100);
        store.save(outgoing, outgoing.withPlaintext("Mine"), true, 100, null, 100, 600000);
        MeshMessage media = incoming(alice, 200);
        store.dao().mediaStatus(media.getMessageId(), "image", 50, "RECEIVING");
        assertEquals(0, unread(alice));
        store.dao().markRead(alice, Collections.singletonList(media.getMessageId()));
        assertFalse(store.dao().chat(media.getMessageId()).read);
        store.dao().mediaStatus(media.getMessageId(), "image", 100, "READY");
        assertEquals(1, unread(alice));
        store.dao().replaceWithdrawn(media.getMessageId(), new byte[] {1}, "UNSENT");
        assertEquals(0, unread(alice));
    }

    int unread(String id) {
        for (ConversationSummary row : store.dao().conversations())
            if (row.conversationId.equals(id)) return row.unread;
        return 0;
    }

    <T> T value(LiveData<T> data) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        Observer<T> observer =
                rows -> {
                    result.set(rows);
                    ready.countDown();
                };
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> data.observeForever(observer));
        try {
            assertTrue("Room query timed out", ready.await(10, TimeUnit.SECONDS));
            return result.get();
        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(() -> data.removeObserver(observer));
        }
    }
}
