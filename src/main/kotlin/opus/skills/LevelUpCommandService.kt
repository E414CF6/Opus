package opus.skills

import me.clip.placeholderapi.PlaceholderAPI
import opus.core.OpusContext
import opus.core.command.AliasMatcher
import opus.core.command.SpecializedCommandExecutor
import opus.core.config.YamlConfigFile
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

enum class LevelUpRunAs {
    CONSOLE,
    PLAYER,
}

data class LevelUpCommandEntry(
    val id: String,
    val enabled: Boolean,
    val allSkills: Boolean,
    val skills: Set<SkillType>,
    val levels: Set<Int>,
    val powerLevels: Set<Int>,
    val runAs: LevelUpRunAs,
    val commands: List<String>,
)

data class ResolvedLevelUpCommand(
    val runAs: LevelUpRunAs,
    val command: String,
)

/**
 * Loads `level_up_commands.yml` and executes milestone commands on skill or power level-ups.
 */
class LevelUpCommandService(
    private val context: OpusContext? = null,
    plugin: Plugin? = context?.plugin,
    initialYaml: YamlConfiguration? = null,
) {
    private val configFile = plugin?.let { YamlConfigFile(it, "level_up_commands.yml") }
    private val entries = mutableListOf<LevelUpCommandEntry>()

    init {
        if (initialYaml != null) {
            applyYaml(initialYaml)
        } else {
            reload()
        }
    }

    fun reload() {
        val file = configFile ?: return
        file.reload()
        applyYaml(file.yaml)
    }

    fun applyYaml(y: YamlConfiguration) {
        entries.clear()
        val root = y.getConfigurationSection("level_up_commands") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            val enabled = sec.getBoolean("enabled", true)
            val runAs = when ((sec.getString("run_as", "CONSOLE") ?: "CONSOLE").uppercase()) {
                "PLAYER" -> LevelUpRunAs.PLAYER
                else -> LevelUpRunAs.CONSOLE
            }
            val rawSkills = when (val v = sec.get("skills")) {
                is String -> listOf(v)
                is List<*> -> v.mapNotNull { it?.toString() }
                else -> emptyList()
            }
            val allSkills = rawSkills.any { it.equals("all", ignoreCase = true) }
            val skills = rawSkills.mapNotNull { SkillType.match(it) }.toSet()
            val levels = sec.getIntegerList("levels").toSet()
            val powerLevels = sec.getIntegerList("power_levels").toSet()
            val commands = when (val v = sec.get("commands")) {
                is String -> listOf(v)
                is List<*> -> v.mapNotNull { it?.toString() }
                else -> emptyList()
            }.filter { it.isNotBlank() }

            if (commands.isEmpty()) continue
            entries += LevelUpCommandEntry(
                id = id,
                enabled = enabled,
                allSkills = allSkills,
                skills = skills,
                levels = levels,
                powerLevels = powerLevels,
                runAs = runAs,
                commands = commands,
            )
        }
    }

    fun resolveCommandsForLevelUp(
        playerName: String,
        state: OpusPlayerState,
        skill: SkillType,
        oldLevel: Int,
        newLevel: Int,
        oldPower: Int,
        newPower: Int,
        language: String = "en",
        papiResolver: ((String) -> String)? = null,
    ): List<ResolvedLevelUpCommand> {
        if (entries.isEmpty()) return emptyList()
        val results = mutableListOf<ResolvedLevelUpCommand>()
        val skillDisplay = skill.displayName(language)

        for (entry in entries) {
            if (!entry.enabled) continue

            // Skill milestone triggers (every level crossed in (oldLevel, newLevel])
            if (entry.levels.isNotEmpty() && (entry.allSkills || skill in entry.skills)) {
                for (lvl in (oldLevel + 1)..newLevel) {
                    if (lvl in entry.levels) {
                        for (raw in entry.commands) {
                            val cmd = substitute(raw, playerName, state, skillDisplay, lvl, newPower, papiResolver)
                            results += ResolvedLevelUpCommand(entry.runAs, cmd)
                        }
                    }
                }
            }

            // Power level milestone triggers (every power level crossed in (oldPower, newPower])
            if (entry.powerLevels.isNotEmpty()) {
                for (pwr in (oldPower + 1)..newPower) {
                    if (pwr in entry.powerLevels) {
                        for (raw in entry.commands) {
                            val cmd = substitute(raw, playerName, state, skillDisplay, newLevel, pwr, papiResolver)
                            results += ResolvedLevelUpCommand(entry.runAs, cmd)
                        }
                    }
                }
            }
        }
        return results
    }

    fun executeOnLevelUp(
        player: Player,
        state: OpusPlayerState,
        skill: SkillType,
        oldLevel: Int,
        newLevel: Int,
        oldPower: Int,
        newPower: Int,
    ) {
        val ctx = context ?: return
        val papiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
        val resolved = resolveCommandsForLevelUp(
            playerName = player.name,
            state = state,
            skill = skill,
            oldLevel = oldLevel,
            newLevel = newLevel,
            oldPower = oldPower,
            newPower = newPower,
            language = ctx.config.language,
            papiResolver = if (papiEnabled) {
                { raw -> runCatching { PlaceholderAPI.setPlaceholders(player, raw) }.getOrDefault(raw) }
            } else null,
        )
        for (item in resolved) {
            val clean = item.command.removePrefix("/").trim()
            if (clean.isEmpty()) continue
            if (AliasMatcher.isSpecializedCommandLine(clean)) {
                SpecializedCommandExecutor.executeAll(
                    ctx,
                    listOf(clean),
                    player,
                    mapOf(
                        "player" to player.name,
                        "skill" to skill.displayName(ctx.config.language),
                        "level" to newLevel.toString(),
                        "power_level" to newPower.toString(),
                    ),
                )
                continue
            }
            when (item.runAs) {
                LevelUpRunAs.CONSOLE -> ctx.scheduler.runGlobal {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean)
                }

                LevelUpRunAs.PLAYER -> ctx.scheduler.runForEntity(player) {
                    if (player.isOnline) {
                        Bukkit.dispatchCommand(player, clean)
                    }
                }
            }
        }
    }

    private fun substitute(
        template: String,
        playerName: String,
        state: OpusPlayerState,
        skillDisplay: String,
        milestoneLevel: Int,
        powerLevel: Int,
        papiResolver: ((String) -> String)?,
    ): String {
        var out = template
            .replace("{@player}", playerName)
            .replace("{@skill}", skillDisplay)
            .replace("{@level}", milestoneLevel.toString())
            .replace("{@power_level}", powerLevel.toString())
        for (s in SkillType.entries) {
            val token = "{@${s.key}_level}"
            if (out.contains(token)) {
                out = out.replace(token, state.getLevel(s).toString())
            }
        }
        if (papiResolver != null) {
            out = papiResolver(out)
        }
        return out
    }
}
