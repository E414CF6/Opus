package opus.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandRegistrationFlag
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.configuration.PluginMeta
import java.util.Locale
import java.util.function.Predicate

/**
 * Metadata and Brigadier node for a subcommand captured under the `/opus` root command.
 */
data class RegisteredSubcommand(
    val name: String,
    val node: LiteralCommandNode<CommandSourceStack>,
    val description: String,
    val aliases: List<String>,
)

/**
 * Central registry holding all Opus module commands under `/opus` instead of registering
 * them directly as standalone root commands (CMI-style architecture modeled after Obsidian).
 */
class OpusCommandRegistry {

    private val subcommands = LinkedHashMap<String, RegisteredSubcommand>()

    @Volatile
    var localDispatcher: CommandDispatcher<CommandSourceStack> = CommandDispatcher()
        private set

    fun clear() {
        subcommands.clear()
        localDispatcher = CommandDispatcher()
    }

    fun registerSubcommand(
        node: LiteralCommandNode<CommandSourceStack>,
        description: String,
        aliases: Collection<String>,
    ) {
        val key = node.literal.lowercase(Locale.ROOT)
        val cleanAliases = aliases.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != key }
            .distinct()
        subcommands[key] = RegisteredSubcommand(
            name = key,
            node = node,
            description = description,
            aliases = cleanAliases,
        )
    }

    fun allSubcommands(): List<RegisteredSubcommand> = subcommands.values.toList()

    fun getSubcommand(name: String): RegisteredSubcommand? =
        subcommands[name.trim().lowercase(Locale.ROOT)]

    /**
     * Attaches all captured subcommands beneath [baseRootBuilder] (`/opus`) and updates
     * [localDispatcher] for Brigadier tab-completion queries under both `/opus` and `/mcmmo`.
     */
    fun buildRootNode(
        baseRootBuilder: LiteralArgumentBuilder<CommandSourceStack>,
    ): LiteralCommandNode<CommandSourceStack> {
        val builtBase = baseRootBuilder.build()
        val existingChildren = builtBase.children
            .filterIsInstance<LiteralCommandNode<CommandSourceStack>>()
            .associateBy { it.literal.lowercase(Locale.ROOT) }
            .toMutableMap()

        for (entry in subcommands.values) {
            val existing = existingChildren[entry.name]
            if (existing != null) {
                existingChildren[entry.name] = mergeLiterals(existing, entry.node)
            } else {
                existingChildren[entry.name] = entry.node
            }
        }

        val finalBuilder = Commands.literal(builtBase.literal).requires(builtBase.requirement)
        builtBase.command?.let { finalBuilder.executes(it) }
        for (child in existingChildren.values) {
            finalBuilder.then(child)
        }
        val rootNode = finalBuilder.build()

        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.root.addChild(rootNode)
        val mcmmoMirror = Commands.literal("mcmmo").requires(rootNode.requirement)
        rootNode.command?.let { mcmmoMirror.executes(it) }
        for (child in rootNode.children) {
            mcmmoMirror.then(child)
        }
        dispatcher.root.addChild(mcmmoMirror.build())
        localDispatcher = dispatcher

        return rootNode
    }

    private fun mergeLiterals(
        primary: LiteralCommandNode<CommandSourceStack>,
        secondary: LiteralCommandNode<CommandSourceStack>,
    ): LiteralCommandNode<CommandSourceStack> {
        val merged = Commands.literal(primary.literal)
        val primaryReq = primary.requirement
        val secondaryReq = secondary.requirement
        merged.requires { src -> primaryReq.test(src) || secondaryReq.test(src) }
        (primary.command ?: secondary.command)?.let { merged.executes(it) }
        for (child in primary.children) {
            merged.then(child)
        }
        for (child in secondary.children) {
            merged.then(wrapChildRequirement(child, secondaryReq))
        }
        return merged.build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapChildRequirement(
        child: CommandNode<CommandSourceStack>,
        parentReq: Predicate<CommandSourceStack>,
    ): CommandNode<CommandSourceStack> {
        val builder = child.createBuilder() as ArgumentBuilder<CommandSourceStack, *>
        val childReq = child.requirement
        builder.requires { src -> parentReq.test(src) && childReq.test(src) }
        return builder.build()
    }
}

/**
 * Intercepting Paper [Commands] implementation passed to modules during `LifecycleEvents.COMMANDS`.
 * Captures every module command into [OpusCommandRegistry] rather than registering standalone root commands.
 */
class OpusCommandRegistrar(
    private val delegate: Commands,
    private val registry: OpusCommandRegistry,
) : Commands {

    override fun getDispatcher(): CommandDispatcher<CommandSourceStack> = delegate.dispatcher

    override fun register(
        node: LiteralCommandNode<CommandSourceStack>,
        description: String?,
        aliases: Collection<String>,
    ): Set<String> {
        registry.registerSubcommand(node, description.orEmpty(), aliases)
        return setOf(node.literal)
    }

    override fun register(
        pluginMeta: PluginMeta,
        node: LiteralCommandNode<CommandSourceStack>,
        description: String?,
        aliases: Collection<String>,
    ): Set<String> {
        registry.registerSubcommand(node, description.orEmpty(), aliases)
        return setOf(node.literal)
    }

    override fun registerWithFlags(
        pluginMeta: PluginMeta,
        node: LiteralCommandNode<CommandSourceStack>,
        description: String?,
        aliases: Collection<String>,
        flags: Set<CommandRegistrationFlag>,
    ): Set<String> {
        registry.registerSubcommand(node, description.orEmpty(), aliases)
        return setOf(node.literal)
    }

    override fun register(
        label: String,
        description: String?,
        aliases: Collection<String>,
        basicCommand: BasicCommand,
    ): Set<String> {
        registry.registerSubcommand(wrapBasicCommand(label, basicCommand), description.orEmpty(), aliases)
        return setOf(label)
    }

    override fun register(
        pluginMeta: PluginMeta,
        label: String,
        description: String?,
        aliases: Collection<String>,
        basicCommand: BasicCommand,
    ): Set<String> {
        registry.registerSubcommand(wrapBasicCommand(label, basicCommand), description.orEmpty(), aliases)
        return setOf(label)
    }

    private fun wrapBasicCommand(
        label: String,
        basicCommand: BasicCommand,
    ): LiteralCommandNode<CommandSourceStack> {
        return Commands.literal(label)
            .requires { src ->
                val perm = basicCommand.permission()
                (perm == null || src.sender.hasPermission(perm)) && basicCommand.canUse(src.sender)
            }
            .executes { ctx ->
                basicCommand.execute(ctx.source, emptyArray())
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("args", StringArgumentType.greedyString())
                    .suggests { ctx, builder ->
                        val raw = builder.remaining
                        val parts = if (raw.isEmpty()) emptyArray() else raw.split(" ").toTypedArray()
                        val lastPrefix = parts.lastOrNull() ?: ""
                        val startOffset = builder.start + raw.length - lastPrefix.length
                        val sub = builder.createOffset(startOffset)
                        basicCommand.suggest(ctx.source, parts).forEach { sub.suggest(it) }
                        sub.buildFuture()
                    }
                    .executes { ctx ->
                        val raw = StringArgumentType.getString(ctx, "args").trim()
                        val parts = if (raw.isEmpty()) emptyArray() else raw.split(Regex("\\s+")).toTypedArray()
                        basicCommand.execute(ctx.source, parts)
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
    }
}
