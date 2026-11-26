package opus.modules.skills

import net.kyori.adventure.bossbar.BossBar
import opus.core.OpusContext
import opus.core.command.AliasGui
import opus.core.compat.PlatformCompat
import opus.core.text.TextFormatter
import opus.core.ui.PaperDialogKit
import opus.events.XpGainReason
import opus.modules.hud.HudDialogs
import opus.modules.hud.HudService
import opus.modules.party.PartyService
import opus.rpg
import opus.skills.HudFeedbackHook
import opus.skills.OpusPlayerState
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import opus.skills.abilityName
import opus.skills.skillName
import opus.skills.subSkillName
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Native Paper Dialog (`1.21.6+`) builders for RPG guides, skill details, cooldowns, rankings, and admin tools.
 */
internal object PaperSkillDialogs {

    fun openGuideDialog(context: OpusContext, player: Player, state: OpusPlayerState) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val mode =
            locale.raw(if (context.config.retroMode) "commands.opus-mode-retro" else "commands.opus-mode-standard")
        val power = state.powerLevel()
        val mastered = context.rpg.ranks.masteredCount(state)

        val body = listOf(
            k.itemBody(
                ItemStack(Material.NETHER_STAR),
                descriptionRaw = locale.raw(
                    "dialog.guide-header",
                    "version" to PlatformCompat.pluginVersion(context.plugin),
                    "mode" to mode,
                ),
            ),
            k.plainBody(
                locale.raw(
                    "dialog.guide-stats",
                    "power" to power,
                    "mastered" to mastered,
                    "total" to SkillType.nonChildSkills.size,
                )
            ),
            k.plainBody(locale.raw("commands.mmoinfo-summary")),
        )

        val actions = buildList {
            add(
                k.actionButton(
                    locale.raw("dialog.btn-open-hub"),
                    width = 140,
                    action = k.customAction { _, p ->
                        SkillGui.openHub(context, p, context.rpg.players.getCached(p))
                    },
                )
            )
            add(
                k.actionButton(
                    locale.raw("dialog.btn-open-hud"),
                    width = 140,
                    action = k.customAction { _, p ->
                        HudDialogs.openSettingsDialog(context, p) { opener ->
                            SkillDialogs.openGuideDialog(context, opener, context.rpg.players.getCached(opener))
                        }
                    },
                )
            )
            add(
                k.actionButton(
                    locale.raw("dialog.btn-open-cooldowns"),
                    width = 140,
                    action = k.customAction { _, p ->
                        SkillDialogs.openCooldownsDialog(context, p, context.rpg.players.getCached(p))
                    },
                )
            )
            add(
                k.actionButton(
                    locale.raw("dialog.btn-open-top"),
                    width = 140,
                    action = k.customAction { _, p ->
                        SkillGui.openLeaderboardAsync(context, p, null, 1)
                    },
                )
            )
            context.getService<PartyService>()?.let { partyService ->
                add(
                    k.actionButton(
                        locale.raw("dialog.btn-open-party"),
                        width = 140,
                        action = k.customAction { _, p ->
                            partyService.openPartyPortal(p)
                        },
                    )
                )
            }
            if (player.hasPermission(context.adminPermission)) {
                add(
                    k.actionButton(
                        locale.raw("dialog.btn-open-admin"),
                        width = 140,
                        action = k.customAction { _, p ->
                            SkillDialogs.openAdminDialog(context, p)
                        },
                    )
                )
            }
        }

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.guide-title"),
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
        val k = PaperDialogKit(context)
        val skillName = locale.skillName(skill)
        val lvl = state.getLevel(skill)
        val power = state.powerLevel()

