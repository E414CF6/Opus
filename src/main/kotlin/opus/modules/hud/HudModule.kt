package opus.modules.hud

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.module.OpusModule
import opus.core.player.OpusPlayerState
import opus.core.player.ScoreboardMode
import opus.core.player.XpHudMode
import opus.core.text.TextFormatter
import opus.skills.HudFeedbackHook
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Modern Paper 26.3 HUD service providing Adventure BossBar XP bars, ActionBar notices,
 * sidebar scoreboards, and mob health bars.
 */
class HudService(private val context: OpusContext) : HudFeedbackHook, Listener {

    private data class ActiveBossBar(
        val skill: SkillType,
        val bar: BossBar,
        var expiresAt: Long,
        var hideTask: ScheduledTask? = null,
    )

    private data class ActiveAbilityBossBar(
        val ability: SuperAbilityType,
        val bar: BossBar,
        val ticker: ScheduledTask? = null,
    )

    private data class ActiveChannelBossBar(
        val bar: BossBar,
        var expiresAt: Long,
        var hideTask: ScheduledTask? = null,
    )

    private val bossBars = ConcurrentHashMap<UUID, ActiveBossBar>()
    private val abilityBossBars = ConcurrentHashMap<UUID, ConcurrentHashMap<SuperAbilityType, ActiveAbilityBossBar>>()
    private val channelBossBars = ConcurrentHashMap<UUID, ConcurrentHashMap<String, ActiveChannelBossBar>>()
    private val boardHideTasks = ConcurrentHashMap<UUID, ScheduledTask>()

    override fun onXpGain(player: Player, state: OpusPlayerState, skill: SkillType, gained: Double, needed: Int) {
        val currentXp = state.getXp(skill)
        val level = state.getLevel(skill)
        val skillName = skill.displayName(context.config.language)

        if (state.hudMode == XpHudMode.BOSSBAR || state.hudMode == XpHudMode.BOTH) {
            showXpBossBar(player, skill, skillName, level, currentXp, needed)
        }
        if (state.hudMode == XpHudMode.ACTIONBAR || state.hudMode == XpHudMode.BOTH) {
            context.locale.sendActionBar(
                player,
                "skill.xp-gain-actionbar",
                "skill" to skillName,
                "xp" to "%.1f".format(gained),
                "current" to currentXp.toInt(),
                "needed" to needed,
            )
        }
    }

    override fun onLevelUp(player: Player, state: OpusPlayerState, skill: SkillType, newLevel: Int) {
        val needed = context.formula.xpToNextLevel(newLevel, context.config.retroMode, state.powerLevel())
        if (state.hudMode == XpHudMode.BOSSBAR || state.hudMode == XpHudMode.BOTH) {
            showXpBossBar(
                player,
                skill,
                skill.displayName(context.config.language),
                newLevel,
                state.getXp(skill),
                needed
            )
        }
        if (state.scoreboardMode == ScoreboardMode.KEEP) {
            showStatsBoard(player, state, persistent = true)
        }
        context.locale.sendActionBar(
            player,
            "skill.level-up-actionbar",
            "skill" to skill.displayName(context.config.language),
            "level" to newLevel,
        )
        updatePowerLevelTag(player, state)
    }

    fun updatePowerLevelTag(player: Player, state: OpusPlayerState) {
        if (!context.config.powerLevelBelowName) return
        val power = state.powerLevel()
        val playerName = player.name
        context.scheduler.runGlobal {
            val main = Bukkit.getScoreboardManager().mainScoreboard
            val obj = ensurePowerObjective(main)
            obj.getScore(playerName).score = power
            for (viewer in Bukkit.getOnlinePlayers()) {
                val viewerBoard = viewer.scoreboard
                if (viewerBoard !== main) {
                    val viewerObj = viewerBoard.getObjective(POWER_OBJECTIVE) ?: continue
                    viewerObj.getScore(playerName).score = power
                }
            }
        }
    }

