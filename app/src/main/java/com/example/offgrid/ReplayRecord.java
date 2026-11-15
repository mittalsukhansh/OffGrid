package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "replay_ledger", indices = @Index("expiresAtMs"))
public class ReplayRecord {
    @PrimaryKey @NonNull public String id = "";
    public long expiresAtMs;
}
