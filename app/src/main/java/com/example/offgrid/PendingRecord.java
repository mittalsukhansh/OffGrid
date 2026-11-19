package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pending_messages")
public class PendingRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public byte[] packet = new byte[0];

    @NonNull
    @androidx.room.ColumnInfo(defaultValue = "''")
    public String transferId = "";

    public long expiresAtMs;
    public long storedAtMs;
    public int forwardCount;
}
