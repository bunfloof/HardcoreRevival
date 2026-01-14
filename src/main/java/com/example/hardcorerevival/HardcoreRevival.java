package com.bun.hardcorerevival;

import com.bun.hardcorerevival.commands.RevivalCommand;
import com.bun.hardcorerevival.corpse.CorpseManager;
import com.bun.hardcorerevival.listeners.DeathListener;
import com.bun.hardcorerevival.listeners.ReviveListener;
import org.bukkit.plugin.java.JavaPlugin;

public class HardcoreRevival extends JavaPlugin {

    private static HardcoreRevival instance;
    private CorpseManager corpseManager;
    private DeathListener deathListener;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Initialize corpse manager (loads existing corpses from JSON)
        corpseManager = new CorpseManager(this);
        corpseManager.loadCorpses();

        // Register event listeners
        deathListener = new DeathListener(this);
        getServer().getPluginManager().registerEvents(deathListener, this);
        getServer().getPluginManager().registerEvents(new ReviveListener(this), this);

        // Register commands
        getCommand("revival").setExecutor(new RevivalCommand(this));

        getLogger().info("HardcoreRevival enabled! Loaded " + corpseManager.getCorpseCount() + " corpses.");
    }

    @Override
    public void onDisable() {
        // Save and clean up corpses
        if (corpseManager != null) {
            corpseManager.saveCorpses();
            corpseManager.removeAllCorpseNPCs();
        }

        getLogger().info("HardcoreRevival disabled!");
    }

    public static HardcoreRevival getInstance() {
        return instance;
    }

    public CorpseManager getCorpseManager() {
        return corpseManager;
    }
    
    public DeathListener getDeathListener() {
        return deathListener;
    }

    /**
     * Reload the plugin configuration
     */
    public void reload() {
        reloadConfig();
        corpseManager.saveCorpses();
        corpseManager.loadCorpses();
        getLogger().info("Configuration reloaded!");
    }
}
