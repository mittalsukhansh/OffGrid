package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "sequence_windows",
        indices = {@Index("senderId"), @Index({"priority", "lastUsed"})})
public class SequenceRecord {
    @PrimaryKey @NonNull public String id = "";

    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String senderId = "";

    @ColumnInfo(defaultValue = "0")
    public int priority;

    @ColumnInfo(defaultValue = "0")
    public long lastUsed;

    public long highest;
    @NonNull public byte[] bits = new byte[0];
}