    fun syncAllPowerLevelTags() {
        context.scheduler.runGlobal {
            val main = Bukkit.getScoreboardManager().mainScoreboard
            if (!context.config.powerLevelBelowName) {
                main.getObjective(POWER_OBJECTIVE)?.unregister()
                for (viewer in Bukkit.getOnlinePlayers()) {
                    val b = viewer.scoreboard
                    if (b !== main) b.getObjective(POWER_OBJECTIVE)?.unregister()
                }
                return@runGlobal
            }
            val obj = ensurePowerObjective(main)
            for (online in Bukkit.getOnlinePlayers()) {
                val power = context.players.getCached(online).powerLevel()
                obj.getScore(online.name).score = power
            }
        }
    }

    private fun ensurePowerObjective(board: Scoreboard) =
        board.getObjective(POWER_OBJECTIVE)
            ?: board.registerNewObjective(
                POWER_OBJECTIVE,
                Criteria.DUMMY,
                context.locale.component("hud.below-name-power"),
            ).also { it.displaySlot = DisplaySlot.BELOW_NAME }

    private fun mirrorPowerObjective(board: Scoreboard) {
        if (!context.config.powerLevelBelowName) return
        val obj = ensurePowerObjective(board)
        for (online in Bukkit.getOnlinePlayers()) {
            obj.getScore(online.name).score = context.players.getCached(online).powerLevel()
        }
    }

    companion object {
        private const val POWER_OBJECTIVE = "opus_power"
        const val MOB_HEALTH_CHANNEL = "mob_health"
        const val XP_EVENT_CHANNEL = "xp_event"
    }

    override fun onActionBarNotice(player: Player, messageKey: String, vararg placeholders: Pair<String, Any?>) {
        context.locale.sendActionBar(player, messageKey, *placeholders)
    }

    override fun onAbilityActivated(
        player: Player,
        ability: SuperAbilityType,
        abilityName: String,
        durationSeconds: Int,
    ) {
        if (durationSeconds <= 0) return
        val totalMs = durationSeconds * 1000L
        val expiresAt = System.currentTimeMillis() + totalMs
        val perPlayer = abilityBossBars.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }

        perPlayer.remove(ability)?.let { prev ->
            prev.ticker?.cancel()
            player.hideBossBar(prev.bar)
        }

        val initialTitle = context.locale.component(
            "hud.ability-bossbar",
            mapOf("ability" to abilityName, "seconds" to durationSeconds.toString()),
        )
        val bar = BossBar.bossBar(initialTitle, 1.0f, ability.skill.barColor, BossBar.Overlay.NOTCHED_10)
        player.showBossBar(bar)

