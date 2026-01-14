package com.bun.hardcorerevival.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.bun.hardcorerevival.HardcoreRevival;
import com.bun.hardcorerevival.corpse.Corpse;
import com.bun.hardcorerevival.corpse.CorpseManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles interaction with corpses for revival
 */
public class ReviveListener implements Listener {

    private final HardcoreRevival plugin;
    private final CorpseManager corpseManager;
    private final ProtocolManager protocolManager;
    
    // Cooldown to prevent multiple clicks consuming multiple items
    private final Map<UUID, Long> reviveCooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 1000; // 1 second cooldown

    public ReviveListener(HardcoreRevival plugin) {
        this.plugin = plugin;
        this.corpseManager = plugin.getCorpseManager();
        this.protocolManager = ProtocolLibrary.getProtocolManager();

        // Register packet listener for entity interaction
        registerInteractListener();
    }

    /**
     * Register a ProtocolLib packet listener to detect clicks on fake entities
     */
    private void registerInteractListener() {
        protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                int entityId = event.getPacket().getIntegers().read(0);
                
                // Check if this is a right-click (interact)
                EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
                if (action != EnumWrappers.EntityUseAction.INTERACT && 
                    action != EnumWrappers.EntityUseAction.INTERACT_AT) {
                    return;
                }

                // Check if this entity ID belongs to a corpse
                Corpse corpse = corpseManager.getCorpseByEntityId(entityId);
                if (corpse == null) {
                    return;
                }

                // Cancel the packet (it's a fake entity)
                event.setCancelled(true);

                // Handle revival on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    handleCorpseInteraction(player, corpse);
                });
            }
        });
    }

    /**
     * Handle a player interacting with a corpse
     */
    private void handleCorpseInteraction(Player reviver, Corpse corpse) {
        // Check cooldown to prevent spam (applies to ALL interactions, not just successful ones)
        long now = System.currentTimeMillis();
        Long lastInteract = reviveCooldowns.get(reviver.getUniqueId());
        if (lastInteract != null && (now - lastInteract) < COOLDOWN_MS) {
            return; // Silent cooldown - don't spam messages
        }
        
        // Set cooldown immediately to prevent spam from multiple packets
        reviveCooldowns.put(reviver.getUniqueId(), now);
        
        // Check permission
        if (!reviver.hasPermission("hardcorerevival.revive")) {
            String message = plugin.getConfig().getString("messages.no-permission", 
                "&cYou don't have permission to do that.");
            reviver.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }

        // Check if reviver has a valid item
        ItemStack heldItem = reviver.getInventory().getItemInMainHand();
        if (!isValidRevivalItem(heldItem, corpse)) {
            String message = plugin.getConfig().getString("messages.invalid-item",
                "&cYou need a Totem of Undying or a player head to revive them!");
            message = message.replace("{player}", corpse.getPlayerName());
            reviver.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        // Perform revival
        performRevival(reviver, corpse, heldItem);
    }

    /**
     * Check if an item is valid for reviving a specific corpse
     */
    private boolean isValidRevivalItem(ItemStack item, Corpse corpse) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        // Check if it's any player head (not mob heads like zombie, skeleton, etc.)
        if (item.getType() == Material.PLAYER_HEAD) {
            // Any player head works for revival
            return true;
        }

        // Check against configured revival items
        List<String> revivalItems = plugin.getConfig().getStringList("revival-items");
        for (String itemName : revivalItems) {
            try {
                Material material = Material.valueOf(itemName.toUpperCase());
                if (item.getType() == material) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in config: " + itemName);
            }
        }

        return false;
    }

    /**
     * Perform the revival of a dead player
     */
    private void performRevival(Player reviver, Corpse corpse, ItemStack usedItem) {
        UUID deadPlayerUuid = corpse.getPlayerUuid();
        Player deadPlayer = Bukkit.getPlayer(deadPlayerUuid);
        Location reviveLocation = corpse.getLocation();
        
        // Get the entity ID BEFORE removing the corpse (for pending despawn)
        int corpseEntityId = corpse.getEntityId();

        // Consume the item if configured
        if (plugin.getConfig().getBoolean("consume-item", true)) {
            usedItem.setAmount(usedItem.getAmount() - 1);
        }

        // ALWAYS store revival location - handles edge cases like:
        // - Player on "Game Over" screen (still "online" but can't be teleported)
        // - Player disconnects before revival completes
        // - Server restart between revival and player rejoining
        if (reviveLocation != null) {
            plugin.getDeathListener().setPendingRevivalLocation(deadPlayerUuid, reviveLocation);
        }
        
        // If player is on Game Over screen (dead but online), they won't receive the despawn packet
        // Store the entity ID so we can despawn it when they respawn
        if (deadPlayer != null && deadPlayer.isOnline() && deadPlayer.isDead() && corpseEntityId > 0) {
            plugin.getDeathListener().setPendingCorpseDespawn(deadPlayerUuid, corpseEntityId);
        }

        // Remove the corpse
        corpseManager.removeCorpse(deadPlayerUuid);

        // Play effects at corpse location (use a clone since we may have modified it)
        Location effectLocation = reviveLocation != null ? reviveLocation.clone() : null;
        if (effectLocation != null && effectLocation.getWorld() != null) {
            World world = effectLocation.getWorld();
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, effectLocation.add(0, 1, 0), 100, 0.5, 1, 0.5, 0.1);
            world.playSound(effectLocation, Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
        }

        // Revive the player if they're online AND actually alive (not on Game Over screen)
        // isDead() returns true when player is on the Game Over screen
        if (deadPlayer != null && deadPlayer.isOnline() && !deadPlayer.isDead()) {
            // Teleport to corpse location (safe location)
            if (reviveLocation != null) {
                deadPlayer.teleport(reviveLocation);
            }

            // Set to survival mode
            deadPlayer.setGameMode(GameMode.SURVIVAL);

            // Set some health and food
            deadPlayer.setHealth(deadPlayer.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() / 2);
            deadPlayer.setFoodLevel(10);
            deadPlayer.setSaturation(5.0f);

            // Send messages
            String revivedMessage = plugin.getConfig().getString("messages.revived",
                "&aYou have been revived by &e{reviver}&a!");
            revivedMessage = revivedMessage.replace("{reviver}", reviver.getName());
            deadPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', revivedMessage));

            // Play sound for the revived player
            deadPlayer.playSound(deadPlayer.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
            
            // Clear the pending location since we handled it
            plugin.getDeathListener().clearPendingRevivalLocation(deadPlayerUuid);
        }

        // Send message to reviver
        String reviverMessage = plugin.getConfig().getString("messages.revived-other",
            "&aYou revived &e{player}&a!");
        reviverMessage = reviverMessage.replace("{player}", corpse.getPlayerName());
        reviver.sendMessage(ChatColor.translateAlternateColorCodes('&', reviverMessage));

        plugin.getLogger().info(reviver.getName() + " revived " + corpse.getPlayerName());
    }

    /**
     * Spawn corpses when a player changes world
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        
        // Spawn corpses in the new world after a short delay
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            corpseManager.spawnCorpsesForPlayer(player);
        }, 10L);
    }
}
