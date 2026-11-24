# Opus

**Opus** is a modern Kotlin RPG skill plugin inspired by mcMMO, built from the ground up for **Paper `26.3`** (with
**Folia**-compatible region scheduling), following the modular architecture and conventions of **Obsidian**.

## Tech Stack & Requirements

- **Server Software:** Paper `26.3` (`folia-supported: true`, `load: POSTWORLD`)
- **Java Toolchain:** Java `25`
- **Language & Build:** Kotlin `2.4.21`, Kotlin Coroutines `1.10.2`, Gradle `9.9.1` (`Shadow` plugin)
- **Database:** HikariCP `6.3.0` supporting **SQLite** (default), **MySQL**, and **MariaDB**
- **Soft Dependencies:** `PlaceholderAPI`, `WorldGuard` (soft flags: `opus`, `opus-xp`, `opus-hardcore`)

## Modules & Features

All **6 modules** can be individually toggled under `modules:` in `config.yml`:

| Module ID | Description | Key Systems & Commands |
| :--- | :--- | :--- |
| `skills` | Core RPG progression engine, perks, level-up milestone commands, Hardcore/Vampirism, GUIs, leaderboards, and admin commands | `/opus`, `/skills` (`/mcstats`), `/<skill>` (all 19 skills), `/mctop`, `/mcrank`, `/inspect`, `/mmoinfo`, `/mccooldown`, `/mcability`, `/mmopower`, `/mcgod`, `/hardcore`, `/vampirism`, `/addxp`, `/addlevels`, `/mmoedit`, `/mcrefresh`, `/xprate`, `/mcremove`, `/mcpurge`, `/skillreset`, `/mmodebug` |
| `hud` | Adventure BossBar XP/ability/warmup/mob-health bars, ActionBar alerts, HUD settings Dialog, sidebar scoreboard, and `BELOW_NAME` power level tag | `/mmohud`, `/mmoxpbar`, `/mcscoreboard`, `/mcnotify`, `/mclevelupsound` |
| `gathering` | Mining, Woodcutting, Herbalism, Excavation, and Fishing | Double/Triple drops, Super Breaker, Blast Mining (`Bigger Bombs`, `Demolitions Expertise` with PvP damage cap), Tree Feller, Leaf Blower, Green Terra, Green/Shroom Thumb, Hylian Luck, multi-block tall plant collapse verification, Archaeology, Treasure Hunter, Magic Hunter, Ice Fishing, Master Angler, Shake |
| `combat` | Swords, Axes, Archery, Crossbows, Tridents, Maces, Spears, Unarmed, Taming, Acrobatics | All 8 Combat Limit Breaks with defender armor-quality scaling, Paper 26.3 attack-strength derivation, Serrated Strikes, Rupture, Counter Attack, Skull Splitter, Critical Strikes, Armor Impact, Skill Shot, Daze, Arrow Retrieval, Explosive Shot, Trick Shot, Super Shotgun, Impale, Crush, Cripple, Spear Mastery, Momentum, Berserk, Disarm (with anti-theft PDC protection), Call of the Wild (summon cap, lifetime, quit cleanup), Beast Lore, Roll, Dodge |
| `crafting` | Repair, Salvage, Smelting, Alchemy, and Chimaera Wing | Iron Block repair, Gold Block salvage, Custom Model Data protection, Arcane Forging/Salvage, Copper/Spear inference, persistent `TileState` PDC furnace/brewing-stand ownership, Fuel Efficiency, Second Smelt, Understanding the Art (smelting XP boost), Flux Mining, Catalysis, native Paper 26.3 `BrewingRecipe` Concoctions, Chimaera Wing |
| `party` | Parties, party leveling & feature unlocks, consent-based alliances & teleports, XP/item sharing, chat channels | `/party` (`rename`, `ally invite/accept/disband`, `gui`, etc.), `/partychat` (`/pc`, `/p`), `/adminchat` (`/ac`, `/a`), `/mcchatspy`, `/ptp` (`accept`, `toggle`, `acceptany`) |

## Modern Architectural Highlights

- **Chunk PDC Placed-Block & Exploit Tracking:** `PlacedBlockTracker` packs `(x & 15, y, z & 15)` coordinates into
  `LongArray` inside `Chunk.persistentDataContainer` on the region thread—covering lava-cobble/basalt farms, Snow Golem
  snow, and Endermite farm tagging—with zero external region backup files and full Folia safety.
- **Native Paper 26.3 APIs:** Custom Alchemy Concoctions register as native `BrewingRecipe` entries via
  `RecipeChoice.predicateChoice`, furnace/brewing-stand ownership persists in `TileState.persistentDataContainer`, and
  tall plant collapse verification runs on Folia region timers (`runAtLocationTimer`).
- **Perks, Milestone Commands & Hardcore Mode:** Permission-tiered XP/cooldown/duration/lucky perks (`PerksService`),
  configurable `level_up_commands.yml` with multi-level & power-level triggers, and opt-in Hardcore death stat loss &
  PvP Vampirism with WorldGuard `opus-hardcore` region support.
- **Paper Brigadier, Interactive GUIs & Dialogs:** Commands register via `LifecycleEvents.COMMANDS`; `/opus`, `/skills`, `/<skill>`, `/mctop`, `/mccooldown`, `/inspect`, `/mcrank`, `/mmoinfo`, `/mmohud`, and `/party` integrate `MenuWindow` inventories, Paper 26.3 `Dialog` forms/confirmations, live `BossBar` timers, and `ActionBar` feedback.
- **Bilingual MiniMessage Locales:** Built-in Korean (`locales/ko_kr.yml`) and English (`locales/en_us.yml`)
  templates with automated key-parity verification in CI tests.

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
