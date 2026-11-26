package opus.core.command

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import opus.core.CoreHost
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.time.Duration.Companion.milliseconds

/**
 * Domain-agnostic helpers for player validation, permission checks, tab completion, and async dispatch.
 */
object CommandHelper {

    fun requirePlayer(host: CoreHost, sender: CommandSender): Player? {
        if (sender is Player) return sender
        host.locale.send(sender, "general.player-only")
        return null
    }

    fun hasPermission(host: CoreHost, sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission(host.adminPermission)) return true
        host.locale.send(sender, "general.no-permission")
        return false
    }

    fun suggestOnlinePlayers(sender: CommandSender, prefix: String): List<String> {
        val viewer = sender as? Player
        return Bukkit.getOnlinePlayers().asSequence()
            .filter { viewer == null || viewer.canSee(it) }
            .map { it.name }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .sorted()
            .toList()
    }

    private class AsyncResultBox<T>(val value: T)

    fun <T> dispatchAsync(
        host: CoreHost,
        sender: CommandSender,
        asyncWork: suspend () -> T,
        onResult: (T) -> Unit,
    ) {
        if (sender !is Player) {
            val box = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(5000L.milliseconds) { AsyncResultBox(asyncWork()) }
            }
            if (box != null) {
                onResult(box.value)
            } else {
                host.locale.send(sender, "general.db-timeout")
            }
            return
        }
        host.scheduler.asyncScope.launch {
            val result = asyncWork()
            host.scheduler.runGlobal {
                onResult(result)
            }
        }
    }
}
