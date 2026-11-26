package opus.skills

import opus.core.config.YamlConfigFile
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin

/**
 * Loads SubSkill rank unlock level thresholds from `ranks.yml` and skill tuning from `skills.yml`.
 */
class RanksConfig(plugin: Plugin? = null, initialYaml: YamlConfiguration? = null) {

    private val configFile = plugin?.let { YamlConfigFile(it, "ranks.yml") }
    private val standardRanks = HashMap<SubSkillType, List<Int>>()
    private val retroRanks = HashMap<SubSkillType, List<Int>>()

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
        standardRanks.clear()
        retroRanks.clear()
        for (subSkill in SubSkillType.entries) {
            val std = y.getIntegerList("${subSkill.name}.standard")
            val ret = y.getIntegerList("${subSkill.name}.retro")
            standardRanks[subSkill] = std.ifEmpty { listOf(1) }
            retroRanks[subSkill] = ret.ifEmpty { listOf(1) }
        }
    }

    fun thresholds(subSkill: SubSkillType, retroMode: Boolean): List<Int> =
        if (retroMode) retroRanks[subSkill] ?: listOf(1) else standardRanks[subSkill] ?: listOf(1)

    fun rankForLevel(subSkill: SubSkillType, level: Int, retroMode: Boolean): Int {
        val list = thresholds(subSkill, retroMode)
        var rank = 0
        for (req in list) {
            if (level >= req) rank++ else break
        }
        return rank.coerceAtMost(subSkill.maxRanks)
    }

    fun nextUnlockLevel(subSkill: SubSkillType, currentRank: Int, retroMode: Boolean): Int? {
        val list = thresholds(subSkill, retroMode)
        return list.getOrNull(currentRank)
    }
}

/**
 * Typed configuration snapshot loaded from `skills.yml`.
 */
class SkillsConfig(plugin: Plugin? = null, initialYaml: YamlConfiguration? = null) {

    private val configFile = plugin?.let { YamlConfigFile(it, "skills.yml") }
    private lateinit var yaml: YamlConfiguration

    var adjustForAttackCooldown: Boolean = true
        private set
    var limitBreakAllowPve: Boolean = false
        private set
    var abilityBaseDurationSec: Int = 2
        private set
    var abilityMaxDurationSec: Int = 22
        private set
    var abilityStandardIncreaseLevel: Int = 5
        private set
    var abilityStandardCapLevel: Int = 100
        private set
    var abilityRetroIncreaseLevel: Int = 50
        private set
    var abilityRetroCapLevel: Int = 1000
        private set
    var abilityCooldownSec: Int = 240
        private set
    var abilityEnchantBuffLevel: Int = 5
        private set
    var defaultLevelCap: Int = 0
        private set

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
        yaml = y
        adjustForAttackCooldown = y.getBoolean("general.adjust-for-attack-cooldown", true)
        limitBreakAllowPve = y.getBoolean("general.limit-break-allow-pve", false)
        abilityBaseDurationSec = y.getInt("general.ability.base-duration-seconds", 2).coerceAtLeast(1)
        abilityMaxDurationSec = y.getInt("general.ability.max-duration-seconds", 22).coerceAtLeast(1)
        abilityStandardIncreaseLevel = y.getInt("general.ability.standard-increase-level", 5).coerceAtLeast(1)
        abilityStandardCapLevel = y.getInt("general.ability.standard-cap-level", 100).coerceAtLeast(1)
        abilityRetroIncreaseLevel = y.getInt("general.ability.retro-increase-level", 50).coerceAtLeast(1)
        abilityRetroCapLevel = y.getInt("general.ability.retro-cap-level", 1000).coerceAtLeast(1)
        abilityCooldownSec = y.getInt("general.ability.cooldown-seconds", 240).coerceAtLeast(1)
        abilityEnchantBuffLevel = y.getInt("general.ability.enchant-buff-level", 5).coerceAtLeast(1)
        defaultLevelCap = y.getInt("level-caps.default", 0).coerceAtLeast(0)
    }

    fun levelCap(skill: SkillType): Int = yaml.getInt("level-caps.${skill.key}", defaultLevelCap).coerceAtLeast(0)

    fun isPvpEnabled(skill: SkillType): Boolean =
        yaml.getBoolean("pvp-enabled.${skill.key}", yaml.getBoolean("pvp-enabled.default", true))

    fun isPveEnabled(skill: SkillType): Boolean =
        yaml.getBoolean("pve-enabled.${skill.key}", yaml.getBoolean("pve-enabled.default", true))

    fun double(path: String, fallback: Double): Double = yaml.getDouble(path, fallback)

    fun int(path: String, fallback: Int): Int = yaml.getInt(path, fallback)

    fun boolean(path: String, fallback: Boolean): Boolean = yaml.getBoolean(path, fallback)

    fun doubleAtRank(path: String, rank: Int, fallback: Double = 0.0): Double {
        val list = yaml.getDoubleList(path)
        if (list.isEmpty() || rank <= 0) return fallback
        return list[(rank - 1).coerceIn(0, list.lastIndex)]
    }

    fun intAtRank(path: String, rank: Int, fallback: Int = 0): Int {
        val list = yaml.getIntegerList(path)
        if (list.isEmpty() || rank <= 0) return fallback
        return list[(rank - 1).coerceIn(0, list.lastIndex)]
    }

    fun maxBonusLevel(prefix: String, retroMode: Boolean, defaultStd: Int = 100, defaultRetro: Int = 1000): Int =
        if (retroMode) {
            yaml.getInt("$prefix.max-bonus-level-retro", defaultRetro).coerceAtLeast(1)
        } else {
            yaml.getInt("$prefix.max-bonus-level-standard", defaultStd).coerceAtLeast(1)
        }
}
