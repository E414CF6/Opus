# Opus

**Opus** is a modern Kotlin reinterpretation of mcMMO (`target/`) built from the ground up for **Paper `26.3`** (with
**Folia**-compatible region scheduling), following the modular architecture and conventions of **Obsidian**.

## Tech Stack & Requirements

- **Server Software:** Paper `26.3` (`folia-supported: true`, `load: POSTWORLD`)
- **Java Toolchain:** Java `25`
- **Language & Build:** Kotlin `2.4.21`, Kotlin Coroutines `1.10.2`, Gradle `9.9.1` (`Shadow` plugin)
- **Database:** HikariCP `6.3.0` supporting **SQLite** (default), **MySQL**, and **MariaDB**
- **Soft Dependencies:** `PlaceholderAPI`, `WorldGuard`

## Modules & Features

All **6 modules** can be individually toggled under `modules:` in `config.yml`:

| Module ID | Description | Key Systems & Commands |
| :--- | :--- | :--- |
| `skills` | Core RPG progression engine, GUIs, leaderboards, and admin commands | `/opus`, `/skills` (`/mcstats`), `/<skill>` (all 19 skills), `/mctop`, `/mcrank`, `/inspect`, `/mccooldown`, `/mcability`, `/mmopower`, `/addxp`, `/addlevels`, `/mmoedit`, `/mcrefresh`, `/xprate`, `/mcremove`, `/mcpurge`, `/skillreset`, `/mmodebug` |
| `hud` | Adventure BossBar XP bars, ActionBar alerts, sidebar scoreboard, and mob health bars | `/mmoxpbar`, `/mcscoreboard`, `/mcnotify`, `/mclevelupsound` |
| `gathering` | Mining, Woodcutting, Herbalism, Excavation, and Fishing | Double/Triple drops, Super Breaker, Blast Mining, Tree Feller, Leaf Blower, Green Terra, Green/Shroom Thumb, Hylian Luck, Archaeology, Treasure Hunter, Magic Hunter, Ice Fishing, Master Angler, Shake |
| `combat` | Swords, Axes, Archery, Crossbows, Tridents, Maces, Spears, Unarmed, Taming, Acrobatics | Serrated Strikes, Rupture, Counter Attack, Skull Splitter, Critical Strikes, Armor Impact, Skill Shot, Daze, Explosive Shot, Trick Shot, Super Shotgun, Impale, Crush, Cripple, Spear Mastery, Momentum, Berserk, Disarm, Call of the Wild, Beast Lore, Roll, Dodge |
| `crafting` | Repair, Salvage, Smelting, Alchemy, and Chimaera Wing | Iron Block repair, Gold Block salvage, Arcane Forging/Salvage, Copper/Spear inference, Fuel Efficiency, Second Smelt, Flux Mining, Catalysis, Concoctions custom potions, Chimaera Wing |
| `party` | Parties, alliances, XP/item sharing, chat channels, and party teleport | `/party`, `/partychat` (`/pc`, `/p`), `/adminchat` (`/ac`, `/a`), `/mcchatspy`, `/ptp` |

## Modern Architectural Highlights

- **Chunk PDC Placed-Block Tracking:** `PlacedBlockTracker` packs `(x & 15, y, z & 15)` coordinates into `LongArray`
  inside `Chunk.persistentDataContainer` on the region thread—zero external region backup files and 100% Folia-safe.
- **Entity PDC Spawn Tagging:** Spawner, spawn egg, nether portal, and breeding multipliers are tracked via
  `Entity.persistentDataContainer`.
- **Paper Brigadier & Interactive GUIs:** Commands register via `LifecycleEvents.COMMANDS`; `/skills`, `/<skill> gui`,
  `/mctop gui`, and `/party gui` open safe `MenuWindow` inventories.
- **Bilingual MiniMessage Locales:** Built-in Korean (`messages_ko.yml`) and English (`messages_en.yml`) templates with
  verified key parity.

## Building & Testing

```bash
./gradlew clean build
```

Packaged plugin JAR output:

```text
build/libs/Opus-1.0-SNAPSHOT.jar
```

Run tests only:

```bash
./gradlew test
```

Launch a local Paper `26.3` server with Opus preloaded:

```bash
./gradlew runServer
```
