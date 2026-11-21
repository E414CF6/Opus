package opus.modules.crafting

import opus.core.config.YamlConfigFile
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffectType

data class RepairableEntry(
    val itemMaterial: Material,
    val repairMaterial: Material,
    val category: String,
    val minQuantity: Int,
    val minimumLevel: Int,
    val xpMultiplier: Double,
)

data class SalvageableEntry(
    val itemMaterial: Material,
    val salvageMaterial: Material,
    val category: String,
    val maxQuantity: Int,
    val minimumLevel: Int,
    val xpMultiplier: Double,
)

data class CustomPotionRecipe(
    val ingredient: Material,
    val displayName: String,
    val effectType: PotionEffectType,
    val durationTicks: Int,
    val amplifier: Int,
    val color: Color,
    val minRank: Int,
)

/**
 * Loads Repair/Salvage item definitions from `repair.yml` with automatic vanilla gear inference
 * (including Copper tools/armor and Spears in Paper 26.3).
 */
class RepairConfig(plugin: Plugin? = null, initialYaml: YamlConfiguration? = null) {

    private val configFile = plugin?.let { YamlConfigFile(it, "repair.yml") }
    private val repairables = HashMap<Material, RepairableEntry>()
    private val salvageables = HashMap<Material, SalvageableEntry>()

    var allowRepairCustomModelData: Boolean = true
        private set
    var allowSalvageCustomModelData: Boolean = true
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
        allowRepairCustomModelData = y.getBoolean("custom-item-support.allow-repair-custom-model-data", true)
        allowSalvageCustomModelData = y.getBoolean("custom-item-support.allow-salvage-custom-model-data", true)
        repairables.clear()
        salvageables.clear()

        y.getConfigurationSection("repairables")?.let { sec ->
            for (key in sec.getKeys(false)) {
                val itemMat = Material.matchMaterial(key) ?: continue
                val sub = sec.getConfigurationSection(key) ?: continue
                val repMat = Material.matchMaterial(sub.getString("material", "") ?: "") ?: continue
                repairables[itemMat] = RepairableEntry(
                    itemMaterial = itemMat,
                    repairMaterial = repMat,
                    category = (sub.getString("category", "OTHER") ?: "OTHER").uppercase(),
                    minQuantity = sub.getInt("min-quantity", 1).coerceAtLeast(1),
                    minimumLevel = sub.getInt("minimum-level", 0).coerceAtLeast(0),
                    xpMultiplier = sub.getDouble("xp-multiplier", 1.0),
                )
            }
        }

