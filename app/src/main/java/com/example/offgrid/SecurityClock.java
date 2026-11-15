package com.example.offgrid;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "security_clock")
public class SecurityClock {
    @PrimaryKey public int id = 1;
    public long highWaterMs;
}
