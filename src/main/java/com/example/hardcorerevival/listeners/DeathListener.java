package com.bun.hardcorerevival.listeners;

import com.bun.hardcorerevival.HardcoreRevival;
import com.bun.hardcorerevival.corpse.Corpse;
import com.bun.hardcorerevival.corpse.CorpseManager;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles player death events for the hardcore revival system
 */
public class DeathListener implements Listener {

    private final HardcoreRevival plugin;
    private final CorpseManager corpseManager;
    
    // Store revival locations for players revived while offline or on Game Over screen
    private final Map<UUID, Location> pendingRevivalLocations = new HashMap<>();
    
    // Store entity IDs that need to be despawned for specific players (corpse removed while they were on Game Over screen)
    private final Map<UUID, Integer> pendingCorpseDespawns = new HashMap<>();

    public DeathListener(HardcoreRevival plugin) {
        this.plugin = plugin;
        this.corpseManager = plugin.getCorpseManager();
    }
    
    /**
     * Store a revival location for a player (works for offline or on Game Over screen)
     */
    public void setPendingRevivalLocation(UUID playerUuid, Location location) {
        if (location != null) {
            pendingRevivalLocations.put(playerUuid, location.clone());
        }
    }
    
    /**
     * Clear pending revival location (called when revival is handled inline)
     */
    public void clearPendingRevivalLocation(UUID playerUuid) {
        pendingRevivalLocations.remove(playerUuid);
    }
    
    /**
     * Check if player has a pending revival location
     */
    public boolean hasPendingRevivalLocation(UUID playerUuid) {
        return pendingRevivalLocations.containsKey(playerUuid);
    }
    
    /**
     * Store an entity ID that needs to be despawned for a player when they respawn
     * (Used when corpse is removed while player is on Game Over screen)
     */
    public void setPendingCorpseDespawn(UUID playerUuid, int entityId) {
        if (entityId > 0) {
            pendingCorpseDespawns.put(playerUuid, entityId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location deathLocation = player.getLocation();

        // Check if player already has a corpse (shouldn't happen, but just in case)
        if (corpseManager.hasCorpse(player.getUniqueId())) {
            corpseManager.removeCorpse(player.getUniqueId());
        }

        // Create the corpse
        Corpse corpse = corpseManager.createCorpse(player, deathLocation);
        Location corpseLocation = corpse.getLocation();

        // Send death coordinates to the player
        String message = plugin.getConfig().getString("messages.death-coordinates",
            "&cYou died at &e{x}, {y}, {z} &cin &e{world}&c. Find someone to revive you!");
        
        message = message
            .replace("{x}", String.valueOf(corpseLocation.getBlockX()))
            .replace("{y}", String.valueOf(corpseLocation.getBlockY()))
            .replace("{z}", String.valueOf(corpseLocation.getBlockZ()))
            .replace("{world}", corpseLocation.getWorld().getName());
        
        // Send message after a short delay (after respawn)
        final String finalMessage = ChatColor.translateAlternateColorCodes('&', message);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(finalMessage);
            }
        }, 20L); // 1 second delay

