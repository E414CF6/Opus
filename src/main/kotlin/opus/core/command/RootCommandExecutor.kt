package opus.core.command

import opus.core.CoreHost
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale

/**
 * Root Bukkit command dispatcher (`/opus`, alias `/mcmmo`) routing all module and core subcommands.
 */
class RootCommandExecutor(
    private val host: CoreHost,
    private val commandAliases: CommandAliasService,
    private val userPermission: String,
    private val openDashboard: (Player) -> Unit,
    private val openSettings: (Player) -> Unit,
    private val openAdminPanel: (Player) -> Unit,
    private val infoReporter: (CommandSender) -> Unit,
    private val modulesReporter: (CommandSender) -> Unit,
) : CommandExecutor, TabCompleter {

    fun registerCoreSubcommands() {
        val reg = host.commandRegistry
        val short = host.rootAliases.firstOrNull() ?: host.rootCommandName

        reg.registerSubcommand(
            name = "menu",
            description = "Open the ${host.pluginDisplayName} Hub GUI",
            permission = userPermission,
            aliases = listOf("gui"),
            standaloneByDefault = false,
        ) { sender, _ ->
            val player = sender as? Player ?: run {
                host.locale.send(sender, "general.player-only")
                return@registerSubcommand true
            }
            openDashboard(player)
            true
        }

        reg.registerSubcommand(
            name = "info",
            description = "Show ${host.pluginDisplayName} version and runtime status",
            permission = userPermission,
            aliases = listOf("version"),
            standaloneByDefault = false,
        ) { sender, _ ->
            infoReporter(sender)
            true
        }

        reg.registerSubcommand(
            name = "settings",
            description = "Open player HUD and preference settings",
            permission = userPermission,
            standaloneByDefault = false,
        ) { sender, _ ->
            val player = sender as? Player ?: run {
                host.locale.send(sender, "general.player-only")
                return@registerSubcommand true
            }
            openSettings(player)
            true
        }

        reg.registerSubcommand(
            name = "admin",
            description = "Open the ${host.pluginDisplayName} Admin Control Panel",
            permission = host.adminPermission,
            standaloneByDefault = false,
        ) { sender, _ ->
            val player = sender as? Player ?: run {
                host.locale.send(sender, "general.player-only")
                return@registerSubcommand true
            }
            openAdminPanel(player)
            true
        }

        reg.registerSubcommand(
            name = "modules",
            description = "View active ${host.pluginDisplayName} modules",
            permission = host.adminPermission,
            aliases = listOf("status"),
            standaloneByDefault = false,
        ) { sender, _ ->
            modulesReporter(sender)
            true
        }

        reg.registerSubcommand(
            name = "reload",
            description = "Reload ${host.pluginDisplayName} configs, locales, modules, and aliases",
            permission = host.adminPermission,
            standaloneByDefault = false,
        ) { sender, _ ->
            host.reloadAll()
            host.locale.send(sender, "general.reloaded")
            if (sender is Player) {
                host.actionBar.pushTransientLocale(sender, "general.reloaded")
            }
            true
        }

        reg.registerSubcommand(
            name = "aliases",
            description = "Manage and inspect dynamic command aliases",
            permission = host.adminPermission,
            aliases = listOf("alias"),
            standaloneByDefault = false,
            tabCompleter = SubcommandTabCompleter { _, args ->
                when (args.size) {
                    0, 1 -> listOf("gui", "dialog", "text", "summary", "list", "create", "delete", "reload")
                        .filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }

                    2 -> if (args[0].equals("delete", ignoreCase = true) || args[0].equals(
                            "remove",
                            ignoreCase = true
                        )
                    ) {
                        commandAliases.getCustomAliases().keys.filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()

                    else -> emptyList()
                }
            },
        ) { sender, args ->
            handleAliasesSubcommand(sender, args, short)
            true
        }
    }

    private fun handleAliasesSubcommand(sender: CommandSender, args: Array<out String>, short: String) {
        val action = args.firstOrNull()?.lowercase(Locale.ROOT)
        if (action == null) {
            if (sender is Player) {
                AliasGui.open(host, sender, onBack = openAdminPanel)
            } else {
                sendAliasesSummary(sender)
            }
            return
        }

        when (action) {
            "gui" -> {
                val player = CommandHelper.requirePlayer(host, sender) ?: return
                AliasGui.open(host, player, onBack = openAdminPanel)
            }

            "dialog" -> {
                val player = CommandHelper.requirePlayer(host, sender) ?: return
                AliasDialogs.openOverviewDialog(host, player, onBack = openAdminPanel)
            }

            "text", "summary" -> sendAliasesSummary(sender)

            "list" -> {
                val all = commandAliases.getCustomAliases()
                if (all.isEmpty()) {
                    host.locale.send(sender, "commands.alias-empty")
                } else {
                    host.locale.send(sender, "commands.alias-header", "count" to all.size)
                    for ((name, cmds) in all) {
                        host.locale.sendRaw(
                            sender,
                            "commands.alias-entry",
                            "name" to name,
                            "commands" to cmds.joinToString(" && "),
                        )
                    }
                }
            }

            "create", "add" -> {
                if (args.size < 3) {
                    host.locale.send(sender, "dialog.alias-invalid-input")
                    return
                }
                val name = args[1].trim().removePrefix("/")
                val commands = args.drop(2).joinToString(" ")
                    .split("&&")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (name.isEmpty() || commands.isEmpty()) {
                    host.locale.send(sender, "dialog.alias-invalid-input")
                    return
                }
                commandAliases.setCustomAlias(name, commands)
                val joined = commands.joinToString(" && ")
                host.locale.send(sender, "commands.alias-created", "name" to name, "action" to joined)
                if (sender is Player) {
                    AliasGui.emitFeedback(
                        host,
                        sender,
                        "commands.alias-created",
                        "name" to name,
                        "action" to commands.first()
                    )
                }
            }

            "delete", "remove", "del" -> {
                if (args.size < 2) {
                    host.locale.sendDirectRaw(sender, "<yellow>Usage: /$short aliases delete <name></yellow>")
                    return
                }
                val name = args[1].trim().removePrefix("/")
                if (commandAliases.removeCustomAlias(name)) {
                    host.locale.send(sender, "commands.alias-deleted", "name" to name)
                    if (sender is Player) {
                        AliasGui.emitFeedback(host, sender, "commands.alias-deleted", "name" to name)
                    }
                } else {
                    host.locale.send(sender, "commands.alias-not-found", "name" to name)
                }
            }

            "reload" -> {
                commandAliases.reload()
                host.locale.send(sender, "gui.alias-reloaded")
                if (sender is Player) {
                    AliasGui.emitFeedback(host, sender, "gui.alias-reloaded")
                }
            }

            else -> sendAliasesSummary(sender)
        }
    }

    private fun sendAliasesSummary(sender: CommandSender) {
        val regular = commandAliases.getRegularAliases().values
        val enabledCount = regular.count { it.enabled }
        val customCount = commandAliases.getCustomAliases().size
        host.locale.send(
            sender,
            "general.aliases-summary",
            "enabled" to enabledCount,
            "total" to regular.size,
            "custom" to customCount,
        )
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player) {
                openDashboard(sender)
            } else {
                infoReporter(sender)
            }
            return true
        }

        val subKey = args[0].lowercase(Locale.ROOT)
        val sub = host.commandRegistry.getSubcommand(subKey)
        if (sub == null) {
            val short = host.rootAliases.firstOrNull() ?: host.rootCommandName
            val available = host.commandRegistry.allSubcommands().joinToString(", ") { it.name }
            host.locale.sendDirectRaw(sender, "<aqua>/$short</aqua> <gray>(Hub GUI) |</gray> <white>$available</white>")
            return true
        }

        if (commandAliases.isBaseDisabled(sub.name)) {
            host.locale.send(sender, "general.command-disabled", "command" to sub.name)
            return true
        }

        if (sub.permission != null && !sender.hasPermission(sub.permission) && !sender.hasPermission(host.adminPermission)) {
            host.locale.send(sender, "general.no-permission")
            return true
        }

        val subArgs = args.drop(1).toTypedArray()
        return sub.executor.execute(sender, subArgs)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size <= 1) {
            val prefix = args.firstOrNull().orEmpty()
            return host.commandRegistry.allSubcommands()
                .filter { !commandAliases.isBaseDisabled(it.name) }
                .filter { it.permission == null || sender.hasPermission(it.permission) || sender.hasPermission(host.adminPermission) }
                .map { it.name }
                .filter { it.startsWith(prefix, ignoreCase = true) }
        }

        val sub = host.commandRegistry.getSubcommand(args[0]) ?: return emptyList()
        if (commandAliases.isBaseDisabled(sub.name)) return emptyList()
        if (sub.permission != null && !sender.hasPermission(sub.permission) && !sender.hasPermission(host.adminPermission)) {
            return emptyList()
        }
        return sub.tabCompleter?.complete(sender, args.drop(1).toTypedArray()).orEmpty()
    }
}
