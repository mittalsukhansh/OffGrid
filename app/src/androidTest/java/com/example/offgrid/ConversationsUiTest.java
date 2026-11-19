package com.example.offgrid;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;

/** UI regression: opening one conversation must not render or mark another contact's messages. */
@RunWith(AndroidJUnit4.class)
public class ConversationsUiTest {
    @Test
    public void asksForDeviceNameAndShowsItInMyContact() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CryptoManager crypto = CryptoManager.getInstance(context);
        String previous = crypto.displayName();
        context.getSharedPreferences("offgrid_identity", 0).edit().remove("display_name").commit();
        try (ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class)) {
            onView(withHint("Your name")).perform(replaceText("Trail teammate"));
            onView(withText("Save name")).perform(click());
            assertEquals("Trail teammate", crypto.displayName());
            onView(withId(R.id.contactButton)).perform(click());
            onView(withText(containsString("Name: Trail teammate"))).check(matches(isDisplayed()));
            onView(withText("Close")).perform(click());
        } finally {
            if (!previous.isEmpty()) crypto.setDisplayName(previous);
        }
    }

    @Test
    public void opensSeparateChatsAndNetworkDiagnosticsWithoutStartingRadios() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CryptoManager.getInstance(context).setDisplayName("UI test device");
        MessageStore store = MessageStore.get(context);
        String alice = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", bob = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String me = CryptoManager.getInstance(context).getLocalId();
        String token = UUID.randomUUID().toString().substring(0, 8);
        MeshMessage first = save(store, alice, me, "Alice only " + token);
        MeshMessage second = save(store, bob, me, "Bob only " + token);
        try (ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class)) {
            eventually(() ->
                    onData(
                                    allOf(
                                            instanceOf(CharSequence.class),
                                            hasToString(
                                                    containsString(
                                                            CryptoManager.formatId(alice, 8)))))
                            .inAdapterView(withId(R.id.conversationList))
                            .perform(click()));
            eventually(() -> onView(withText("Alice only " + token)).check(matches(isDisplayed())));
            onView(withText("Bob only " + token)).check(doesNotExist());
            onView(withId(R.id.hopText)).check(matches(withText(startsWith("TTL "))));
            onView(withId(R.id.sendButton)).check(matches(withContentDescription("Send message")));
            eventually(() -> assertTrue(store.dao().chat(first.getMessageId()).read));
            assertFalse(store.dao().chat(second.getMessageId()).read);
            scenario.recreate();
            eventually(() -> onView(withText("Alice only " + token)).check(matches(isDisplayed())));
            androidx.test.espresso.Espresso.pressBack();
            eventually(() -> onView(withId(R.id.conversationList)).check(matches(isDisplayed())));
            eventually(() ->
                    onData(
                                    allOf(
                                            instanceOf(CharSequence.class),
                                            hasToString(
                                                    containsString(
                                                            CryptoManager.formatId(bob, 8)))))
                            .inAdapterView(withId(R.id.conversationList))
                            .perform(click()));
            eventually(() -> onView(withText("Bob only " + token)).check(matches(isDisplayed())));
            onView(withText("Alice only " + token)).check(doesNotExist());
            onView(withId(R.id.diagnosticsButton)).perform(click());
            eventually(() ->
                    onView(withText(containsString("Mesh stopped. Start the mesh to inspect")))
                            .check(matches(isDisplayed())));
            onView(withText("Wi-Fi Direct · no access point")).perform(click());
            eventually(() -> onView(withText("Enable Wi-Fi Direct")).check(matches(isDisplayed())));
            onView(withText("Find nearby OffGrid phones")).check(matches(isDisplayed()));
            onView(withText("Close")).perform(click());
        } finally {
            store.database
                    .getOpenHelper()
                    .getWritableDatabase()
                    .execSQL(
                            "DELETE FROM messages WHERE id IN (?,?)",
                            new Object[] {first.getMessageId(), second.getMessageId()});
        }
    }

    @Test
    public void attachmentControlsPauseAcrossRotationResumeAndCancelWithoutStartingRadios()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CryptoManager crypto = CryptoManager.getInstance(context);
        crypto.setDisplayName("Transfer UI test");
        MessageStore store = MessageStore.get(context);
        String target = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        MeshMessage wire =
                MeshMessage.createPrivate(
                        MeshMessage.newId(crypto.getLocalId()),
                        crypto.getLocalId(),
                        target,
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        crypto.getPublicKeyBase64(),
                        7,
                        now);
        store.save(wire, wire.withPlaintext("Photo test"), true, now, wire, now, now + 600_000);
        MediaRecord record = new MediaRecord();
        record.id = wire.getMessageId();
        record.senderId = crypto.getLocalId();
        record.targetId = target;
        record.mime = "image/jpeg";
        record.outgoing = true;
        record.byteCount = 1000;
        record.chunkCount = 2;
        record.createdAt = now;
        store.dao().putMedia(record);
        store.dao().linkTransfer(record.id, record.id);
        store.dao().mediaStatus(record.id, "image", 0, "QUEUED");
        try (ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class)) {
            eventually(() ->
                    onData(
                                    allOf(
                                            instanceOf(CharSequence.class),
                                            hasToString(
                                                    containsString(
                                                            CryptoManager.formatId(target, 8)))))
                            .inAdapterView(withId(R.id.conversationList))
                            .perform(click()));
            eventually(() -> onView(withId(R.id.transferProgress)).check(matches(isDisplayed())));
            onView(withId(R.id.messageDetails)).perform(click());
            eventually(() ->
                    onView(withText(containsString("Mesh stopped.")))
                            .check(matches(isDisplayed())));
            onView(withText("Controls")).perform(click());
            onView(withText("Pause sending")).perform(click());
            eventually(() -> assertEquals("PAUSED", store.dao().chat(record.id).status));
            scenario.recreate();
            eventually(() -> onView(withId(R.id.messageDetails)).perform(click()));
            eventually(() -> onView(withText("Controls")).perform(click()));
            onView(withText("Resume sending")).perform(click());
            eventually(() -> assertEquals("QUEUED", store.dao().chat(record.id).status));
            onView(withId(R.id.messageDetails)).perform(click());
            eventually(() -> onView(withText("Controls")).perform(click()));
            onView(withText("Cancel local sending")).perform(click());
            onView(withText("Cancel local sending?")).check(matches(isDisplayed()));
            onView(withText("Continue")).perform(click());
            eventually(() -> assertEquals("CANCELLED", store.dao().chat(record.id).status));
            assertTrue(store.dao().transferPacketIds(record.id).isEmpty());
        } finally {
            store.database.runInTransaction(() -> {
                store.dao().deleteTransferPackets(record.id);
                store.database
                        .getOpenHelper()
                        .getWritableDatabase()
                        .execSQL("DELETE FROM media WHERE id = ?", new Object[] {record.id});
                store.database
                        .getOpenHelper()
                        .getWritableDatabase()
                        .execSQL("DELETE FROM messages WHERE id = ?", new Object[] {record.id});
            });
        }
    }

    private MeshMessage save(MessageStore store, String sender, String me, String text)
            throws Exception {
        long now = System.currentTimeMillis();
        MeshMessage wire =
                MeshMessage.createPrivate(
                        MeshMessage.newId(sender),
                        sender,
                        me,
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                        7,
                        now);
        store.save(wire, wire.withPlaintext(text), false, now, null, now, now + 600000);
        return wire;
    }

    private void eventually(Runnable assertion) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 10000;
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError | RuntimeException error) {
                if (android.os.SystemClock.elapsedRealtime() >= until) throw error;
                Thread.sleep(100);
            }
        }
    }
}
