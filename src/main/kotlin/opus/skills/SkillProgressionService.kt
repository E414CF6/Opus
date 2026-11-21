package opus.skills

import opus.core.OpusContext
import opus.core.player.OpusPlayerState
import opus.core.text.TextFormatter
import opus.events.*
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player

interface PartyXpShareHook {
    fun shareXp(source: Player, skill: SkillType, rawXp: Double, awardDirect: (Player, SkillType, Double) -> Unit)
}

interface HudFeedbackHook {
    fun onXpGain(player: Player, state: OpusPlayerState, skill: SkillType, gained: Double, needed: Int)
    fun onLevelUp(player: Player, state: OpusPlayerState, skill: SkillType, newLevel: Int)
    fun onActionBarNotice(player: Player, messageKey: String, vararg placeholders: Pair<String, Any?>)
}

/**
 * Coordinates XP awards, level-ups, sub-skill rank unlocks, and Super Ability lifecycle.
 */
class SkillProgressionService(private val context: OpusContext) {

    fun awardXp(
        player: Player,
        skill: SkillType,
        rawAmount: Double,
        reason: XpGainReason = XpGainReason.OTHER,
        allowPartyShare: Boolean = true,
    ) {
        if (rawAmount <= 0.0) return
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return
        if (context.config.worldBlacklist.contains(player.world.name.lowercase())) return
        if (!player.hasPermission(skill.permissionNode) && !player.hasPermission("opus.skill.*")) return

        if (skill.isChild) {
            val parents = skill.parentSkills()
            if (parents.isEmpty()) return
            val share = rawAmount / parents.size.toDouble()
            for (parent in parents) {
                awardXp(player, parent, share, reason, allowPartyShare)
            }
            return
        }

        val state = context.players.getCached(player)
        val expCfg = context.experienceConfig

        var effective = rawAmount * expCfg.skillMultiplier(skill) * expCfg.globalMultiplier
        if (reason == XpGainReason.PVP) {
            effective *= expCfg.pvpMultiplier
        }
        if (player.hasPermission("opus.xp.boost.${skill.key}") || player.hasPermission("opus.xp.boost.*")) {
            effective *= expCfg.customXpPerkBoost
        }
        if (expCfg.earlyGameBoostEnabled) {
            val level = state.getLevel(skill)
            val threshold = if (context.config.retroMode) 200 else 20
            if (level < threshold) {
                effective *= 2.0
            }
        }
        if (expCfg.diminishedReturnsEnabled) {
            val windowMs = expCfg.diminishedReturnsMinutes * 60_000L
            val earned = state.trackDiminishedWindow(skill, windowMs, effective)
            if (earned > expCfg.diminishedReturnsThreshold) {
                effective *= expCfg.diminishedReturnsMinimum
            }
        }

        if (effective <= 0.0) return

        if (allowPartyShare && reason != XpGainReason.SHARED_PARTY) {
            context.getService<PartyXpShareHook>()?.shareXp(player, skill, effective) { member, s, shared ->
                awardXpDirect(member, s, shared, XpGainReason.SHARED_PARTY)
            }
        }

        awardXpDirect(player, skill, effective, reason)
    }

