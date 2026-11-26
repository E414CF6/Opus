package opus.modules.skills

import net.kyori.adventure.bossbar.BossBar
import opus.core.OpusContext
import opus.core.command.AliasGui
import opus.core.command.CommandHelper
import opus.core.compat.PlatformCompat
import opus.core.ui.MenuItems
import opus.core.ui.MenuWindow
import opus.modules.hud.HudService
import opus.rpg
import opus.skills.HudFeedbackHook
import opus.skills.OpusPlayerState
import opus.skills.SkillType
import opus.skills.skillName
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * UI dispatcher for RPG guides, skill details, cooldowns, rankings, and admin tools.
 * Uses native Paper Dialogs on `1.21.6+` and falls back to Chest GUIs / chat commands on older versions.
 */
object SkillDialogs {

    fun openGuideDialog(context: OpusContext, player: Player, state: OpusPlayerState) {
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openGuideDialog(context, player, state)
        } else {
            SkillGui.openHub(context, player, state)
        }
    }

    fun openSkillGuideDialog(
        context: OpusContext,
        player: Player,
        state: OpusPlayerState,
        skill: SkillType,
    ) {
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openSkillGuideDialog(context, player, state, skill)
        } else {
            SkillGui.openSkillDetail(context, player, state, skill)
        }
    }

    fun openCooldownsDialog(context: OpusContext, player: Player, state: OpusPlayerState) {
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openCooldownsDialog(context, player, state)
        } else {
            SkillGui.openCooldowns(context, player, state)
        }
    }

    fun openRankingsDialog(
        context: OpusContext,
        viewer: Player,
        targetState: OpusPlayerState,
        ranks: Map<SkillType?, Int?>,
    ) {
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openRankingsDialog(context, viewer, targetState, ranks)
        } else {
            SkillGui.openHub(context, viewer, targetState, readOnlyInspect = targetState.uuid != viewer.uniqueId)
        }
    }

    fun openAdminDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission(context.adminPermission)) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openAdminDialog(context, admin)
        } else {
            openAdminMenu(context, admin)
        }
    }

    fun openXpRateDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission(context.adminPermission)) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openXpRateDialog(context, admin)
        } else {
            openXpRateMenu(context, admin)
        }
    }

    fun openHardcoreDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission(context.adminPermission)) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openHardcoreDialog(context, admin)
        } else {
            openHardcoreMenu(context, admin)
        }
    }

    fun openPlayerEditDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission(context.adminPermission)) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperSkillDialogs.openPlayerEditDialog(context, admin)
        } else {
            admin.closeInventory()
            context.locale.send(admin, "commands.usage-addxp")
            context.locale.send(admin, "commands.usage-mmoedit")
        }
    }

    fun applyXpRate(context: OpusContext, sender: CommandSender, rate: Double, isEvent: Boolean) {
        context.rpg.experienceConfig.globalMultiplier = rate
        context.rpg.experienceConfig.xpEventActive = isEvent
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

    fun openSkillResetConfirm(
        context: OpusContext,
        admin: Player,
        target: Player,
        skill: SkillType?,
    ) {
        val locale = context.locale
        val skillLabel = skill?.let { locale.skillName(it) } ?: locale.raw("commands.skillreset-all-label")
        context.dialogs.openConfirmation(
            player = admin,
            titleRaw = locale.raw("dialog.admin-reset-confirm-title"),
            bodyRaw = locale.raw(
                "dialog.admin-reset-confirm-body",
                "player" to target.name,
                "skill" to skillLabel,
            ),
            onConfirm = { p ->
                val st = context.rpg.players.getCached(target)
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
        context.dialogs.openConfirmation(
            player = admin,
            titleRaw = locale.raw("dialog.admin-remove-confirm-title"),
            bodyRaw = locale.raw("dialog.admin-remove-confirm-body", "player" to targetName),
            onConfirm = { p ->
                CommandHelper.dispatchAsync(
                    context,
                    p,
                    asyncWork = {
                        val st = context.rpg.players.findByName(targetName) ?: return@dispatchAsync false
                        context.rpg.players.removePlayer(st.uuid)
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
        context.dialogs.openConfirmation(
            player = admin,
            titleRaw = locale.raw("dialog.admin-purge-confirm-title"),
            bodyRaw = locale.raw("dialog.admin-purge-confirm-body", "days" to days),
            onConfirm = { p ->
                CommandHelper.dispatchAsync(
                    context,
                    p,
                    asyncWork = { context.rpg.playerRepository.purgeInactive(cutoff) },
                    onResult = { count ->
                        locale.send(p, "commands.purge-result", "count" to count)
                        locale.sendActionBar(p, "commands.purge-result", "count" to count)
                    },
                )
            },
        )
    }

    private fun openAdminMenu(context: OpusContext, admin: Player) {
        val locale = context.locale
        val menu = MenuWindow(locale.component("dialog.admin-title"), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        menu.set(
            10,
            MenuItems.item(
                Material.EXPERIENCE_BOTTLE,
                locale.raw("dialog.admin-btn-xprate"),
                listOf(locale.raw("commands.xprate-current", "rate" to context.rpg.experienceConfig.globalMultiplier)),
            ),
        ) { click ->
            openXpRateMenu(context, click.player)
        }
        menu.set(
            12,
            MenuItems.item(
                Material.WITHER_SKELETON_SKULL,
                locale.raw("dialog.admin-btn-hardcore"),
                listOf(
                    locale.raw(
                        "commands.hardcore-status",
                        "state" to if (context.config.hardcoreStatLossEnabled) "ON" else "OFF",
                        "rate" to context.config.hardcoreStatLossPercentage,
                    )
                ),
            ),
        ) { click ->
            openHardcoreMenu(context, click.player)
        }
        menu.set(
            13,
            MenuItems.item(
                Material.WRITABLE_BOOK,
                locale.raw("dialog.admin-btn-edit-player"),
                listOf(locale.raw("commands.usage-mmoedit")),
            ),
        ) { click ->
            openPlayerEditDialog(context, click.player)
        }
        menu.set(
            14,
            MenuItems.item(
                Material.NAME_TAG,
                locale.raw("dialog.admin-btn-aliases"),
            ),
        ) { click ->
            AliasGui.open(context, click.player) { openAdminMenu(context, it) }
        }
        menu.set(
            16,
            MenuItems.item(
                Material.CLOCK,
                locale.raw("dialog.admin-btn-reload"),
            ),
        ) { click ->
            context.reloadAll()
            locale.send(click.player, "general.reloaded")
            context.actionBar.pushTransientLocale(click.player, "general.reloaded")
            openAdminMenu(context, click.player)
        }
        menu.set(22, MenuItems.closeButton(context)) { click ->
            click.player.closeInventory()
        }
        menu.open(admin)
    }

    private fun openXpRateMenu(context: OpusContext, admin: Player) {
        val locale = context.locale
        val menu = MenuWindow(locale.component("dialog.admin-xprate-title"), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val rates = listOf(10 to 1.0, 11 to 1.5, 12 to 2.0, 13 to 3.0, 14 to 5.0)
        for ((slot, rate) in rates) {
            menu.set(
                slot,
                MenuItems.item(
                    Material.EXPERIENCE_BOTTLE,
                    "<gold>${rate}x</gold>",
                    listOf(
                        locale.raw(
                            "commands.xprate-current",
                            "rate" to context.rpg.experienceConfig.globalMultiplier
                        )
                    ),
                ),
            ) { click ->
                applyXpRate(context, click.player, rate, isEvent = rate > 1.0)
                openXpRateMenu(context, click.player)
            }
        }
        menu.set(
            16,
            MenuItems.item(Material.MILK_BUCKET, locale.raw("dialog.admin-btn-reset-rate")),
        ) { click ->
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
            openXpRateMenu(context, click.player)
        }
        menu.set(21, MenuItems.backButton(context)) { click -> openAdminMenu(context, click.player) }
        menu.set(23, MenuItems.closeButton(context)) { click -> click.player.closeInventory() }
        menu.open(admin)
    }

    private fun openHardcoreMenu(context: OpusContext, admin: Player) {
        val locale = context.locale
        val menu = MenuWindow(locale.component("dialog.admin-hc-title"), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        menu.set(
            11,
            MenuItems.toggle(
                Material.SKELETON_SKULL,
                locale.raw("dialog.admin-hc-loss"),
                context.config.hardcoreStatLossEnabled,
                context,
                listOf(locale.raw("gui.click-toggle")),
            ),
        ) { click ->
            context.config.hardcoreStatLossEnabled = !context.config.hardcoreStatLossEnabled
            locale.sendActionBar(click.player, "dialog.admin-hc-saved")
            openHardcoreMenu(context, click.player)
        }
        menu.set(
            15,
            MenuItems.toggle(
                Material.REDSTONE,
                locale.raw("dialog.admin-hc-vamp"),
                context.config.hardcoreVampirismEnabled,
                context,
                listOf(locale.raw("gui.click-toggle")),
            ),
        ) { click ->
            context.config.hardcoreVampirismEnabled = !context.config.hardcoreVampirismEnabled
            locale.sendActionBar(click.player, "dialog.admin-hc-saved")
            openHardcoreMenu(context, click.player)
        }
        menu.set(21, MenuItems.backButton(context)) { click -> openAdminMenu(context, click.player) }
        menu.set(23, MenuItems.closeButton(context)) { click -> click.player.closeInventory() }
        menu.open(admin)
    }
}
