package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Content is encrypted; identifiers, timestamps and status remain queryable metadata. */
@Entity(
        tableName = "messages",
        indices = {@Index("conversationId"), @Index("displayTimeMs")})
public class ChatRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String conversationId = "";
    @NonNull public byte[] content = new byte[0];
    public boolean outgoing;
    public long displayTimeMs;
    @NonNull public String status = "RECEIVED";

    @NonNull
    @ColumnInfo(defaultValue = "'text'")
    public String kind = "text";

    @ColumnInfo(defaultValue = "0")
    public int progress;
}