    fun awardXpDirect(
        player: Player,
        skill: SkillType,
        amount: Double,
        reason: XpGainReason = XpGainReason.OTHER,
    ) {
        if (skill.isChild || amount <= 0.0) return
        val state = context.players.getCached(player)
        val cap = effectiveLevelCap(skill, state)
        val currentLevel = state.getLevel(skill)
        if (cap in 1..currentLevel) return

        val xpEvent = OpusSkillXpGainEvent(player, skill, amount, reason)
        Bukkit.getPluginManager().callEvent(xpEvent)
        if (xpEvent.isCancelled || xpEvent.xpGained <= 0.0) return

        var level = currentLevel
        var xp = state.getXp(skill) + xpEvent.xpGained
        val power = state.powerLevel()
        var leveledUp = false

        while (true) {
            if (cap in 1..level) {
                xp = 0.0
                break
            }
            val needed = context.formula.xpToNextLevel(level, context.config.retroMode, power).toDouble()
            if (xp < needed) break

            val prevLevel = level
            val nextLevel = level + 1
            val lvlEvent = OpusSkillLevelUpEvent(player, skill, prevLevel, nextLevel)
            Bukkit.getPluginManager().callEvent(lvlEvent)
            if (lvlEvent.isCancelled) break

            xp -= needed
            level = nextLevel
            leveledUp = true
            notifySubSkillUnlocks(player, skill, prevLevel, nextLevel)
        }

        state.setSkill(skill, level, xp)

        val neededAfter = context.formula.xpToNextLevel(level, context.config.retroMode, state.powerLevel())
        val hud = context.getService<HudFeedbackHook>()
        hud?.onXpGain(player, state, skill, xpEvent.xpGained, neededAfter)

        if (leveledUp) {
            onLevelUp(player, state, skill, currentLevel, level)
            hud?.onLevelUp(player, state, skill, level)
        }
    }

    fun effectiveLevelCap(skill: SkillType, state: OpusPlayerState): Int {
        val skillCap = context.skillsConfig.levelCap(skill)
        val powerCap = context.config.powerLevelCap
        if (powerCap <= 0) return skillCap
        val remainingPower = (powerCap - state.powerLevel()).coerceAtLeast(0)
        val maxFromPower = state.getLevel(skill) + remainingPower
        return if (skillCap <= 0) maxFromPower else minOf(skillCap, maxFromPower)
    }

    private fun notifySubSkillUnlocks(player: Player, skill: SkillType, oldLevel: Int, newLevel: Int) {
        val lang = context.config.language
        for (sub in skill.subSkills()) {
            val oldRank = context.ranks.getRank(oldLevel, sub)
            val newRank = context.ranks.getRank(newLevel, sub)
            if (newRank > oldRank) {
                context.locale.send(
                    player,
                    "skill.subskill-unlocked",
                    "subskill" to sub.displayName(lang),
                    "rank" to newRank,
                )
            }
        }
    }

    private fun onLevelUp(player: Player, state: OpusPlayerState, skill: SkillType, oldLevel: Int, newLevel: Int) {
        val lang = context.config.language
        val skillName = skill.displayName(lang)
        val totalPower = state.powerLevel()
        context.locale.send(
            player,
            "skill.level-up",
            "skill" to skillName,
            "level" to newLevel,
            "total" to totalPower,
        )
        if (context.config.levelUpSounds && state.levelUpSound) {
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f)
        }

