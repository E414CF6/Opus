package opus.core.module

import io.papermc.paper.command.brigadier.Commands
import opus.core.config.OpusConfig
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

/**
 * Contract for self-contained feature modules in Opus.
 */
interface OpusModule {
    val id: String
    val description: String
    val enabledByDefault: Boolean get() = true

    fun onEnable() {}
    fun onDisable() {}
    fun onReload() {}

    fun listeners(): List<Listener> = emptyList()
    fun registerCommands(registrar: Commands) {}
}

/**
 * Manages ordered registration, configuration gating, listener lifecycle, and reload of [OpusModule]s.
 */
class ModuleRegistry(
    private val plugin: Plugin,
    private val config: OpusConfig,
) {
    private val registered = LinkedHashMap<String, OpusModule>()
    private val active = LinkedHashMap<String, OpusModule>()
    private val activeListeners = mutableListOf<Listener>()

    fun register(module: OpusModule) {
        require(!registered.containsKey(module.id)) { "Duplicate module id: ${module.id}" }
        registered[module.id] = module
    }

    fun enableAll() {
        for ((id, module) in registered) {
            if (!config.isModuleEnabled(id, module.enabledByDefault)) {
                plugin.logger.info("Module '$id' is disabled in config.yml.")
                continue
            }
            runCatching {
                module.onEnable()
                module.listeners().forEach { listener ->
                    Bukkit.getPluginManager().registerEvents(listener, plugin)
                    activeListeners += listener
                }
                active[id] = module
                plugin.logger.info("Enabled module '$id' (${module.description}).")
            }.onFailure { err ->
                plugin.logger.severe("Failed to enable module '$id': ${err.message}")
                err.printStackTrace()
            }
        }
    }

    fun registerAllCommands(registrar: Commands) {
        for (module in active.values) {
            runCatching { module.registerCommands(registrar) }.onFailure { err ->
                plugin.logger.severe("Failed to register commands for module '${module.id}': ${err.message}")
                err.printStackTrace()
            }
        }
    }

    fun reloadAll() {
        for (module in active.values) {
            runCatching { module.onReload() }.onFailure { err ->
                plugin.logger.severe("Failed to reload module '${module.id}': ${err.message}")
                err.printStackTrace()
            }
        }
    }

    fun disableAll() {
        activeListeners.forEach { HandlerList.unregisterAll(it) }
        activeListeners.clear()
        for (module in active.values.reversed()) {
            runCatching { module.onDisable() }.onFailure { err ->
                plugin.logger.severe("Error disabling module '${module.id}': ${err.message}")
                err.printStackTrace()
            }
        }
        active.clear()
    }

    fun isActive(id: String): Boolean = active.containsKey(id)

    fun activeModules(): Collection<OpusModule> = active.values.toList()
}
