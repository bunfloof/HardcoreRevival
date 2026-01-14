package com.bun.hardcorerevival.commands;

import com.bun.hardcorerevival.HardcoreRevival;
import com.bun.hardcorerevival.corpse.Corpse;
import com.bun.hardcorerevival.corpse.CorpseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin commands for managing the revival system
 */
public class RevivalCommand implements CommandExecutor, TabCompleter {

    private final HardcoreRevival plugin;
    private final CorpseManager corpseManager;

    public RevivalCommand(HardcoreRevival plugin) {
        this.plugin = plugin;
        this.corpseManager = plugin.getCorpseManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hardcorerevival.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(ChatColor.GREEN + "HardcoreRevival configuration reloaded!");
            }
            case "list" -> listCorpses(sender);
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /revival remove <player>");
                    return true;
                }
                removeCorpse(sender, args[1]);
            }
            case "tp", "teleport" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /revival tp <player>");
                    return true;
                }
                teleportToCorpse(player, args[1]);
            }
            case "revive" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /revival revive <player>");
                    return true;
                }
                forceRevive(sender, args[1]);
            }
            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== HardcoreRevival Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/revival reload " + ChatColor.GRAY + "- Reload configuration");
        sender.sendMessage(ChatColor.YELLOW + "/revival list " + ChatColor.GRAY + "- List all corpses");
        sender.sendMessage(ChatColor.YELLOW + "/revival remove <player> " + ChatColor.GRAY + "- Remove a corpse");
        sender.sendMessage(ChatColor.YELLOW + "/revival tp <player> " + ChatColor.GRAY + "- Teleport to a corpse");
        sender.sendMessage(ChatColor.YELLOW + "/revival revive <player> " + ChatColor.GRAY + "- Force revive a player");
    }

    private void listCorpses(CommandSender sender) {
        var corpses = corpseManager.getAllCorpses();
        
        if (corpses.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No corpses found.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Corpses (" + corpses.size() + ") ===");
        for (Corpse corpse : corpses) {
            Location loc = corpse.getLocation();
            String locStr = loc != null 
                ? String.format("%s: %d, %d, %d", loc.getWorld().getName(), 
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                : "Unknown location";
            
            long timeDead = (System.currentTimeMillis() - corpse.getDeathTime()) / 1000 / 60;
            
            sender.sendMessage(ChatColor.YELLOW + "- " + ChatColor.WHITE + corpse.getPlayerName() 
                + ChatColor.GRAY + " at " + locStr + " (" + timeDead + " min ago)");
        }
    }

    private void removeCorpse(CommandSender sender, String playerName) {
        // Find corpse by player name
        Corpse target = null;
        for (Corpse corpse : corpseManager.getAllCorpses()) {
            if (corpse.getPlayerName().equalsIgnoreCase(playerName)) {
                target = corpse;
                break;
            }
        }

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "No corpse found for player: " + playerName);
            return;
        }

        corpseManager.removeCorpse(target.getPlayerUuid());
        sender.sendMessage(ChatColor.GREEN + "Removed corpse for " + target.getPlayerName());

        // If player is online and in spectator, put them back in survival
        Player player = Bukkit.getPlayer(target.getPlayerUuid());
        if (player != null && player.isOnline()) {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.sendMessage(ChatColor.GREEN + "Your corpse has been removed by an admin. You are now alive!");
        }
    }

    private void teleportToCorpse(Player player, String targetName) {
        Corpse target = null;
        for (Corpse corpse : corpseManager.getAllCorpses()) {
            if (corpse.getPlayerName().equalsIgnoreCase(targetName)) {
                target = corpse;
                break;
            }
        }

        if (target == null) {
            player.sendMessage(ChatColor.RED + "No corpse found for player: " + targetName);
            return;
        }

        Location loc = target.getLocation();
        if (loc == null) {
            player.sendMessage(ChatColor.RED + "Corpse location is invalid.");
            return;
        }

        player.teleport(loc);
        player.sendMessage(ChatColor.GREEN + "Teleported to " + target.getPlayerName() + "'s corpse.");
    }

    private void forceRevive(CommandSender sender, String playerName) {
        Corpse target = null;
        for (Corpse corpse : corpseManager.getAllCorpses()) {
            if (corpse.getPlayerName().equalsIgnoreCase(playerName)) {
                target = corpse;
                break;
            }
        }

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "No corpse found for player: " + playerName);
            return;
        }

        UUID deadUuid = target.getPlayerUuid();
        Location reviveLocation = target.getLocation();
        
        // Remove the corpse
        corpseManager.removeCorpse(deadUuid);

        // Revive the player if online
        Player deadPlayer = Bukkit.getPlayer(deadUuid);
        if (deadPlayer != null && deadPlayer.isOnline()) {
            if (reviveLocation != null) {
                deadPlayer.teleport(reviveLocation);
            }
            deadPlayer.setGameMode(org.bukkit.GameMode.SURVIVAL);
            deadPlayer.setHealth(deadPlayer.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
            deadPlayer.setFoodLevel(20);
            deadPlayer.sendMessage(ChatColor.GREEN + "You have been revived by an admin!");
        }

        sender.sendMessage(ChatColor.GREEN + "Force revived " + target.getPlayerName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("hardcorerevival.admin")) {
            return completions;
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (String sub : List.of("reload", "list", "remove", "tp", "revive")) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("remove") || subCommand.equals("tp") || subCommand.equals("revive")) {
                String partial = args[1].toLowerCase();
                for (Corpse corpse : corpseManager.getAllCorpses()) {
                    if (corpse.getPlayerName().toLowerCase().startsWith(partial)) {
                        completions.add(corpse.getPlayerName());
                    }
                }
            }
        }

        return completions;
    }
}
