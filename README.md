# Opus

**Opus** is a modern Kotlin RPG skill plugin inspired by mcMMO, supporting **Paper/Spigot `1.20.4–26.3+`** and **Folia** (`folia-supported: true`) while sharing the domain-agnostic modular `core/` architecture and conventions of companion plugins **Claims**, **Protector**, and **Obsidian**.

## Tech Stack & Requirements

- **Server Software:** Paper / Spigot `1.20.4–26.3+` (`api-version: '1.20'`, `folia-supported: true`, `load: POSTWORLD`)
- **Java Compatibility:** Java `17+` bytecode target (compiled with Java `25` toolchain against Paper `26.3` API)
- **Language & Build:** Kotlin `2.4.21` (`JVM_17` target), Kotlin Coroutines `1.10.2`, Gradle (`Shadow` `9.6.1` relocating `kotlin`, `kotlinx`, and `com.zaxxer.hikari` under `opus.libs.*`)
- **Database:** HikariCP `6.3.0` supporting **SQLite** (default), **MySQL**, and **MariaDB**
- **Soft Dependencies:** `PlaceholderAPI`, `WorldGuard` (soft flags: `opus`, `opus-xp`, `opus-hardcore`)

## Modules & Features

All **6 feature modules** (`FeatureModule`) can be individually toggled under `modules:` in `config.yml`:

| Module ID | Description | Key Systems & Commands |
| :--- | :--- | :--- |
| `skills` | Core RPG progression engine, perks, level-up milestone commands, Hardcore/Vampirism, GUIs, leaderboards, and admin commands | `/opus` (`/mcmmo`), `/skills` (`/mcstats`), `/<skill>` (all 19 skills), `/mctop`, `/mcrank`, `/mcinspect` (`/mmoinspect`, `/opus inspect`), `/mmoinfo`, `/mccooldown`, `/mcability`, `/mmopower`, `/mcgod`, `/hardcore`, `/vampirism`, `/addxp`, `/addlevels`, `/mmoedit`, `/mcrefresh`, `/xprate`, `/mcremove`, `/mcpurge`, `/skillreset`, `/mmodebug` |
| `hud` | Adventure BossBar XP/ability/warmup/mob-health bars, ActionBar alerts, HUD settings UI (`Dialog`/`MenuWindow`), sidebar scoreboard, and `BELOW_NAME` power level tag | `/mmohud`, `/mmoxpbar`, `/mcscoreboard`, `/mcnotify`, `/mclevelupsound` |
| `gathering` | Mining, Woodcutting, Herbalism, Excavation, and Fishing | Double/Triple drops, Super Breaker, Blast Mining (`Bigger Bombs`, `Demolitions Expertise` with PvP damage cap), Tree Feller, Leaf Blower, Green Terra, Green/Shroom Thumb, Hylian Luck, multi-block tall plant collapse verification, Archaeology, Treasure Hunter, Magic Hunter, Ice Fishing, Master Angler, Shake |
| `combat` | Swords, Axes, Archery, Crossbows, Tridents, Maces, Spears, Unarmed, Taming, Acrobatics | All 8 Combat Limit Breaks with defender armor-quality scaling, attack-strength derivation, Serrated Strikes, Rupture, Counter Attack, Skull Splitter, Critical Strikes, Armor Impact, Skill Shot, Daze, Arrow Retrieval, Explosive Shot, Trick Shot, Super Shotgun, Impale, Crush, Cripple, Spear Mastery, Momentum, Berserk, Disarm (with anti-theft PDC protection), Call of the Wild (summon cap, lifetime, quit cleanup), Beast Lore, Roll, Dodge |
| `crafting` | Repair, Salvage, Smelting, Alchemy, and Chimaera Wing | Iron Block repair, Gold Block salvage, Custom Model Data protection, Arcane Forging/Salvage, Copper/Spear inference, persistent `TileState` PDC furnace/brewing-stand ownership, Fuel Efficiency, Second Smelt, Understanding the Art (smelting XP boost), Flux Mining, Catalysis, version-adaptive `BrewingRecipe` / cauldron Concoctions, Chimaera Wing |
| `party` | Parties, party leveling & feature unlocks, consent-based alliances & teleports, XP/item sharing, chat channels | `/party` (`rename`, `ally invite/accept/disband`, `gui`, etc.), `/partychat` (`/pc`, `/p`), `/adminchat` (`/opusac`, `/opus ac`), `/mcchatspy`, `/ptp` (`accept`, `toggle`, `acceptany`) |

## Modern Architectural Highlights

- **Shared Domain-Agnostic `core/` Layer:** `CoreHost`, `PluginScheduler`, `FeatureModule`, `DatabaseManager`, `LocaleService`, unified `core/ui/` (`ActionBarService`, `BossBarService`, `MenuWindow`, `DialogKit`, `PaperDialogKit`), `PlatformCompat`, and `CommandRegistry` / `RootCommandExecutor` / `CommandAliasService` keep `opus.core` strictly free of RPG/domain imports (verified in unit tests).
- **Cross-Version `1.20.4–26.3+` & Collision-Safe Command Dispatch:** `/opus` (alias `/mcmmo`) dispatches all module subcommands cleanly alongside standalone `AliasCommandProxy` entries, while `/stats`, `/inspect`, `/ac`, and `/a` default to `/opus <subcommand>` only (leaving standalone `/mcstats`, `/mcinspect`, `/opusac`, etc. enabled) so Opus coexists without command collisions alongside **Protector** and **Obsidian** (all overridable in `aliases.yml`).
- **Chunk PDC Placed-Block & Exploit Tracking:** `PlacedBlockTracker` packs `(x & 15, y, z & 15)` coordinates into `LongArray` inside `Chunk.persistentDataContainer` on the region thread—covering lava-cobble/basalt farms, Snow Golem snow, and Endermite farm tagging—with zero external region backup files and full Folia safety.
- **Version-Adaptive Paper/Spigot & Folia UI:** Native Paper `Dialog` API (`1.21.6+`) isolated inside `PaperDialogKit` and `Paper*Dialogs` classes with automatic `MenuWindow` Chest GUI fallbacks on `1.20.4–1.21.5`, plus Folia-safe `RegionScheduler` / `EntityScheduler` / `GlobalRegionScheduler` / Coroutine dispatchers.
- **Bilingual MiniMessage Locales:** Built-in Korean (`locales/ko_kr.yml`) and English (`locales/en_us.yml`) templates with automated key-parity verification in CI tests.

## Building & Testing

```bash
./gradlew clean test shadowJar
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
