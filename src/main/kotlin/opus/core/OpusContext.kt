package opus.core

import opus.core.command.CommandAliasService
import opus.core.command.CommandRegistry
import opus.core.config.OpusConfig
import opus.core.locale.LocaleService
import opus.core.module.ModuleRegistry
import opus.core.scheduler.PluginScheduler
import opus.core.storage.DatabaseDialect
import opus.core.storage.DatabaseManager
import opus.core.ui.ActionBarService
import opus.core.ui.BossBarService
import opus.core.ui.DialogKit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Domain-agnostic central infrastructure container for Opus implementing [CoreHost].
 */
class OpusContext(override val plugin: JavaPlugin) : CoreHost {

    val config = OpusConfig(plugin)
    override val locale = LocaleService(plugin, { config.language }, OpusConfig.Companion::normalizeLocale)
    override val scheduler = PluginScheduler(plugin)

    val database: DatabaseManager = createDatabaseManager()

    override val bossBar = BossBarService(scheduler)
    override val actionBar = ActionBarService(scheduler, locale) { config.actionBarNotifications }
    val dialogs = DialogKit(this)

    val modules = ModuleRegistry(plugin) { id, defaultEnabled -> config.isModuleEnabled(id, defaultEnabled) }
    override val commandRegistry = CommandRegistry()

    override val pluginDisplayName: String = "Opus"
    override val rootCommandName: String = "opus"
    override val rootAliases: Set<String> = setOf("mcmmo")
    override val registerStandaloneAliases: Boolean get() = config.registerStandaloneAliases
    override val adminPermission: String = "opus.admin"
    override val customAliasPermissionPrefix: String = "opus.customalias."
    override val defaultCustomAliases: Map<String, List<String>> = linkedMapOf(
        "mcmmo" to listOf("opus $1-"),
        "mcstats" to listOf("opus skills $1-"),
        "mmoinspect" to listOf("opus inspect $1-"),
        "opusguide" to listOf(
            "msg: <aqua><bold>Opus Guide:</bold></aqua> <gray>Use <white>/opus</white> or <white>/skills</white> to open the RPG hub.</gray>",
            "actionbar: <green>Opened Opus RPG Guide!</green>",
        ),
    )

    val commandAliases = CommandAliasService(this)

    private val extensions = HostExtensionRegistry()
    private val reloadHooks = CopyOnWriteArrayList<() -> Unit>()
    private val placeholderResolvers = CopyOnWriteArrayList<(Player?, String) -> String>()
    private val conditionEvaluators = CopyOnWriteArrayList<(String, Player?) -> Boolean?>()

    fun <T : Any> registerService(type: Class<T>, instance: T) {
        extensions.register(type, instance)
    }

    inline fun <reified T : Any> registerService(instance: T) {
        registerService(T::class.java, instance)
    }

    override fun <T : Any> getService(type: Class<T>): T? = extensions.get(type)

    inline fun <reified T : Any> getService(): T? = getService(T::class.java)

    fun registerReloadHook(hook: () -> Unit) {
        reloadHooks += hook
    }

    fun registerPlaceholderResolver(resolver: (Player?, String) -> String) {
        placeholderResolvers += resolver
    }

    fun registerConditionEvaluator(evaluator: (String, Player?) -> Boolean?) {
        conditionEvaluators += evaluator
    }

    override fun resolveDynamicPlaceholders(player: Player?, input: String): String {
        var out = input
        for (resolver in placeholderResolvers) {
            out = resolver(player, out)
        }
        return out
    }

    override fun evaluateDynamicCondition(condition: String, player: Player?): Boolean? {
        for (evaluator in conditionEvaluators) {
            val res = evaluator(condition, player)
            if (res != null) return res
        }
        return null
    }

    override fun reloadAll() {
        config.reload()
        locale.reload()
        reloadHooks.forEach { hook ->
            runCatching(hook).onFailure { err ->
                plugin.logger.warning("Reload hook failed: ${err.message}")
            }
        }
        modules.reloadAll()
        commandAliases.reload()
    }

    fun shutdown() {
        commandAliases.shutdown()
        modules.disableAll()
        actionBar.stop()
        bossBar.clearAll()
        scheduler.shutdown()
        database.close()
    }

    private fun createDatabaseManager(): DatabaseManager {
        val dialect = when (config.storageMethod) {
            "mysql" -> DatabaseDialect.MYSQL
            "mariadb" -> DatabaseDialect.MARIADB
            else -> DatabaseDialect.SQLITE
        }
        return if (dialect == DatabaseDialect.SQLITE) {
            DatabaseManager.forSqlite(
                file = File(plugin.dataFolder, config.sqliteFile),
                tablePrefix = config.mysqlTablePrefix,
                poolName = "Opus-SQLite",
            )
        } else {
            DatabaseManager.forMySql(
                dialect = dialect,
                host = config.mysqlHost,
                port = config.mysqlPort,
                database = config.mysqlDatabase,
                username = config.mysqlUsername,
                password = config.mysqlPassword,
                tablePrefix = config.mysqlTablePrefix,
                useSsl = config.mysqlUseSsl,
                poolSize = config.mysqlPoolSize,
            )
        }
    }
}