        val body = buildList {
            add(
                k.itemBody(
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
                val needed = context.rpg.formula.xpToNextLevel(lvl, context.config.retroMode, power)
                val pct = if (needed <= 0) 100 else ((xp / needed) * 100.0).toInt().coerceIn(0, 100)
                add(
                    k.plainBody(
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
                add(k.plainBody("<gray>($parents)</gray>"))
            }

            skill.superAbility()?.let { ability ->
                val dur = context.rpg.probability.abilityDurationSeconds(lvl)
                val rem = state.getCooldownRemainingMillis(ability)
                val cdStr = if (rem <= 0L) locale.raw("commands.cooldown-ready") else TextFormatter.formatDuration(rem)
                add(
                    k.plainBody(
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
                val rank = context.rpg.ranks.getRank(state, sub)
                val nextReq = context.rpg.ranks.nextUnlockLevel(state, sub)
                val status = if (rank > 0) {
                    locale.raw("commands.skill-subskill-unlocked")
                } else {
                    locale.raw("commands.skill-subskill-locked").replace("{req}", (nextReq ?: 1).toString())
                }
                add(
                    k.plainBody(
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
                k.actionButton(
                    locale.raw("dialog.btn-open-skill-gui"),
                    width = 140,
                    action = k.customAction { _, p ->
                        SkillGui.openSkillDetail(context, p, state, skill)
                    },
                )
            )
            if (!skill.isChild) {
                add(
                    k.actionButton(
                        locale.raw("dialog.btn-open-top"),
                        width = 140,
                        action = k.customAction { _, p ->
                            SkillGui.openLeaderboardAsync(context, p, skill, 1)
                        },
                    )
                )
            }
            add(
                k.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = k.customAction { _, p ->
                        SkillDialogs.openGuideDialog(context, p, context.rpg.players.getCached(p))
                    },
                )
            )
        }

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("gui.detail-title", "skill" to skillName),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openCooldownsDialog(context: OpusContext, player: Player, state: OpusPlayerState) {
        val locale = context.locale
        val k = PaperDialogKit(context)
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
            k.plainBody(
                locale.raw("commands.cooldown-entry", "ability" to name, "state" to stateStr)
            )
        }

        val actions = listOf(
            k.actionButton(
                locale.raw("dialog.btn-open-gui"),
                width = 140,
                action = k.customAction { _, p ->
                    SkillGui.openCooldowns(context, p, context.rpg.players.getCached(p))
                },
            ),
            k.actionButton(
                locale.raw("dialog.btn-open-hub"),
                width = 140,
                action = k.customAction { _, p ->
                    SkillGui.openHub(context, p, context.rpg.players.getCached(p))
                },
            ),
        )

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.cooldowns-title"),
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
        val k = PaperDialogKit(context)
        val body = buildList {
            add(
                k.plainBody(
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
                    k.plainBody(
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
            k.actionButton(
                locale.raw("dialog.btn-inspect-gui"),
                width = 150,
                action = k.customAction { _, p ->
                    SkillGui.openHub(context, p, targetState, readOnlyInspect = targetState.uuid != p.uniqueId)
                },
            ),
            k.actionButton(
                locale.raw("dialog.btn-open-top"),
                width = 150,
                action = k.customAction { _, p ->
                    SkillGui.openLeaderboardAsync(context, p, null, 1)
                },
            ),
        )

        k.showMultiAction(
            player = viewer,
            titleRaw = locale.raw("commands.rank-header", "player" to targetState.username),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openAdminDialog(context: OpusContext, admin: Player) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val body = listOf(
            k.itemBody(
                ItemStack(Material.COMMAND_BLOCK),
                descriptionRaw = locale.raw("dialog.admin-desc"),
            ),
            k.plainBody(
                locale.raw("commands.xprate-current", "rate" to context.rpg.experienceConfig.globalMultiplier)
            ),
            k.plainBody(
                locale.raw(
                    "commands.hardcore-status",
                    "state" to if (context.config.hardcoreStatLossEnabled) "ON" else "OFF",
                    "rate" to context.config.hardcoreStatLossPercentage,
                )
            ),
        )

        val actions = listOf(
            k.actionButton(
                locale.raw("dialog.admin-btn-xprate"),
                width = 150,
                action = k.customAction { _, p -> SkillDialogs.openXpRateDialog(context, p) },
            ),
            k.actionButton(
                locale.raw("dialog.admin-btn-hardcore"),
                width = 150,
                action = k.customAction { _, p -> SkillDialogs.openHardcoreDialog(context, p) },
            ),
            k.actionButton(
                locale.raw("dialog.admin-btn-edit-player"),
                width = 150,
                action = k.customAction { _, p -> SkillDialogs.openPlayerEditDialog(context, p) },
            ),
            k.actionButton(
                locale.raw("dialog.admin-btn-aliases"),
                width = 150,
                action = k.customAction { _, p ->
                    AliasGui.open(context, p) { backPlayer -> SkillDialogs.openAdminDialog(context, backPlayer) }
                },
            ),
            k.actionButton(
                locale.raw("dialog.admin-btn-reload"),
                width = 150,
                action = k.customAction { _, p ->
                    context.reloadAll()
                    locale.send(p, "general.reloaded")
                    context.actionBar.pushTransientLocale(p, "general.reloaded")
                    SkillDialogs.openAdminDialog(context, p)
                },
            ),
        )

        k.showMultiAction(
            player = admin,
            titleRaw = locale.raw("dialog.admin-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openXpRateDialog(context: OpusContext, admin: Player) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val current = context.rpg.experienceConfig.globalMultiplier.toFloat().coerceIn(0.5f, 10.0f)
        val inputs = listOf(
            k.numberRangeInput(
                key = "rate",
                labelRaw = locale.raw("dialog.admin-xprate-label"),
                start = 0.5f,
                end = 10.0f,
                initial = current,
                step = 0.5f,
            ),
            k.boolInput(
                key = "event",
                labelRaw = locale.raw("dialog.admin-xprate-event"),
                initial = context.rpg.experienceConfig.xpEventActive,
            ),
        )

        val applyBtn = k.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = k.customAction { response, p ->
                val rate = (response.getFloat("rate") ?: current).toDouble()
                val isEvent = response.getBoolean("event") ?: false
                SkillDialogs.applyXpRate(context, p, rate, isEvent)
            },
        )
        val resetBtn = k.actionButton(
            locale.raw("dialog.admin-btn-reset-rate"),
            width = 140,
            action = k.customAction { _, _ ->
                val restored = context.rpg.experienceConfig.configuredGlobalMultiplier
                context.rpg.experienceConfig.globalMultiplier = restored
                context.rpg.experienceConfig.xpEventActive = false
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
        val backBtn = k.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = k.customAction { _, p -> SkillDialogs.openAdminDialog(context, p) },
        )

        k.showMultiAction(
            player = admin,
            titleRaw = locale.raw("dialog.admin-xprate-title"),
            body = listOf(
                k.plainBody(
                    locale.raw(
                        "commands.xprate-current",
                        "rate" to context.rpg.experienceConfig.globalMultiplier,
                    )
                )
            ),
            inputs = inputs,
            actions = listOf(applyBtn, resetBtn, backBtn),
            columns = 2,
        )
    }

    fun openHardcoreDialog(context: OpusContext, admin: Player) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val inputs = listOf(
            k.boolInput(
                "stat_loss",
                locale.raw("dialog.admin-hc-loss"),
                context.config.hardcoreStatLossEnabled,
            ),
            k.numberRangeInput(
                "loss_pct",
                locale.raw("dialog.admin-hc-loss-pct"),
                0.0f,
                50.0f,
                context.config.hardcoreStatLossPercentage.toFloat().coerceIn(0f, 50f),
                step = 1.0f,
            ),
            k.boolInput(
                "vampirism",
                locale.raw("dialog.admin-hc-vamp"),
                context.config.hardcoreVampirismEnabled,
            ),
            k.numberRangeInput(
                "vamp_pct",
                locale.raw("dialog.admin-hc-vamp-pct"),
                0.0f,
                50.0f,
                context.config.hardcoreVampirismPercentage.toFloat().coerceIn(0f, 50f),
                step = 1.0f,
            ),
        )

        val saveBtn = k.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = k.customAction { response, p ->
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
        val backBtn = k.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = k.customAction { _, p -> SkillDialogs.openAdminDialog(context, p) },
        )

        k.showMultiAction(
            player = admin,
            titleRaw = locale.raw("dialog.admin-hc-title"),
            inputs = inputs,
            actions = listOf(saveBtn, backBtn),
            columns = 2,
        )
    }

    fun openPlayerEditDialog(context: OpusContext, admin: Player) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val skillOptions = SkillType.nonChildSkills.mapIndexed { idx, s ->
            Triple(s.key, locale.skillName(s), idx == 0)
        }
        val modeOptions = listOf(
            Triple("ADD_XP", locale.raw("dialog.admin-mode-addxp"), true),
            Triple("ADD_LEVELS", locale.raw("dialog.admin-mode-addlevels"), false),
            Triple("SET_LEVEL", locale.raw("dialog.admin-mode-setlevel"), false),
        )

        val inputs = listOf(
            k.textInput(
                "player",
                locale.raw("dialog.admin-input-player"),
                initial = admin.name,
                maxLength = 24,
            ),
            k.singleOptionInput("skill", locale.raw("dialog.admin-input-skill"), skillOptions),
            k.singleOptionInput("mode", locale.raw("dialog.admin-input-action"), modeOptions),
            k.textInput("amount", locale.raw("dialog.admin-input-amount"), initial = "100", maxLength = 12),
        )

        val applyBtn = k.actionButton(
            locale.raw("dialog.confirm"),
            width = 140,
            action = k.customAction { response, p ->
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
                        context.rpg.progression.awardXpDirect(target, skill, num, XpGainReason.COMMAND)
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
                        val st = context.rpg.players.getCached(target)
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
                        val st = context.rpg.players.getCached(target)
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
        val backBtn = k.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = k.customAction { _, p -> SkillDialogs.openAdminDialog(context, p) },
        )

        k.showMultiAction(
            player = admin,
            titleRaw = locale.raw("dialog.admin-edit-title"),
            inputs = inputs,
            actions = listOf(applyBtn, backBtn),
            columns = 2,
        )
    }
}
