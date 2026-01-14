package com.bun.hardcorerevival.corpse;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.bun.hardcorerevival.util.SkinFetcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Manages player corpses - spawning NPCs, storage, and cleanup
 */
public class CorpseManager {

    private final JavaPlugin plugin;
    private final ProtocolManager protocolManager;
    private final Map<UUID, Corpse> corpses = new HashMap<>();
    private final File dataFile;
    private final Gson gson;

    // Track fake entity IDs we've used
    private static int nextEntityId = Integer.MAX_VALUE - 10000;
    
    // Store the game profiles we create so we can remove them from tab later
    private final Map<UUID, GameProfile> corpseProfiles = new HashMap<>();

    public CorpseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.dataFile = new File(plugin.getDataFolder(), "corpses.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Create a corpse for a dead player
     */
    public Corpse createCorpse(Player player, Location deathLocation) {
        // Find safe location if death was in void/lava
        Location safeLocation = findSafeLocation(deathLocation);
        
        // Create corpse data
        Corpse corpse = new Corpse(player.getUniqueId(), player.getName(), safeLocation);
        corpses.put(player.getUniqueId(), corpse);
        
        // Spawn the NPC for all online players
        spawnCorpseNPC(corpse, player);
        
        // Save to disk
        saveCorpses();
        
        return corpse;
    }

    /**
     * Remove a corpse (when player is revived or manually removed)
     */
    public void removeCorpse(UUID playerUuid) {
        Corpse corpse = corpses.remove(playerUuid);
        if (corpse != null && corpse.hasEntityId()) {
            despawnCorpseNPC(corpse);
        }
        corpseProfiles.remove(playerUuid);
        saveCorpses();
    }

    /**
     * Get a corpse by player UUID
     */
    public Corpse getCorpse(UUID playerUuid) {
        return corpses.get(playerUuid);
    }

    /**
     * Get a corpse by the NPC's entity ID
     */
    public Corpse getCorpseByEntityId(int entityId) {
        for (Corpse corpse : corpses.values()) {
            if (corpse.getEntityId() == entityId) {
                return corpse;
            }
        }
        return null;
    }

    /**
     * Check if a player has a corpse
     */
    public boolean hasCorpse(UUID playerUuid) {
        return corpses.containsKey(playerUuid);
    }

    /**
     * Get all corpses
     */
    public Collection<Corpse> getAllCorpses() {
        return corpses.values();
    }

    /**
     * Get corpse count
     */
    public int getCorpseCount() {
        return corpses.size();
    }

    /**
     * Spawn the corpse NPC using native NMS packets
     */
    public void spawnCorpseNPC(Corpse corpse, Player sourcePlayer) {
        Location loc = corpse.getLocation();
        if (loc == null || loc.getWorld() == null) {
            plugin.getLogger().warning("Cannot spawn corpse for " + corpse.getPlayerName() + " - world not loaded");
            return;
        }

        // Generate unique entity ID
        int entityId = nextEntityId--;
        corpse.setEntityId(entityId);

        // Create native Mojang GameProfile with skin
        GameProfile gameProfile = createGameProfile(corpse, sourcePlayer);
        corpseProfiles.put(corpse.getPlayerUuid(), gameProfile);

        // Send packets to all players in the same world
        for (Player viewer : loc.getWorld().getPlayers()) {
            sendSpawnPackets(viewer, corpse, entityId, gameProfile, loc);
        }

        plugin.getLogger().info("Spawned corpse NPC for " + corpse.getPlayerName() + " at " + 
            loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
    }
    
    /**
     * Spawn corpse from loaded data (no source player available)
     */
    public void spawnCorpseNPC(Corpse corpse) {
        Location loc = corpse.getLocation();
        if (loc == null || loc.getWorld() == null) {
            plugin.getLogger().warning("Cannot spawn corpse for " + corpse.getPlayerName() + " - world not loaded");
            return;
        }

        // Generate unique entity ID
        int entityId = nextEntityId--;
        corpse.setEntityId(entityId);

        // Create native Mojang GameProfile - try to get skin from offline player
        GameProfile gameProfile = createGameProfileOffline(corpse);
        corpseProfiles.put(corpse.getPlayerUuid(), gameProfile);

        // Send packets to all players in the same world
        for (Player viewer : loc.getWorld().getPlayers()) {
            sendSpawnPackets(viewer, corpse, entityId, gameProfile, loc);
        }

        plugin.getLogger().info("Spawned corpse NPC for " + corpse.getPlayerName() + " at " + 
            loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
    }

    /**
     * Create a Mojang GameProfile with skin data from an online player
     */
    private GameProfile createGameProfile(Corpse corpse, Player sourcePlayer) {
        // Use a random UUID so it doesn't conflict with the real player
        UUID npcUuid = UUID.randomUUID();
        
        // Method 1: Try NMS GameProfile (instant, no network)
        try {
            if (sourcePlayer instanceof CraftPlayer craftPlayer) {
                GameProfile nmsProfile = craftPlayer.getHandle().getGameProfile();
                if (nmsProfile != null) {
                    var propertyMap = nmsProfile.properties();
                    if (propertyMap != null) {
                        Collection<Property> textures = propertyMap.get("textures");
                        if (textures != null && !textures.isEmpty()) {
                            for (Property prop : textures) {
                                String value = prop.value();
                                String signature = prop.signature();
                                if (value != null && !value.isEmpty()) {
                                    // Use helper to create profile with skin already applied
                                    GameProfile gameProfile = SkinFetcher.createProfileWithSkin(
                                        npcUuid, corpse.getPlayerName(), value, signature);
                                    plugin.getLogger().info("Applied skin for corpse (NMS): " + corpse.getPlayerName());
                                    return gameProfile;
                                }
                            }
                        }
                    }
                    plugin.getLogger().fine("NMS profile exists but no textures for " + corpse.getPlayerName());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("NMS skin fetch error for " + corpse.getPlayerName() + ": " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
        
        // Method 2: Fetch from Mojang API using the REAL player UUID
        try {
            plugin.getLogger().info("Fetching skin from Mojang API for " + corpse.getPlayerName() + " (UUID: " + sourcePlayer.getUniqueId() + ")");
            SkinFetcher.SkinData skinData = SkinFetcher.fetchSkin(plugin, sourcePlayer.getUniqueId());
            if (skinData != null && skinData.isValid()) {
                // Use helper to create profile with skin already applied
                GameProfile gameProfile = SkinFetcher.createProfileWithSkin(
                    npcUuid, corpse.getPlayerName(), skinData);
                plugin.getLogger().info("Applied skin for corpse (Mojang API): " + corpse.getPlayerName());
                return gameProfile;
            } else {
                plugin.getLogger().warning("Mojang API returned no skin data for " + corpse.getPlayerName());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Mojang API skin fetch error for " + corpse.getPlayerName() + ": " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
        
        plugin.getLogger().warning("Could not get skin for " + corpse.getPlayerName() + " - using default Steve/Alex skin");
        // Return a profile without skin
        return SkinFetcher.createProfileWithSkin(npcUuid, corpse.getPlayerName(), (SkinFetcher.SkinData) null);
    }
    
    /**
     * Create a Mojang GameProfile for offline player (try to fetch skin from Mojang API)
     */
    private GameProfile createGameProfileOffline(Corpse corpse) {
        // If player is actually online, use the online method
        Player onlinePlayer = Bukkit.getPlayer(corpse.getPlayerUuid());
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            return createGameProfile(corpse, onlinePlayer);
        }
        
        UUID npcUuid = UUID.randomUUID();
        
        // Fetch skin from Mojang API (this is the most reliable method for offline players)
        try {
            SkinFetcher.SkinData skinData = SkinFetcher.fetchSkin(plugin, corpse.getPlayerUuid());
            if (skinData != null && skinData.isValid()) {
                GameProfile gameProfile = SkinFetcher.createProfileWithSkin(
                    npcUuid, corpse.getPlayerName(), skinData);
                plugin.getLogger().fine("Applied offline skin for corpse (via Mojang API): " + corpse.getPlayerName());
                return gameProfile;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not fetch skin from Mojang API for " + corpse.getPlayerName());
        }
        
        plugin.getLogger().fine("No textures found for " + corpse.getPlayerName() + " - using default skin");
        return SkinFetcher.createProfileWithSkin(npcUuid, corpse.getPlayerName(), (SkinFetcher.SkinData) null);
    }

    /**
     * Send spawn packets to a specific viewer using NMS
     */
    private void sendSpawnPackets(Player viewer, Corpse corpse, int entityId, 
                                   GameProfile gameProfile, Location loc) {
        try {
            CraftPlayer craftViewer = (CraftPlayer) viewer;
            ServerPlayer serverPlayer = craftViewer.getHandle();
            
            // 1. Send Player Info Add packet (adds to tab list temporarily)
            ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                gameProfile.id(),
                gameProfile,
                true, // listed
                0, // latency
                net.minecraft.world.level.GameType.SURVIVAL,
                net.minecraft.network.chat.Component.literal(corpse.getPlayerName()),
                true, // showHat
                0, // listOrder
                null // chatSession
            );
            
            ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                Collections.singletonList(entry)
            );
            serverPlayer.connection.send(infoPacket);

            // 2. Spawn Entity packet using NMS (NAMED_ENTITY_SPAWN was removed in 1.20.2+)
            ClientboundAddEntityPacket spawnPacket = new ClientboundAddEntityPacket(
                entityId,
                gameProfile.id(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getPitch(),
                loc.getYaw(),
                EntityType.PLAYER,
                0, // data
                Vec3.ZERO, // velocity
                loc.getYaw() // headYaw
            );
            serverPlayer.connection.send(spawnPacket);

            // 3. Entity Metadata packet - Set pose to swimming (horizontal)
            PacketContainer metadata = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            metadata.getIntegers().write(0, entityId);
            
            boolean useSwimmingPose = plugin.getConfig().getBoolean("corpse.use-swimming-pose", true);
            boolean glowing = plugin.getConfig().getBoolean("corpse.glowing", false);
            
            List<WrappedDataValue> dataValues = new ArrayList<>();
            
            // Entity flags (index 0) - set glowing if enabled
            byte entityFlags = 0;
            if (glowing) {
                entityFlags |= 0x40; // Glowing flag
            }
            dataValues.add(new WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte.class), entityFlags));
            
            // Pose (index 6) - SWIMMING for horizontal position
            if (useSwimmingPose) {
                dataValues.add(new WrappedDataValue(6, WrappedDataWatcher.Registry.get(
                    EnumWrappers.getEntityPoseClass()), EnumWrappers.EntityPose.SWIMMING.toNms()));
            }
            
            metadata.getDataValueCollectionModifier().write(0, dataValues);
            protocolManager.sendServerPacket(viewer, metadata);

            // 4. Remove from tab list after a short delay (so skin loads)
            final UUID profileId = gameProfile.id();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (viewer.isOnline()) {
                        CraftPlayer cp = (CraftPlayer) viewer;
                        ClientboundPlayerInfoRemovePacket removePacket = 
                            new ClientboundPlayerInfoRemovePacket(Collections.singletonList(profileId));
                        cp.getHandle().connection.send(removePacket);
                    }
                } catch (Exception e) {
                    // Player might have disconnected
                }
            }, 40L); // 2 seconds

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send spawn packets to " + viewer.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Despawn a corpse NPC for all viewers
     */
    private void despawnCorpseNPC(Corpse corpse) {
        if (!corpse.hasEntityId()) return;

        Location loc = corpse.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        for (Player viewer : loc.getWorld().getPlayers()) {
            sendDespawnPacket(viewer, corpse.getEntityId());
        }
    }

    /**
     * Send despawn packet to a viewer (private)
     */
    private void sendDespawnPacket(Player viewer, int entityId) {
        try {
            PacketContainer destroyEntity = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroyEntity.getIntLists().write(0, Collections.singletonList(entityId));
            protocolManager.sendServerPacket(viewer, destroyEntity);
        } catch (Exception e) {
            // Player might have disconnected
        }
    }
    
    /**
     * Send despawn packet to a specific player (public, for delayed despawns)
     */
    public void sendDespawnPacketToPlayer(Player player, int entityId) {
        sendDespawnPacket(player, entityId);
    }

    /**
     * Spawn all corpses for a player who just joined
     */
    public void spawnCorpsesForPlayer(Player player) {
        for (Corpse corpse : corpses.values()) {
            Location loc = corpse.getLocation();
            if (loc != null && loc.getWorld() != null && 
                loc.getWorld().equals(player.getWorld()) && corpse.hasEntityId()) {
                
                GameProfile gameProfile = corpseProfiles.get(corpse.getPlayerUuid());
                if (gameProfile == null) {
                    gameProfile = createGameProfileOffline(corpse);
                    corpseProfiles.put(corpse.getPlayerUuid(), gameProfile);
                }
                
                sendSpawnPackets(player, corpse, corpse.getEntityId(), gameProfile, loc);
            }
        }
    }

    /**
     * Remove all corpse NPCs (for plugin disable)
     */
    public void removeAllCorpseNPCs() {
        for (Corpse corpse : corpses.values()) {
            if (corpse.hasEntityId()) {
                despawnCorpseNPC(corpse);
            }
        }
    }

    /**
     * Find a safe location near the death location
     */
    public Location findSafeLocation(Location deathLocation) {
        World world = deathLocation.getWorld();
        if (world == null) return deathLocation;

        // Check if current location is safe
        if (isSafeLocation(deathLocation)) {
            return deathLocation.clone();
        }

        int searchRadius = plugin.getConfig().getInt("safe-location-search-radius", 50);

        // Search in expanding squares
        for (int radius = 1; radius <= searchRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Only check perimeter of current radius
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    int x = deathLocation.getBlockX() + dx;
                    int z = deathLocation.getBlockZ() + dz;

                    // Search vertically from death Y
                    for (int dy = 0; dy <= radius; dy++) {
                        for (int yDir : new int[]{dy, -dy}) {
                            if (dy == 0 && yDir < 0) continue;
                            
                            int y = deathLocation.getBlockY() + yDir;
                            if (y < world.getMinHeight() || y > world.getMaxHeight()) continue;

                            Location test = new Location(world, x + 0.5, y, z + 0.5, 
                                deathLocation.getYaw(), deathLocation.getPitch());
                            if (isSafeLocation(test)) {
                                return test;
                            }
                        }
                    }
                }
            }
        }

        // Last resort: spawn at world spawn
        plugin.getLogger().warning("Could not find safe location for corpse, using world spawn");
        return world.getSpawnLocation();
    }

    /**
     * Check if a location is safe for a corpse
     */
    private boolean isSafeLocation(Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        int y = location.getBlockY();
        
        // Check for void
        if (y < world.getMinHeight() + 1) return false;

        Block feet = location.getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);

        // Check block at feet - must be passable (air, water, etc. but not lava)
        if (!feet.isPassable() || feet.getType() == Material.LAVA) return false;

        // Check block below - must be solid and not dangerous
        Material belowType = below.getType();
        if (!below.isSolid()) return false;
        if (belowType == Material.LAVA || belowType == Material.MAGMA_BLOCK || 
            belowType == Material.CACTUS || belowType == Material.CAMPFIRE ||
            belowType == Material.SOUL_CAMPFIRE || belowType == Material.FIRE ||
            belowType == Material.SOUL_FIRE) {
            return false;
        }

        return true;
    }

    /**
     * Save corpses to JSON file
     */
    public void saveCorpses() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            try (Writer writer = new FileWriter(dataFile)) {
                gson.toJson(corpses, writer);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save corpses: " + e.getMessage());
        }
    }

    /**
     * Load corpses from JSON file
     */
    public void loadCorpses() {
        if (!dataFile.exists()) {
            return;
        }

        try (Reader reader = new FileReader(dataFile)) {
            Type type = new TypeToken<HashMap<UUID, Corpse>>(){}.getType();
            Map<UUID, Corpse> loaded = gson.fromJson(reader, type);
            
            if (loaded != null) {
                corpses.clear();
                corpses.putAll(loaded);
                
                // Respawn NPCs for loaded corpses
                for (Corpse corpse : corpses.values()) {
                    spawnCorpseNPC(corpse);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load corpses: " + e.getMessage());
        }
    }
}