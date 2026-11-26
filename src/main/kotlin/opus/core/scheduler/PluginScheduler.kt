package opus.core.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import opus.core.compat.PlatformCompat
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

fun interface TaskHandle {
    fun cancel()
}

/**
 * Paper / Folia-compatible scheduler and CoroutineScope bridge, supporting headless tests when [plugin] is null.
 */
class PluginScheduler(private val plugin: Plugin? = null) {

    private val rootJob = SupervisorJob()

    /** Coroutine scope for asynchronous I/O and background work. */
    val asyncScope: CoroutineScope = CoroutineScope(rootJob + Dispatchers.IO)

    private val useFolia: Boolean by lazy {
        plugin != null && PlatformCompat.supportsFoliaSchedulers && runCatching {
            Bukkit.getGlobalRegionScheduler()
            true
        }.getOrDefault(false)
    }

    val isFolia: Boolean
        get() = useFolia

    /** Coroutine dispatcher executing on the global/main thread. */
    val globalDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            runGlobal { block.run() }
        }
    }

    fun entityDispatcher(entity: Entity): CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            runForEntity(entity) { block.run() }
        }
    }

    fun runAsync(task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        return if (useFolia) {
            val st = Bukkit.getAsyncScheduler().runNow(host) { task() }
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskAsynchronously(host, Runnable(task))
            TaskHandle { bt.cancel() }
        }
    }

    fun runAsyncDelayed(delayMillis: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val ms = delayMillis.coerceAtLeast(1L)
        return if (useFolia) {
            val st = Bukkit.getAsyncScheduler().runDelayed(host, { task() }, ms, TimeUnit.MILLISECONDS)
            TaskHandle { st.cancel() }
        } else {
            val ticks = (ms / 50L).coerceAtLeast(1L)
            val bt = Bukkit.getScheduler().runTaskLaterAsynchronously(host, Runnable(task), ticks)
            TaskHandle { bt.cancel() }
        }
    }

    fun runAsyncTimer(initialDelaySec: Long, periodSec: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val init = initialDelaySec.coerceAtLeast(1L)
        val period = periodSec.coerceAtLeast(1L)
        return if (useFolia) {
            val st = Bukkit.getAsyncScheduler().runAtFixedRate(host, { task() }, init, period, TimeUnit.SECONDS)
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskTimerAsynchronously(host, Runnable(task), init * 20L, period * 20L)
            TaskHandle { bt.cancel() }
        }
    }

    fun runAsyncTimerMillis(initialDelayMs: Long, periodMs: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val init = initialDelayMs.coerceAtLeast(1L)
        val period = periodMs.coerceAtLeast(1L)
        return if (useFolia) {
            val st = Bukkit.getAsyncScheduler().runAtFixedRate(host, { task() }, init, period, TimeUnit.MILLISECONDS)
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskTimerAsynchronously(
                host,
                Runnable(task),
                (init / 50L).coerceAtLeast(1L),
                (period / 50L).coerceAtLeast(1L),
            )
            TaskHandle { bt.cancel() }
        }
    }

    fun runGlobal(task: () -> Unit) {
        val host = plugin ?: return
        if (!host.isEnabled) return
        if (useFolia) {
            Bukkit.getGlobalRegionScheduler().execute(host, task)
        } else {
            Bukkit.getScheduler().runTask(host, Runnable(task))
        }
    }

    fun runGlobalDelayed(delayTicks: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val ticks = delayTicks.coerceAtLeast(1L)
        return if (useFolia) {
            val st = Bukkit.getGlobalRegionScheduler().runDelayed(host, { task() }, ticks)
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskLater(host, Runnable(task), ticks)
            TaskHandle { bt.cancel() }
        }
    }

    fun runGlobalTimer(initialDelayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val init = initialDelayTicks.coerceAtLeast(1L)
        val period = periodTicks.coerceAtLeast(1L)
        return if (useFolia) {
            val st = Bukkit.getGlobalRegionScheduler().runAtFixedRate(host, { task() }, init, period)
            TaskHandle { st.cancel() }
        } else {
            val bt: BukkitTask = Bukkit.getScheduler().runTaskTimer(host, Runnable(task), init, period)
            TaskHandle { bt.cancel() }
        }
    }

    fun runAtLocation(location: Location, task: () -> Unit) {
        val host = plugin ?: return
        if (!host.isEnabled) return
        if (useFolia) {
            Bukkit.getRegionScheduler().execute(host, location, task)
        } else {
            Bukkit.getScheduler().runTask(host, Runnable(task))
        }
    }

    fun runAtLocationTimer(
        location: Location,
        initialDelayTicks: Long,
        periodTicks: Long,
        task: (TaskHandle) -> Unit,
    ): TaskHandle? {
        val host = plugin ?: return null
        if (!host.isEnabled) return null
        val init = initialDelayTicks.coerceAtLeast(1L)
        val period = periodTicks.coerceAtLeast(1L)
        return if (useFolia) {
            var stRef: ScheduledTask? = null
            val handle = TaskHandle { stRef?.cancel() }
            stRef = Bukkit.getRegionScheduler().runAtFixedRate(
                host,
                location,
                { scheduled ->
                    stRef = scheduled
                    task(TaskHandle { scheduled.cancel() })
                },
                init,
                period,
            )
            handle
        } else {
            var btRef: BukkitTask? = null
            val handle = TaskHandle { btRef?.cancel() }
            btRef = Bukkit.getScheduler().runTaskTimer(
                host,
                Runnable {
                    task(handle)
                },
                init,
                period,
            )
            handle
        }
    }

    fun runForEntity(entity: Entity, retired: (() -> Unit)? = null, task: () -> Unit): Boolean {
        val host = plugin ?: return false
        if (!host.isEnabled) return false
        return if (useFolia) {
            entity.scheduler.execute(host, task, retired, 1L)
        } else {
            Bukkit.getScheduler().runTask(host, Runnable {
                if (entity.isValid) {
                    task()
                } else {
                    retired?.invoke()
                }
            })
            true
        }
    }

    fun runForEntityDelayed(entity: Entity, delayTicks: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val ticks = delayTicks.coerceAtLeast(1L)
        return if (useFolia) {
            val st = entity.scheduler.runDelayed(host, { task() }, null, ticks) ?: return null
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskLater(host, Runnable {
                if (entity.isValid) task()
            }, ticks)
            TaskHandle { bt.cancel() }
        }
    }

    fun runForEntityTimer(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        retired: (() -> Unit)? = null,
        task: (TaskHandle) -> Unit,
    ): TaskHandle? {
        val host = plugin ?: return null
        val init = initialDelayTicks.coerceAtLeast(1L)
        val period = periodTicks.coerceAtLeast(1L)
        return if (useFolia) {
            var stRef: ScheduledTask? = null
            val handle = TaskHandle { stRef?.cancel() }
            stRef = entity.scheduler.runAtFixedRate(
                host,
                { scheduled ->
                    stRef = scheduled
                    task(TaskHandle { scheduled.cancel() })
                },
                retired,
                init,
                period,
            ) ?: return null
            handle
        } else {
            var btRef: BukkitTask? = null
            val handle = TaskHandle { btRef?.cancel() }
            btRef = Bukkit.getScheduler().runTaskTimer(
                host,
                Runnable {
                    if (!entity.isValid) {
                        btRef?.cancel()
                        retired?.invoke()
                        return@Runnable
                    }
                    task(handle)
                },
                init,
                period,
            )
            handle
        }
    }

    fun shutdown() {
        rootJob.cancel()
        val host = plugin ?: return
        if (useFolia) {
            runCatching { Bukkit.getAsyncScheduler().cancelTasks(host) }
            runCatching { Bukkit.getGlobalRegionScheduler().cancelTasks(host) }
        } else {
            runCatching { Bukkit.getScheduler().cancelTasks(host) }
        }
    }
}
