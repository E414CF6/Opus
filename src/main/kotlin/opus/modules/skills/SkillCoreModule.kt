package opus.modules.skills

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.Commands
import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.module.OpusModule
import opus.core.player.OpusPlayerState
import opus.core.text.TextFormatter
import opus.events.XpGainReason
import opus.modules.hud.HudService
import opus.skills.HudFeedbackHook
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Core RPG progression commands, GUIs, leaderboards, hardcore/vampirism, and administrative skill management.
 */
class SkillCoreModule(private val context: OpusContext) : OpusModule {
    override val id = "skills"
    override val description = "Core RPG skill commands, GUIs, leaderboards, hardcore mode, and admin management"

    private val hardcoreService = HardcoreService(context)
    private val xpEventJoinListener = object : Listener {
        @EventHandler
        fun onJoin(event: PlayerJoinEvent) {
            if (context.config.xpEventAnnounceOnJoin && context.experienceConfig.xpEventActive) {
                context.locale.send(
                    event.player,
                    "commands.xprate-event-broadcast",
                    "rate" to context.experienceConfig.globalMultiplier,
                )
            }
        }
    }

    override fun listeners(): List<Listener> = listOf(hardcoreService, xpEventJoinListener)

    override fun registerCommands(registrar: Commands) {
        registerStatsAndHubCommands(registrar)
        registerIndividualSkillCommands(registrar)
        registerLeaderboardCommands(registrar)
        registerAdminCommands(registrar)
    }

