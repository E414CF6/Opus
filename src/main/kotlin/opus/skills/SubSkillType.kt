package opus.skills

/**
 * All sub-skills across the 19 primary skills.
 */
enum class SubSkillType(
    val parent: SkillType,
    val maxRanks: Int,
    val enName: String,
    val koName: String,
) {
    /* Acrobatics */
    ACROBATICS_DODGE(SkillType.ACROBATICS, 1, "Dodge", "회피"),
    ACROBATICS_ROLL(SkillType.ACROBATICS, 1, "Roll", "구르기"),

    /* Alchemy */
    ALCHEMY_CATALYSIS(SkillType.ALCHEMY, 1, "Catalysis", "촉매 가속"),
    ALCHEMY_CONCOCTIONS(SkillType.ALCHEMY, 8, "Concoctions", "비전 조합"),

    /* Archery */
    ARCHERY_ARROW_RETRIEVAL(SkillType.ARCHERY, 1, "Arrow Retrieval", "화살 회수"),
    ARCHERY_DAZE(SkillType.ARCHERY, 1, "Daze", "현혹"),
    ARCHERY_SKILL_SHOT(SkillType.ARCHERY, 20, "Skill Shot", "정밀 사격"),
    ARCHERY_ARCHERY_LIMIT_BREAK(SkillType.ARCHERY, 10, "Archery Limit Break", "궁술 한계 돌파"),

    /* Axes */
    AXES_ARMOR_IMPACT(SkillType.AXES, 20, "Armor Impact", "갑옷 파쇄"),
    AXES_AXE_MASTERY(SkillType.AXES, 4, "Axe Mastery", "도끼 숙련"),
    AXES_AXES_LIMIT_BREAK(SkillType.AXES, 10, "Axes Limit Break", "도끼술 한계 돌파"),
    AXES_CRITICAL_STRIKES(SkillType.AXES, 1, "Critical Strikes", "치명타"),
    AXES_GREATER_IMPACT(SkillType.AXES, 1, "Greater Impact", "강력한 충격"),
    AXES_SKULL_SPLITTER(SkillType.AXES, 1, "Skull Splitter", "두개골 분쇄"),

    /* Crossbows */
    CROSSBOWS_CROSSBOWS_LIMIT_BREAK(SkillType.CROSSBOWS, 10, "Crossbows Limit Break", "석궁술 한계 돌파"),
    CROSSBOWS_TRICK_SHOT(SkillType.CROSSBOWS, 3, "Trick Shot", "도탄 사격"),
    CROSSBOWS_POWERED_SHOT(SkillType.CROSSBOWS, 20, "Powered Shot", "강화 사격"),

    /* Excavation */
    EXCAVATION_ARCHAEOLOGY(SkillType.EXCAVATION, 8, "Archaeology", "고고학"),
    EXCAVATION_GIGA_DRILL_BREAKER(SkillType.EXCAVATION, 1, "Giga Drill Breaker", "기가 드릴 브레이커"),

    /* Fishing */
    FISHING_FISHERMANS_DIET(SkillType.FISHING, 5, "Fisherman's Diet", "어부의 식단"),
    FISHING_ICE_FISHING(SkillType.FISHING, 1, "Ice Fishing", "얼음 낚시"),
    FISHING_MAGIC_HUNTER(SkillType.FISHING, 1, "Magic Hunter", "마법 낚시"),
    FISHING_MASTER_ANGLER(SkillType.FISHING, 8, "Master Angler", "명인 낚시꾼"),
    FISHING_TREASURE_HUNTER(SkillType.FISHING, 8, "Treasure Hunter", "보물 사냥꾼"),
    FISHING_SHAKE(SkillType.FISHING, 8, "Shake", "낚아채기"),

    /* Herbalism */
    HERBALISM_DOUBLE_DROPS(SkillType.HERBALISM, 1, "Double Drops", "이중 수확"),
    HERBALISM_VERDANT_BOUNTY(SkillType.HERBALISM, 1, "Verdant Bounty", "풍요의 축복"),
    HERBALISM_FARMERS_DIET(SkillType.HERBALISM, 5, "Farmer's Diet", "농부의 식단"),
    HERBALISM_GREEN_TERRA(SkillType.HERBALISM, 1, "Green Terra", "그린 테라"),
    HERBALISM_GREEN_THUMB(SkillType.HERBALISM, 4, "Green Thumb", "녹색 손길"),
    HERBALISM_HYLIAN_LUCK(SkillType.HERBALISM, 1, "Hylian Luck", "하이랄의 행운"),
    HERBALISM_SHROOM_THUMB(SkillType.HERBALISM, 1, "Shroom Thumb", "버섯 손길"),

    /* Maces */
    MACES_MACES_LIMIT_BREAK(SkillType.MACES, 10, "Maces Limit Break", "철퇴술 한계 돌파"),
    MACES_CRUSH(SkillType.MACES, 4, "Crush", "분쇄"),
    MACES_CRIPPLE(SkillType.MACES, 4, "Cripple", "둔화 일격"),

    /* Mining */
    MINING_BIGGER_BOMBS(SkillType.MINING, 1, "Bigger Bombs", "거대 폭탄"),
    MINING_BLAST_MINING(SkillType.MINING, 8, "Blast Mining", "발파 채광"),
    MINING_DEMOLITIONS_EXPERTISE(SkillType.MINING, 1, "Demolitions Expertise", "폭파 전문가"),
    MINING_DOUBLE_DROPS(SkillType.MINING, 1, "Double Drops", "이중 채광"),
    MINING_SUPER_BREAKER(SkillType.MINING, 1, "Super Breaker", "슈퍼 브레이커"),
    MINING_MOTHER_LODE(SkillType.MINING, 1, "Mother Lode", "대광맥"),

    /* Repair */
    REPAIR_ARCANE_FORGING(SkillType.REPAIR, 8, "Arcane Forging", "비전 연마"),
    REPAIR_REPAIR_MASTERY(SkillType.REPAIR, 1, "Repair Mastery", "수리 숙련"),
    REPAIR_SUPER_REPAIR(SkillType.REPAIR, 1, "Super Repair", "대성공 수리"),

    /* Salvage */
    SALVAGE_SCRAP_COLLECTOR(SkillType.SALVAGE, 8, "Scrap Collector", "고철 수집가"),
    SALVAGE_ARCANE_SALVAGE(SkillType.SALVAGE, 8, "Arcane Salvage", "비전 분해"),

    /* Smelting */
    SMELTING_FUEL_EFFICIENCY(SkillType.SMELTING, 3, "Fuel Efficiency", "연료 효율"),
    SMELTING_SECOND_SMELT(SkillType.SMELTING, 1, "Second Smelt", "이중 제련"),
    SMELTING_UNDERSTANDING_THE_ART(SkillType.SMELTING, 8, "Understanding the Art", "제련의 이해"),
    SMELTING_FLUX_MINING(SkillType.SMELTING, 1, "Flux Mining", "용융 채광"),

    /* Spears */
    SPEARS_SPEARS_LIMIT_BREAK(SkillType.SPEARS, 10, "Spears Limit Break", "창술 한계 돌파"),
    SPEARS_MOMENTUM(SkillType.SPEARS, 10, "Momentum", "기세"),
    SPEARS_SPEAR_MASTERY(SkillType.SPEARS, 8, "Spear Mastery", "창 숙련"),

    /* Swords */
    SWORDS_COUNTER_ATTACK(SkillType.SWORDS, 1, "Counter Attack", "반격"),
    SWORDS_RUPTURE(SkillType.SWORDS, 4, "Rupture", "파열 출혈"),
    SWORDS_SERRATED_STRIKES(SkillType.SWORDS, 1, "Serrated Strikes", "톱니 일격"),
    SWORDS_STAB(SkillType.SWORDS, 2, "Stab", "찌르기"),
    SWORDS_SWORDS_LIMIT_BREAK(SkillType.SWORDS, 10, "Swords Limit Break", "검술 한계 돌파"),

    /* Taming */
    TAMING_BEAST_LORE(SkillType.TAMING, 1, "Beast Lore", "야수의 지식"),
    TAMING_CALL_OF_THE_WILD(SkillType.TAMING, 1, "Call of the Wild", "야성의 부름"),
    TAMING_ENVIRONMENTALLY_AWARE(SkillType.TAMING, 1, "Environmentally Aware", "환경 적응"),
    TAMING_FAST_FOOD_SERVICE(SkillType.TAMING, 1, "Fast Food Service", "흡혈 본능"),
    TAMING_GORE(SkillType.TAMING, 1, "Gore", "물어뜯기"),
    TAMING_HOLY_HOUND(SkillType.TAMING, 1, "Holy Hound", "신성한 사냥개"),
    TAMING_PUMMEL(SkillType.TAMING, 1, "Pummel", "강타"),
    TAMING_SHARPENED_CLAWS(SkillType.TAMING, 1, "Sharpened Claws", "날카로운 발톱"),
    TAMING_SHOCK_PROOF(SkillType.TAMING, 1, "Shock Proof", "충격 방어"),
    TAMING_THICK_FUR(SkillType.TAMING, 1, "Thick Fur", "두꺼운 모피"),

    /* Tridents */
    TRIDENTS_IMPALE(SkillType.TRIDENTS, 10, "Impale", "찌르기 강화"),
    TRIDENTS_TRIDENTS_LIMIT_BREAK(SkillType.TRIDENTS, 10, "Tridents Limit Break", "삼지창술 한계 돌파"),

    /* Unarmed */
    UNARMED_ARROW_DEFLECT(SkillType.UNARMED, 1, "Arrow Deflect", "화살 쳐내기"),
    UNARMED_BERSERK(SkillType.UNARMED, 1, "Berserk", "광폭화"),
    UNARMED_BLOCK_CRACKER(SkillType.UNARMED, 1, "Block Cracker", "블록 파쇄"),
    UNARMED_DISARM(SkillType.UNARMED, 1, "Disarm", "무장 해제"),
    UNARMED_STEEL_ARM_STYLE(SkillType.UNARMED, 20, "Steel Arm Style", "강철 팔"),
    UNARMED_IRON_GRIP(SkillType.UNARMED, 1, "Iron Grip", "강철 손아귀"),
    UNARMED_UNARMED_LIMIT_BREAK(SkillType.UNARMED, 10, "Unarmed Limit Break", "맨손격투 한계 돌파"),

    /* Woodcutting */
    WOODCUTTING_KNOCK_ON_WOOD(SkillType.WOODCUTTING, 2, "Knock on Wood", "나무 두드리기"),
    WOODCUTTING_HARVEST_LUMBER(SkillType.WOODCUTTING, 1, "Harvest Lumber", "원목 수확"),
    WOODCUTTING_LEAF_BLOWER(SkillType.WOODCUTTING, 1, "Leaf Blower", "나뭇잎 날리기"),
    WOODCUTTING_TREE_FELLER(SkillType.WOODCUTTING, 1, "Tree Feller", "트리 펠러"),
    WOODCUTTING_CLEAN_CUTS(SkillType.WOODCUTTING, 1, "Clean Cuts", "깔끔한 벌목");

    val permissionNode: String
        get() = "opus.ability.${parent.key}.${name.removePrefix("${parent.name}_").lowercase()}"

    fun displayName(language: String): String =
        if (language.equals("ko", ignoreCase = true)) koName else enName

    companion object {
        private val byParent: Map<SkillType, List<SubSkillType>> = entries.groupBy { it.parent }

        fun forSkill(skill: SkillType): List<SubSkillType> = byParent[skill].orEmpty()
    }
}
