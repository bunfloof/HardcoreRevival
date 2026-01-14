# HardcoreRevival Plugin

A Minecraft 1.21.11 plugin for Spigot/Paper that adds a hardcore revival system. When players die, they become spectators and leave behind a corpse that other players can revive.

## Features

- **Player Corpses**: When a player dies, a corpse NPC spawns at their death location (using ProtocolLib for packet-based NPCs)
- **Swimming Pose**: Corpses appear laying down in a horizontal swimming pose
- **Safe Spawn**: If a player dies in void/lava, the corpse spawns at the nearest safe location
- **Death Coordinates**: Dead players receive a message with their corpse coordinates
- **Revival Items**: Revive corpses using:
  - Totem of Undying
  - The dead player's head (compatible with HeadDrop plugin)
- **Spectator Mode**: Dead players are set to spectator mode until revived
- **Persistent Storage**: Corpses are saved to JSON and persist across server restarts

## Requirements

- **Minecraft**: 1.21.11
- **Server**: Spigot or Paper (and forks)
- **Java**: 21
- **ProtocolLib**: Required dependency (download from GitHub)

## Installation

1. Install [ProtocolLib](https://github.com/dmulloy2/ProtocolLib/releases/tag/dev-build) development buildon your server
2. Build this plugin with `./gradlew build`
3. Copy `build/libs/hardcore-revival-1.0.0.jar` to your server's `plugins/` folder
4. Restart your server

## Configuration

```yaml
# config.yml

messages:
  death-coordinates: "&cYou died at &e{x}, {y}, {z} &cin &e{world}&c. Find someone to revive you!"
  revived: "&aYou have been revived by &e{reviver}&a!"
  revived-other: "&aYou revived &e{player}&a!"
  no-permission: "&cYou don't have permission to do that."
  invalid-item: "&cYou need a Totem of Undying or a player head to revive them!"

# Items that can revive (player's own head always works)
revival-items:
  - TOTEM_OF_UNDYING

# Should the revival item be consumed?
consume-item: true

# Search radius for safe corpse spawn location
safe-location-search-radius: 50

corpse:
  use-swimming-pose: true  # Horizontal "dead body" pose
  glowing: false           # Make corpses glow for visibility
  expire-time: -1          # Minutes until auto-removal (-1 = never)
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/revival reload` | Reload configuration | `hardcorerevival.admin` |
| `/revival list` | List all corpses | `hardcorerevival.admin` |
| `/revival remove <player>` | Remove a player's corpse | `hardcorerevival.admin` |
| `/revival tp <player>` | Teleport to a corpse | `hardcorerevival.admin` |
| `/revival revive <player>` | Force revive a player | `hardcorerevival.admin` |

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `hardcorerevival.admin` | Access to admin commands | op |
| `hardcorerevival.revive` | Ability to revive other players | true |

## How It Works

1. **Death**: When a player dies:
   - A corpse NPC spawns at their location (or nearest safe spot)
   - They respawn as a spectator
   - They receive coordinates of their corpse

2. **Revival**: Another player can revive them by:
   - Right-clicking the corpse while holding a Totem of Undying, OR
   - Right-clicking while holding the dead player's severed head

3. **Revived**: The dead player is:
   - Teleported to their corpse location
   - Set to survival mode
   - Given half health and some food

## Integration with HeadDrop

This plugin works great with head-dropping plugins! When a player kills another player:
1. The victim's head drops
2. The killer can bring the head to the corpse
3. Right-click to revive (consumes the head)

## Technical Notes

- Corpses are fake entities (packets only) - no actual entities are spawned
- Corpse data is stored in `plugins/HardcoreRevival/corpses.json`
- Entity IDs for corpses are generated from `Integer.MAX_VALUE` downward to avoid conflicts
- Corpses are re-spawned when players join or change worlds

## Building

This project includes a Gradle wrapper that will automatically download the correct Gradle version (8.5).

```bash
# Use the wrapper (recommended) - downloads Gradle 8.5 automatically
./gradlew build

# Output: build/libs/hardcore-revival-1.0.0.jar
```

**Note:** If your system has an old version of Gradle (check with `gradle --version`), always use `./gradlew` instead of `gradle` to ensure compatibility with Java 21.