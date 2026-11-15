package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Durable deletion marker. It deliberately outlives the message and replay window. */
@Entity(tableName = "withdrawals")
public class WithdrawalRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String senderId = "";
    @NonNull public String targetId = "";
    @NonNull public String senderKey = "";
    @NonNull public String requestId = "";
    @NonNull public byte[] request = new byte[0];
    @NonNull public byte[] response = new byte[0];
    public boolean outgoing;
    public boolean confirmed;
    public long timestamp;
    public long createdAt;
}
