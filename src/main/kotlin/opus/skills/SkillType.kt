package opus.skills

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Material

enum class SkillCategory(val enName: String, val koName: String) {
    GATHERING("Gathering", "채집"),
    COMBAT("Combat", "전투"),
    CRAFTING("Crafting & Artisan", "제작/가공"),
    SUPPORT("Support", "보조"),
}

/**
 * All 19 primary skills in Opus, preserving mcMMO's full skill roster with modern Paper 26.3 metadata.
 */
enum class SkillType(
    val category: SkillCategory,
    val enName: String,
    val koName: String,
    val icon: Material,
    val barColor: BossBar.Color,
    val isChild: Boolean = false,
) {
    MINING(SkillCategory.GATHERING, "Mining", "채광", Material.DIAMOND_PICKAXE, BossBar.Color.YELLOW),
    WOODCUTTING(SkillCategory.GATHERING, "Woodcutting", "벌목", Material.DIAMOND_AXE, BossBar.Color.GREEN),
    HERBALISM(SkillCategory.GATHERING, "Herbalism", "약초학", Material.DIAMOND_HOE, BossBar.Color.GREEN),
    EXCAVATION(SkillCategory.GATHERING, "Excavation", "발굴", Material.DIAMOND_SHOVEL, BossBar.Color.YELLOW),
    FISHING(SkillCategory.GATHERING, "Fishing", "낚시", Material.FISHING_ROD, BossBar.Color.PURPLE),

    SWORDS(SkillCategory.COMBAT, "Swords", "검술", Material.DIAMOND_SWORD, BossBar.Color.BLUE),
    AXES(SkillCategory.COMBAT, "Axes", "도끼술", Material.NETHERITE_AXE, BossBar.Color.BLUE),
    ARCHERY(SkillCategory.COMBAT, "Archery", "궁술", Material.BOW, BossBar.Color.BLUE),
    CROSSBOWS(SkillCategory.COMBAT, "Crossbows", "석궁술", Material.CROSSBOW, BossBar.Color.BLUE),
    TRIDENTS(SkillCategory.COMBAT, "Tridents", "삼지창술", Material.TRIDENT, BossBar.Color.BLUE),
    MACES(SkillCategory.COMBAT, "Maces", "철퇴술", Material.MACE, BossBar.Color.BLUE),
    SPEARS(SkillCategory.COMBAT, "Spears", "창술", Material.DIAMOND_SPEAR, BossBar.Color.BLUE),
    UNARMED(SkillCategory.COMBAT, "Unarmed", "맨손격투", Material.IRON_INGOT, BossBar.Color.BLUE),
    TAMING(SkillCategory.SUPPORT, "Taming", "조련", Material.BONE, BossBar.Color.RED),
    ACROBATICS(SkillCategory.SUPPORT, "Acrobatics", "곡예", Material.FEATHER, BossBar.Color.PINK),

    REPAIR(SkillCategory.CRAFTING, "Repair", "수리", Material.ANVIL, BossBar.Color.PURPLE),
    SALVAGE(SkillCategory.CRAFTING, "Salvage", "분해", Material.GOLD_BLOCK, BossBar.Color.PURPLE, isChild = true),
    SMELTING(SkillCategory.CRAFTING, "Smelting", "제련", Material.BLAST_FURNACE, BossBar.Color.PURPLE, isChild = true),
    ALCHEMY(SkillCategory.CRAFTING, "Alchemy", "연금술", Material.BREWING_STAND, BossBar.Color.PURPLE);

    val key: String get() = name.lowercase()
    val permissionNode: String get() = "opus.skill.$key"

    fun displayName(language: String): String =
        if (language.equals("ko", ignoreCase = true)) koName else enName

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