        y.getConfigurationSection("salvageables")?.let { sec ->
            for (key in sec.getKeys(false)) {
                val itemMat = Material.matchMaterial(key) ?: continue
                val sub = sec.getConfigurationSection(key) ?: continue
                val salMat = Material.matchMaterial(sub.getString("material", "") ?: "") ?: continue
                salvageables[itemMat] = SalvageableEntry(
                    itemMaterial = itemMat,
                    salvageMaterial = salMat,
                    category = (sub.getString("category", "OTHER") ?: "OTHER").uppercase(),
                    maxQuantity = sub.getInt("max-quantity", 1).coerceAtLeast(1),
                    minimumLevel = sub.getInt("minimum-level", 0).coerceAtLeast(0),
                    xpMultiplier = sub.getDouble("xp-multiplier", 1.0),
                )
            }
        }
    }

    fun findRepairable(material: Material): RepairableEntry? {
        repairables[material]?.let { return it }
        return inferFromMaterialName(material)?.first
    }

    fun findSalvageable(material: Material): SalvageableEntry? {
        salvageables[material]?.let { return it }
        return inferFromMaterialName(material)?.second
    }

    private fun inferFromMaterialName(material: Material): Pair<RepairableEntry, SalvageableEntry>? {
        val name = material.name
        val (prefix, mat, cat) = when {
            name.startsWith("WOODEN_") -> Triple("WOODEN_", Material.OAK_PLANKS, "WOOD")
            name.startsWith("STONE_") -> Triple("STONE_", Material.COBBLESTONE, "STONE")
            name.startsWith("COPPER_") -> Triple("COPPER_", Material.COPPER_INGOT, "COPPER")
            name.startsWith("IRON_") -> Triple("IRON_", Material.IRON_INGOT, "IRON")
            name.startsWith("GOLDEN_") -> Triple("GOLDEN_", Material.GOLD_INGOT, "GOLD")
            name.startsWith("DIAMOND_") -> Triple("DIAMOND_", Material.DIAMOND, "DIAMOND")
            name.startsWith("NETHERITE_") -> Triple("NETHERITE_", Material.NETHERITE_INGOT, "NETHERITE")
            name.startsWith("LEATHER_") -> Triple("LEATHER_", Material.LEATHER, "LEATHER")
            name.startsWith("CHAINMAIL_") -> Triple("CHAINMAIL_", Material.IRON_INGOT, "IRON")
            else -> return null
        }
        val suffix = name.removePrefix(prefix)
        val qty = when (suffix) {
            "HELMET" -> 5
            "CHESTPLATE" -> 8
            "LEGGINGS" -> 7
            "BOOTS" -> 4
            "PICKAXE", "AXE" -> 3
            "SWORD", "HOE" -> 2
            "SHOVEL", "SPEAR" -> 1
            else -> return null
        }
        val xpMult = when (suffix) {
            "SHOVEL", "SPEAR" -> 0.25
            "SWORD", "HOE" -> 0.5
            "PICKAXE", "AXE" -> 1.0
            else -> 1.5
        }
        return RepairableEntry(material, mat, cat, qty, 0, xpMult) to
                SalvageableEntry(material, mat, cat, qty, 0, xpMult)
    }
}

/**
 * Loads Alchemy Concoctions tiers and custom potion recipes from `potions.yml`.
 */
class AlchemyConfig(plugin: Plugin? = null, initialYaml: YamlConfiguration? = null) {

    private val configFile = plugin?.let { YamlConfigFile(it, "potions.yml") }
    val ingredientRankRequired = HashMap<Material, Int>()
    val customRecipes = HashMap<Material, CustomPotionRecipe>()

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
        ingredientRankRequired.clear()
        customRecipes.clear()

        val rankSec = y.getConfigurationSection("concoctions.rank-ingredients")
        if (rankSec != null) {
            for (rankKey in rankSec.getKeys(false)) {
                val rank = rankKey.toIntOrNull() ?: continue
                for (matName in rankSec.getStringList(rankKey)) {
                    Material.matchMaterial(matName)?.let { mat ->
                        ingredientRankRequired[mat] = rank
                    }
                }
            }
        }

        val recSec = y.getConfigurationSection("concoctions.custom-recipes")
        if (recSec != null) {
            for (ingKey in recSec.getKeys(false)) {
                val ing = Material.matchMaterial(ingKey) ?: continue
                val sub = recSec.getConfigurationSection(ingKey) ?: continue
                val effName = (sub.getString("effect", "NIGHT_VISION") ?: "NIGHT_VISION").lowercase()
                val effKey = NamespacedKey.minecraft(effName)
                val effType = Registry.MOB_EFFECT.get(effKey) ?: continue
                val rgb = sub.getInt("color-rgb", 0x38BDF8)
                customRecipes[ing] = CustomPotionRecipe(
                    ingredient = ing,
                    displayName = sub.getString("name", ing.name) ?: ing.name,
                    effectType = effType,
                    durationTicks = sub.getInt("duration-ticks", 2400).coerceAtLeast(20),
                    amplifier = sub.getInt("amplifier", 0).coerceAtLeast(0),
                    color = Color.fromRGB(rgb and 0xFFFFFF),
                    minRank = sub.getInt("min-rank", 1).coerceAtLeast(1),
                )
            }
        }
    }
}
