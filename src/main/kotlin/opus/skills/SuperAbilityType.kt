package opus.skills

/**
 * Active right-click Super Abilities tied to primary skills.
 */
enum class SuperAbilityType(
    val skill: SkillType,
    val enName: String,
    val koName: String,
    val linkedSubSkill: SubSkillType? = null,
) {
    SUPER_BREAKER(SkillType.MINING, "Super Breaker", "슈퍼 브레이커", SubSkillType.MINING_SUPER_BREAKER),
    GIGA_DRILL_BREAKER(
        SkillType.EXCAVATION,
        "Giga Drill Breaker",
        "기가 드릴 브레이커",
        SubSkillType.EXCAVATION_GIGA_DRILL_BREAKER
    ),
    TREE_FELLER(SkillType.WOODCUTTING, "Tree Feller", "트리 펠러", SubSkillType.WOODCUTTING_TREE_FELLER),
    GREEN_TERRA(SkillType.HERBALISM, "Green Terra", "그린 테라", SubSkillType.HERBALISM_GREEN_TERRA),
    BERSERK(SkillType.UNARMED, "Berserk", "광폭화", SubSkillType.UNARMED_BERSERK),
    SERRATED_STRIKES(SkillType.SWORDS, "Serrated Strikes", "톱니 일격", SubSkillType.SWORDS_SERRATED_STRIKES),
    SKULL_SPLITTER(SkillType.AXES, "Skull Splitter", "두개골 분쇄", SubSkillType.AXES_SKULL_SPLITTER),
    EXPLOSIVE_SHOT(SkillType.ARCHERY, "Explosive Shot", "폭발 화살"),
    SUPER_SHOTGUN(SkillType.CROSSBOWS, "Super Shotgun", "슈퍼 샷건"),
    TRIDENTS_SUPER_ABILITY(SkillType.TRIDENTS, "Tidal Surge", "해일의 일격"),
    MACES_SUPER_ABILITY(SkillType.MACES, "Seismic Slam", "대지 강타"),
    SPEARS_SUPER_ABILITY(SkillType.SPEARS, "Vanguard Thrust", "선봉 돌격");

    val permissionNode: String
        get() = linkedSubSkill?.permissionNode ?: "opus.ability.${skill.key}.${name.lowercase()}"

    fun displayName(language: String): String =
        if (language.equals("ko", ignoreCase = true)) koName else enName

    companion object {
        private val bySkill = entries.associateBy { it.skill }

        fun forSkill(skill: SkillType): SuperAbilityType? = bySkill[skill]
    }
}
