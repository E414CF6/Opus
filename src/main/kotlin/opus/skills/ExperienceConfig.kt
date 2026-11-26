package opus.skills

import opus.core.config.YamlConfigFile
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.plugin.Plugin

enum class FormulaCurve {
    LINEAR, EXPONENTIAL,
}

/**
 * Typed configuration snapshot loaded from `experience.yml`.
 */
class ExperienceConfig(plugin: Plugin? = null, initialYaml: YamlConfiguration? = null) {

    private val configFile = plugin?.let { YamlConfigFile(it, "experience.yml") }

    var earlyGameBoostEnabled: Boolean = true
        private set
    var curve: FormulaCurve = FormulaCurve.LINEAR
        private set
    var linearBase: Double = 1020.0
        private set
    var linearMultiplier: Double = 20.0
        private set
    var expMultiplier: Double = 0.1
        private set
    var expExponent: Double = 1.80
        private set
    var expBase: Double = 2000.0
        private set
    var cumulativeCurve: Boolean = false
        private set

    @Volatile
    var globalMultiplier: Double = 1.0
    var pvpMultiplier: Double = 1.0
        private set
    var customXpPerkBoost: Double = 1.25
        private set

    var spawnerMultiplier: Double = 0.0
        private set
    var spawnEggMultiplier: Double = 0.0
        private set
    var netherPortalMultiplier: Double = 0.0
        private set
    var breedingMultiplier: Double = 1.0
        private set
    var playerTamedMultiplier: Double = 0.0
        private set

    private val skillMultipliers = HashMap<SkillType, Double>()

    var combatXpCeilingEnabled: Boolean = true
        private set
    var combatXpDamageLimit: Double = 100.0
        private set
    var dodgeXpFarmingGuard: Boolean = true
        private set
    var dodgeXpCapPerMob: Double = 2400.0
        private set
    var fishingAntiExploit: Boolean = true
        private set
    var fishingMoveRange: Double = 3.0
        private set
    var fishingOverfishLimit: Int = 10
        private set
    var treeFellerReducedXp: Boolean = true
        private set
    var limitTallPlantFarming: Boolean = true
        private set
    var pistonCheatingGuard: Boolean = true
        private set
    var lavaStoneCobbleGuard: Boolean = true
        private set
    var snowGolemExcavationGuard: Boolean = true
        private set
    var endermanEndermiteGuard: Boolean = true
        private set
    var preventArmorStandInteraction: Boolean = true
        private set
    var preventMannequinInteraction: Boolean = true
        private set
    var preventPluginNpcInteraction: Boolean = true
        private set

    @Volatile
    var configuredGlobalMultiplier: Double = 1.0
        private set

    @Volatile
    var xpEventActive: Boolean = false

    var diminishedReturnsEnabled: Boolean = false
        private set
    var diminishedReturnsMinutes: Int = 10
        private set
    var diminishedReturnsMinimum: Double = 0.05
        private set
    var diminishedReturnsThreshold: Double = 20000.0
        private set

    var acrobaticsDodgeXp: Double = 800.0
        private set
    var acrobaticsRollXp: Double = 600.0
        private set
    var acrobaticsFallXp: Double = 600.0
        private set
    var acrobaticsFeatherFallingMultiplier: Double = 2.0
        private set

    var alchemyStageXp: Map<Int, Double> = mapOf(1 to 666.0, 2 to 1111.0, 3 to 1750.0, 4 to 2250.0, 5 to 0.0)
        private set

    var archeryDistanceMultiplier: Double = 0.025
        private set

    private val fishingXp = HashMap<String, Double>()
    var fishingShakeXp: Double = 50.0
        private set

    var repairBaseXp: Double = 1000.0
        private set
    private val repairMaterialMultipliers = HashMap<String, Double>()

    var salvageBaseXp: Double = 600.0
        private set

