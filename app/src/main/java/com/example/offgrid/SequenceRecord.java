package com.example.offgrid;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sequence_windows")
public class SequenceRecord {
    @PrimaryKey @NonNull public String id = "";
    public long highest;
    @NonNull public byte[] bits = new byte[0];
}