        val ticker = context.scheduler.runForEntityTimer(
            player,
            10L,
            10L,
            retired = {
                perPlayer.remove(ability)?.let { player.hideBossBar(it.bar) }
            },
        ) { task ->
            val remainingMs = expiresAt - System.currentTimeMillis()
            if (!player.isOnline || remainingMs <= 0L) {
                perPlayer.remove(ability)?.let { player.hideBossBar(it.bar) }
                task.cancel()
                return@runForEntityTimer
            }
            val remSec = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)
            val progress = (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0.0f, 1.0f)
            bar.name(
                context.locale.component(
                    "hud.ability-bossbar",
                    mapOf("ability" to abilityName, "seconds" to remSec.toString()),
                )
            )
            bar.progress(progress)
        }

        perPlayer[ability] = ActiveAbilityBossBar(ability, bar, ticker)
    }

    override fun onAbilityExpired(player: Player, ability: SuperAbilityType) {
        val perPlayer = abilityBossBars[player.uniqueId] ?: return
        perPlayer.remove(ability)?.let { active ->
            active.ticker?.cancel()
            player.hideBossBar(active.bar)
        }
    }

    override fun showChannelBossBar(
        player: Player,
        channel: String,
        title: Component,
        color: BossBar.Color,
        progress: Float,
        durationTicks: Long,
        overlay: BossBar.Overlay,
    ) {
        val clamped = progress.coerceIn(0.0f, 1.0f)
        val safeTicks = durationTicks.coerceAtLeast(1L)
        val expiresAt = System.currentTimeMillis() + safeTicks * 50L
        val perPlayer = channelBossBars.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val existing = perPlayer[channel]
        if (existing != null) {
            existing.bar.name(title)
            existing.bar.color(color)
            existing.bar.overlay(overlay)
            existing.bar.progress(clamped)
            existing.expiresAt = expiresAt
            existing.hideTask?.cancel()
            existing.hideTask = scheduleChannelBossBarHide(player, channel, expiresAt, safeTicks)
            return
        }
        val bar = BossBar.bossBar(title, clamped, color, overlay)
        player.showBossBar(bar)
        val entry = ActiveChannelBossBar(bar, expiresAt)
        entry.hideTask = scheduleChannelBossBarHide(player, channel, expiresAt, safeTicks)
        perPlayer[channel] = entry
    }

    override fun hideChannelBossBar(player: Player, channel: String) {
        val perPlayer = channelBossBars[player.uniqueId] ?: return
        perPlayer.remove(channel)?.let { active ->
            active.hideTask?.cancel()
            player.hideBossBar(active.bar)
        }
    }

    private fun scheduleChannelBossBarHide(
        player: Player,
        channel: String,
        targetExpiry: Long,
        delayTicks: Long,
    ): ScheduledTask? {
        return context.scheduler.runForEntityDelayed(player, delayTicks) {
            val perPlayer = channelBossBars[player.uniqueId] ?: return@runForEntityDelayed
            val active = perPlayer[channel] ?: return@runForEntityDelayed
            if (active.expiresAt <= targetExpiry) {
                player.hideBossBar(active.bar)
                perPlayer.remove(channel)
            }
        }
    }

    private fun showXpBossBar(
        player: Player,
        skill: SkillType,
        skillName: String,
        level: Int,
        currentXp: Double,
        needed: Int,
    ) {
        val progress = if (needed <= 0) 1.0f else (currentXp / needed.toDouble()).toFloat().coerceIn(0.0f, 1.0f)
        val title = context.locale.component(
            "skill.xp-bossbar-title",
            mapOf(
                "skill" to skillName,
                "level" to level.toString(),
                "current" to currentXp.toInt().toString(),
                "needed" to needed.toString(),
            ),
        )
        val durationTicks = context.config.bossBarDurationSeconds * 20L
        val expiresAt = System.currentTimeMillis() + (context.config.bossBarDurationSeconds * 1000L)

        val existing = bossBars[player.uniqueId]
        if (existing != null && existing.skill == skill) {
            existing.bar.name(title)
            existing.bar.progress(progress)
            existing.expiresAt = expiresAt
            existing.hideTask?.cancel()
            existing.hideTask = scheduleBossBarHide(player, expiresAt, durationTicks)
            return
        }

        if (existing != null) {
            existing.hideTask?.cancel()
            player.hideBossBar(existing.bar)
        }

        val bar = BossBar.bossBar(title, progress, skill.barColor, BossBar.Overlay.NOTCHED_6)
        player.showBossBar(bar)
        val entry = ActiveBossBar(skill, bar, expiresAt)
        entry.hideTask = scheduleBossBarHide(player, expiresAt, durationTicks)
        bossBars[player.uniqueId] = entry
    }

    private fun scheduleBossBarHide(player: Player, targetExpiry: Long, delayTicks: Long): ScheduledTask? {
        return context.scheduler.runForEntityDelayed(player, delayTicks) {
            val active = bossBars[player.uniqueId] ?: return@runForEntityDelayed
            if (active.expiresAt <= targetExpiry) {
                player.hideBossBar(active.bar)
                bossBars.remove(player.uniqueId)
            }
        }
    }

    fun showSkillBoard(player: Player, state: OpusPlayerState, skill: SkillType) {
        if (!context.config.scoreboardEnabled || state.scoreboardMode == ScoreboardMode.OFF) return
        val level = state.getLevel(skill)
        val needed =
            if (skill.isChild) 0 else context.formula.xpToNextLevel(level, context.config.retroMode, state.powerLevel())
        val lines = mutableListOf(
            context.locale.raw("hud.board-level", "level" to level),
            if (skill.isChild) {
                context.locale.raw("hud.board-child-skill")
            } else {
                context.locale.raw("hud.board-xp", "xp" to state.getXp(skill).toInt(), "needed" to needed)
            },
        )
        for (sub in skill.subSkills().take(8)) {
            val r = context.ranks.getRank(state, sub)
            lines += context.locale.raw(
                "hud.board-subskill",
                "subskill" to context.locale.subSkillName(sub),
                "rank" to r,
            )
        }
        applySidebar(
            player,
            context.locale.raw("hud.board-skill-title", "skill" to context.locale.skillName(skill)),
            lines,
            state.scoreboardMode == ScoreboardMode.KEEP
        )
    }

    fun showStatsBoard(
        player: Player,
        state: OpusPlayerState,
        persistent: Boolean = state.scoreboardMode == ScoreboardMode.KEEP
    ) {
        if (!context.config.scoreboardEnabled || state.scoreboardMode == ScoreboardMode.OFF) return
        val lines = mutableListOf(context.locale.raw("hud.board-power", "power" to state.powerLevel()))
        SkillType.nonChildSkills
            .sortedByDescending { state.getLevel(it) }
            .take(10)
            .forEach { skill ->
                lines += context.locale.raw(
                    "hud.board-skill-line",
                    "skill" to context.locale.skillName(skill),
                    "level" to state.getLevel(skill),
                )
            }
        applySidebar(player, context.locale.raw("hud.board-stats-title"), lines, persistent)
    }

    fun clearBoard(player: Player) {
        boardHideTasks.remove(player.uniqueId)?.cancel()
        context.scheduler.runForEntity(player) {
            player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        }
    }

    private fun applySidebar(player: Player, titleRaw: String, lines: List<String>, persistent: Boolean) {
        context.scheduler.runForEntity(player) {
            val board = Bukkit.getScoreboardManager().newScoreboard
            val obj = board.registerNewObjective("opus_hud", Criteria.DUMMY, TextFormatter.format(titleRaw))
            obj.displaySlot = DisplaySlot.SIDEBAR
            val unique = lines.take(14)
            unique.forEachIndexed { idx, line ->
                val teamName = "line_$idx"
                val entry = "\u00a7${Integer.toHexString(idx)}"
                val team = board.registerNewTeam(teamName)
                team.addEntry(entry)
                team.prefix(TextFormatter.format(line))
                obj.getScore(entry).score = unique.size - idx
            }
            mirrorPowerObjective(board)
            player.scoreboard = board
            boardHideTasks.remove(player.uniqueId)?.cancel()
            if (!persistent) {
                val ticks = context.config.scoreboardDefaultSeconds * 20L
                context.scheduler.runForEntityDelayed(player, ticks) {
                    if (player.isOnline && context.players.getCached(player).scoreboardMode != ScoreboardMode.KEEP) {
                        clearBoard(player)
                    }
                }?.let { boardHideTasks[player.uniqueId] = it }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageEntity(event: EntityDamageByEntityEvent) {
        if (!context.config.mobHealthBarEnabled) return
        val style = context.config.mobHealthBarStyle
        if (style == "DISABLED") return
        val attacker = event.damager as? Player ?: return
        val target = event.entity as? LivingEntity ?: return
        if (target is Player) return

        val maxHp = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        val remaining = (target.health - event.finalDamage).coerceAtLeast(0.0)
        when (style) {
            "BOSSBAR" -> {
                val title = context.locale.component(
                    "hud.mob-health-bar",
                    mapOf(
                        "name" to target.name,
                        "bar" to TextFormatter.progressBar(remaining, maxHp, 10),
                        "hp" to "%.1f".format(remaining),
                        "max_hp" to "%.0f".format(maxHp),
                    ),
                )
                val progress = if (maxHp <= 0.0) 0.0f else (remaining / maxHp).toFloat().coerceIn(0.0f, 1.0f)
                showChannelBossBar(
                    player = attacker,
                    channel = MOB_HEALTH_CHANNEL,
                    title = title,
                    color = BossBar.Color.RED,
                    progress = progress,
                    durationTicks = context.config.mobHealthBarDurationSeconds * 20L,
                )
            }

            "BAR" -> {
                context.locale.sendActionBar(
                    attacker,
                    "hud.mob-health-bar",
                    "name" to target.name,
                    "bar" to TextFormatter.progressBar(remaining, maxHp, 10),
                    "hp" to "%.1f".format(remaining),
                    "max_hp" to "%.0f".format(maxHp),
                )
            }

            else -> {
                val hearts = ceil(remaining / 2.0).toInt().coerceIn(0, 10)
                val maxHearts = ceil(maxHp / 2.0).toInt().coerceIn(1, 10)
                context.locale.sendActionBar(
                    attacker,
                    "hud.mob-health-hearts",
                    "name" to target.name,
                    "hearts" to "❤".repeat(hearts),
                    "empty_hearts" to "❤".repeat((maxHearts - hearts).coerceAtLeast(0)),
                    "hp" to "%.1f".format(remaining),
                    "max_hp" to "%.0f".format(maxHp),
                )
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        context.scheduler.runForEntityDelayed(player, 20L) {
            if (!player.isOnline) return@runForEntityDelayed
            if (context.config.powerLevelBelowName) {
                updatePowerLevelTag(player, context.players.getCached(player))
            }
            if (context.config.xpEventAnnounceOnJoin && context.experienceConfig.xpEventActive) {
                showChannelBossBar(
                    player = player,
                    channel = XP_EVENT_CHANNEL,
                    title = context.locale.component(
                        "hud.xp-event-bossbar",
                        mapOf("rate" to context.experienceConfig.globalMultiplier.toString()),
                    ),
                    color = BossBar.Color.YELLOW,
                    progress = 1.0f,
                    durationTicks = 120L,
                )
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        bossBars.remove(uuid)?.let {
            it.hideTask?.cancel()
            event.player.hideBossBar(it.bar)
        }
        abilityBossBars.remove(uuid)?.values?.forEach {
            it.ticker?.cancel()
            event.player.hideBossBar(it.bar)
        }
        channelBossBars.remove(uuid)?.values?.forEach {
            it.hideTask?.cancel()
            event.player.hideBossBar(it.bar)
        }
        boardHideTasks.remove(uuid)?.cancel()
    }

    fun shutdown() {
        for ((uuid, active) in bossBars) {
            active.hideTask?.cancel()
            Bukkit.getPlayer(uuid)?.hideBossBar(active.bar)
        }
        bossBars.clear()
        for ((uuid, map) in abilityBossBars) {
            val player = Bukkit.getPlayer(uuid)
            map.values.forEach {
                it.ticker?.cancel()
                player?.hideBossBar(it.bar)
            }
        }
        abilityBossBars.clear()
        for ((uuid, map) in channelBossBars) {
            val player = Bukkit.getPlayer(uuid)
            map.values.forEach {
                it.hideTask?.cancel()
                player?.hideBossBar(it.bar)
            }
        }
        channelBossBars.clear()
        boardHideTasks.values.forEach { it.cancel() }
        boardHideTasks.clear()
    }
}

class HudModule(private val context: OpusContext) : OpusModule {
    override val id = "hud"
    override val description = "Adventure BossBar XP bars, ActionBar alerts, sidebar scoreboard, and mob health bars"

    val service = HudService(context)

    override fun onEnable() {
        context.registerService(service)
        context.registerService(HudFeedbackHook::class.java, service)
        service.syncAllPowerLevelTags()
    }

    override fun onReload() {
        service.syncAllPowerLevelTags()
    }

    override fun onDisable() {
        service.shutdown()
    }

    override fun listeners(): List<Listener> = listOf(service)

    override fun registerCommands(registrar: Commands) {
        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.hud.use", executor = { ctx, _ ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    HudDialogs.openSettingsDialog(context, player, service)
                    Command.SINGLE_SUCCESS
                })
            },
            "mmohud",
            "Open HUD and notification settings dialog",
            listOf("hud", "hudsettings"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.hud.use",
                    executor = { ctx, args ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                            ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                        if (args.firstOrNull().equals("dialog", ignoreCase = true)) {
                            HudDialogs.openSettingsDialog(context, player, service)
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val state = context.players.getCached(player)
                        val mode = args.firstOrNull()?.uppercase()?.let { raw ->
                            runCatching { XpHudMode.valueOf(raw) }.getOrNull()
                        } ?: when (state.hudMode) {
                            XpHudMode.BOSSBAR -> XpHudMode.ACTIONBAR
                            XpHudMode.ACTIONBAR -> XpHudMode.BOTH
                            XpHudMode.BOTH -> XpHudMode.OFF
                            XpHudMode.OFF -> XpHudMode.BOSSBAR
                        }
                        state.hudMode = mode
                        state.markDirty()
                        context.locale.send(player, "commands.xpbar-set", "mode" to mode.name)
                        context.locale.sendActionBar(player, "commands.xpbar-set", "mode" to mode.name)
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, _ -> XpHudMode.entries.map { it.name.lowercase() } + "dialog" },
                )
            },
            "mmoxpbar",
            "Configure XP HUD display mode",
            listOf("xpbarsettings"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.hud.use",
                    executor = { ctx, args ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                            ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                        val state = context.players.getCached(player)
                        when (args.firstOrNull()?.lowercase()) {
                            "keep" -> {
                                if (context.config.scoreboardAllowKeep) {
                                    state.scoreboardMode = ScoreboardMode.KEEP
                                    state.markDirty()
                                    service.showStatsBoard(player, state, persistent = true)
                                }
                            }

                            "clear" -> {
                                service.clearBoard(player)
                            }

                            "off" -> {
                                state.scoreboardMode = ScoreboardMode.OFF
                                state.markDirty()
                                service.clearBoard(player)
                            }

                            else -> {
                                state.scoreboardMode = ScoreboardMode.DEFAULT
                                state.markDirty()
                                service.showStatsBoard(player, state, persistent = false)
                            }
                        }
                        context.locale.send(player, "commands.scoreboard-mode", "mode" to state.scoreboardMode.name)
                        context.locale.sendActionBar(
                            player,
                            "commands.scoreboard-mode",
                            "mode" to state.scoreboardMode.name
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, _ -> listOf("keep", "clear", "off", "default") },
                )
            },
            "mcscoreboard",
            "Manage Opus sidebar scoreboard",
            listOf("mcsb"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.hud.use", executor = { ctx, _ ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    val state = context.players.getCached(player)
                    state.notifyAbilities = !state.notifyAbilities
                    state.markDirty()
                    context.locale.send(
                        player,
                        "commands.notify-toggled",
                        "state" to if (state.notifyAbilities) "ON" else "OFF"
                    )
                    context.locale.sendActionBar(
                        player,
                        "commands.notify-toggled",
                        "state" to if (state.notifyAbilities) "ON" else "OFF"
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "mcnotify",
            "Toggle ability notifications",
            listOf("notify"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.hud.use", executor = { ctx, _ ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    val state = context.players.getCached(player)
                    state.levelUpSound = !state.levelUpSound
                    state.markDirty()
                    context.locale.send(
                        player,
                        "commands.sound-toggled",
                        "state" to if (state.levelUpSound) "ON" else "OFF"
                    )
                    context.locale.sendActionBar(
                        player,
                        "commands.sound-toggled",
                        "state" to if (state.levelUpSound) "ON" else "OFF"
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "mclevelupsound",
            "Toggle level-up sound",
            listOf("levelupsound"),
        )
    }
}
