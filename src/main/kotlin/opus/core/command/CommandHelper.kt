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
import opus.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

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
        val standaloneAliases = if (context.config.registerStandaloneAliases) aliases else emptyList()
        registrar.register(
            context.plugin.pluginMeta,
            node,
            description,
            standaloneAliases,
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
        return Bukkit.getOnlinePlayers().asSequence()
            .filter { viewer == null || viewer.canSee(it) }
            .map { it.name }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .sorted()
            .toList()
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
                withTimeoutOrNull(5000L) { AsyncResultBox(asyncWork()) }
            }
            if (box != null) {
                onResult(box.value)
            } else {
                sender.sendMessage(TextFormatter.format("<red>Command timed out while querying database.</red>"))
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

    fun registerRootCommand(registrar: Commands, context: OpusContext, onGui: ((Player) -> Unit)? = null) {
        val sendInfo: (CommandSender) -> Int = { sender ->
            val active = context.modules.activeModules().joinToString(", ") { it.id }
            val mode = if (context.config.retroMode) "Retro (0-1000)" else "Standard (0-100)"
            sender.sendMessage(
                context.locale.prefix().append(
                    TextFormatter.format(
                        "<gray>Opus <white>v${context.plugin.pluginMeta.version}</white> | DB: <white>${context.database.dialect}</white> | Mode: <white>$mode</white> | Lang: <white>${context.config.language}</white> | Modules: <aqua>$active</aqua></gray>"
                    )
                )
            )
            Command.SINGLE_SUCCESS
        }

        val root = Commands.literal("opus")
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
            .then(Commands.literal("reload").requires { it.sender.hasPermission("opus.admin") }.executes { ctx ->
                context.reloadAll()
                context.locale.send(ctx.source.sender, "general.reloaded")
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("modules").requires { it.sender.hasPermission("opus.admin") }.executes { ctx ->
                val sender = ctx.source.sender
                context.modules.activeModules().forEach { mod ->
                    sender.sendMessage(
                        TextFormatter.format(" <dark_gray>•</dark_gray> <aqua>${mod.id}</aqua> <gray>- ${mod.description}</gray>")
                    )
                }
                Command.SINGLE_SUCCESS
            })
            .build()

        registrar.register(
            context.plugin.pluginMeta,
            root,
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
