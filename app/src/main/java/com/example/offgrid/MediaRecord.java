package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "media")
public class MediaRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String senderId = "";
    @NonNull public String targetId = "";
    @NonNull public String mime = "";
    @NonNull public String state = "OFFERED";
    @NonNull public byte[] metadata = new byte[0];
    @NonNull public byte[] file = new byte[0];
    @NonNull public byte[] received = new byte[0];
    public boolean outgoing;
    public int byteCount;
    public int chunkCount;
    public long createdAt;
    public long lastQuery;
}
