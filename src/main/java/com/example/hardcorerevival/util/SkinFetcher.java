package com.bun.hardcorerevival.util;

import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Utility class to fetch player skins from Mojang's API with caching
 */
public class SkinFetcher {

    private static final String MOJANG_SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    
    // Cache skin data to avoid repeated API calls (Mojang rate limits)
    private static final Map<UUID, CachedSkin> skinCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 30 * 60 * 1000; // 30 minutes
    private static final long FAILED_CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutes for failed attempts

    /**
     * Fetch skin data for a player UUID asynchronously
     */
    public static void fetchSkinAsync(JavaPlugin plugin, UUID playerUuid, Consumer<SkinData> callback) {
        CompletableFuture.runAsync(() -> {
            SkinData skinData = fetchSkin(plugin, playerUuid);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(skinData));
        });
    }

    /**
     * Fetch skin data synchronously with caching
     */
    public static SkinData fetchSkin(JavaPlugin plugin, UUID playerUuid) {
        // Check cache first
        CachedSkin cached = skinCache.get(playerUuid);
        if (cached != null && !cached.isExpired()) {
            if (cached.skinData != null) {
                plugin.getLogger().fine("Using cached skin for " + playerUuid);
            }
            return cached.skinData;
        }
        
        // Fetch from Mojang API
        SkinData skinData = fetchFromMojang(plugin, playerUuid);
        
        // Cache the result (even if null, to avoid repeated failed requests)
        long expiry = System.currentTimeMillis() + (skinData != null ? CACHE_DURATION_MS : FAILED_CACHE_DURATION_MS);
        skinCache.put(playerUuid, new CachedSkin(skinData, expiry));
        
        return skinData;
    }
    
    /**
     * Fetch skin from Mojang API (internal, no caching)
     */
    private static SkinData fetchFromMojang(JavaPlugin plugin, UUID playerUuid) {
        // Check if this looks like an offline-mode UUID (version 3)
        // Online UUIDs are version 4, offline are version 3
        if (playerUuid.version() == 3) {
            plugin.getLogger().fine("UUID " + playerUuid + " appears to be offline-mode - skipping Mojang API");
            return null;
        }
        
        try {
            String uuidString = playerUuid.toString().replace("-", "");
            URL url = new URL(String.format(MOJANG_SESSION_URL, uuidString));
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // Increased timeout
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "HardcoreRevival-Plugin/1.0");
            connection.setRequestProperty("Accept", "application/json");
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 429) {
                plugin.getLogger().warning("Mojang API rate limited - skin fetch failed for " + playerUuid);
                return null;
            }
            if (responseCode == 204 || responseCode == 404) {
                plugin.getLogger().fine("Player not found in Mojang database: " + playerUuid);
                return null;
            }
            if (responseCode != 200) {
                plugin.getLogger().warning("Mojang API returned HTTP " + responseCode + " for UUID " + playerUuid);
                return null;
            }
            
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                
                if (!json.has("properties")) {
                    plugin.getLogger().fine("No properties in Mojang response for " + playerUuid);
                    return null;
                }
                
                JsonArray properties = json.getAsJsonArray("properties");
                for (int i = 0; i < properties.size(); i++) {
                    JsonObject property = properties.get(i).getAsJsonObject();
                    if ("textures".equals(property.get("name").getAsString())) {
                        String value = property.get("value").getAsString();
                        String signature = property.has("signature") ? property.get("signature").getAsString() : null;
                        plugin.getLogger().info("Successfully fetched skin from Mojang for " + playerUuid);
                        return new SkinData(value, signature);
                    }
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            plugin.getLogger().warning("Mojang API timeout for " + playerUuid);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to fetch skin from Mojang for " + playerUuid + ": " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }
    
    /**
     * Clear the skin cache (useful for plugin reload)
     */
    public static void clearCache() {
        skinCache.clear();
    }

    /**
     * Apply skin data to a GameProfile using ProtocolLib's wrapper for mutability
     */
    public static void applySkinToProfile(GameProfile profile, SkinData skinData) {
        if (skinData != null && skinData.value() != null) {
            WrappedGameProfile wrapped = WrappedGameProfile.fromHandle(profile);
            wrapped.getProperties().put("textures", 
                new WrappedSignedProperty("textures", skinData.value(), skinData.signature()));
        }
    }
    
    /**
     * Create a new GameProfile with skin data already applied.
     * This is the safest way to create a profile with textures in newer MC versions
     * where GameProfile.properties() returns an immutable map.
     */
    public static GameProfile createProfileWithSkin(UUID uuid, String name, SkinData skinData) {
        WrappedGameProfile wrapped = new WrappedGameProfile(uuid, name);
        if (skinData != null && skinData.value() != null) {
            wrapped.getProperties().put("textures", 
                new WrappedSignedProperty("textures", skinData.value(), skinData.signature()));
        }
        return (GameProfile) wrapped.getHandle();
    }
    
    /**
     * Create a new GameProfile with skin data copied from texture value/signature strings.
     */
    public static GameProfile createProfileWithSkin(UUID uuid, String name, String textureValue, String textureSignature) {
        WrappedGameProfile wrapped = new WrappedGameProfile(uuid, name);
        if (textureValue != null && !textureValue.isEmpty()) {
            wrapped.getProperties().put("textures", 
                new WrappedSignedProperty("textures", textureValue, textureSignature));
        }
        return (GameProfile) wrapped.getHandle();
    }

    /**
     * Record to hold skin texture data
     */
    public record SkinData(String value, String signature) {
        public boolean isValid() {
            return value != null && !value.isEmpty();
        }
    }
    
    /**
     * Internal class to hold cached skin data with expiry
     */
    private record CachedSkin(SkinData skinData, long expiryTime) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
