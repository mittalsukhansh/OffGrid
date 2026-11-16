package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
            ChatRecord.class,
            SequenceRecord.class,
            PendingRecord.class,
            ReplayRecord.class,
            SecurityClock.class,
            WithdrawalRecord.class,
            MediaRecord.class,
            MediaPart.class
        },
        version = 5,
        exportSchema = true)
public abstract class MessageDatabase extends RoomDatabase {
    public static final Migration MIGRATION_4_5 =
            new Migration(4, 5) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS sequence_windows (id TEXT NOT NULL PRIMARY"
                                    + " KEY, highest INTEGER NOT NULL, bits BLOB NOT NULL)");
                }
            };
    public static final Migration MIGRATION_3_4 =
            new Migration(3, 4) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    // Existing history must not suddenly appear as unread.
                    db.execSQL("ALTER TABLE messages ADD COLUMN `read` INTEGER NOT NULL DEFAULT 1");
                }
            };
    public static final Migration MIGRATION_2_3 =
            new Migration(2, 3) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'");
                    db.execSQL(
                            "ALTER TABLE messages ADD COLUMN progress INTEGER NOT NULL DEFAULT 0");
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS withdrawals (id TEXT NOT NULL PRIMARY KEY,"
                                + " senderId TEXT NOT NULL, targetId TEXT NOT NULL, senderKey TEXT"
                                + " NOT NULL, requestId TEXT NOT NULL, request BLOB NOT NULL,"
                                + " response BLOB NOT NULL, outgoing INTEGER NOT NULL, confirmed"
                                + " INTEGER NOT NULL, timestamp INTEGER NOT NULL, createdAt INTEGER"
                                + " NOT NULL)");
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS media (id TEXT NOT NULL PRIMARY KEY,"
                                + " senderId TEXT NOT NULL, targetId TEXT NOT NULL, mime TEXT NOT"
                                + " NULL, state TEXT NOT NULL, metadata BLOB NOT NULL, file BLOB"
                                + " NOT NULL, received BLOB NOT NULL, outgoing INTEGER NOT NULL,"
                                + " byteCount INTEGER NOT NULL, chunkCount INTEGER NOT NULL,"
                                + " createdAt INTEGER NOT NULL, lastQuery INTEGER NOT NULL)");
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS media_parts (mediaId TEXT NOT NULL,"
                                + " partIndex INTEGER NOT NULL, data BLOB NOT NULL, packetId TEXT"
                                + " NOT NULL, sentAt INTEGER NOT NULL, PRIMARY"
                                + " KEY(mediaId,partIndex))");
                }
            };
    public static final Migration MIGRATION_1_2 =
            new Migration(1, 2) {
                @Override
                public void migrate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS replay_ledger (id TEXT NOT NULL PRIMARY"
                                    + " KEY, expiresAtMs INTEGER NOT NULL)");
                    db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_replay_ledger_expiresAtMs ON"
                                    + " replay_ledger(expiresAtMs)");
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS security_clock (id INTEGER NOT NULL PRIMARY"
                                    + " KEY, highWaterMs INTEGER NOT NULL)");
                }
            };

    public abstract MessageDao messages();
}
