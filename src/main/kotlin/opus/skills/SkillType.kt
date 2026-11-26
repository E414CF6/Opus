package opus.skills

import net.kyori.adventure.bossbar.BossBar
import opus.core.compat.PlatformCompat
import org.bukkit.Material

enum class SkillCategory(val enName: String, val koName: String) {
    GATHERING("Gathering", "채집"),
    COMBAT("Combat", "전투"),
    CRAFTING("Crafting & Artisan", "제작/가공"),
    SUPPORT("Support", "보조"),
}

/**
 * All 19 primary skills in Opus, preserving mcMMO's full skill roster across Paper 1.20.4–26.3+.
 */
enum class SkillType(
    val category: SkillCategory,
    val enName: String,
    val koName: String,
    private val iconMaterialName: String,
    private val fallbackIcon: Material,
    val barColor: BossBar.Color,
    val isChild: Boolean = false,
) {
    MINING(SkillCategory.GATHERING, "Mining", "채광", "DIAMOND_PICKAXE", Material.DIAMOND_PICKAXE, BossBar.Color.YELLOW),
    WOODCUTTING(SkillCategory.GATHERING, "Woodcutting", "벌목", "DIAMOND_AXE", Material.DIAMOND_AXE, BossBar.Color.GREEN),
    HERBALISM(SkillCategory.GATHERING, "Herbalism", "약초학", "DIAMOND_HOE", Material.DIAMOND_HOE, BossBar.Color.GREEN),
    EXCAVATION(
        SkillCategory.GATHERING,
        "Excavation",
        "발굴",
        "DIAMOND_SHOVEL",
        Material.DIAMOND_SHOVEL,
        BossBar.Color.YELLOW
    ),
    FISHING(SkillCategory.GATHERING, "Fishing", "낚시", "FISHING_ROD", Material.FISHING_ROD, BossBar.Color.PURPLE),

    SWORDS(SkillCategory.COMBAT, "Swords", "검술", "DIAMOND_SWORD", Material.DIAMOND_SWORD, BossBar.Color.BLUE),
    AXES(SkillCategory.COMBAT, "Axes", "도끼술", "NETHERITE_AXE", Material.NETHERITE_AXE, BossBar.Color.BLUE),
    ARCHERY(SkillCategory.COMBAT, "Archery", "궁술", "BOW", Material.BOW, BossBar.Color.BLUE),
    CROSSBOWS(SkillCategory.COMBAT, "Crossbows", "석궁술", "CROSSBOW", Material.CROSSBOW, BossBar.Color.BLUE),
    TRIDENTS(SkillCategory.COMBAT, "Tridents", "삼지창술", "TRIDENT", Material.TRIDENT, BossBar.Color.BLUE),
    MACES(SkillCategory.COMBAT, "Maces", "철퇴술", "MACE", Material.IRON_AXE, BossBar.Color.BLUE),
    SPEARS(SkillCategory.COMBAT, "Spears", "창술", "DIAMOND_SPEAR", Material.TRIDENT, BossBar.Color.BLUE),
    UNARMED(SkillCategory.COMBAT, "Unarmed", "맨손격투", "IRON_INGOT", Material.IRON_INGOT, BossBar.Color.BLUE),
    TAMING(SkillCategory.SUPPORT, "Taming", "조련", "BONE", Material.BONE, BossBar.Color.RED),
    ACROBATICS(SkillCategory.SUPPORT, "Acrobatics", "곡예", "FEATHER", Material.FEATHER, BossBar.Color.PINK),

    REPAIR(SkillCategory.CRAFTING, "Repair", "수리", "ANVIL", Material.ANVIL, BossBar.Color.PURPLE),
    SALVAGE(
        SkillCategory.CRAFTING,
        "Salvage",
        "분해",
        "GOLD_BLOCK",
        Material.GOLD_BLOCK,
        BossBar.Color.PURPLE,
        isChild = true
    ),
    SMELTING(
        SkillCategory.CRAFTING,
        "Smelting",
        "제련",
        "BLAST_FURNACE",
        Material.BLAST_FURNACE,
        BossBar.Color.PURPLE,
        isChild = true
    ),
    ALCHEMY(SkillCategory.CRAFTING, "Alchemy", "연금술", "BREWING_STAND", Material.BREWING_STAND, BossBar.Color.PURPLE);

    val icon: Material
        get() = PlatformCompat.resolveMaterial(iconMaterialName, fallbackIcon)

    val key: String get() = name.lowercase()
    val permissionNode: String get() = "opus.skill.$key"

    fun displayName(language: String): String =
        if (language.startsWith("ko", ignoreCase = true)) koName else enName

    fun parentSkills(): List<SkillType> = when (this) {
        SALVAGE -> listOf(REPAIR, FISHING)
        SMELTING -> listOf(MINING, REPAIR)
        else -> emptyList()
    }

    fun superAbility(): SuperAbilityType? = SuperAbilityType.forSkill(this)

    fun subSkills(): List<SubSkillType> = SubSkillType.forSkill(this)

    companion object {
        val nonChildSkills: List<SkillType> = entries.filter { !it.isChild }
        val childSkills: List<SkillType> = entries.filter { it.isChild }

        fun match(input: String): SkillType? {
            val cleaned = input.trim()
            if (cleaned.isEmpty()) return null
            return entries.firstOrNull {
                it.name.equals(cleaned, ignoreCase = true) ||
                        it.enName.equals(cleaned, ignoreCase = true) ||
                        it.koName.equals(cleaned, ignoreCase = true)
            }
        }
    }
}
