package com.example.offgrid;

import static org.junit.Assert.*;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class SecurityMigrationTest {
    @Rule
    public MigrationTestHelper helper =
            new MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    MessageDatabase.class.getCanonicalName(),
                    new FrameworkSQLiteOpenHelperFactory());

    @Test
    public void migrationAddsReplayProtectionWithoutDeletingEncryptedHistoryOrQueue()
            throws Exception {
        String name = "migration-" + UUID.randomUUID();
        try {
            SupportSQLiteDatabase db = helper.createDatabase(name, 1);
            db.execSQL(
                    "INSERT INTO messages VALUES ('old-message', 'public', X'010203', 1, 100,"
                            + " 'QUEUED')");
            db.execSQL(
                    "INSERT INTO pending_messages VALUES ('old-message', X'040506', 999999, 100,"
                            + " 2)");
            db.close();
            db = helper.runMigrationsAndValidate(name, 2, true, MessageDatabase.MIGRATION_1_2);
            try (android.database.Cursor rows =
                    db.query("SELECT hex(content), status FROM messages")) {
                assertTrue(rows.moveToFirst());
                assertEquals("010203", rows.getString(0));
                assertEquals("QUEUED", rows.getString(1));
            }
            try (android.database.Cursor rows =
                    db.query("SELECT forwardCount FROM pending_messages")) {
                assertTrue(rows.moveToFirst());
                assertEquals(2, rows.getInt(0));
            }
            db.close();
        } finally {
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
        }
    }

    @Test
    public void sharingMigrationPreservesOldMessagesAndAddsDefaults() throws Exception {
        String name = "sharing-migration-" + UUID.randomUUID();
        try {
            SupportSQLiteDatabase db = helper.createDatabase(name, 2);
            db.execSQL(
                    "INSERT INTO messages VALUES"
                            + " ('old-message','public',X'010203',1,100,'QUEUED')");
            db.close();
            db = helper.runMigrationsAndValidate(name, 3, true, MessageDatabase.MIGRATION_2_3);
            try (android.database.Cursor rows =
                    db.query("SELECT hex(content),kind,progress FROM messages")) {
                assertTrue(rows.moveToFirst());
                assertEquals("010203", rows.getString(0));
                assertEquals("text", rows.getString(1));
                assertEquals(0, rows.getInt(2));
            }
            db.close();
        } finally {
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
        }
    }

    @Test
    public void unreadMigrationKeepsHistoryAndMarksLegacyMessagesRead() throws Exception {
        String name = "unread-migration-" + UUID.randomUUID();
        try {
            SupportSQLiteDatabase db = helper.createDatabase(name, 3);
            db.execSQL(
                    "INSERT INTO messages VALUES"
                            + " ('old-message','public',X'010203',0,100,'RECEIVED','text',0)");
            db.close();
            db = helper.runMigrationsAndValidate(name, 4, true, MessageDatabase.MIGRATION_3_4);
            try (android.database.Cursor rows =
                    db.query("SELECT hex(content),`read` FROM messages")) {
                assertTrue(rows.moveToFirst());
                assertEquals("010203", rows.getString(0));
                assertEquals(1, rows.getInt(1));
            }
            db.close();
        } finally {
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
        }
    }

    @Test
    public void sequenceMigrationPreservesHistory() throws Exception {
        String name = "sequence-migration-" + UUID.randomUUID();
        try {
            SupportSQLiteDatabase db = helper.createDatabase(name, 4);
            db.execSQL(
                    "INSERT INTO messages VALUES"
                            + " ('old-message','public',X'010203',0,1,100,'RECEIVED','text',0)");
            db.close();
            db = helper.runMigrationsAndValidate(name, 5, true, MessageDatabase.MIGRATION_4_5);
            try (android.database.Cursor rows = db.query("SELECT hex(content) FROM messages")) {
                assertTrue(rows.moveToFirst());
                assertEquals("010203", rows.getString(0));
            }
            db.close();
        } finally {
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
        }
    }

    @Test
    public void replayReservationMigrationRetainsWindowBytes() throws Exception {
        String name = "replay-reservation-migration-" + UUID.randomUUID();
        String sender = "11111111111111111111111111111111";
        try {
            SupportSQLiteDatabase db = helper.createDatabase(name, 5);
            db.execSQL(
                    "INSERT INTO sequence_windows VALUES ('"
                            + sender
                            + ":22222222222222222222222222222222',42,X'01')");
            db.close();
            db = helper.runMigrationsAndValidate(name, 6, true, MessageDatabase.MIGRATION_5_6);
            try (android.database.Cursor row =
                    db.query(
                            "SELECT senderId,highest,hex(bits),priority,lastUsed FROM"
                                + " sequence_windows")) {
                assertTrue(row.moveToFirst());
                assertEquals(sender, row.getString(0));
                assertEquals(42, row.getLong(1));
                assertEquals("01", row.getString(2));
                assertEquals(0, row.getInt(3));
                assertEquals(0, row.getLong(4));
            }
            db.close();
        } finally {
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
        }
    }
}
