package opus.core.scheduler

import net.kyori.adventure.bossbar.BossBar
import opus.core.OpusContext
import opus.core.ui.ActionBarPriority
import opus.skills.HudFeedbackHook
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Runs a Folia-safe warmup countdown with live BossBar + ActionBar progress and
 * continuous movement/damage cancellation checks.
 */
object WarmupRunner {

    private val activeWarmups = ConcurrentHashMap<String, MutableSet<UUID>>()

    fun start(
        context: OpusContext,
        player: Player,
        channel: String,
        warmupSeconds: Long,
        cancelOnDamage: Boolean = true,
        barColor: BossBar.Color = BossBar.Color.BLUE,
        barTitleKey: String,
        actionBarKey: String,
        cancelledKey: String,
        placeholders: Map<String, String> = emptyMap(),
        onComplete: () -> Unit,
    ): Boolean {
        val set = activeWarmups.computeIfAbsent(channel) { ConcurrentHashMap.newKeySet() }
        if (!set.add(player.uniqueId)) return false

        if (warmupSeconds <= 0L) {
            set.remove(player.uniqueId)
            onComplete()
            return true
        }

        val startLoc = player.location.clone()
        val startHp = player.health
        val totalTicks = warmupSeconds * 20L
        var elapsedTicks = 0L

        val hud = context.getService<HudFeedbackHook>()

        val warmupStateKey = "warmup_$channel"
        fun emitProgress(remainingTicks: Long) {
            val secText = ceil(remainingTicks / 20.0).toInt().coerceAtLeast(1).toString()
            val merged = placeholders + ("seconds" to secText)
            val progress = (remainingTicks.toFloat() / totalTicks.toFloat()).coerceIn(0.0f, 1.0f)
            hud?.showChannelBossBar(
                player = player,
                channel = channel,
                title = context.locale.component(barTitleKey, merged),
                color = barColor,
                progress = progress,
                durationTicks = 25L,
            )
            context.actionBar.setState(
                player = player,
                key = warmupStateKey,
                priority = ActionBarPriority.WARMUP,
                component = context.locale.component(actionBarKey, merged),
                ttlMillis = 1500L,
            )
        }

        emitProgress(totalTicks)

        val stepTicks = 5L
        val task = context.scheduler.runForEntityTimer(
            player,
            stepTicks,
            stepTicks,
            retired = {
                set.remove(player.uniqueId)
                context.actionBar.clearState(player.uniqueId, warmupStateKey)
                hud?.hideChannelBossBar(player, channel)
            },
        ) { scheduled ->
            elapsedTicks += stepTicks
            if (!player.isOnline || player.isDead) {
                set.remove(player.uniqueId)
                context.actionBar.clearState(player.uniqueId, warmupStateKey)
                hud?.hideChannelBossBar(player, channel)
                scheduled.cancel()
                return@runForEntityTimer
            }

            val moved = player.location.world != startLoc.world || player.location.distanceSquared(startLoc) > 1.0
            val damaged = cancelOnDamage && player.health < startHp
            if (moved || damaged) {
                set.remove(player.uniqueId)
                context.actionBar.clearState(player.uniqueId, warmupStateKey)
                hud?.hideChannelBossBar(player, channel)
                context.actionBar.pushTransientLocale(player, cancelledKey)
                scheduled.cancel()
                return@runForEntityTimer
            }

            val remaining = (totalTicks - elapsedTicks).coerceAtLeast(0L)
            if (remaining <= 0L) {
                set.remove(player.uniqueId)
                context.actionBar.clearState(player.uniqueId, warmupStateKey)
                hud?.hideChannelBossBar(player, channel)
                scheduled.cancel()
                onComplete()
                return@runForEntityTimer
            }

            emitProgress(remaining)
        }

        if (task == null) {
            set.remove(player.uniqueId)
            context.actionBar.clearState(player.uniqueId, warmupStateKey)
            hud?.hideChannelBossBar(player, channel)
            return false
        }
        return true
    }

    fun clearAll() {
        activeWarmups.clear()
    }
}