        plugin.getLogger().info(player.getName() + " died at " + 
            deathLocation.getBlockX() + ", " + deathLocation.getBlockY() + ", " + deathLocation.getBlockZ() +
            " - Corpse spawned at " + corpseLocation.getBlockX() + ", " + corpseLocation.getBlockY() + ", " + corpseLocation.getBlockZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        
        // Check if there's a corpse entity that needs to be despawned for this player
        Integer pendingDespawnId = pendingCorpseDespawns.remove(player.getUniqueId());
        if (pendingDespawnId != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    corpseManager.sendDespawnPacketToPlayer(player, pendingDespawnId);
                }
            }, 5L);
        }

        // Check if player was revived (has pending revival location)
        // This handles BOTH cases:
        // 1. Player on Game Over screen who clicks "Spectate"
        // 2. Player who logged out from Game Over screen and is now respawning on login
        Location pendingLocation = pendingRevivalLocations.remove(player.getUniqueId());
        if (pendingLocation != null) {
            plugin.getLogger().info("Respawning revived player " + player.getName() + " at safe location");
            
            // SET THE RESPAWN LOCATION - this is the key fix!
            // This ensures Minecraft respawns them at the right place
            event.setRespawnLocation(pendingLocation);
            
            // Also set up their state after respawn
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setGameMode(GameMode.SURVIVAL);
                    player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() / 2);
                    player.setFoodLevel(10);
                    player.setSaturation(5.0f);
                    player.setInvulnerable(true);
                    player.sendMessage(ChatColor.GREEN + "You have been revived!");
                    
                    // Remove invulnerability after a delay
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            player.setInvulnerable(false);
                        }
                    }, 40L);
                }
            }, 1L);
            return;
        }

        // Check if player has a corpse (meaning they died and weren't revived yet)
        if (corpseManager.hasCorpse(player.getUniqueId())) {
            Corpse corpse = corpseManager.getCorpse(player.getUniqueId());
            Location corpseLocation = corpse != null ? corpse.getLocation() : null;
            
            // Set respawn at corpse location
            if (corpseLocation != null) {
                event.setRespawnLocation(corpseLocation.clone().add(0, 1.5, 0));
            }
            
            // Set to spectator mode after respawn
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setGameMode(GameMode.SPECTATOR);
                    player.sendMessage(ChatColor.GRAY + "You are now a spectator. Have another player revive your corpse!");
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // If player is dead (logged out from Game Over screen), they will respawn
        // Let onPlayerRespawn handle the pending revival location
        // Don't remove it here or the respawn handler won't find it
        if (player.isDead()) {
            plugin.getLogger().info("Player " + player.getName() + " joining while dead - will respawn");
            // Spawn corpses after they respawn
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                corpseManager.spawnCorpsesForPlayer(player);
            }, 40L);
            return;
        }
        
        // Check if there's a corpse entity that needs to be despawned for this player
        Integer pendingDespawnId = pendingCorpseDespawns.remove(player.getUniqueId());
        if (pendingDespawnId != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    corpseManager.sendDespawnPacketToPlayer(player, pendingDespawnId);
                }
            }, 5L);
        }
        
        // Check if player was revived (has pending revival location)
        // Only handle here if player is ALIVE (not going through respawn)
        Location revivalLocation = pendingRevivalLocations.remove(player.getUniqueId());
        if (revivalLocation != null) {
            plugin.getLogger().info("Player " + player.getName() + " joining alive with pending revival at " + 
                revivalLocation.getBlockX() + ", " + revivalLocation.getBlockY() + ", " + revivalLocation.getBlockZ());
            
            // Protect and set up the player
            player.setInvulnerable(true);
            player.setGameMode(GameMode.SURVIVAL);
            player.setFallDistance(0);
            player.setFireTicks(0);
            
            // Teleport on next tick
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.teleport(revivalLocation);
                    player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() / 2);
                    player.setFoodLevel(10);
                    player.setSaturation(5.0f);
                    player.sendMessage(ChatColor.GREEN + "You were revived while offline! Welcome back.");
                    
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            player.setInvulnerable(false);
                        }
                    }, 40L);
                }
            }, 1L);
            
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                corpseManager.spawnCorpsesForPlayer(player);
            }, 20L);
            
            return;
        }

        // Spawn existing corpses for this player
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            corpseManager.spawnCorpsesForPlayer(player);
        }, 20L);

        // If this player has a corpse, they need to be in spectator
        // But if they logged out alive (as spectator), onPlayerRespawn won't fire
        if (corpseManager.hasCorpse(player.getUniqueId())) {
            Corpse corpse = corpseManager.getCorpse(player.getUniqueId());
            Location corpseLocation = corpse != null ? corpse.getLocation() : null;
            
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setGameMode(GameMode.SPECTATOR);
                    
                    if (corpseLocation != null) {
                        player.teleport(corpseLocation.clone().add(0, 1.5, 0));
                        
                        String message = plugin.getConfig().getString("messages.death-coordinates",
                            "&cYou died at &e{x}, {y}, {z} &cin &e{world}&c. Find someone to revive you!");
                        
                        message = message
                            .replace("{x}", String.valueOf(corpseLocation.getBlockX()))
                            .replace("{y}", String.valueOf(corpseLocation.getBlockY()))
                            .replace("{z}", String.valueOf(corpseLocation.getBlockZ()))
                            .replace("{world}", corpseLocation.getWorld().getName());
                        
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    }
                }
            }, 1L);
        } else if (player.getGameMode() == GameMode.SPECTATOR) {
            // Fallback: player in spectator but no corpse
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && !corpseManager.hasCorpse(player.getUniqueId())) {
                    player.setGameMode(GameMode.SURVIVAL);
                    player.sendMessage(ChatColor.GREEN + "You were revived while offline! Welcome back.");
                }
            }, 10L);
        }
    }
}
