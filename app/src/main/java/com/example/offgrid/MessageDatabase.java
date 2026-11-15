package com.example.offgrid;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.annotation.NonNull;

@Database(entities = {ChatRecord.class, PendingRecord.class, ReplayRecord.class, SecurityClock.class},
        version = 2, exportSchema = true)
public abstract class MessageDatabase extends RoomDatabase {
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS replay_ledger (id TEXT NOT NULL PRIMARY KEY, expiresAtMs INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_replay_ledger_expiresAtMs ON replay_ledger(expiresAtMs)");
            db.execSQL("CREATE TABLE IF NOT EXISTS security_clock (id INTEGER NOT NULL PRIMARY KEY, highWaterMs INTEGER NOT NULL)");
        }
    };
    public abstract MessageDao messages();
}
