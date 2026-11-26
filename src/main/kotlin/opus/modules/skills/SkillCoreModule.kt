package opus.modules.skills

import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.command.CommandRegistry
import opus.core.command.SubcommandTabCompleter
import opus.core.module.FeatureModule
import opus.core.text.TextFormatter
import opus.events.XpGainReason
import opus.modules.hud.HudService
import opus.rpg
import opus.skills.HudFeedbackHook
import opus.skills.OpusPlayerState
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import opus.skills.skillName
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Core RPG progression commands, GUIs, leaderboards, hardcore/vampirism, and administrative skill management.
 */
class SkillCoreModule(private val context: OpusContext) : FeatureModule {
    override val id = "skills"
    override val description = "Core RPG skill commands, GUIs, leaderboards, hardcore mode, and admin management"

    private val hardcoreService = HardcoreService(context)
    private val xpEventJoinListener = object : Listener {
        @EventHandler
        fun onJoin(event: PlayerJoinEvent) {
            if (context.config.xpEventAnnounceOnJoin && context.rpg.experienceConfig.xpEventActive) {
                context.locale.send(
                    event.player,
                    "commands.xprate-event-broadcast",
                    "rate" to context.rpg.experienceConfig.globalMultiplier,
                )
            }
        }
    }

    override fun listeners(): List<Listener> = listOf(hardcoreService, xpEventJoinListener)

    override fun registerSubcommands(registry: CommandRegistry) {
        registerStatsAndHubCommands(registry)
        registerIndividualSkillCommands(registry)
        registerLeaderboardCommands(registry)
        registerAdminCommands(registry)
    }

    private fun registerStatsAndHubCommands(registry: CommandRegistry) {
        registry.registerSubcommand(
            name = "skills",
            description = "Open Opus RPG skills GUI or view stats",
            permission = "opus.command",
            aliases = listOf("mcstats", "stats"),
            disabledStandaloneAliases = listOf("stats"),
            tabCompleter = SubcommandTabCompleter { _, parts ->
                val prefix = parts.lastOrNull() ?: ""
                if (parts.size <= 1) listOf("gui", "dialog", "text").filter { it.startsWith(prefix, ignoreCase = true) }
                else emptyList()
            },
        ) { sender, args ->
            if (sender is Player && args.isEmpty()) {
                val state = context.rpg.players.getCached(sender)
                SkillGui.openHub(context, sender, state)
                return@registerSubcommand true
            }
            val player = CommandHelper.requirePlayer(context, sender) ?: return@registerSubcommand true
            val state = context.rpg.players.getCached(player)
            if (args.firstOrNull().equals("gui", ignoreCase = true)) {
                SkillGui.openHub(context, player, state)
            } else if (args.firstOrNull().equals("dialog", ignoreCase = true)) {
                SkillDialogs.openGuideDialog(context, player, state)
            } else {
                sendStatsText(sender, state, null)
            }
            true
        }

        registry.registerSubcommand(
            name = "inspect",
            description = "Inspect a player's Opus RPG stats",
            permission = "opus.command",
            aliases = listOf("mcinspect", "mmoinspect"),
            disabledStandaloneAliases = listOf("inspect"),
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                if (parts.size <= 1) {
                    CommandHelper.suggestOnlinePlayers(sender, parts.lastOrNull() ?: "")
                } else {
                    listOf("gui", "text").filter { it.startsWith(parts.lastOrNull() ?: "", ignoreCase = true) }
                }
            },
        ) { sender, args ->
            val useText = args.any { it.equals("text", ignoreCase = true) }
            val targetName = args.firstOrNull {
                !it.equals("text", ignoreCase = true) && !it.equals("gui", ignoreCase = true)
            }
            if (targetName == null) {
                val player = CommandHelper.requirePlayer(context, sender) ?: return@registerSubcommand true
                val state = context.rpg.players.getCached(player)
                if (useText) {
                    sendStatsText(sender, state, null)
                } else {
                    SkillGui.openHub(context, player, state)
                }
                return@registerSubcommand true
            }
            CommandHelper.dispatchAsync(
                context,
                sender,
                asyncWork = {
                    val state = context.rpg.players.findByName(targetName)
                    val rank = state?.let { context.rpg.playerRepository.findRank(it.uuid, null) }
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
            true
        }

        registry.registerSubcommand(
            name = "mccooldown",
            description = "Show active Super Ability cooldowns",
            permission = "opus.command",
            aliases = listOf("mccooldowns"),
            tabCompleter = SubcommandTabCompleter { _, parts ->
                val prefix = parts.lastOrNull() ?: ""
                if (parts.size <= 1) listOf("dialog", "gui", "text").filter { it.startsWith(prefix, ignoreCase = true) }
                else emptyList()
            },
        ) { sender, args ->
            val player = CommandHelper.requirePlayer(context, sender) ?: return@registerSubcommand true
            val state = context.rpg.players.getCached(player)
            when (args.firstOrNull()?.lowercase()) {
                "gui" -> SkillGui.openCooldowns(context, player, state)
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
                                "time" to TextFormatter.formatDuration(rem),
                            )
                        }
                        context.locale.sendRaw(
                            player,
                            "commands.cooldown-entry",
                            "ability" to ability.displayName(lang),
                            "state" to stateStr,
                        )
                    }
                }

