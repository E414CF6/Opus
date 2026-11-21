package opus.core.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Paper / Folia-compatible scheduler and CoroutineScope bridge.
 */
class OpusScheduler(private val plugin: Plugin) {

    private val rootJob = SupervisorJob()

    /** Coroutine scope for asynchronous I/O and background work. */
    val asyncScope: CoroutineScope = CoroutineScope(rootJob + Dispatchers.IO)

    /** Coroutine dispatcher executing on Paper's global region thread. */
    val globalDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!plugin.isEnabled) return
            Bukkit.getGlobalRegionScheduler().execute(plugin, block)
        }
    }

    fun entityDispatcher(entity: Entity): CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!plugin.isEnabled) return
            entity.scheduler.execute(plugin, block, null, 1L)
        }
    }

    fun runAsync(task: () -> Unit): ScheduledTask = Bukkit.getAsyncScheduler().runNow(plugin) { task() }

    fun runAsyncDelayed(delayMillis: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler().runDelayed(plugin, { task() }, delayMillis, TimeUnit.MILLISECONDS)

    fun runAsyncTimer(initialDelaySec: Long, periodSec: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, { task() }, initialDelaySec, periodSec, TimeUnit.SECONDS)

    fun runGlobal(task: () -> Unit) {
        if (!plugin.isEnabled) return
        Bukkit.getGlobalRegionScheduler().execute(plugin, task)
    }

    fun runGlobalDelayed(delayTicks: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task() }, delayTicks.coerceAtLeast(1L))

    fun runGlobalTimer(initialDelayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task() },
            initialDelayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L),
        )

    fun runAtLocation(location: Location, task: () -> Unit) {
        if (!plugin.isEnabled) return
        Bukkit.getRegionScheduler().execute(plugin, location, task)
    }

    fun runAtLocationTimer(
        location: Location,
        initialDelayTicks: Long,
        periodTicks: Long,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask? {
        if (!plugin.isEnabled) return null
        return Bukkit.getRegionScheduler().runAtFixedRate(
            plugin,
            location,
            { scheduled -> task(scheduled) },
            initialDelayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L),
        )
    }

    fun runForEntity(entity: Entity, retired: (() -> Unit)? = null, task: () -> Unit): Boolean {
        if (!plugin.isEnabled) return false
        return entity.scheduler.execute(plugin, task, retired, 1L)
    }

    fun runForEntityDelayed(entity: Entity, delayTicks: Long, task: () -> Unit): ScheduledTask? {
        if (!plugin.isEnabled) return null
        return entity.scheduler.runDelayed(plugin, { task() }, null, delayTicks.coerceAtLeast(1L))
    }

    fun runForEntityTimer(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        retired: (() -> Unit)? = null,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask? {
        if (!plugin.isEnabled) return null
        return entity.scheduler.runAtFixedRate(
            plugin,
            { scheduled -> task(scheduled) },
            retired,
            initialDelayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L),
        )
    }

    fun shutdown() {
        rootJob.cancel()
        Bukkit.getAsyncScheduler().cancelTasks(plugin)
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin)
    }
}
