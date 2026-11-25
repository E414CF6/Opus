package opus.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import opus.core.OpusContext
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.time.Duration.Companion.milliseconds

/**
 * Helpers for building Paper Brigadier commands with unified permission, async dispatch, and suggestions.
 */
object CommandHelper {

    fun registerWithOptionalAliases(
        registrar: Commands,
        context: OpusContext,
        nodeBuilder: (String) -> LiteralArgumentBuilder<CommandSourceStack>,
        primaryName: String,
        description: String,
        aliases: List<String> = emptyList(),
    ) {
        val node = nodeBuilder(primaryName).build()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            description,
            aliases,
        )
    }

    fun requirePlayer(context: OpusContext, sender: CommandSender): Player? {
        if (sender is Player) return sender
        context.locale.send(sender, "general.player-only")
        return null
    }

    fun hasPermission(context: OpusContext, sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission("opus.admin")) return true
        context.locale.send(sender, "general.no-permission")
        return false
    }

    fun suggestOnlinePlayers(sender: CommandSender, prefix: String): List<String> {
        val viewer = sender as? Player
        return Bukkit.getOnlinePlayers().asSequence().filter { viewer == null || viewer.canSee(it) }.map { it.name }
            .filter { it.startsWith(prefix, ignoreCase = true) }.sorted().toList()
    }

    private class AsyncResultBox<T>(val value: T)

    fun <T> dispatchAsync(
        context: OpusContext,
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
                context.locale.send(sender, "general.db-timeout")
            }
            return
        }
        context.scheduler.asyncScope.launch {
            val result = asyncWork()
            context.scheduler.runGlobal {
                onResult(result)
            }
        }
    }

    /**
     * Builds the `/opus` root command (`info`, `version`, `gui`, `settings`, `admin`, `reload`, `modules`, `aliases`).
     */
    fun buildRootCommand(
        context: OpusContext,
        onGui: ((Player) -> Unit)? = null,
        onSettings: ((Player) -> Unit)? = null,
        onAdmin: ((Player) -> Unit)? = null,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        val sendInfo: (CommandSender) -> Int = { sender ->
            val active = context.modules.activeModules().joinToString(", ") { it.id }
            val mode = context.locale.raw(
                if (context.config.retroMode) "commands.opus-mode-retro" else "commands.opus-mode-standard"
            )
            context.locale.send(
                sender,
                "commands.opus-info",
                "version" to context.plugin.pluginMeta.version,
                "db" to context.database.dialect,
                "mode" to mode,
                "lang" to context.config.language,
                "modules" to active,
            )
            Command.SINGLE_SUCCESS
        }

        val sendAliasesSummary: (CommandSender) -> Int = { sender ->
            val regular = context.commandAliases.getRegularAliases().values
            val enabledCount = regular.count { it.enabled }
            val customCount = context.commandAliases.getCustomAliases().size
            context.locale.send(
                sender,
                "general.aliases-summary",
                "enabled" to enabledCount,
                "total" to regular.size,
                "custom" to customCount,
            )
            Command.SINGLE_SUCCESS
        }

        val aliasesNode = Commands.literal("aliases")
            .requires { it.sender.hasPermission("opus.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                if (sender is Player) {
                    AliasGui.open(context, sender)
                    Command.SINGLE_SUCCESS
                } else {
                    sendAliasesSummary(sender)
                }
            }
            .then(Commands.literal("gui").executes { ctx ->
                val player = requirePlayer(context, ctx.source.sender) ?: return@executes Command.SINGLE_SUCCESS
                AliasGui.open(context, player)
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("dialog").executes { ctx ->
                val player = requirePlayer(context, ctx.source.sender) ?: return@executes Command.SINGLE_SUCCESS
                AliasDialogs.openOverviewDialog(context, player)
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("text").executes { ctx -> sendAliasesSummary(ctx.source.sender) })
            .then(Commands.literal("summary").executes { ctx -> sendAliasesSummary(ctx.source.sender) })
            .then(Commands.literal("list").executes { ctx ->
                val sender = ctx.source.sender
                val all = context.commandAliases.getCustomAliases()
                if (all.isEmpty()) {
                    context.locale.send(sender, "commands.alias-empty")
                } else {
                    context.locale.send(sender, "commands.alias-header", "count" to all.size)
                    for ((name, cmds) in all) {
                        context.locale.sendRaw(
                            sender,
                            "commands.alias-entry",
                            "name" to name,
                            "commands" to cmds.joinToString(" && "),
                        )
                    }
                }
                Command.SINGLE_SUCCESS
            })
            .then(
                Commands.literal("create").then(
                    Commands.argument("name", StringArgumentType.word()).then(
                        Commands.argument("action", StringArgumentType.greedyString()).executes { ctx ->
                            val sender = ctx.source.sender
                            val name = StringArgumentType.getString(ctx, "name").trim().removePrefix("/")
                            val action = StringArgumentType.getString(ctx, "action").trim()
                            val commands = action.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
                            if (name.isEmpty() || commands.isEmpty()) {
                                context.locale.send(sender, "dialog.alias-invalid-input")
                                return@executes Command.SINGLE_SUCCESS
                            }
                            context.commandAliases.setCustomAlias(name, commands)
                            val joined = commands.joinToString(" && ")
                            context.locale.send(sender, "commands.alias-created", "name" to name, "action" to joined)
                            if (sender is Player) {
                                AliasGui.emitFeedback(
                                    context,
                                    sender,
                                    "commands.alias-created",
                                    "name" to name,
                                    "action" to commands.first(),
                                )
                            }
                            Command.SINGLE_SUCCESS
                        }
                    )
                )
            )
            .then(
                Commands.literal("delete").then(
                    Commands.argument("name", StringArgumentType.word()).suggests { _, builder ->
                        context.commandAliases.getCustomAliases().keys
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach(builder::suggest)
                        builder.buildFuture()
                    }.executes { ctx ->
                        val sender = ctx.source.sender
                        val name = StringArgumentType.getString(ctx, "name").trim().removePrefix("/")
                        if (context.commandAliases.removeCustomAlias(name)) {
                            context.locale.send(sender, "commands.alias-deleted", "name" to name)
                            if (sender is Player) {
                                AliasGui.emitFeedback(context, sender, "commands.alias-deleted", "name" to name)
                            }
                        } else {
                            context.locale.send(sender, "commands.alias-not-found", "name" to name)
                        }
                        Command.SINGLE_SUCCESS
                    }
                )
            )
            .then(Commands.literal("reload").executes { ctx ->
                val sender = ctx.source.sender
                context.commandAliases.reload()
                context.locale.send(sender, "gui.alias-reloaded")
                if (sender is Player) {
                    AliasGui.emitFeedback(context, sender, "gui.alias-reloaded")
                }
                Command.SINGLE_SUCCESS
            })

        return Commands.literal("opus")
            .requires { it.sender.hasPermission("opus.command") || it.sender.hasPermission("opus.admin") }
            .executes { ctx ->
                val sender = ctx.source.sender
                if (sender is Player && onGui != null) {
                    onGui(sender)
                } else {
                    sendInfo(sender)
                }
                Command.SINGLE_SUCCESS
            }
            .then(Commands.literal("info").executes { ctx -> sendInfo(ctx.source.sender) })
            .then(Commands.literal("version").executes { ctx -> sendInfo(ctx.source.sender) })
            .then(Commands.literal("gui").executes { ctx ->
                val player = requirePlayer(context, ctx.source.sender) ?: return@executes Command.SINGLE_SUCCESS
                onGui?.invoke(player) ?: sendInfo(player)
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("settings").executes { ctx ->
                val player = requirePlayer(context, ctx.source.sender) ?: return@executes Command.SINGLE_SUCCESS
                onSettings?.invoke(player) ?: sendInfo(player)
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("admin").requires { it.sender.hasPermission("opus.admin") }.executes { ctx ->
                val player = requirePlayer(context, ctx.source.sender) ?: return@executes Command.SINGLE_SUCCESS
                onAdmin?.invoke(player) ?: sendInfo(player)
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("reload").requires { it.sender.hasPermission("opus.admin") }.executes { ctx ->
                val sender = ctx.source.sender
                context.reloadAll()
                context.locale.send(sender, "general.reloaded")
                if (sender is Player) {
                    context.actionBar.pushTransientLocale(sender, "general.reloaded")
                }
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("modules").requires { it.sender.hasPermission("opus.admin") }.executes { ctx ->
                val sender = ctx.source.sender
                context.modules.activeModules().forEach { mod ->
                    context.locale.sendRaw(
                        sender,
                        "commands.opus-module-entry",
                        "id" to mod.id,
                        "description" to mod.description,
                    )
                }
                Command.SINGLE_SUCCESS
            })
            .then(aliasesNode)
    }

    fun registerRootCommand(
        registrar: Commands,
        context: OpusContext,
        onGui: ((Player) -> Unit)? = null,
        onSettings: ((Player) -> Unit)? = null,
        onAdmin: ((Player) -> Unit)? = null,
    ) {
        registrar.register(
            context.plugin.pluginMeta,
            buildRootCommand(context, onGui, onSettings, onAdmin).build(),
            "Opus RPG core management command",
            listOf("mcmmo"),
        )
    }

    fun simpleArgsCommand(
        name: String,
        permission: String,
        executor: (CommandContext<CommandSourceStack>, Array<String>) -> Int,
        tabSuggester: ((CommandSender, Array<String>) -> List<String>)? = null,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal(name)
            .requires { it.sender.hasPermission(permission) || it.sender.hasPermission("opus.admin") }
            .executes { ctx -> executor(ctx, emptyArray()) }
            .then(Commands.argument("args", StringArgumentType.greedyString()).suggests { ctx, builder ->
                if (tabSuggester != null) {
                    val raw = builder.remaining
                    val parts = if (raw.isEmpty()) arrayOf("") else raw.split(" ").toTypedArray()
                    val lastPrefix = parts.lastOrNull() ?: ""
                    val startOffset = builder.start + raw.length - lastPrefix.length
                    val sub = builder.createOffset(startOffset)
                    tabSuggester(ctx.source.sender, parts).forEach { sub.suggest(it) }
                    return@suggests sub.buildFuture()
                }
                builder.buildFuture()
            }.executes { ctx ->
                val raw = StringArgumentType.getString(ctx, "args").trim()
                val args = if (raw.isEmpty()) emptyArray() else raw.split(Regex("\\s+")).toTypedArray()
                executor(ctx, args)
            })
    }
}
