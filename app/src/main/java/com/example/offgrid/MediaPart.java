package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(
        tableName = "media_parts",
        primaryKeys = {"mediaId", "partIndex"})
public class MediaPart {
    @NonNull public String mediaId = "";
    public int partIndex;
    @NonNull public byte[] data = new byte[0];
    @NonNull public String packetId = "";
    public long sentAt;
}