    private val blockXpBySkill = HashMap<SkillType, MutableMap<Material, Double>>()
    private val smeltingXp = HashMap<Material, Double>()
    private val tamingXp = HashMap<EntityType, Double>()
    var combatDefaultMultiplier: Double = 1.0
        private set
    private val combatEntityMultipliers = HashMap<EntityType, Double>()

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
        earlyGameBoostEnabled = y.getBoolean("early-game-boost.enabled", true)
        curve = when ((y.getString("formula.curve", "LINEAR") ?: "LINEAR").uppercase()) {
            "EXPONENTIAL" -> FormulaCurve.EXPONENTIAL
            else -> FormulaCurve.LINEAR
        }
        linearBase = y.getDouble("formula.linear.base", 1020.0)
        linearMultiplier = y.getDouble("formula.linear.multiplier", 20.0)
        expMultiplier = y.getDouble("formula.exponential.multiplier", 0.1)
        expExponent = y.getDouble("formula.exponential.exponent", 1.80)
        expBase = y.getDouble("formula.exponential.base", 2000.0)
        cumulativeCurve = y.getBoolean("formula.cumulative-curve", false)

        globalMultiplier = y.getDouble("multipliers.global", 1.0)
        configuredGlobalMultiplier = globalMultiplier
        pvpMultiplier = y.getDouble("multipliers.pvp", 1.0)
        customXpPerkBoost = y.getDouble("multipliers.custom-xp-perk-boost", 1.25)

        spawnerMultiplier = y.getDouble("multipliers.spawn-reasons.spawner", 0.0)
        spawnEggMultiplier = y.getDouble("multipliers.spawn-reasons.spawn-egg", 0.0)
        netherPortalMultiplier = y.getDouble("multipliers.spawn-reasons.nether-portal", 0.0)
        breedingMultiplier = y.getDouble("multipliers.spawn-reasons.breeding", 1.0)
        playerTamedMultiplier = y.getDouble("multipliers.spawn-reasons.player-tamed", 0.0)

        skillMultipliers.clear()
        for (skill in SkillType.entries) {
            skillMultipliers[skill] = y.getDouble("multipliers.skills.${skill.key}", 1.0)
        }

        combatXpCeilingEnabled = y.getBoolean("exploit-fix.combat-xp-ceiling.enabled", true)
        combatXpDamageLimit = y.getDouble("exploit-fix.combat-xp-ceiling.damage-limit", 100.0)
        dodgeXpFarmingGuard = y.getBoolean("exploit-fix.acrobatics-dodge-xp-farming", true)
        dodgeXpCapPerMob = y.getDouble("exploit-fix.dodge-xp-cap-per-mob", 2400.0)
        fishingAntiExploit = y.getBoolean("exploit-fix.fishing-anti-exploit", true)
        fishingMoveRange = y.getDouble("exploit-fix.fishing-move-range", 3.0)
        fishingOverfishLimit = y.getInt("exploit-fix.fishing-overfish-limit", 10).coerceAtLeast(1)
        treeFellerReducedXp = y.getBoolean("exploit-fix.tree-feller-reduced-xp", true)
        limitTallPlantFarming = y.getBoolean("exploit-fix.limit-tall-plant-farming", true)
        pistonCheatingGuard = y.getBoolean("exploit-fix.piston-cheating", true)
        lavaStoneCobbleGuard = y.getBoolean("exploit-fix.lava-stone-cobble-farming", true)
        snowGolemExcavationGuard = y.getBoolean("exploit-fix.snow-golem-excavation", true)
        endermanEndermiteGuard = y.getBoolean("exploit-fix.enderman-endermite-farms", true)
        preventArmorStandInteraction = y.getBoolean("exploit-fix.prevent-armor-stand-interaction", true)
        preventMannequinInteraction = y.getBoolean("exploit-fix.prevent-mannequin-interaction", true)
        preventPluginNpcInteraction = y.getBoolean("exploit-fix.prevent-plugin-npc-interaction", true)

        diminishedReturnsEnabled = y.getBoolean("diminished-returns.enabled", false)
        diminishedReturnsMinutes = y.getInt("diminished-returns.time-interval-minutes", 10).coerceAtLeast(1)
        diminishedReturnsMinimum =
            y.getDouble("diminished-returns.guaranteed-minimum-percentage", 0.05).coerceIn(0.0, 1.0)
        diminishedReturnsThreshold = y.getDouble("diminished-returns.default-threshold", 20000.0)