    private fun registerStatsAndHubCommands(registrar: Commands) {
        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.command",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (sender is Player && args.isEmpty()) {
                            val state = context.players.getCached(sender)
                            SkillGui.openHub(context, sender, state)
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val player = CommandHelper.requirePlayer(context, sender)
                            ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                        val state = context.players.getCached(player)
                        if (args.firstOrNull().equals("gui", ignoreCase = true)) {
                            SkillGui.openHub(context, player, state)
                        } else if (args.firstOrNull().equals("dialog", ignoreCase = true)) {
                            SkillDialogs.openGuideDialog(context, player, state)
                        } else {
                            sendStatsText(sender, state, null)
                        }
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, _ -> listOf("gui", "dialog", "text") },
                )
            },
            "skills",
            "Open Opus RPG skills GUI or view stats",
            listOf("mcstats", "stats"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.command",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        val useText = args.any { it.equals("text", ignoreCase = true) }
                        val targetName = args.firstOrNull {
                            !it.equals("text", ignoreCase = true) && !it.equals(
                                "gui",
                                ignoreCase = true
                            )
                        }
                        if (targetName == null) {
                            val player = CommandHelper.requirePlayer(context, sender)
                                ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                            val state = context.players.getCached(player)
                            if (useText) {
                                sendStatsText(sender, state, null)
                            } else {
                                SkillGui.openHub(context, player, state)
                            }
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = {
                                val state = context.players.findByName(targetName)
                                val rank = state?.let { context.playerRepository.findRank(it.uuid, null) }
                                state to rank
                            },
                            onResult = { (state, rank) ->
                                if (state == null) {
                                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                                } else if (sender is Player && !useText) {
                                    SkillGui.openHub(
                                        context,
                                        sender,
                                        state,
                                        readOnlyInspect = state.uuid != sender.uniqueId,
                                    )
                                } else {
                                    sendStatsText(sender, state, rank)
                                }
                            },
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        if (parts.size <= 1) CommandHelper.suggestOnlinePlayers(
                            sender,
                            parts.lastOrNull() ?: ""
                        ) else listOf("gui", "text")
                    },
                )
            },
            "inspect",
            "Inspect a player's Opus RPG stats",
            listOf("mcinspect", "mmoinspect"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.command",
                    executor = { ctx, args ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                            ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                        val state = context.players.getCached(player)
                        when (args.firstOrNull()?.lowercase()) {
                            "gui" -> {
                                SkillGui.openCooldowns(context, player, state)
                                return@simpleArgsCommand Command.SINGLE_SUCCESS
                            }

                            "text" -> {
                                val lang = context.config.language
                                context.locale.sendRaw(player, "commands.cooldown-header")
                                val now = System.currentTimeMillis()
                                for (ability in SuperAbilityType.entries) {
                                    val rem = state.getCooldownRemainingMillis(ability, now)
                                    val stateStr = if (rem <= 0L) {
                                        context.locale.raw("commands.cooldown-ready")
                                    } else {
                                        context.locale.raw(
                                            "commands.cooldown-active",
                                            "time" to TextFormatter.formatDuration(rem)
                                        )
                                    }
                                    context.locale.sendRaw(
                                        player,
                                        "commands.cooldown-entry",
                                        "ability" to ability.displayName(lang),
                                        "state" to stateStr,
                                    )
                                }
                                return@simpleArgsCommand Command.SINGLE_SUCCESS
                            }

                            else -> {
                                SkillDialogs.openCooldownsDialog(context, player, state)
                                return@simpleArgsCommand Command.SINGLE_SUCCESS
                            }
                        }
                    },
                    tabSuggester = { _, _ -> listOf("dialog", "gui", "text") },
                )
            },
            "mccooldown",
            "Show active Super Ability cooldowns",
            listOf("mccooldowns"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.command", executor = { ctx, _ ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    val state = context.players.getCached(player)
                    state.abilityReadyingEnabled = !state.abilityReadyingEnabled
                    val key =
                        if (state.abilityReadyingEnabled) "skill.ability-toggle-on" else "skill.ability-toggle-off"
                    context.locale.send(player, key)
                    context.locale.sendActionBar(player, key)
                    Command.SINGLE_SUCCESS
                })
            },
            "mcability",
            "Toggle right-click ability readying",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.command", executor = { ctx, _ ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    val state = context.players.getCached(player)
                    val power = state.powerLevel()
                    val mastered = context.ranks.masteredCount(state)
                    val total = SkillType.nonChildSkills.size
                    context.locale.send(
                        player,
                        "commands.power-info",
                        "power" to power,
                        "mastered" to mastered,
                        "total" to total,
                    )
                    context.locale.sendActionBar(
                        player,
                        "commands.power-info",
                        "power" to power,
                        "mastered" to mastered,
                        "total" to total,
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "mmopower",
            "Show power level and mastery summary",
            listOf("powerlevel", "mmopowerlevel"),
        )
    }

    private fun registerIndividualSkillCommands(registrar: Commands) {
        for (skill in SkillType.entries) {
            CommandHelper.registerWithOptionalAliases(
                registrar,
                context,
                { name ->
                    CommandHelper.simpleArgsCommand(
                        name,
                        "opus.command",
                        executor = { ctx, args ->
                            val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                                ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                            val state = context.players.getCached(player)
                            when (args.firstOrNull()?.lowercase()) {
                                "gui" -> {
                                    SkillGui.openSkillDetail(context, player, state, skill)
                                    return@simpleArgsCommand Command.SINGLE_SUCCESS
                                }

                                "dialog" -> {
                                    SkillDialogs.openSkillGuideDialog(context, player, state, skill)
                                    return@simpleArgsCommand Command.SINGLE_SUCCESS
                                }
                            }
                            sendSkillDetailText(player, state, skill)
                            context.getService<HudService>()?.showSkillBoard(player, state, skill)
                            Command.SINGLE_SUCCESS
                        },
                        tabSuggester = { _, _ -> listOf("gui", "dialog") },
                    )
                },
                skill.key,
                "Inspect ${skill.enName} skill details",
            )
        }
    }

    private fun registerLeaderboardCommands(registrar: Commands) {
        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.command",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        var skill: SkillType? = null
                        var page = 1
                        var useText = false
                        for (arg in args) {
                            when {
                                arg.equals("gui", ignoreCase = true) -> useText = false
                                arg.equals("text", ignoreCase = true) -> useText = true
                                arg.toIntOrNull() != null -> page = arg.toInt().coerceAtLeast(1)
                                else -> {
                                    val matched = SkillType.match(arg)
                                    if (matched == null) {
                                        context.locale.send(sender, "general.invalid-skill", "skill" to arg)
                                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                                    }
                                    if (matched.isChild) {
                                        context.locale.send(sender, "general.invalid-skill", "skill" to arg)
                                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                                    }
                                    skill = matched
                                }
                            }
                        }
                        val offset = (page - 1) * 10
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = { context.playerRepository.readTopSkill(skill, 10, offset) },
                            onResult = { entries ->
                                if (!useText && sender is Player) {
                                    SkillGui.openLeaderboard(context, sender, skill, entries, page)
                                    return@dispatchAsync
                                }
                                val lang = context.config.language
                                val skillLabel =
                                    skill?.displayName(lang) ?: if (lang == "ko") "총합 레벨" else "Power Level"
                                context.locale.sendRaw(
                                    sender,
                                    "commands.top-header",
                                    "skill" to skillLabel,
                                    "page" to page
                                )
                                if (entries.isEmpty()) {
                                    context.locale.sendRaw(sender, "commands.top-empty")
                                } else {
                                    entries.forEachIndexed { idx, entry ->
                                        context.locale.sendRaw(
                                            sender,
                                            "commands.top-entry",
                                            "pos" to (offset + idx + 1),
                                            "player" to entry.username,
                                            "level" to entry.level,
                                        )
                                    }
                                }
                            },
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, parts ->
                        if (parts.size <= 1) {
                            (SkillType.nonChildSkills.map { it.key } + "gui").filter {
                                it.startsWith(parts.lastOrNull() ?: "", ignoreCase = true)
                            }
                        } else emptyList()
                    },
                )
            },
            "mctop",
            "View Opus skill leaderboards",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.command",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        val useText = args.any { it.equals("text", ignoreCase = true) }
                        val targetName = args.firstOrNull { !it.equals("text", ignoreCase = true) } ?: sender.name
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = {
                                val state = context.players.findByName(targetName)
                                    ?: (Bukkit.getPlayerExact(targetName)?.let { context.players.getCached(it) })
                                    ?: return@dispatchAsync null
                                val ranks = mutableMapOf<SkillType?, Int?>()
                                ranks[null] = context.playerRepository.findRank(state.uuid, null)
                                for (s in SkillType.nonChildSkills) {
                                    ranks[s] = context.playerRepository.findRank(state.uuid, s)
                                }
                                state to ranks
                            },
                            onResult = { res ->
                                if (res == null) {
                                    context.locale.send(sender, "general.player-not-found", "player" to targetName)
                                    return@dispatchAsync
                                }
                                val (state, ranks) = res
                                if (sender is Player && !useText) {
                                    SkillDialogs.openRankingsDialog(context, sender, state, ranks)
                                    return@dispatchAsync
                                }
                                val lang = context.config.language
                                context.locale.sendRaw(sender, "commands.rank-header", "player" to state.username)
                                for (s in SkillType.nonChildSkills) {
                                    val r = ranks[s]
                                    if (r != null) {
                                        context.locale.sendRaw(
                                            sender,
                                            "commands.rank-entry",
                                            "skill" to s.displayName(lang),
                                            "rank" to r,
                                            "level" to state.getLevel(s),
                                        )
                                    }
                                }
                            },
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        if (parts.size <= 1) CommandHelper.suggestOnlinePlayers(
                            sender,
                            parts.lastOrNull() ?: ""
                        ) else emptyList()
                    },
                )
            },
            "mcrank",
            "View player skill rankings",
        )
    }

    private fun registerAdminCommands(registrar: Commands) {
        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (args.size < 3) {
                            context.locale.send(sender, "commands.usage-addxp")
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val target = Bukkit.getPlayerExact(args[0])
                        if (target == null) {
                            context.locale.send(sender, "general.player-not-found", "player" to args[0])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val skill = SkillType.match(args[1])
                        if (skill == null) {
                            context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val amount = args[2].toDoubleOrNull()
                        if (amount == null || amount <= 0.0) {
                            context.locale.send(sender, "general.invalid-number", "value" to args[2])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        context.progression.awardXpDirect(target, skill, amount, XpGainReason.COMMAND)
                        context.locale.send(
                            sender,
                            "commands.addxp-success",
                            "player" to target.name,
                            "skill" to skill.displayName(context.config.language),
                            "amount" to amount.toInt(),
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        when (parts.size) {
                            1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                            2 -> SkillType.nonChildSkills.map { it.key }
                                .filter { it.startsWith(parts[1], ignoreCase = true) }

                            else -> emptyList()
                        }
                    },
                )
            },
            "addxp",
            "Add XP to a player's skill",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (args.size < 3) {
                            context.locale.send(sender, "commands.usage-addlevels")
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val target = Bukkit.getPlayerExact(args[0])
                        if (target == null) {
                            context.locale.send(sender, "general.player-not-found", "player" to args[0])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val skill = SkillType.match(args[1])?.takeIf { !it.isChild }
                        if (skill == null) {
                            context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val amount = args[2].toIntOrNull()
                        if (amount == null || amount <= 0) {
                            context.locale.send(sender, "general.invalid-number", "value" to args[2])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val state = context.players.getCached(target)
                        state.setSkill(skill, state.getLevel(skill) + amount, state.getXp(skill))
                        context.locale.send(
                            sender,
                            "commands.addlevels-success",
                            "player" to target.name,
                            "skill" to skill.displayName(context.config.language),
                            "amount" to amount,
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        when (parts.size) {
                            1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                            2 -> SkillType.nonChildSkills.map { it.key }
                                .filter { it.startsWith(parts[1], ignoreCase = true) }

                            else -> emptyList()
                        }
                    },
                )
            },
            "addlevels",
            "Add levels to a player's skill",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (args.size < 3) {
                            context.locale.send(sender, "commands.usage-mmoedit")
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val skill = SkillType.match(args[1])?.takeIf { !it.isChild }
                        if (skill == null) {
                            context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val level = args[2].toIntOrNull()
                        if (level == null || level < 0) {
                            context.locale.send(sender, "general.invalid-number", "value" to args[2])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        CommandHelper.dispatchAsync(
                            context,
                            sender,
                            asyncWork = {
                                val state = context.players.findByName(args[0])
                                    ?: Bukkit.getPlayerExact(args[0])?.let { context.players.getCached(it) }
                                    ?: return@dispatchAsync null
                                state.setSkill(skill, level, 0.0)
                                context.players.saveState(state)
                                state
                            },
                            onResult = { state ->
                                if (state == null) {
                                    context.locale.send(sender, "general.player-not-found", "player" to args[0])
                                } else {
                                    context.locale.send(
                                        sender,
                                        "commands.mmoedit-success",
                                        "player" to state.username,
                                        "skill" to skill.displayName(context.config.language),
                                        "level" to level,
                                    )
                                }
                            },
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        when (parts.size) {
                            1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                            2 -> SkillType.nonChildSkills.map { it.key }
                                .filter { it.startsWith(parts[1], ignoreCase = true) }

                            else -> emptyList()
                        }
                    },
                )
            },
            "mmoedit",
            "Set a player's skill level",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        val target = if (args.isNotEmpty()) Bukkit.getPlayerExact(args[0]) else sender as? Player
                        if (target == null) {
                            context.locale.send(
                                sender,
                                "general.player-not-found",
                                "player" to (args.firstOrNull() ?: "")
                            )
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        context.players.getCached(target).clearAllCooldowns()
                        context.locale.send(sender, "commands.refresh-success", "player" to target.name)
                        context.locale.sendActionBar(target, "commands.refresh-success", "player" to target.name)
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        if (parts.size <= 1) CommandHelper.suggestOnlinePlayers(
                            sender,
                            parts.lastOrNull() ?: ""
                        ) else emptyList()
                    },
                )
            },
            "mcrefresh",
            "Reset all Super Ability cooldowns",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (args.isEmpty()) {
                            if (sender is Player) {
                                SkillDialogs.openXpRateDialog(context, sender)
                            } else {
                                context.locale.send(
                                    sender,
                                    "commands.xprate-current",
                                    "rate" to context.experienceConfig.globalMultiplier,
                                )
                            }
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        if (args[0].equals("reset", ignoreCase = true)) {
                            val restored = context.experienceConfig.configuredGlobalMultiplier
                            context.experienceConfig.globalMultiplier = restored
                            context.experienceConfig.xpEventActive = false
                            Bukkit.broadcast(
                                context.locale.prefixedComponent(
                                    "commands.xprate-event-reset",
                                    mapOf("rate" to restored.toString()),
                                )
                            )
                            val hud = context.getService<HudFeedbackHook>()
                            for (online in Bukkit.getOnlinePlayers()) {
                                hud?.hideChannelBossBar(online, HudService.XP_EVENT_CHANNEL)
                                context.locale.sendActionBar(online, "commands.xprate-event-reset", "rate" to restored)
                            }
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val rate = args[0].toDoubleOrNull()
                        if (rate == null || rate <= 0.0) {
                            context.locale.send(sender, "general.invalid-number", "value" to args[0])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val isEvent = args.getOrNull(1)?.equals("true", ignoreCase = true) == true
                        SkillDialogs.applyXpRate(context, sender, rate, isEvent)
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, parts ->
                        when (parts.size) {
                            1 -> listOf("reset", "1.5", "2.0", "3.0")
                            2 -> listOf("true", "false")
                            else -> emptyList()
                        }
                    },
                )
            },
            "xprate",
            "View or change global XP rate or start an XP event",
            listOf("mcxprate"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.command.mcgod",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        val target = if (args.isNotEmpty()) Bukkit.getPlayerExact(args[0]) else sender as? Player
                        if (target == null) {
                            context.locale.send(
                                sender,
                                "general.player-not-found",
                                "player" to (args.firstOrNull() ?: ""),
                            )
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val state = context.players.getCached(target)
                        state.godMode = !state.godMode
                        context.locale.send(
                            sender,
                            "commands.god-toggled",
                            "player" to target.name,
                            "state" to if (state.godMode) "ON" else "OFF",
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        if (parts.size <= 1) CommandHelper.suggestOnlinePlayers(
                            sender,
                            parts.lastOrNull() ?: "",
                        ) else emptyList()
                    },
                )
            },
            "mcgod",
            "Toggle Opus god mode",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.command",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        val useText = args.any { it.equals("text", ignoreCase = true) }
                        val skillArg = args.firstOrNull { !it.equals("text", ignoreCase = true) }
                        if (skillArg != null) {
                            val skill = SkillType.match(skillArg)
                            if (skill == null) {
                                context.locale.send(sender, "general.invalid-skill", "skill" to skillArg)
                                return@simpleArgsCommand Command.SINGLE_SUCCESS
                            }
                            if (sender is Player && !useText) {
                                SkillDialogs.openSkillGuideDialog(
                                    context,
                                    sender,
                                    context.players.getCached(sender),
                                    skill
                                )
                            } else if (sender is Player) {
                                sendSkillDetailText(sender, context.players.getCached(sender), skill)
                            } else {
                                context.locale.sendRaw(
                                    sender,
                                    "commands.mmoinfo-console-skill",
                                    "skill" to context.locale.skillName(skill),
                                    "count" to skill.subSkills().size,
                                )
                            }
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        if (sender is Player && !useText) {
                            SkillDialogs.openGuideDialog(context, sender, context.players.getCached(sender))
                        } else {
                            context.locale.sendRaw(sender, "commands.mmoinfo-header")
                            context.locale.sendRaw(sender, "commands.mmoinfo-summary")
                        }
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, parts ->
                        if (parts.size <= 1) SkillType.entries.map { it.key }
                            .filter { it.startsWith(parts.lastOrNull() ?: "", ignoreCase = true) }
                        else emptyList()
                    },
                )
            },
            "mmoinfo",
            "Display Opus RPG help and skill guides",
            listOf("mcinfo"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (args.isEmpty() && sender is Player) {
                            SkillDialogs.openHardcoreDialog(context, sender)
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        when (args.firstOrNull()?.lowercase()) {
                            "on", "enable" -> context.config.hardcoreStatLossEnabled = true
                            "off", "disable" -> context.config.hardcoreStatLossEnabled = false
                            "rate" -> {
                                val pct = args.getOrNull(1)?.toDoubleOrNull()
                                if (pct == null || pct < 0.0 || pct > 100.0) {
                                    context.locale.send(
                                        sender,
                                        "general.invalid-number",
                                        "value" to (args.getOrNull(1) ?: "")
                                    )
                                    return@simpleArgsCommand Command.SINGLE_SUCCESS
                                }
                                context.config.hardcoreStatLossPercentage = pct
                            }
                        }
                        context.locale.send(
                            sender,
                            "commands.hardcore-status",
                            "state" to if (context.config.hardcoreStatLossEnabled) "ON" else "OFF",
                            "rate" to context.config.hardcoreStatLossPercentage,
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, parts ->
                        if (parts.size <= 1) listOf("on", "off", "rate") else emptyList()
                    },
                )
            },
            "hardcore",
            "Configure Hardcore death stat loss mode",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (args.isEmpty() && sender is Player) {
                            SkillDialogs.openHardcoreDialog(context, sender)
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        when (args.firstOrNull()?.lowercase()) {
                            "on", "enable" -> context.config.hardcoreVampirismEnabled = true
                            "off", "disable" -> context.config.hardcoreVampirismEnabled = false
                            "rate" -> {
                                val pct = args.getOrNull(1)?.toDoubleOrNull()
                                if (pct == null || pct < 0.0 || pct > 100.0) {
                                    context.locale.send(
                                        sender,
                                        "general.invalid-number",
                                        "value" to (args.getOrNull(1) ?: "")
                                    )
                                    return@simpleArgsCommand Command.SINGLE_SUCCESS
                                }
                                context.config.hardcoreVampirismPercentage = pct
                            }
                        }
                        context.locale.send(
                            sender,
                            "commands.vampirism-status",
                            "state" to if (context.config.hardcoreVampirismEnabled) "ON" else "OFF",
                            "rate" to context.config.hardcoreVampirismPercentage,
                        )
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, parts ->
                        if (parts.size <= 1) listOf("on", "off", "rate") else emptyList()
                    },
                )
            },
            "vampirism",
            "Configure PvP Vampirism mode",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.admin", executor = { ctx, args ->
                    val sender = ctx.source.sender
                    val targetName = args.firstOrNull() ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    if (sender is Player && !args.getOrNull(1).equals("confirm", ignoreCase = true)) {
                        SkillDialogs.openRemovePlayerConfirm(context, sender, targetName)
                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                    }
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = {
                            val state = context.players.findByName(targetName) ?: return@dispatchAsync false
                            context.players.removePlayer(state.uuid)
                        },
                        onResult = { removed ->
                            if (removed) {
                                context.locale.send(sender, "commands.remove-success", "player" to targetName)
                            } else {
                                context.locale.send(sender, "general.player-not-found", "player" to targetName)
                            }
                        },
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "mcremove",
            "Delete a player record from the database",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.admin", executor = { ctx, args ->
                    val sender = ctx.source.sender
                    val days = args.firstOrNull()?.toLongOrNull() ?: 180L
                    if (sender is Player && !args.any { it.equals("confirm", ignoreCase = true) }) {
                        SkillDialogs.openPurgeConfirm(context, sender, days)
                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                    }
                    val cutoff = System.currentTimeMillis() - (days * 86_400_000L)
                    CommandHelper.dispatchAsync(
                        context,
                        sender,
                        asyncWork = { context.playerRepository.purgeInactive(cutoff) },
                        onResult = { count ->
                            context.locale.send(sender, "commands.purge-result", "count" to count)
                        },
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "mcpurge",
            "Purge inactive or zero-level users from database",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.admin",
                    executor = { ctx, args ->
                        val sender = ctx.source.sender
                        if (args.size < 2) {
                            context.locale.send(sender, "commands.usage-skillreset")
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val target = Bukkit.getPlayerExact(args[0])
                        if (target == null) {
                            context.locale.send(sender, "general.player-not-found", "player" to args[0])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val isAll = args[1].equals("all", ignoreCase = true)
                        val skill = if (isAll) null else SkillType.match(args[1])?.takeIf { !it.isChild }
                        if (!isAll && skill == null) {
                            context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        if (sender is Player && !args.getOrNull(2).equals("confirm", ignoreCase = true)) {
                            SkillDialogs.openSkillResetConfirm(context, sender, target, skill)
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        val state = context.players.getCached(target)
                        if (skill == null) {
                            for (s in SkillType.nonChildSkills) {
                                state.setSkill(s, context.config.startingLevel, 0.0)
                            }
                            context.locale.send(
                                sender,
                                "commands.skillreset-success",
                                "player" to target.name,
                                "skill" to context.locale.raw("commands.skillreset-all-label"),
                            )
                        } else {
                            state.setSkill(skill, context.config.startingLevel, 0.0)
                            context.locale.send(
                                sender,
                                "commands.skillreset-success",
                                "player" to target.name,
                                "skill" to skill.displayName(context.config.language),
                            )
                        }
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        when (parts.size) {
                            1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                            2 -> (SkillType.nonChildSkills.map { it.key } + "all").filter {
                                it.startsWith(
                                    parts[1],
                                    ignoreCase = true
                                )
                            }

                            else -> emptyList()
                        }
                    },
                )
            },
            "skillreset",
            "Reset one or all skills for a player",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.admin", executor = { ctx, _ ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    val state = context.players.getCached(player)
                    state.debugMode = !state.debugMode
                    context.locale.send(
                        player,
                        "commands.debug-toggled",
                        "state" to if (state.debugMode) "ON" else "OFF"
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "mmodebug",
            "Toggle skill debug output",
        )
    }

    private fun sendStatsText(sender: CommandSender, state: OpusPlayerState, globalRank: Int?) {
        val lang = context.config.language
        val power = state.powerLevel()
        context.locale.sendRaw(sender, "commands.stats-header", "player" to state.username)
        context.locale.sendRaw(
            sender,
            "commands.stats-power",
            "power" to power,
            "rank" to (globalRank?.toString() ?: "-"),
        )
        for (skill in SkillType.nonChildSkills) {
            val lvl = state.getLevel(skill)
            val xp = state.getXp(skill).toInt()
            val needed = context.formula.xpToNextLevel(lvl, context.config.retroMode, power)
            context.locale.sendRaw(
                sender,
                "commands.stats-line",
                "skill" to skill.displayName(lang),
                "level" to lvl,
                "xp" to xp,
                "needed" to needed,
            )
        }
    }

    private fun sendSkillDetailText(sender: CommandSender, state: OpusPlayerState, skill: SkillType) {
        val lang = context.config.language
        val lvl = state.getLevel(skill)
        val power = state.powerLevel()
        context.locale.sendRaw(sender, "commands.skill-header", "skill" to skill.displayName(lang), "level" to lvl)
        if (!skill.isChild) {
            val xp = state.getXp(skill)
            val needed = context.formula.xpToNextLevel(lvl, context.config.retroMode, power)
            val pct = if (needed <= 0) 100 else ((xp / needed) * 100.0).toInt().coerceIn(0, 100)
            context.locale.sendRaw(
                sender,
                "commands.skill-progress",
                "xp" to xp.toInt(),
                "needed" to needed,
                "bar" to TextFormatter.progressBar(xp, needed.toDouble(), 12),
                "percent" to pct,
            )
        }
        for (sub in skill.subSkills()) {
            val rank = context.ranks.getRank(state, sub)
            val nextReq = context.ranks.nextUnlockLevel(state, sub)
            val status = if (rank > 0) {
                context.locale.raw("commands.skill-subskill-unlocked")
            } else {
                context.locale.raw("commands.skill-subskill-locked").replace("{req}", (nextReq ?: 1).toString())
            }
            context.locale.sendRaw(
                sender,
                "commands.skill-subskill-line",
                "name" to sub.displayName(lang),
                "rank" to rank,
                "max_rank" to sub.maxRanks,
                "status" to status,
            )
        }
        skill.superAbility()?.let { ability ->
            val dur = context.probability.abilityDurationSeconds(lvl)
            val rem = state.getCooldownRemainingMillis(ability)
            val cdStr =
                if (rem <= 0L) context.locale.raw("commands.cooldown-ready") else TextFormatter.formatDuration(rem)
            context.locale.sendRaw(
                sender,
                "commands.skill-ability-info",
                "ability" to ability.displayName(lang),
                "duration" to dur,
                "cooldown" to cdStr,
            )
        }
    }
}
