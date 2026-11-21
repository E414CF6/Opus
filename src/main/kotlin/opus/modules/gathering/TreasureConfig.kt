package opus.modules.gathering

import opus.core.config.YamlConfigFile
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.util.concurrent.ThreadLocalRandom

enum class FishingRarity {
    COMMON,
    UNCOMMON,
    RARE,
    EPIC,
    LEGENDARY,
    MYTHIC,
}

data class ExcavationTreasure(
    val material: Material,
    val amount: Int,
    val xp: Double,
    val chancePercent: Double,
    val minLevelStandard: Int,
    val minLevelRetro: Int,
    val blocks: Set<Material>,
)

data class FishingTreasure(
    val material: Material,
    val rarity: FishingRarity,
    val amount: Int,
    val xp: Double,
)

/**
 * Loads Excavation Archaeology and Fishing Treasure Hunter loot tables from `treasures.yml`.
 */
class TreasureConfig(plugin: Plugin? = null, initialYaml: YamlConfiguration? = null) {

    private val configFile = plugin?.let { YamlConfigFile(it, "treasures.yml") }

    val excavationTreasures = mutableListOf<ExcavationTreasure>()
    val fishingRarityWeights = HashMap<FishingRarity, Double>()
    val fishingRankUnlocks = HashMap<FishingRarity, Int>()
    val fishingItemsByRarity = HashMap<FishingRarity, MutableList<FishingTreasure>>()

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
        excavationTreasures.clear()
        val excSec = y.getConfigurationSection("excavation")
        if (excSec != null) {
            for (key in excSec.getKeys(false)) {
                val mat = Material.matchMaterial(key) ?: continue
                val sub = excSec.getConfigurationSection(key) ?: continue
                val blocks = sub.getStringList("blocks").mapNotNull { Material.matchMaterial(it) }.toSet()
                if (blocks.isEmpty()) continue
                excavationTreasures += ExcavationTreasure(
                    material = mat,
                    amount = sub.getInt("amount", 1).coerceAtLeast(1),
                    xp = sub.getDouble("xp", 50.0),
                    chancePercent = sub.getDouble("chance", 1.0),
                    minLevelStandard = sub.getInt("level-standard", 1),
                    minLevelRetro = sub.getInt("level-retro", 10),
                    blocks = blocks,
                )
            }
        }

        fishingRarityWeights.clear()
        fishingRankUnlocks.clear()
        fishingItemsByRarity.clear()

        for (rarity in FishingRarity.entries) {
            fishingRarityWeights[rarity] = y.getDouble("fishing.rarity-weights.${rarity.name}", 10.0)
            fishingRankUnlocks[rarity] = y.getInt("fishing.rank-unlocks.${rarity.name}", 1)
            fishingItemsByRarity[rarity] = mutableListOf()
        }

        val fishSec = y.getConfigurationSection("fishing.items")
        if (fishSec != null) {
            for (key in fishSec.getKeys(false)) {
                val mat = Material.matchMaterial(key) ?: continue
                val sub = fishSec.getConfigurationSection(key) ?: continue
                val rarity = runCatching {
                    FishingRarity.valueOf((sub.getString("rarity", "COMMON") ?: "COMMON").uppercase())
                }.getOrDefault(FishingRarity.COMMON)
                fishingItemsByRarity.getOrPut(rarity) { mutableListOf() }.add(
                    FishingTreasure(
                        material = mat,
                        rarity = rarity,
                        amount = sub.getInt("amount", 1).coerceAtLeast(1),
                        xp = sub.getDouble("xp", 200.0),
                    )
                )
            }
        }
    }

    fun rollFishingTreasure(treasureHunterRank: Int): FishingTreasure? {
        if (treasureHunterRank <= 0) return null
        val eligible = FishingRarity.entries.filter { rarity ->
            treasureHunterRank >= (fishingRankUnlocks[rarity] ?: 1) &&
                    !fishingItemsByRarity[rarity].isNullOrEmpty()
        }
        if (eligible.isEmpty()) return null
        val totalWeight = eligible.sumOf { (fishingRarityWeights[it] ?: 0.0).coerceAtLeast(0.0) }
        if (totalWeight <= 0.0) return null
        var roll = ThreadLocalRandom.current().nextDouble(totalWeight)
        var chosen: FishingRarity = eligible.last()
        for (rarity in eligible) {
            roll -= (fishingRarityWeights[rarity] ?: 0.0).coerceAtLeast(0.0)
            if (roll <= 0.0) {
                chosen = rarity
                break
            }
        }
        val pool = fishingItemsByRarity[chosen] ?: return null
        return pool[ThreadLocalRandom.current().nextInt(pool.size)]
    }
}