                else -> SkillDialogs.openCooldownsDialog(context, player, state)
            }
            true
        }

        registry.registerSubcommand(
            name = "mcability",
            description = "Toggle right-click ability readying",
            permission = "opus.command",
        ) { sender, _ ->
            val player = CommandHelper.requirePlayer(context, sender) ?: return@registerSubcommand true
            val state = context.rpg.players.getCached(player)
            state.abilityReadyingEnabled = !state.abilityReadyingEnabled
            val key = if (state.abilityReadyingEnabled) "skill.ability-toggle-on" else "skill.ability-toggle-off"
            context.locale.send(player, key)
            context.locale.sendActionBar(player, key)
            true
        }

        registry.registerSubcommand(
            name = "mmopower",
            description = "Show power level and mastery summary",
            permission = "opus.command",
            aliases = listOf("powerlevel", "mmopowerlevel"),
        ) { sender, _ ->
            val player = CommandHelper.requirePlayer(context, sender) ?: return@registerSubcommand true
            val state = context.rpg.players.getCached(player)
            val power = state.powerLevel()
            val mastered = context.rpg.ranks.masteredCount(state)
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
            true
        }
    }

    private fun registerIndividualSkillCommands(registry: CommandRegistry) {
        for (skill in SkillType.entries) {
            registry.registerSubcommand(
                name = skill.key,
                description = "Inspect ${skill.enName} skill details",
                permission = "opus.command",
                tabCompleter = SubcommandTabCompleter { _, parts ->
                    val prefix = parts.lastOrNull() ?: ""
                    if (parts.size <= 1) listOf("gui", "dialog").filter { it.startsWith(prefix, ignoreCase = true) }
                    else emptyList()
                },
            ) { sender, args ->
                val player = CommandHelper.requirePlayer(context, sender) ?: return@registerSubcommand true
                val state = context.rpg.players.getCached(player)
                when (args.firstOrNull()?.lowercase()) {
                    "gui" -> {
                        SkillGui.openSkillDetail(context, player, state, skill)
                        return@registerSubcommand true
                    }

                    "dialog" -> {
                        SkillDialogs.openSkillGuideDialog(context, player, state, skill)
                        return@registerSubcommand true
                    }
                }
                sendSkillDetailText(player, state, skill)
                context.getService<HudService>()?.showSkillBoard(player, state, skill)
                true
            }
        }
    }

    private fun registerLeaderboardCommands(registry: CommandRegistry) {
        registry.registerSubcommand(
            name = "mctop",
            description = "View Opus skill leaderboards",
            permission = "opus.command",
            tabCompleter = SubcommandTabCompleter { _, parts ->
                if (parts.size <= 1) {
                    (SkillType.nonChildSkills.map { it.key } + "gui").filter {
                        it.startsWith(parts.lastOrNull() ?: "", ignoreCase = true)
                    }
                } else {
                    emptyList()
                }
            },
        ) { sender, args ->
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
                        if (matched == null || matched.isChild) {
                            context.locale.send(sender, "general.invalid-skill", "skill" to arg)
                            return@registerSubcommand true
                        }
                        skill = matched
                    }
                }
            }
            val offset = (page - 1) * 10
            CommandHelper.dispatchAsync(
                context,
                sender,
                asyncWork = { context.rpg.playerRepository.readTopSkill(skill, 10, offset) },
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
                        "page" to page,
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
            true
        }

        registry.registerSubcommand(
            name = "mcrank",
            description = "View player skill rankings",
            permission = "opus.command",
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                if (parts.size <= 1) CommandHelper.suggestOnlinePlayers(sender, parts.lastOrNull() ?: "")
                else emptyList()
            },
        ) { sender, args ->
            val useText = args.any { it.equals("text", ignoreCase = true) }
            val targetName = args.firstOrNull { !it.equals("text", ignoreCase = true) } ?: sender.name
            CommandHelper.dispatchAsync(
                context,
                sender,
                asyncWork = {
                    val state = context.rpg.players.findByName(targetName)
                        ?: (Bukkit.getPlayerExact(targetName)?.let { context.rpg.players.getCached(it) })
                        ?: return@dispatchAsync null
                    val ranks = mutableMapOf<SkillType?, Int?>()
                    ranks[null] = context.rpg.playerRepository.findRank(state.uuid, null)
                    for (s in SkillType.nonChildSkills) {
                        ranks[s] = context.rpg.playerRepository.findRank(state.uuid, s)
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
            true
        }
    }

    private fun registerAdminCommands(registry: CommandRegistry) {
        registry.registerSubcommand(
            name = "addxp",
            description = "Add XP to a player's skill",
            permission = "opus.admin",
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                when (parts.size) {
                    1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                    2 -> SkillType.nonChildSkills.map { it.key }
                        .filter { it.startsWith(parts[1], ignoreCase = true) }

                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.size < 3) {
                context.locale.send(sender, "commands.usage-addxp")
                return@registerSubcommand true
            }
            val target = Bukkit.getPlayerExact(args[0])
            if (target == null) {
                context.locale.send(sender, "general.player-not-found", "player" to args[0])
                return@registerSubcommand true
            }
            val skill = SkillType.match(args[1])
            if (skill == null) {
                context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                return@registerSubcommand true
            }
            val amount = args[2].toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                context.locale.send(sender, "general.invalid-number", "value" to args[2])
                return@registerSubcommand true
            }
            context.rpg.progression.awardXpDirect(target, skill, amount, XpGainReason.COMMAND)
            context.locale.send(
                sender,
                "commands.addxp-success",
                "player" to target.name,
                "skill" to skill.displayName(context.config.language),
                "amount" to amount.toInt(),
            )
            true
        }

        registry.registerSubcommand(
            name = "addlevels",
            description = "Add levels to a player's skill",
            permission = "opus.admin",
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                when (parts.size) {
                    1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                    2 -> SkillType.nonChildSkills.map { it.key }
                        .filter { it.startsWith(parts[1], ignoreCase = true) }

                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.size < 3) {
                context.locale.send(sender, "commands.usage-addlevels")
                return@registerSubcommand true
            }
            val target = Bukkit.getPlayerExact(args[0])
            if (target == null) {
                context.locale.send(sender, "general.player-not-found", "player" to args[0])
                return@registerSubcommand true
            }
            val skill = SkillType.match(args[1])?.takeIf { !it.isChild }
            if (skill == null) {
                context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                return@registerSubcommand true
            }
            val amount = args[2].toIntOrNull()
            if (amount == null || amount <= 0) {
                context.locale.send(sender, "general.invalid-number", "value" to args[2])
                return@registerSubcommand true
            }
            val state = context.rpg.players.getCached(target)
            state.setSkill(skill, state.getLevel(skill) + amount, state.getXp(skill))
            context.locale.send(
                sender,
                "commands.addlevels-success",
                "player" to target.name,
                "skill" to skill.displayName(context.config.language),
                "amount" to amount,
            )
            true
        }

        registry.registerSubcommand(
            name = "mmoedit",
            description = "Set a player's skill level",
            permission = "opus.admin",
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                when (parts.size) {
                    1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                    2 -> SkillType.nonChildSkills.map { it.key }
                        .filter { it.startsWith(parts[1], ignoreCase = true) }

                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.size < 3) {
                context.locale.send(sender, "commands.usage-mmoedit")
                return@registerSubcommand true
            }
            val skill = SkillType.match(args[1])?.takeIf { !it.isChild }
            if (skill == null) {
                context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                return@registerSubcommand true
            }
            val level = args[2].toIntOrNull()
            if (level == null || level < 0) {
                context.locale.send(sender, "general.invalid-number", "value" to args[2])
                return@registerSubcommand true
            }
            CommandHelper.dispatchAsync(
                context,
                sender,
                asyncWork = {
                    val state = context.rpg.players.findByName(args[0])
                        ?: Bukkit.getPlayerExact(args[0])?.let { context.rpg.players.getCached(it) }
                        ?: return@dispatchAsync null
                    state.setSkill(skill, level, 0.0)
                    context.rpg.players.saveState(state)
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
            true
        }

        registry.registerSubcommand(
            name = "mcrefresh",
            description = "Reset all Super Ability cooldowns",
            permission = "opus.admin",
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                if (parts.size <= 1) CommandHelper.suggestOnlinePlayers(sender, parts.lastOrNull() ?: "")
                else emptyList()
            },
        ) { sender, args ->
            val target = if (args.isNotEmpty()) Bukkit.getPlayerExact(args[0]) else sender as? Player
            if (target == null) {
                context.locale.send(
                    sender,
                    "general.player-not-found",
                    "player" to (args.firstOrNull() ?: ""),
                )
                return@registerSubcommand true
            }
            context.rpg.players.getCached(target).clearAllCooldowns()
            context.locale.send(sender, "commands.refresh-success", "player" to target.name)
            context.locale.sendActionBar(target, "commands.refresh-success", "player" to target.name)
            true
        }

        registry.registerSubcommand(
            name = "xprate",
            description = "View or change global XP rate or start an XP event",
            permission = "opus.admin",
            aliases = listOf("mcxprate"),
            tabCompleter = SubcommandTabCompleter { _, parts ->
                when (parts.size) {
                    1 -> listOf("reset", "1.5", "2.0", "3.0").filter { it.startsWith(parts[0], ignoreCase = true) }
                    2 -> listOf("true", "false").filter { it.startsWith(parts[1], ignoreCase = true) }
                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.isEmpty()) {
                if (sender is Player) {
                    SkillDialogs.openXpRateDialog(context, sender)
                } else {
                    context.locale.send(
                        sender,
                        "commands.xprate-current",
                        "rate" to context.rpg.experienceConfig.globalMultiplier,
                    )
                }
                return@registerSubcommand true
            }
            if (args[0].equals("reset", ignoreCase = true)) {
                val restored = context.rpg.experienceConfig.configuredGlobalMultiplier
                context.rpg.experienceConfig.globalMultiplier = restored
                context.rpg.experienceConfig.xpEventActive = false
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
                return@registerSubcommand true
            }
            val rate = args[0].toDoubleOrNull()
            if (rate == null || rate <= 0.0) {
                context.locale.send(sender, "general.invalid-number", "value" to args[0])
                return@registerSubcommand true
            }
            val isEvent = args.getOrNull(1)?.equals("true", ignoreCase = true) == true
            SkillDialogs.applyXpRate(context, sender, rate, isEvent)
            true
        }

        registry.registerSubcommand(
            name = "mcgod",
            description = "Toggle Opus god mode",
            permission = "opus.command.mcgod",
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                if (parts.size <= 1) CommandHelper.suggestOnlinePlayers(sender, parts.lastOrNull() ?: "")
                else emptyList()
            },
        ) { sender, args ->
            val target = if (args.isNotEmpty()) Bukkit.getPlayerExact(args[0]) else sender as? Player
            if (target == null) {
                context.locale.send(
                    sender,
                    "general.player-not-found",
                    "player" to (args.firstOrNull() ?: ""),
                )
                return@registerSubcommand true
            }
            val state = context.rpg.players.getCached(target)
            state.godMode = !state.godMode
            context.locale.send(
                sender,
                "commands.god-toggled",
                "player" to target.name,
                "state" to if (state.godMode) "ON" else "OFF",
            )
            true
        }

        registry.registerSubcommand(
            name = "mmoinfo",
            description = "Display Opus RPG help and skill guides",
            permission = "opus.command",
            aliases = listOf("mcinfo"),
            tabCompleter = SubcommandTabCompleter { _, parts ->
                if (parts.size <= 1) {
                    SkillType.entries.map { it.key }
                        .filter { it.startsWith(parts.lastOrNull() ?: "", ignoreCase = true) }
                } else {
                    emptyList()
                }
            },
        ) { sender, args ->
            val useText = args.any { it.equals("text", ignoreCase = true) }
            val skillArg = args.firstOrNull { !it.equals("text", ignoreCase = true) }
            if (skillArg != null) {
                val skill = SkillType.match(skillArg)
                if (skill == null) {
                    context.locale.send(sender, "general.invalid-skill", "skill" to skillArg)
                    return@registerSubcommand true
                }
                if (sender is Player && !useText) {
                    SkillDialogs.openSkillGuideDialog(
                        context,
                        sender,
                        context.rpg.players.getCached(sender),
                        skill,
                    )
                } else if (sender is Player) {
                    sendSkillDetailText(sender, context.rpg.players.getCached(sender), skill)
                } else {
                    context.locale.sendRaw(
                        sender,
                        "commands.mmoinfo-console-skill",
                        "skill" to context.locale.skillName(skill),
                        "count" to skill.subSkills().size,
                    )
                }
                return@registerSubcommand true
            }
            if (sender is Player && !useText) {
                SkillDialogs.openGuideDialog(context, sender, context.rpg.players.getCached(sender))
            } else {
                context.locale.sendRaw(sender, "commands.mmoinfo-header")
                context.locale.sendRaw(sender, "commands.mmoinfo-summary")
            }
            true
        }

        registry.registerSubcommand(
            name = "hardcore",
            description = "Configure Hardcore death stat loss mode",
            permission = "opus.admin",
            tabCompleter = SubcommandTabCompleter { _, parts ->
                if (parts.size <= 1) listOf("on", "off", "rate").filter {
                    it.startsWith(
                        parts.lastOrNull() ?: "",
                        ignoreCase = true
                    )
                }
                else emptyList()
            },
        ) { sender, args ->
            if (args.isEmpty() && sender is Player) {
                SkillDialogs.openHardcoreDialog(context, sender)
                return@registerSubcommand true
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
                            "value" to (args.getOrNull(1) ?: ""),
                        )
                        return@registerSubcommand true
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
            true
        }

        registry.registerSubcommand(
            name = "vampirism",
            description = "Configure PvP Vampirism mode",
            permission = "opus.admin",
            tabCompleter = SubcommandTabCompleter { _, parts ->
                if (parts.size <= 1) listOf("on", "off", "rate").filter {
                    it.startsWith(
                        parts.lastOrNull() ?: "",
                        ignoreCase = true
                    )
                }
                else emptyList()
            },
        ) { sender, args ->
            if (args.isEmpty() && sender is Player) {
                SkillDialogs.openHardcoreDialog(context, sender)
                return@registerSubcommand true
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
                            "value" to (args.getOrNull(1) ?: ""),
                        )
                        return@registerSubcommand true
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
            true
        }

        registry.registerSubcommand(
            name = "mcremove",
            description = "Delete a player record from the database",
            permission = "opus.admin",
        ) { sender, args ->
            val targetName = args.firstOrNull() ?: return@registerSubcommand true
            if (sender is Player && !args.getOrNull(1).equals("confirm", ignoreCase = true)) {
                SkillDialogs.openRemovePlayerConfirm(context, sender, targetName)
                return@registerSubcommand true
            }
            CommandHelper.dispatchAsync(
                context,
                sender,
                asyncWork = {
                    val state = context.rpg.players.findByName(targetName) ?: return@dispatchAsync false
                    context.rpg.players.removePlayer(state.uuid)
                },
                onResult = { removed ->
                    if (removed) {
                        context.locale.send(sender, "commands.remove-success", "player" to targetName)
                    } else {
                        context.locale.send(sender, "general.player-not-found", "player" to targetName)
                    }
                },
            )
            true
        }

        registry.registerSubcommand(
            name = "mcpurge",
            description = "Purge inactive or zero-level users from database",
            permission = "opus.admin",
        ) { sender, args ->
            val days = args.firstOrNull()?.toLongOrNull() ?: 180L
            if (sender is Player && !args.any { it.equals("confirm", ignoreCase = true) }) {
                SkillDialogs.openPurgeConfirm(context, sender, days)
                return@registerSubcommand true
            }
            val cutoff = System.currentTimeMillis() - (days * 86_400_000L)
            CommandHelper.dispatchAsync(
                context,
                sender,
                asyncWork = { context.rpg.playerRepository.purgeInactive(cutoff) },
                onResult = { count ->
                    context.locale.send(sender, "commands.purge-result", "count" to count)
                },
            )
            true
        }

        registry.registerSubcommand(
            name = "skillreset",
            description = "Reset one or all skills for a player",
            permission = "opus.admin",
            tabCompleter = SubcommandTabCompleter { sender, parts ->
                when (parts.size) {
                    1 -> CommandHelper.suggestOnlinePlayers(sender, parts[0])
                    2 -> (SkillType.nonChildSkills.map { it.key } + "all")
                        .filter { it.startsWith(parts[1], ignoreCase = true) }

                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.size < 2) {
                context.locale.send(sender, "commands.usage-skillreset")
                return@registerSubcommand true
            }
            val target = Bukkit.getPlayerExact(args[0])
            if (target == null) {
                context.locale.send(sender, "general.player-not-found", "player" to args[0])
                return@registerSubcommand true
            }
            val isAll = args[1].equals("all", ignoreCase = true)
            val skill = if (isAll) null else SkillType.match(args[1])?.takeIf { !it.isChild }
            if (!isAll && skill == null) {
                context.locale.send(sender, "general.invalid-skill", "skill" to args[1])
                return@registerSubcommand true
            }
            if (sender is Player && !args.getOrNull(2).equals("confirm", ignoreCase = true)) {
                SkillDialogs.openSkillResetConfirm(context, sender, target, skill)
                return@registerSubcommand true
            }
            val state = context.rpg.players.getCached(target)
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
            true
        }

        registry.registerSubcommand(
            name = "mmodebug",
            description = "Toggle skill debug output",
            permission = "opus.admin",
        ) { sender, _ ->
            val player = CommandHelper.requirePlayer(context, sender) ?: return@registerSubcommand true
            val state = context.rpg.players.getCached(player)
            state.debugMode = !state.debugMode
            context.locale.send(
                player,
                "commands.debug-toggled",
                "state" to if (state.debugMode) "ON" else "OFF",
            )
            true
        }
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
            val needed = context.rpg.formula.xpToNextLevel(lvl, context.config.retroMode, power)
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
            val needed = context.rpg.formula.xpToNextLevel(lvl, context.config.retroMode, power)
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
            val rank = context.rpg.ranks.getRank(state, sub)
            val nextReq = context.rpg.ranks.nextUnlockLevel(state, sub)
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
            val dur = context.rpg.probability.abilityDurationSeconds(lvl)
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