        acrobaticsDodgeXp = y.getDouble("values.acrobatics.dodge", 800.0)
        acrobaticsRollXp = y.getDouble("values.acrobatics.roll", 600.0)
        acrobaticsFallXp = y.getDouble("values.acrobatics.fall", 600.0)
        acrobaticsFeatherFallingMultiplier = y.getDouble("values.acrobatics.feather-falling-multiplier", 2.0)

        alchemyStageXp = (1..5).associateWith { stage ->
            y.getDouble("values.alchemy.stage-$stage", 0.0)
        }
        archeryDistanceMultiplier = y.getDouble("values.archery.distance-multiplier", 0.025)

        fishingXp.clear()
        y.getConfigurationSection("values.fishing")?.getKeys(false)?.forEach { key ->
            if (key.equals("SHAKE", ignoreCase = true)) {
                fishingShakeXp = y.getDouble("values.fishing.$key", 50.0)
            } else {
                fishingXp[key.uppercase()] = y.getDouble("values.fishing.$key", 0.0)
            }
        }

        repairBaseXp = y.getDouble("values.repair.base", 1000.0)
        repairMaterialMultipliers.clear()
        y.getConfigurationSection("values.repair.materials")?.getKeys(false)?.forEach { key ->
            repairMaterialMultipliers[key.uppercase()] = y.getDouble("values.repair.materials.$key", 1.5)
        }

        salvageBaseXp = y.getDouble("values.salvage.base", 600.0)

        smeltingXp.clear()
        y.getConfigurationSection("values.smelting")?.getKeys(false)?.forEach { key ->
            Material.matchMaterial(key)?.let { mat ->
                smeltingXp[mat] = y.getDouble("values.smelting.$key", 0.0)
            }
        }

        blockXpBySkill.clear()
        loadBlockXpMap(y, "values.excavation", SkillType.EXCAVATION)
        loadBlockXpMap(y, "values.woodcutting", SkillType.WOODCUTTING)
        loadBlockXpMap(y, "values.herbalism", SkillType.HERBALISM)
        loadBlockXpMap(y, "values.mining", SkillType.MINING)

        tamingXp.clear()
        y.getConfigurationSection("values.taming")?.getKeys(false)?.forEach { key ->
            runCatching { EntityType.valueOf(key.uppercase()) }.getOrNull()?.let { type ->
                tamingXp[type] = y.getDouble("values.taming.$key", 0.0)
            }
        }

        combatDefaultMultiplier = y.getDouble("values.combat.default-multiplier", 1.0)
        combatEntityMultipliers.clear()
        y.getConfigurationSection("values.combat.entity-multipliers")?.getKeys(false)?.forEach { key ->
            runCatching { EntityType.valueOf(key.uppercase()) }.getOrNull()?.let { type ->
                combatEntityMultipliers[type] = y.getDouble("values.combat.entity-multipliers.$key", 1.0)
            }
        }
    }

    private fun loadBlockXpMap(y: YamlConfiguration, path: String, skill: SkillType) {
        val section = y.getConfigurationSection(path) ?: return
        val map = blockXpBySkill.computeIfAbsent(skill) { HashMap() }
        for (key in section.getKeys(false)) {
            Material.matchMaterial(key)?.let { mat ->
                map[mat] = section.getDouble(key, 0.0)
            }
        }
    }

    fun skillMultiplier(skill: SkillType): Double = skillMultipliers[skill] ?: 1.0

    fun blockXp(skill: SkillType, material: Material): Double = blockXpBySkill[skill]?.get(material) ?: 0.0

    fun isSkillBlock(skill: SkillType, material: Material): Boolean =
        blockXpBySkill[skill]?.containsKey(material) == true

    fun smeltingXp(material: Material): Double = smeltingXp[material] ?: 0.0

    fun fishingXp(material: Material): Double = fishingXp[material.name] ?: 100.0

    fun tamingXp(entityType: EntityType): Double = tamingXp[entityType] ?: 250.0

    fun combatMultiplier(entityType: EntityType): Double =
        combatEntityMultipliers[entityType] ?: combatDefaultMultiplier

    fun repairMaterialMultiplier(category: String): Double =
        repairMaterialMultipliers[category.uppercase()] ?: repairMaterialMultipliers["OTHER"] ?: 1.5
}
