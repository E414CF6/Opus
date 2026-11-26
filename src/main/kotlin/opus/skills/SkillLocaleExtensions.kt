package opus.skills

import opus.core.locale.LocaleService

fun LocaleService.skillName(skill: SkillType): String {
    return rawOrNull("skills.${skill.key}") ?: skill.displayName(currentLanguage)
}

fun LocaleService.subSkillName(sub: SubSkillType): String {
    return rawOrNull("subskills.${sub.name.lowercase()}") ?: sub.displayName(currentLanguage)
}

fun LocaleService.abilityName(ability: SuperAbilityType): String {
    return rawOrNull("abilities.${ability.name.lowercase()}") ?: ability.displayName(currentLanguage)
}

fun LocaleService.categoryName(category: SkillCategory): String {
    return rawOrNull("categories.${category.name.lowercase()}")
        ?: if (currentLanguage.startsWith("ko", ignoreCase = true)) category.koName else category.enName
}
