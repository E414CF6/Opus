package opus.skills

import opus.core.config.OpusConfig
import org.bukkit.entity.Player

/**
 * Evaluates sub-skill ranks, unlock thresholds, and skill mastery checks.
 */
class RankService(
    private val config: OpusConfig,
    private val ranksConfig: RanksConfig,
) {

    fun getRank(level: Int, subSkill: SubSkillType): Int =
        ranksConfig.rankForLevel(subSkill, level, config.retroMode)

    fun getRank(state: OpusPlayerState, subSkill: SubSkillType): Int =
        getRank(state.getLevel(subSkill.parent), subSkill)

    fun hasUnlocked(state: OpusPlayerState, subSkill: SubSkillType, player: Player? = null): Boolean {
        if (player != null && !player.hasPermission(subSkill.permissionNode) && !player.hasPermission("opus.ability.*")) {
            return false
        }
        return getRank(state, subSkill) >= 1
    }

    fun nextUnlockLevel(state: OpusPlayerState, subSkill: SubSkillType): Int? {
        val currentRank = getRank(state, subSkill)
        if (currentRank >= subSkill.maxRanks) return null
        return ranksConfig.nextUnlockLevel(subSkill, currentRank, config.retroMode)
    }

    fun masteryThreshold(): Int = if (config.retroMode) 1000 else 100

    fun isMastered(state: OpusPlayerState, skill: SkillType): Boolean =
        state.getLevel(skill) >= masteryThreshold()

    fun masteredCount(state: OpusPlayerState): Int =
        SkillType.nonChildSkills.count { isMastered(state, it) }
}
