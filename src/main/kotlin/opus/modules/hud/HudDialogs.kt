package opus.modules.hud

import opus.core.OpusContext
import opus.core.compat.PlatformCompat
import opus.core.ui.MenuItems
import opus.core.ui.MenuWindow
import opus.rpg
import opus.skills.ScoreboardMode
import opus.skills.XpHudMode
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * HUD, notification, and audio preference UI: Paper Dialog on `1.21.6+` and interactive [MenuWindow] fallback.
 */
object HudDialogs {

    fun openSettingsDialog(
        context: OpusContext,
        player: Player,
        hudService: HudService? = context.getService(),
        onBack: ((Player) -> Unit)? = null,
    ) {
        if (PlatformCompat.supportsDialogs) {
            PaperHudDialogs.openSettingsDialog(context, player, hudService, onBack)
        } else {
            openSettingsMenu(context, player, hudService, onBack)
        }
    }

    fun openSettingsMenu(
        context: OpusContext,
        player: Player,
        hudService: HudService? = context.getService(),
        onBack: ((Player) -> Unit)? = null,
    ) {
        val state = context.rpg.players.getCached(player)
        val locale = context.locale
        val menu = MenuWindow(locale.component("dialog.hud-title"), 4)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        menu.set(
            11,
            MenuItems.item(
                Material.EXPERIENCE_BOTTLE,
                locale.raw("dialog.hud-xp-label"),
                listOf("<aqua>${state.hudMode.name}</aqua>", locale.raw("gui.click-toggle")),
            ),
        ) { click ->
            val next = when (state.hudMode) {
                XpHudMode.BOSSBAR -> XpHudMode.ACTIONBAR
                XpHudMode.ACTIONBAR -> XpHudMode.BOTH
                XpHudMode.BOTH -> XpHudMode.OFF
                XpHudMode.OFF -> XpHudMode.BOSSBAR
            }
            state.hudMode = next
            state.markDirty()
            if (next == XpHudMode.OFF || next == XpHudMode.ACTIONBAR) {
                hudService?.hideChannelBossBar(click.player, HudService.XP_EVENT_CHANNEL)
            }
            locale.sendActionBar(click.player, "dialog.hud-saved")
            openSettingsMenu(context, click.player, hudService, onBack)
        }

        menu.set(
            12,
            MenuItems.item(
                Material.OAK_SIGN,
                locale.raw("dialog.hud-sb-label"),
                listOf("<aqua>${state.scoreboardMode.name}</aqua>", locale.raw("gui.click-toggle")),
            ),
        ) { click ->
            val next = when (state.scoreboardMode) {
                ScoreboardMode.DEFAULT -> if (context.config.scoreboardAllowKeep) ScoreboardMode.KEEP else ScoreboardMode.OFF
                ScoreboardMode.KEEP -> ScoreboardMode.OFF
                ScoreboardMode.OFF -> ScoreboardMode.DEFAULT
            }
            state.scoreboardMode = next
            state.markDirty()
            when (next) {
                ScoreboardMode.KEEP -> hudService?.showStatsBoard(click.player, state, persistent = true)
                ScoreboardMode.OFF -> hudService?.clearBoard(click.player)
                ScoreboardMode.DEFAULT -> hudService?.showStatsBoard(click.player, state, persistent = false)
            }
            locale.sendActionBar(click.player, "dialog.hud-saved")
            openSettingsMenu(context, click.player, hudService, onBack)
        }

        menu.set(
            13,
            MenuItems.toggle(Material.BELL, locale.raw("dialog.hud-notify-label"), state.notifyAbilities, context),
        ) { click ->
            state.notifyAbilities = !state.notifyAbilities
            state.markDirty()
            locale.sendActionBar(click.player, "dialog.hud-saved")
            openSettingsMenu(context, click.player, hudService, onBack)
        }

        menu.set(
            14,
            MenuItems.toggle(Material.NOTE_BLOCK, locale.raw("dialog.hud-sound-label"), state.levelUpSound, context),
        ) { click ->
            state.levelUpSound = !state.levelUpSound
            state.markDirty()
            locale.sendActionBar(click.player, "dialog.hud-saved")
            openSettingsMenu(context, click.player, hudService, onBack)
        }

        menu.set(
            15,
            MenuItems.toggle(
                Material.GOLDEN_PICKAXE,
                locale.raw("dialog.hud-readying-label"),
                state.abilityReadyingEnabled,
                context,
            ),
        ) { click ->
            state.abilityReadyingEnabled = !state.abilityReadyingEnabled
            state.markDirty()
            locale.sendActionBar(click.player, "dialog.hud-saved")
            openSettingsMenu(context, click.player, hudService, onBack)
        }

        if (onBack != null) {
            menu.set(30, MenuItems.backButton(context)) { click -> onBack(click.player) }
        }
        menu.set(31, MenuItems.closeButton(context)) { click -> click.player.closeInventory() }
        menu.open(player)
    }
}
