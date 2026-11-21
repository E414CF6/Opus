package opus.skills

import opus.core.config.OpusConfig
import opus.core.config.SkillsConfig
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import java.util.concurrent.ThreadLocalRandom

/**
 * Level-scaled probability calculations (with Luck attribute & attack-cooldown weighting) and RNG checks.
 */
class ProbabilityService(
    private val config: OpusConfig,
    private val skillsConfig: SkillsConfig,
    private val perks: PerksService? = null,
) {

    fun linearChancePercent(
        skillLevel: Int,
        maxBonusLevel: Int,
        chanceMax: Double,
        player: Player? = null,
        attackStrength: Double = 1.0,
        skill: SkillType? = null,
    ): Double {
        val cappedLevel = skillLevel.coerceIn(0, maxBonusLevel.coerceAtLeast(1))
        var chance = (cappedLevel.toDouble() / maxBonusLevel.toDouble()) * chanceMax
        if (player != null) {
            val luck = player.getAttribute(Attribute.LUCK)?.value ?: 0.0
            if (luck != 0.0) {
                chance *= (1.0 + (luck * 0.05)).coerceAtLeast(0.1)
            }
            if (skill != null && perks?.hasLuckyPerk(player, skill) == true) {
                chance *= 1.3333333333333333
            }
        }
        if (skillsConfig.adjustForAttackCooldown) {
            chance *= attackStrength.coerceIn(0.0, 1.0)
        }
        return chance.coerceIn(0.0, 100.0)
    }

    fun rollPercent(chancePercent: Double): Boolean {
        if (chancePercent <= 0.0) return false
        if (chancePercent >= 100.0) return true
        return ThreadLocalRandom.current().nextDouble(100.0) < chancePercent
    }

    fun abilityDurationSeconds(skillLevel: Int): Int {
        val base = skillsConfig.abilityBaseDurationSec
        val max = skillsConfig.abilityMaxDurationSec
        val inc =
            if (config.retroMode) skillsConfig.abilityRetroIncreaseLevel else skillsConfig.abilityStandardIncreaseLevel
        val cap = if (config.retroMode) skillsConfig.abilityRetroCapLevel else skillsConfig.abilityStandardCapLevel
        val effective = skillLevel.coerceIn(0, cap)
        val bonus = effective / inc.coerceAtLeast(1)
        return (base + bonus).coerceIn(base, max)
    }
}
