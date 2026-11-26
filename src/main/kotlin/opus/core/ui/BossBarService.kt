package opus.core.ui

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import opus.core.scheduler.PluginScheduler
import opus.core.scheduler.TaskHandle
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reusable multi-channel Adventure [BossBar] manager with automatic timed dismissal.
 */
class BossBarService(
    private val scheduler: PluginScheduler,
    private val enabledProvider: () -> Boolean = { true },
) {
    private data class BarEntry(
        val bar: BossBar,
        var hideTask: TaskHandle? = null,
    )

    private val playerBars = ConcurrentHashMap<UUID, ConcurrentHashMap<String, BarEntry>>()

    fun showOrUpdate(
        player: Player,
        key: String,
        title: Component,
        progress: Float,
        color: BossBar.Color = BossBar.Color.BLUE,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
    ) {
        if (!enabledProvider()) return
        val bars = playerBars.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val clamped = progress.coerceIn(0f, 1f)
        val existing = bars[key]
        if (existing != null) {
            existing.hideTask?.cancel()
            existing.hideTask = null
            existing.bar.name(title)
            existing.bar.progress(clamped)
            existing.bar.color(color)
            existing.bar.overlay(overlay)
        } else {
            val created = BossBar.bossBar(title, clamped, color, overlay)
            bars[key] = BarEntry(created)
            player.showBossBar(created)
        }
    }

    fun showTemporary(
        player: Player,
        key: String,
        title: Component,
        progress: Float = 1f,
        color: BossBar.Color = BossBar.Color.BLUE,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
        durationTicks: Long = 60L,
    ) {
        if (!enabledProvider()) return
        showOrUpdate(player, key, title, progress, color, overlay)
        val entry = playerBars[player.uniqueId]?.get(key) ?: return
        entry.hideTask = scheduler.runForEntityDelayed(player, durationTicks.coerceAtLeast(1L)) {
            hide(player, key)
        }
    }

    fun completeAndAutoHide(
        player: Player,
        key: String,
        title: Component,
        color: BossBar.Color = BossBar.Color.GREEN,
        delayTicks: Long = 60L,
    ) {
        showTemporary(player, key, title, 1f, color, BossBar.Overlay.PROGRESS, delayTicks)
    }

    fun hide(player: Player, key: String) {
        val bars = playerBars[player.uniqueId] ?: return
        val entry = bars.remove(key) ?: return
        entry.hideTask?.cancel()
        runCatching { player.hideBossBar(entry.bar) }
        if (bars.isEmpty()) {
            playerBars.remove(player.uniqueId)
        }
    }

    fun hideAll(player: Player) {
        val bars = playerBars.remove(player.uniqueId) ?: return
        for (entry in bars.values) {
            entry.hideTask?.cancel()
            runCatching { player.hideBossBar(entry.bar) }
        }
    }

    fun clearAll() {
        for ((uuid, bars) in playerBars) {
            val player = Bukkit.getPlayer(uuid)
            for (entry in bars.values) {
                entry.hideTask?.cancel()
                if (player != null) {
                    runCatching { player.hideBossBar(entry.bar) }
                }
            }
        }
        playerBars.clear()
    }
}
