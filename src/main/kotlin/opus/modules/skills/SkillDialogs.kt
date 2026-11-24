package opus.modules.skills

import net.kyori.adventure.bossbar.BossBar
import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.gui.OpusDialogs
import opus.core.player.OpusPlayerState
import opus.core.text.TextFormatter
import opus.events.XpGainReason
import opus.modules.hud.HudDialogs
import opus.modules.hud.HudService
import opus.modules.party.PartyService
import opus.skills.HudFeedbackHook
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paper 26.3 Dialogs for RPG guides, skill details, cooldowns, rankings, and admin tools.
 */
object SkillDialogs {

    fun openGuideDialog(context: OpusContext, player: Player, state: OpusPlayerState) {
        val locale = context.locale
        val mode =
            locale.raw(if (context.config.retroMode) "commands.opus-mode-retro" else "commands.opus-mode-standard")
        val power = state.powerLevel()
        val mastered = context.ranks.masteredCount(state)

        val body = listOf(
            OpusDialogs.itemBody(
                ItemStack(Material.NETHER_STAR),
                descriptionRaw = locale.raw(
                    "dialog.guide-header",
                    "version" to context.plugin.pluginMeta.version,
                    "mode" to mode,
                ),
            ),
            OpusDialogs.plainBody(
                locale.raw(
                    "dialog.guide-stats",
                    "power" to power,
                    "mastered" to mastered,
                    "total" to SkillType.nonChildSkills.size,
                )
            ),
            OpusDialogs.plainBody(locale.raw("commands.mmoinfo-summary")),
        )

        val actions = buildList {
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.btn-open-hub"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p ->
                        SkillGui.openHub(context, p, context.players.getCached(p))
                    },
                )
            )
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.btn-open-hud"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p ->
                        HudDialogs.openSettingsDialog(context, p) { opener ->
                            openGuideDialog(context, opener, context.players.getCached(opener))
                        }
                    },
                )
            )
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.btn-open-cooldowns"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p ->
                        openCooldownsDialog(context, p, context.players.getCached(p))
                    },
                )
            )
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.btn-open-top"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p ->
                        SkillGui.openLeaderboardAsync(context, p, null, 1)
                    },
                )
            )
            context.getService<PartyService>()?.let { partyService ->
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.btn-open-party"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            partyService.openPartyPortal(p)
                        },
                    )
                )
            }
            if (player.hasPermission("opus.admin")) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.btn-open-admin"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            openAdminDialog(context, p)
                        },
                    )
                )
            }
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.guide-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openSkillGuideDialog(
        context: OpusContext,
        player: Player,
        state: OpusPlayerState,
        skill: SkillType,
    ) {
        val locale = context.locale
        val skillName = locale.skillName(skill)
        val lvl = state.getLevel(skill)
        val power = state.powerLevel()

        val body = buildList {
            add(
                OpusDialogs.itemBody(
                    ItemStack(skill.icon),
                    descriptionRaw = locale.raw(
                        "commands.skill-header",
                        "skill" to skillName,
                        "level" to lvl,
                    ),
                )
            )
            if (!skill.isChild) {
                val xp = state.getXp(skill)
                val needed = context.formula.xpToNextLevel(lvl, context.config.retroMode, power)
                val pct = if (needed <= 0) 100 else ((xp / needed) * 100.0).toInt().coerceIn(0, 100)
                add(
                    OpusDialogs.plainBody(
                        locale.raw(
                            "commands.skill-progress",
                            "xp" to xp.toInt(),
                            "needed" to needed,
                            "bar" to TextFormatter.progressBar(xp, needed.toDouble(), 12),
                            "percent" to pct,
                        )
                    )
                )
            } else {
                val parents = skill.parentSkills().joinToString(" + ") { locale.skillName(it) }
                add(OpusDialogs.plainBody("<gray>($parents)</gray>"))
            }

            skill.superAbility()?.let { ability ->
                val dur = context.probability.abilityDurationSeconds(lvl)
                val rem = state.getCooldownRemainingMillis(ability)
                val cdStr = if (rem <= 0L) locale.raw("commands.cooldown-ready") else TextFormatter.formatDuration(rem)
                add(
                    OpusDialogs.plainBody(
                        locale.raw(
                            "commands.skill-ability-info",
                            "ability" to locale.abilityName(ability),
                            "duration" to dur,
                            "cooldown" to cdStr,
                        )
                    )
                )
            }

            for (sub in skill.subSkills()) {
                val rank = context.ranks.getRank(state, sub)
                val nextReq = context.ranks.nextUnlockLevel(state, sub)
                val status = if (rank > 0) {
                    locale.raw("commands.skill-subskill-unlocked")
                } else {
                    locale.raw("commands.skill-subskill-locked").replace("{req}", (nextReq ?: 1).toString())
                }
                add(
                    OpusDialogs.plainBody(
                        locale.raw(
                            "commands.skill-subskill-line",
                            "name" to locale.subSkillName(sub),
                            "rank" to rank,
                            "max_rank" to sub.maxRanks,
                            "status" to status,
                        )
                    )
                )
            }
        }

        val actions = buildList {
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.btn-open-skill-gui"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p ->
                        SkillGui.openSkillDetail(context, p, state, skill)
                    },
                )
            )
            if (!skill.isChild) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.btn-open-top"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            SkillGui.openLeaderboardAsync(context, p, skill, 1)
                        },
                    )
                )
            }
            add(
                OpusDialogs.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p ->
                        openGuideDialog(context, p, context.players.getCached(p))
                    },
                )
            )
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("gui.detail-title", mapOf("skill" to skillName)),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openCooldownsDialog(context: OpusContext, player: Player, state: OpusPlayerState) {
        val locale = context.locale
        val now = System.currentTimeMillis()
        val body = SuperAbilityType.entries.map { ability ->
            val name = locale.abilityName(ability)
            val stateStr = when {
                state.isAbilityActive(ability, now) -> locale.raw("dialog.cooldown-active-now")
                state.getCooldownRemainingMillis(ability, now) <= 0L -> locale.raw("commands.cooldown-ready")
                else -> locale.raw(
                    "commands.cooldown-active",
                    "time" to TextFormatter.formatDuration(state.getCooldownRemainingMillis(ability, now)),
                )
            }
            OpusDialogs.plainBody(
                locale.raw("commands.cooldown-entry", "ability" to name, "state" to stateStr)
            )
        }

        val actions = listOf(
            OpusDialogs.actionButton(
                locale.raw("dialog.btn-open-gui"),
                width = 140,
                action = OpusDialogs.customAction(context) { _, p ->
                    SkillGui.openCooldowns(context, p, context.players.getCached(p))
                },
            ),
            OpusDialogs.actionButton(
                locale.raw("dialog.btn-open-hub"),
                width = 140,
                action = OpusDialogs.customAction(context) { _, p ->
                    SkillGui.openHub(context, p, context.players.getCached(p))
                },
            ),
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.cooldowns-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openRankingsDialog(
        context: OpusContext,
        viewer: Player,
        targetState: OpusPlayerState,
        ranks: Map<SkillType?, Int?>,
    ) {
        val locale = context.locale
        val body = buildList {
            add(
                OpusDialogs.plainBody(
                    locale.raw(
                        "commands.stats-power",
                        "power" to targetState.powerLevel(),
                        "rank" to (ranks[null]?.toString() ?: "-"),
                    )
                )
            )
            for (skill in SkillType.nonChildSkills) {
                val r = ranks[skill]?.toString() ?: "-"
                add(
                    OpusDialogs.plainBody(
                        locale.raw(
                            "commands.rank-entry",
                            "skill" to locale.skillName(skill),
                            "rank" to r,
                            "level" to targetState.getLevel(skill),
                        )
                    )
                )
            }
        }

        val actions = listOf(
            OpusDialogs.actionButton(
                locale.raw("dialog.btn-inspect-gui"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p ->
                    SkillGui.openHub(context, p, targetState, readOnlyInspect = targetState.uuid != p.uniqueId)
                },
            ),
            OpusDialogs.actionButton(
                locale.raw("dialog.btn-open-top"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p ->
                    SkillGui.openLeaderboardAsync(context, p, null, 1)
                },
            ),
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = viewer,
            title = locale.component("commands.rank-header", mapOf("player" to targetState.username)),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openAdminDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission("opus.admin")) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        val locale = context.locale
        val body = listOf(
            OpusDialogs.itemBody(
                ItemStack(Material.COMMAND_BLOCK),
                descriptionRaw = locale.raw("dialog.admin-desc"),
            ),
            OpusDialogs.plainBody(
                locale.raw("commands.xprate-current", "rate" to context.experienceConfig.globalMultiplier)
            ),
            OpusDialogs.plainBody(
                locale.raw(
                    "commands.hardcore-status",
                    "state" to if (context.config.hardcoreStatLossEnabled) "ON" else "OFF",
                    "rate" to context.config.hardcoreStatLossPercentage,
                )
            ),
        )

        val actions = listOf(
            OpusDialogs.actionButton(
                locale.raw("dialog.admin-btn-xprate"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p -> openXpRateDialog(context, p) },
            ),
            OpusDialogs.actionButton(
                locale.raw("dialog.admin-btn-hardcore"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p -> openHardcoreDialog(context, p) },
            ),
            OpusDialogs.actionButton(
                locale.raw("dialog.admin-btn-edit-player"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p -> openPlayerEditDialog(context, p) },
            ),
            OpusDialogs.actionButton(
                locale.raw("dialog.admin-btn-reload"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p ->
                    context.reloadAll()
                    locale.send(p, "general.reloaded")
                    locale.sendActionBar(p, "general.reloaded")
                    openAdminDialog(context, p)
                },
            ),
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = admin,
            title = locale.component("dialog.admin-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openXpRateDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission("opus.admin")) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        val locale = context.locale
        val current = context.experienceConfig.globalMultiplier.toFloat().coerceIn(0.5f, 10.0f)
        val inputs = listOf(
            OpusDialogs.numberRangeInput(
                key = "rate",
                labelRaw = locale.raw("dialog.admin-xprate-label"),
                start = 0.5f,
                end = 10.0f,
                initial = current,
                step = 0.5f,
            ),
            OpusDialogs.boolInput(
                key = "event",
                labelRaw = locale.raw("dialog.admin-xprate-event"),
                initial = context.experienceConfig.xpEventActive,
            ),
        )

        val applyBtn = OpusDialogs.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
                val rate = (response.getFloat("rate") ?: current).toDouble()
                val isEvent = response.getBoolean("event") ?: false
                applyXpRate(context, p, rate, isEvent)
            },
        )
        val resetBtn = OpusDialogs.actionButton(
            locale.raw("dialog.admin-btn-reset-rate"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p ->
                val restored = context.experienceConfig.configuredGlobalMultiplier
                context.experienceConfig.globalMultiplier = restored
                context.experienceConfig.xpEventActive = false
                Bukkit.broadcast(
                    locale.prefixedComponent("commands.xprate-event-reset", mapOf("rate" to restored.toString()))
                )
                val hud = context.getService<HudFeedbackHook>()
                for (online in Bukkit.getOnlinePlayers()) {
                    hud?.hideChannelBossBar(online, HudService.XP_EVENT_CHANNEL)
                    locale.sendActionBar(online, "commands.xprate-event-reset", "rate" to restored)
                }
            },
        )
        val backBtn = OpusDialogs.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p -> openAdminDialog(context, p) },
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = admin,
            title = locale.component("dialog.admin-xprate-title"),
            body = listOf(
                OpusDialogs.plainBody(
                    locale.raw(
                        "commands.xprate-current",
                        "rate" to context.experienceConfig.globalMultiplier
                    )
                )
            ),
            inputs = inputs,
            actions = listOf(applyBtn, resetBtn, backBtn),
            columns = 2,
        )
    }

    fun applyXpRate(context: OpusContext, sender: org.bukkit.command.CommandSender, rate: Double, isEvent: Boolean) {
        context.experienceConfig.globalMultiplier = rate
        context.experienceConfig.xpEventActive = isEvent
        if (isEvent) {
            Bukkit.broadcast(
                context.locale.prefixedComponent("commands.xprate-event-broadcast", mapOf("rate" to rate.toString()))
            )
            val hud = context.getService<HudFeedbackHook>()
            for (online in Bukkit.getOnlinePlayers()) {
                hud?.showChannelBossBar(
                    player = online,
                    channel = HudService.XP_EVENT_CHANNEL,
                    title = context.locale.component("hud.xp-event-bossbar", mapOf("rate" to rate.toString())),
                    color = BossBar.Color.YELLOW,
                    progress = 1.0f,
                    durationTicks = 120L,
                )
                context.locale.sendActionBar(online, "commands.xprate-event-broadcast", "rate" to rate)
            }
        } else {
            context.locale.send(sender, "commands.xprate-set", "rate" to rate)
            if (sender is Player) {
                context.locale.sendActionBar(sender, "commands.xprate-set", "rate" to rate)
            }
        }
    }

    fun openHardcoreDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission("opus.admin")) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        val locale = context.locale
        val inputs = listOf(
            OpusDialogs.boolInput(
                "stat_loss",
                locale.raw("dialog.admin-hc-loss"),
                context.config.hardcoreStatLossEnabled
            ),
            OpusDialogs.numberRangeInput(
                "loss_pct",
                locale.raw("dialog.admin-hc-loss-pct"),
                0.0f,
                50.0f,
                context.config.hardcoreStatLossPercentage.toFloat().coerceIn(0f, 50f),
                step = 1.0f,
            ),
            OpusDialogs.boolInput(
                "vampirism",
                locale.raw("dialog.admin-hc-vamp"),
                context.config.hardcoreVampirismEnabled
            ),
            OpusDialogs.numberRangeInput(
                "vamp_pct",
                locale.raw("dialog.admin-hc-vamp-pct"),
                0.0f,
                50.0f,
                context.config.hardcoreVampirismPercentage.toFloat().coerceIn(0f, 50f),
                step = 1.0f,
            ),
        )

        val saveBtn = OpusDialogs.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
                response.getBoolean("stat_loss")?.let { context.config.hardcoreStatLossEnabled = it }
                response.getFloat("loss_pct")?.let { context.config.hardcoreStatLossPercentage = it.toDouble() }
                response.getBoolean("vampirism")?.let { context.config.hardcoreVampirismEnabled = it }
                response.getFloat("vamp_pct")?.let { context.config.hardcoreVampirismPercentage = it.toDouble() }

                locale.send(
                    p,
                    "commands.hardcore-status",
                    "state" to if (context.config.hardcoreStatLossEnabled) "ON" else "OFF",
                    "rate" to context.config.hardcoreStatLossPercentage,
                )
                locale.send(
                    p,
                    "commands.vampirism-status",
                    "state" to if (context.config.hardcoreVampirismEnabled) "ON" else "OFF",
                    "rate" to context.config.hardcoreVampirismPercentage,
                )
                locale.sendActionBar(p, "dialog.admin-hc-saved")
            },
        )
        val backBtn = OpusDialogs.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p -> openAdminDialog(context, p) },
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = admin,
            title = locale.component("dialog.admin-hc-title"),
            inputs = inputs,
            actions = listOf(saveBtn, backBtn),
            columns = 2,
        )
    }

    fun openPlayerEditDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission("opus.admin")) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        val locale = context.locale
        val skillOptions = SkillType.nonChildSkills.mapIndexed { idx, s ->
            Triple(s.key, locale.skillName(s), idx == 0)
        }
        val modeOptions = listOf(
            Triple("ADD_XP", locale.raw("dialog.admin-mode-addxp"), true),
            Triple("ADD_LEVELS", locale.raw("dialog.admin-mode-addlevels"), false),
            Triple("SET_LEVEL", locale.raw("dialog.admin-mode-setlevel"), false),
        )

        val inputs = listOf(
            OpusDialogs.textInput(
                "player",
                locale.raw("dialog.admin-input-player"),
                initial = admin.name,
                maxLength = 24
            ),
            OpusDialogs.singleOptionInput("skill", locale.raw("dialog.admin-input-skill"), skillOptions),
            OpusDialogs.singleOptionInput("mode", locale.raw("dialog.admin-input-action"), modeOptions),
            OpusDialogs.textInput("amount", locale.raw("dialog.admin-input-amount"), initial = "100", maxLength = 12),
        )

        val applyBtn = OpusDialogs.actionButton(
            locale.raw("dialog.confirm"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
                val targetName = response.getText("player")?.trim() ?: return@customAction
                val skillKey = response.getText("skill") ?: return@customAction
                val mode = response.getText("mode") ?: "ADD_XP"
                val rawAmt = response.getText("amount")?.trim() ?: ""

                val skill = SkillType.match(skillKey)?.takeIf { !it.isChild } ?: return@customAction
                val target = Bukkit.getPlayerExact(targetName)
                if (target == null) {
                    locale.send(p, "general.player-not-found", "player" to targetName)
                    locale.sendActionBar(p, "general.player-not-found", "player" to targetName)
                    return@customAction
                }
                val num = rawAmt.toDoubleOrNull()
                if (num == null || num < 0.0) {
                    locale.send(p, "general.invalid-number", "value" to rawAmt)
                    return@customAction
                }

                when (mode) {
                    "ADD_XP" -> {
                        if (num <= 0.0) return@customAction
                        context.progression.awardXpDirect(target, skill, num, XpGainReason.COMMAND)
                        locale.send(
                            p,
                            "commands.addxp-success",
                            "player" to target.name,
                            "skill" to locale.skillName(skill),
                            "amount" to num.toInt(),
                        )
                    }

                    "ADD_LEVELS" -> {
                        val lvlAdd = num.toInt()
                        if (lvlAdd <= 0) return@customAction
                        val st = context.players.getCached(target)
                        st.setSkill(skill, st.getLevel(skill) + lvlAdd, st.getXp(skill))
                        locale.send(
                            p,
                            "commands.addlevels-success",
                            "player" to target.name,
                            "skill" to locale.skillName(skill),
                            "amount" to lvlAdd,
                        )
                    }

                    "SET_LEVEL" -> {
                        val lvl = num.toInt().coerceAtLeast(0)
                        val st = context.players.getCached(target)
                        st.setSkill(skill, lvl, 0.0)
                        locale.send(
                            p,
                            "commands.mmoedit-success",
                            "player" to target.name,
                            "skill" to locale.skillName(skill),
                            "level" to lvl,
                        )
                    }
                }
                locale.sendActionBar(p, "dialog.admin-edit-applied", "player" to target.name)
            },
        )
        val backBtn = OpusDialogs.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p -> openAdminDialog(context, p) },
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = admin,
            title = locale.component("dialog.admin-edit-title"),
            inputs = inputs,
            actions = listOf(applyBtn, backBtn),
            columns = 2,
        )
    }

    fun openSkillResetConfirm(
        context: OpusContext,
        admin: Player,
        target: Player,
        skill: SkillType?,
    ) {
        val locale = context.locale
        val skillLabel = skill?.let { locale.skillName(it) } ?: locale.raw("commands.skillreset-all-label")
        OpusDialogs.showConfirm(
            context = context,
            player = admin,
            title = locale.component("dialog.admin-reset-confirm-title"),
            body = listOf(
                OpusDialogs.plainBody(
                    locale.raw(
                        "dialog.admin-reset-confirm-body",
                        "player" to target.name,
                        "skill" to skillLabel,
                    )
                )
            ),
            onConfirm = { p ->
                val st = context.players.getCached(target)
                if (skill == null) {
                    for (s in SkillType.nonChildSkills) {
                        st.setSkill(s, context.config.startingLevel, 0.0)
                    }
                } else {
                    st.setSkill(skill, context.config.startingLevel, 0.0)
                }
                locale.send(p, "commands.skillreset-success", "player" to target.name, "skill" to skillLabel)
                locale.sendActionBar(p, "commands.skillreset-success", "player" to target.name, "skill" to skillLabel)
            },
        )
    }

    fun openRemovePlayerConfirm(context: OpusContext, admin: Player, targetName: String) {
        val locale = context.locale
        OpusDialogs.showConfirm(
            context = context,
            player = admin,
            title = locale.component("dialog.admin-remove-confirm-title"),
            body = listOf(
                OpusDialogs.plainBody(locale.raw("dialog.admin-remove-confirm-body", "player" to targetName))
            ),
            onConfirm = { p ->
                CommandHelper.dispatchAsync(
                    context,
                    p,
                    asyncWork = {
                        val st = context.players.findByName(targetName) ?: return@dispatchAsync false
                        context.players.removePlayer(st.uuid)
                    },
                    onResult = { removed ->
                        if (removed) {
                            locale.send(p, "commands.remove-success", "player" to targetName)
                            locale.sendActionBar(p, "commands.remove-success", "player" to targetName)
                        } else {
                            locale.send(p, "general.player-not-found", "player" to targetName)
                        }
                    },
                )
            },
        )
    }

    fun openPurgeConfirm(context: OpusContext, admin: Player, days: Long) {
        val locale = context.locale
        val cutoff = System.currentTimeMillis() - (days * 86_400_000L)
        OpusDialogs.showConfirm(
            context = context,
            player = admin,
            title = locale.component("dialog.admin-purge-confirm-title"),
            body = listOf(
                OpusDialogs.plainBody(locale.raw("dialog.admin-purge-confirm-body", "days" to days))
            ),
            onConfirm = { p ->
                CommandHelper.dispatchAsync(
                    context,
                    p,
                    asyncWork = { context.playerRepository.purgeInactive(cutoff) },
                    onResult = { count ->
                        locale.send(p, "commands.purge-result", "count" to count)
                        locale.sendActionBar(p, "commands.purge-result", "count" to count)
                    },
                )
            },
        )
    }
}
