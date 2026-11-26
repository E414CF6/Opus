package opus.modules.skills

import opus.core.OpusContext
import opus.events.OpusHardcoreStatLossEvent
import opus.events.OpusVampirismEvent
import opus.integration.WorldGuardHook
import opus.rpg
import opus.skills.OpusPlayerState
import opus.skills.SkillType
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import kotlin.math.floor

data class StatDeltaResult(
    val levelsChanged: MutableMap<SkillType, Int>,
    val xpChanged: MutableMap<SkillType, Double>,
    val totalLevels: Int,
)

/**
 * Handles Hardcore death stat penalties and PvP Vampirism stat stealing.
 */
class HardcoreService(private val context: OpusContext) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.player
        if (victim.hasPermission("opus.bypass.hardcore")) return
        if (context.config.worldBlacklist.contains(victim.world.name.lowercase())) return
        val wg = context.getService<WorldGuardHook>()
        if (wg != null && !wg.isHardcoreAllowedAt(victim)) return

        val killer = victim.killer
        if (killer != null && killer != victim && context.config.hardcoreVampirismEnabled) {
            if (wg == null || wg.isHardcoreAllowedAt(killer)) {
                applyVampirism(killer, victim)
            }
        }

        if (context.config.hardcoreStatLossEnabled) {
            applyDeathStatLoss(victim)
        }
    }

    private fun applyDeathStatLoss(victim: org.bukkit.entity.Player) {
        val state = context.rpg.players.getCached(victim)
        val delta = computeDeathStatLoss(
            state = state,
            percentage = context.config.hardcoreStatLossPercentage,
            threshold = context.config.hardcoreStatLossThreshold,
            excludedSkills = context.config.hardcoreExcludedSkills,
        )
        if (delta.totalLevels <= 0 && delta.xpChanged.values.all { it <= 0.0 }) return

        val lossEvent = OpusHardcoreStatLossEvent(victim, delta.levelsChanged, delta.xpChanged)
        Bukkit.getPluginManager().callEvent(lossEvent)
        if (lossEvent.isCancelled) return

        var totalLost = 0
        for (skill in SkillType.nonChildSkills) {
            val lvlLost = lossEvent.levelsLost[skill] ?: 0
            val xpLost = lossEvent.xpLost[skill] ?: 0.0
            if (lvlLost > 0 || xpLost > 0.0) {
                val nextLvl = (state.getLevel(skill) - lvlLost).coerceAtLeast(0)
                val nextXp = (state.getXp(skill) - xpLost).coerceAtLeast(0.0)
                state.setSkill(skill, nextLvl, nextXp)
                totalLost += lvlLost
            }
        }
        if (totalLost > 0) {
            context.locale.send(victim, "gameplay.hardcore-stat-loss", "levels" to totalLost)
        }
    }

    private fun applyVampirism(killer: org.bukkit.entity.Player, victim: org.bukkit.entity.Player) {
        val killerState = context.rpg.players.getCached(killer)
        val victimState = context.rpg.players.getCached(victim)
        val delta = computeVampirism(
            killerState = killerState,
            victimState = victimState,
            percentage = context.config.hardcoreVampirismPercentage,
            threshold = context.config.hardcoreVampirismThreshold,
            excludedSkills = context.config.hardcoreExcludedSkills,
        )
        if (delta.totalLevels <= 0) return

        val vampEvent = OpusVampirismEvent(killer, victim, delta.levelsChanged, delta.xpChanged)
        Bukkit.getPluginManager().callEvent(vampEvent)
        if (vampEvent.isCancelled) return

        var totalStolen = 0
        for (skill in SkillType.nonChildSkills) {
            val stolen = vampEvent.levelsStolen[skill] ?: 0
            val xpStolen = vampEvent.xpStolen[skill] ?: 0.0
            if (stolen > 0 || xpStolen > 0.0) {
                victimState.setSkill(
                    skill,
                    (victimState.getLevel(skill) - stolen).coerceAtLeast(0),
                    (victimState.getXp(skill) - xpStolen).coerceAtLeast(0.0),
                )
                killerState.setSkill(
                    skill,
                    killerState.getLevel(skill) + stolen,
                    killerState.getXp(skill) + xpStolen,
                )
                totalStolen += stolen
            }
        }
        if (totalStolen > 0) {
            context.locale.send(killer, "gameplay.vampirism-killer", "levels" to totalStolen, "victim" to victim.name)
            context.locale.send(victim, "gameplay.vampirism-victim", "levels" to totalStolen, "killer" to killer.name)
        }
    }

    companion object {
        fun computeDeathStatLoss(
            state: OpusPlayerState,
            percentage: Double,
            threshold: Int,
            excludedSkills: Set<String> = emptySet(),
        ): StatDeltaResult {
            val levels = HashMap<SkillType, Int>()
            val xp = HashMap<SkillType, Double>()
            var total = 0
            for (skill in SkillType.nonChildSkills) {
                if (skill.key in excludedSkills) continue
                val lvl = state.getLevel(skill)
                if (lvl <= 0 || lvl <= threshold) continue
                val rawLoss = (lvl - threshold).coerceAtLeast(0) * (percentage * 0.01)
                val lvlLoss = rawLoss.toInt()
                val xpLoss = floor(state.getXp(skill) * (rawLoss - lvlLoss))
                if (lvlLoss > 0 || xpLoss > 0.0) {
                    levels[skill] = lvlLoss
                    xp[skill] = xpLoss
                    total += lvlLoss
                }
            }
            return StatDeltaResult(levels, xp, total)
        }

        fun computeVampirism(
            killerState: OpusPlayerState,
            victimState: OpusPlayerState,
            percentage: Double,
            threshold: Int,
            excludedSkills: Set<String> = emptySet(),
        ): StatDeltaResult {
            val levels = HashMap<SkillType, Int>()
            val xp = HashMap<SkillType, Double>()
            var total = 0
            for (skill in SkillType.nonChildSkills) {
                if (skill.key in excludedSkills) continue
                val killerLvl = killerState.getLevel(skill)
                val victimLvl = victimState.getLevel(skill)
                if (victimLvl <= 0 || victimLvl <= threshold || victimLvl < killerLvl / 2) continue
                val rawStolen = victimLvl * (percentage * 0.01)
                val lvlStolen = rawStolen.toInt()
                val xpStolen = floor(victimState.getXp(skill) * (rawStolen - lvlStolen))
                if (lvlStolen > 0 || xpStolen > 0.0) {
                    levels[skill] = lvlStolen
                    xp[skill] = xpStolen
                    total += lvlStolen
                }
            }
            return StatDeltaResult(levels, xp, total)
        }
    }
}
