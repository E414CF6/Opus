package opus.core.ui

import net.kyori.adventure.text.Component
import opus.core.locale.LocaleService
import opus.core.scheduler.PluginScheduler
import opus.core.scheduler.TaskHandle
import opus.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ActionBarPriority(val weight: Int) {
    XP(20),
    ABILITY(50),
    COMBAT(70),
    WARMUP(90),
    TRANSIENT(100),
}

/**
 * Coordinates persistent state HUDs and transient feedback on the ActionBar without flickering or clobbering.
 */
class ActionBarService(
    private val scheduler: PluginScheduler = PluginScheduler(null),
    private val locale: LocaleService? = null,
    private val lowPriorityEnabledProvider: () -> Boolean = { true },
) : Listener {

    data class StateEntry(
        val key: String,
        val priority: ActionBarPriority,
        val component: Component,
        val expiresAtMillis: Long = Long.MAX_VALUE,
    )

    private val playerStates = ConcurrentHashMap<UUID, ConcurrentHashMap<String, StateEntry>>()
    private var tickTask: TaskHandle? = null

    fun start() {
        stop()
        tickTask = scheduler.runAsyncTimerMillis(50L, 50L) {
            tickAll()
        }
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        playerStates.clear()
    }

    fun setState(
        player: Player,
        key: String,
        priority: ActionBarPriority,
        component: Component,
        ttlMillis: Long? = null,
    ) {
        setState(player.uniqueId, key, priority, component, ttlMillis)
        dispatchNow(player)
    }

    fun setState(
        uuid: UUID,
        key: String,
        priority: ActionBarPriority,
        component: Component,
        ttlMillis: Long? = null,
    ) {
        val expires = if (ttlMillis != null && ttlMillis > 0L) {
            System.currentTimeMillis() + ttlMillis
        } else {
            Long.MAX_VALUE
        }
        val map = playerStates.computeIfAbsent(uuid) { ConcurrentHashMap() }
        map[key] = StateEntry(key, priority, component, expires)
    }

    fun setLocaleState(
        player: Player,
        stateKey: String,
        priority: ActionBarPriority,
        localeKey: String,
        vararg placeholders: Pair<String, Any?>,
        ttlMillis: Long? = null,
    ) {
        val loc = locale ?: return
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        setState(player, stateKey, priority, loc.component(localeKey, map), ttlMillis)
    }

    fun clearState(uuid: UUID, key: String) {
        val map = playerStates[uuid] ?: return
        map.remove(key)
        if (map.isEmpty()) {
            playerStates.remove(uuid)
        }
    }

    fun pushTransient(player: Player, component: Component, durationMillis: Long = 2500L) {
        setState(player, TRANSIENT_KEY, ActionBarPriority.TRANSIENT, component, durationMillis)
    }

    fun pushTransientRaw(player: Player, miniMessage: String, durationMillis: Long = 2500L) {
        pushTransient(player, TextFormatter.format(miniMessage), durationMillis)
    }

    fun pushTransientLocale(
        player: Player,
        localeKey: String,
        vararg placeholders: Pair<String, Any?>,
        durationMillis: Long = 2500L,
    ) {
        val loc = locale ?: return
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        pushTransient(player, loc.component(localeKey, map), durationMillis)
    }

    fun resolveActiveEntry(
        uuid: UUID,
        nowMillis: Long = System.currentTimeMillis(),
        includeLowPriorityHud: Boolean = true,
    ): StateEntry? {
        val map = playerStates[uuid] ?: return null
        var best: StateEntry? = null
        val expired = mutableListOf<String>()
        for ((k, entry) in map) {
            if (entry.expiresAtMillis <= nowMillis) {
                expired += k
                continue
            }
            if (!includeLowPriorityHud && entry.priority.weight < ActionBarPriority.COMBAT.weight) {
                continue
            }
            if (best == null || entry.priority.weight > best.priority.weight) {
                best = entry
            }
        }
        expired.forEach { map.remove(it) }
        if (map.isEmpty()) {
            playerStates.remove(uuid)
        }
        return best
    }

    private fun dispatchNow(player: Player) {
        if (!player.isOnline) return
        val entry = resolveActiveEntry(
            player.uniqueId,
            includeLowPriorityHud = lowPriorityEnabledProvider(),
        ) ?: return
        scheduler.runForEntity(player) {
            if (player.isOnline) {
                player.sendActionBar(entry.component)
            }
        }
    }

    private fun tickAll() {
        if (playerStates.isEmpty()) return
        val now = System.currentTimeMillis()
        val includeLow = lowPriorityEnabledProvider()
        for (uuid in playerStates.keys.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                playerStates.remove(uuid)
                continue
            }
            val entry = resolveActiveEntry(uuid, now, includeLow) ?: continue
            scheduler.runForEntity(player) {
                if (player.isOnline) {
                    player.sendActionBar(entry.component)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        playerStates.remove(event.player.uniqueId)
    }

    companion object {
        const val TRANSIENT_KEY = "__transient__"
    }
}