        if (context.config.levelUpBroadcastEnabled && player.hasPermission("opus.broadcast.levelup")) {
            val interval = context.config.levelUpMilestoneInterval
            if (newLevel / interval > oldLevel / interval) {
                val msg = context.locale.prefixedComponent(
                    "skill.level-up-broadcast",
                    mapOf("player" to player.name, "skill" to skillName, "level" to newLevel.toString()),
                )
                broadcastFiltered(player, msg)
            }
        }
        if (context.config.powerLevelBroadcastEnabled && player.hasPermission("opus.broadcast.levelup")) {
            val diff = newLevel - oldLevel
            val oldPower = totalPower - diff
            val pInterval = context.config.powerLevelMilestoneInterval
            if (totalPower / pInterval > oldPower / pInterval) {
                val msg = context.locale.prefixedComponent(
                    "skill.powerlevel-broadcast",
                    mapOf("player" to player.name, "power" to totalPower.toString()),
                )
                broadcastFiltered(player, msg)
            }
        }
    }

    private fun broadcastFiltered(source: Player, message: net.kyori.adventure.text.Component) {
        for (target in Bukkit.getOnlinePlayers()) {
            if (context.config.broadcastOnlySameWorld && target.world != source.world) continue
            target.sendMessage(message)
        }
        if (context.config.broadcastSendToConsole) {
            Bukkit.getConsoleSender().sendMessage(message)
        }
    }

    fun readyTool(player: Player, skill: SkillType) {
        val ability = skill.superAbility() ?: return
        val state = context.players.getCached(player)
        if (!state.abilityReadyingEnabled) return
        if (state.isAbilityActive(ability)) return
        if (!player.hasPermission(ability.permissionNode) && !player.hasPermission("opus.ability.*")) return

        val linked = ability.linkedSubSkill
        if (linked != null && !context.ranks.hasUnlocked(state, linked, player)) return

        val remainingCd = state.getCooldownRemainingMillis(ability)
        if (remainingCd > 0L && !player.hasPermission("opus.bypass.cooldown")) {
            sendAbilityNotice(
                player,
                state,
                "skill.ability-cooldown",
                "ability" to ability.displayName(context.config.language),
                "time" to TextFormatter.formatDuration(remainingCd),
            )
            return
        }

        if (state.isToolReadied(skill)) return
        state.readyTool(skill)
        val abilityName = ability.displayName(context.config.language)
        sendAbilityNotice(player, state, "skill.ability-ready", "ability" to abilityName)

        context.scheduler.runForEntityDelayed(player, 80L) {
            if (state.isToolReadied(skill)) {
                state.unreadyTool(skill)
                if (!state.isAbilityActive(ability) && state.notifyAbilities) {
                    sendAbilityNotice(player, state, "skill.ability-unready", "ability" to abilityName)
                }
            }
        }
    }

    fun tryActivateAbility(player: Player, skill: SkillType): Boolean {
        val ability = skill.superAbility() ?: return false
        val state = context.players.getCached(player)
        if (state.isAbilityActive(ability)) return true
        if (!state.isToolReadied(skill)) return false

        val remainingCd = state.getCooldownRemainingMillis(ability)
        if (remainingCd > 0L && !player.hasPermission("opus.bypass.cooldown")) {
            return false
        }

        val level = state.getLevel(skill)
        val durationSec = context.probability.abilityDurationSeconds(level)
        val cooldownSec = context.skillsConfig.abilityCooldownSec

        val event = OpusSuperAbilityActivateEvent(player, ability, durationSec, cooldownSec)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return false

        val now = System.currentTimeMillis()
        state.activateAbility(ability, event.durationSeconds * 1000L, now)
        if (!player.hasPermission("opus.bypass.cooldown")) {
            state.setCooldown(ability, event.cooldownSeconds * 1000L, now)
        }

        val lang = context.config.language
        val abilityName = ability.displayName(lang)
        sendAbilityNotice(
            player,
            state,
            "skill.ability-activated",
            "ability" to abilityName,
            "duration" to event.durationSeconds,
        )
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.4f)

        // Alert nearby players
        for (nearby in player.world.players) {
            if (nearby != player && nearby.location.distanceSquared(player.location) <= 2500.0) {
                context.locale.send(
                    nearby,
                    "skill.ability-activated-other",
                    "player" to player.name,
                    "ability" to abilityName,
                )
            }
        }

        context.scheduler.runForEntityDelayed(player, event.durationSeconds * 20L) {
            if (state.isAbilityActive(ability)) {
                state.deactivateAbility(ability)
            }
            Bukkit.getPluginManager().callEvent(OpusSuperAbilityExpireEvent(player, ability))
            if (player.isOnline) {
                sendAbilityNotice(player, state, "skill.ability-expired", "ability" to abilityName)
            }
        }

        if (!player.hasPermission("opus.bypass.cooldown") && event.cooldownSeconds > 0) {
            context.scheduler.runForEntityDelayed(player, event.cooldownSeconds * 20L) {
                if (player.isOnline && state.getCooldownRemainingMillis(ability) == 0L) {
                    sendAbilityNotice(player, state, "skill.ability-refreshed", "ability" to abilityName)
                }
            }
        }
        return true
    }

    private fun sendAbilityNotice(
        player: Player,
        state: OpusPlayerState,
        key: String,
        vararg placeholders: Pair<String, Any?>,
    ) {
        if (!state.notifyAbilities) return
        val hud = context.getService<HudFeedbackHook>()
        if (hud != null && context.config.actionBarNotifications) {
            hud.onActionBarNotice(player, key, *placeholders)
        } else {
            context.locale.send(player, key, *placeholders)
        }
    }
}
