package com.bun.hardcorerevival.corpse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Represents a player's corpse data
 */
public class Corpse {

    private final UUID playerUuid;
    private final String playerName;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final long deathTime;
    
    // Runtime-only field, not saved to JSON
    private transient int entityId = -1;

    public Corpse(UUID playerUuid, String playerName, Location location) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.worldName = location.getWorld().getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
        this.deathTime = System.currentTimeMillis();
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public Location getLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    public String getWorldName() {
        return worldName;
    }

    public long getDeathTime() {
        return deathTime;
    }

    public int getEntityId() {
        return entityId;
    }

    public void setEntityId(int entityId) {
        this.entityId = entityId;
    }

    public boolean hasEntityId() {
        return entityId != -1;
    }
}
