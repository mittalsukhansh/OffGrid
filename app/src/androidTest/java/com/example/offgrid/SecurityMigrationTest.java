package com.example.offgrid;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SecurityMigrationTest {
    @Rule public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), MessageDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    @Test public void migrationAddsReplayProtectionWithoutDeletingEncryptedHistoryOrQueue() throws Exception {
        String name = "migration-" + UUID.randomUUID();
        try {
            SupportSQLiteDatabase db = helper.createDatabase(name, 1);
            db.execSQL("INSERT INTO messages VALUES ('old-message', 'public', X'010203', 1, 100, 'QUEUED')");
            db.execSQL("INSERT INTO pending_messages VALUES ('old-message', X'040506', 999999, 100, 2)");
            db.close();
            db = helper.runMigrationsAndValidate(name, 2, true, MessageDatabase.MIGRATION_1_2);
            try (android.database.Cursor rows = db.query("SELECT hex(content), status FROM messages")) {
                assertTrue(rows.moveToFirst());
                assertEquals("010203", rows.getString(0));
                assertEquals("QUEUED", rows.getString(1));
            }
            try (android.database.Cursor rows = db.query("SELECT forwardCount FROM pending_messages")) {
                assertTrue(rows.moveToFirst()); assertEquals(2, rows.getInt(0));
            }
            db.close();
        } finally {
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
        }
    }
}
