package opus.core

import opus.core.command.CommandRegistry
import opus.core.locale.LocaleService
import opus.core.scheduler.PluginScheduler
import opus.core.ui.ActionBarService
import opus.core.ui.BossBarService
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Domain-agnostic host contract shared by reusable `core/` services across plugins.
 */
interface CoreHost {
    val plugin: JavaPlugin
    val scheduler: PluginScheduler
    val locale: LocaleService
    val actionBar: ActionBarService
    val bossBar: BossBarService
    val commandRegistry: CommandRegistry

    val pluginDisplayName: String
    val rootCommandName: String
    val rootAliases: Set<String>
    val registerStandaloneAliases: Boolean
    val adminPermission: String
    val customAliasPermissionPrefix: String
    val defaultCustomAliases: Map<String, List<String>>

    fun reloadAll()

    fun <T : Any> getService(type: Class<T>): T?

    fun resolveDynamicPlaceholders(player: Player?, input: String): String = input

    fun evaluateDynamicCondition(condition: String, player: Player?): Boolean? = null
}

/**
 * Simple in-memory holder storing registered extension hooks on a [CoreHost].
 */
class HostExtensionRegistry {
    private val services = ConcurrentHashMap<Class<*>, Any>()

    fun <T : Any> register(type: Class<T>, instance: T) {
        services[type] = instance
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(type: Class<T>): T? = services[type] as? T
}
