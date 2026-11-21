package opus.skills

import opus.core.config.ExperienceConfig
import org.bukkit.permissions.Permissible

/**
 * Evaluates permission-based XP, cooldown, activation duration, and lucky perks.
 */
class PerksService(private val experienceConfig: ExperienceConfig) {

    private data class XpTier(val key: String, val multiplier: Double)

    private val xpTiers = listOf(
        XpTier("quadruple", 4.0),
        XpTier("triple", 3.0),
        XpTier("250percent", 2.5),
        XpTier("double", 2.0),
        XpTier("150percent", 1.5),
        XpTier("125percent", 1.25),
        XpTier("110percent", 1.1),
    )

    fun resolveXpMultiplier(permissible: Permissible, skill: SkillType): Double =
        resolveXpMultiplier(skill) { node -> permissible.hasPermission(node) }

    fun resolveXpMultiplier(skill: SkillType, hasPerm: (String) -> Boolean): Double {
        if (hasPerm("opus.perks.xp.customboost.${skill.key}") ||
            hasPerm("opus.perks.xp.customboost.all") ||
            hasPerm("opus.xp.boost.${skill.key}") ||
            hasPerm("opus.xp.boost.*")
        ) {
            return experienceConfig.customXpPerkBoost
        }
        for (tier in xpTiers) {
            if (hasPerm("opus.perks.xp.${tier.key}.${skill.key}") ||
                hasPerm("opus.perks.xp.${tier.key}.all")
            ) {
                return tier.multiplier
            }
        }
        return 1.0
    }

    fun applyCooldownPerk(permissible: Permissible, baseCooldownSeconds: Int): Int =
        applyCooldownPerk(baseCooldownSeconds) { node -> permissible.hasPermission(node) }

    fun applyCooldownPerk(baseCooldownSeconds: Int, hasPerm: (String) -> Boolean): Int {
        val factor = when {
            hasPerm("opus.perks.cooldowns.halved") -> 0.5
            hasPerm("opus.perks.cooldowns.thirded") -> 2.0 / 3.0
            hasPerm("opus.perks.cooldowns.quartered") -> 0.75
            else -> 1.0
        }
        return (baseCooldownSeconds * factor).toInt().coerceAtLeast(1)
    }

    fun applyActivationDurationPerk(permissible: Permissible, baseSeconds: Int): Int =
        applyActivationDurationPerk(baseSeconds) { node -> permissible.hasPermission(node) }

    fun applyActivationDurationPerk(baseSeconds: Int, hasPerm: (String) -> Boolean): Int {
        val bonus = when {
            hasPerm("opus.perks.activationtime.twelveseconds") -> 12
            hasPerm("opus.perks.activationtime.eightseconds") -> 8
            hasPerm("opus.perks.activationtime.fourseconds") -> 4
            else -> 0
        }
        return (baseSeconds + bonus).coerceAtLeast(1)
    }

    fun hasLuckyPerk(permissible: Permissible, skill: SkillType): Boolean =
        hasLuckyPerk(skill) { node -> permissible.hasPermission(node) }

    fun hasLuckyPerk(skill: SkillType, hasPerm: (String) -> Boolean): Boolean =
        hasPerm("opus.perks.lucky.${skill.key}") || hasPerm("opus.perks.lucky.all")
}
