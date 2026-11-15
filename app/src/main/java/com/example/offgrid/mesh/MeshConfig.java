package com.example.offgrid.mesh;

import java.util.UUID;

/** Shared radio identity and timing; changing UUIDs breaks discovery with existing peers. */
public final class MeshConfig {
    public static final UUID SERVICE_UUID = UUID.fromString("b8f1a2c4-6d3e-4f70-9a15-2e8c7d4b1f93");
    public static final UUID MESSAGE_CHAR_UUID =
            UUID.fromString("b8f1a2c5-6d3e-4f70-9a15-2e8c7d4b1f93");
    public static final long PENDING_TTL_MS = 10 * 60_000L;
    public static final long MAINTENANCE_MS = 5_000;
    public static final long KEY_ANNOUNCE_INTERVAL_MS = 10 * 60_000L;
    public static final int KEY_ANNOUNCE_TTL = 4;

    private MeshConfig() {}
}
