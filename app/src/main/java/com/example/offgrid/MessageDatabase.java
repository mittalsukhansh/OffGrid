package com.example.offgrid;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {ChatRecord.class, PendingRecord.class}, version = 1, exportSchema = true)
public abstract class MessageDatabase extends RoomDatabase {
    public abstract MessageDao messages();
}
