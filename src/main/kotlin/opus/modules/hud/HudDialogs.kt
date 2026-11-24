package opus.modules.hud

import opus.core.OpusContext
import opus.core.gui.OpusDialogs
import opus.core.player.ScoreboardMode
import opus.core.player.XpHudMode
import org.bukkit.entity.Player

/**
 * Paper 26.3 Dialogs for configuring player HUD, notifications, and audio preferences.
 */
object HudDialogs {

    fun openSettingsDialog(
        context: OpusContext,
        player: Player,
        hudService: HudService? = context.getService(),
        onBack: ((Player) -> Unit)? = null,
    ) {
        val state = context.players.getCached(player)
        val locale = context.locale

        val xpOptions = listOf(
            Triple(XpHudMode.BOSSBAR.name, locale.raw("dialog.hud-xp-bossbar"), state.hudMode == XpHudMode.BOSSBAR),
            Triple(
                XpHudMode.ACTIONBAR.name,
                locale.raw("dialog.hud-xp-actionbar"),
                state.hudMode == XpHudMode.ACTIONBAR
            ),
            Triple(XpHudMode.BOTH.name, locale.raw("dialog.hud-xp-both"), state.hudMode == XpHudMode.BOTH),
            Triple(XpHudMode.OFF.name, locale.raw("dialog.hud-xp-off"), state.hudMode == XpHudMode.OFF),
        )

        val sbOptions = buildList {
            add(
                Triple(
                    ScoreboardMode.DEFAULT.name,
                    locale.raw("dialog.hud-sb-default"),
                    state.scoreboardMode == ScoreboardMode.DEFAULT
                )
            )
            if (context.config.scoreboardAllowKeep) {
                add(
                    Triple(
                        ScoreboardMode.KEEP.name,
                        locale.raw("dialog.hud-sb-keep"),
                        state.scoreboardMode == ScoreboardMode.KEEP
                    )
                )
            }
            add(
                Triple(
                    ScoreboardMode.OFF.name,
                    locale.raw("dialog.hud-sb-off"),
                    state.scoreboardMode == ScoreboardMode.OFF
                )
            )
        }

        val inputs = listOf(
            OpusDialogs.singleOptionInput("hud_mode", locale.raw("dialog.hud-xp-label"), xpOptions),
            OpusDialogs.singleOptionInput("sb_mode", locale.raw("dialog.hud-sb-label"), sbOptions),
            OpusDialogs.boolInput("notify", locale.raw("dialog.hud-notify-label"), state.notifyAbilities),
            OpusDialogs.boolInput("sound", locale.raw("dialog.hud-sound-label"), state.levelUpSound),
            OpusDialogs.boolInput("readying", locale.raw("dialog.hud-readying-label"), state.abilityReadyingEnabled),
        )

        val saveAction = OpusDialogs.customAction(context) { response, p ->
            val targetState = context.players.getCached(p)
            val newHud = response.getText("hud_mode")?.let { raw ->
                runCatching { XpHudMode.valueOf(raw) }.getOrNull()
            } ?: targetState.hudMode
            val newSb = response.getText("sb_mode")?.let { raw ->
                runCatching { ScoreboardMode.valueOf(raw) }.getOrNull()
            } ?: targetState.scoreboardMode

            targetState.hudMode = newHud
            targetState.scoreboardMode = newSb
            response.getBoolean("notify")?.let { targetState.notifyAbilities = it }
            response.getBoolean("sound")?.let { targetState.levelUpSound = it }
            response.getBoolean("readying")?.let { targetState.abilityReadyingEnabled = it }
            targetState.markDirty()

            when (newSb) {
                ScoreboardMode.KEEP -> hudService?.showStatsBoard(p, targetState, persistent = true)
                ScoreboardMode.OFF -> hudService?.clearBoard(p)
                ScoreboardMode.DEFAULT -> hudService?.showStatsBoard(p, targetState, persistent = false)
            }
            if (newHud == XpHudMode.OFF || newHud == XpHudMode.ACTIONBAR) {
                hudService?.hideChannelBossBar(p, HudService.XP_EVENT_CHANNEL)
            }

            locale.sendActionBar(p, "dialog.hud-saved")
            locale.send(p, "dialog.hud-saved")
            onBack?.invoke(p)
        }

        val actions = buildList {
            add(OpusDialogs.actionButton(locale.raw("dialog.save"), width = 140, action = saveAction))
            if (onBack != null) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("gui.back"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p -> onBack(p) },
                    )
                )
            }
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.hud-title"),
            body = listOf(OpusDialogs.plainBody(locale.raw("dialog.hud-desc"))),
            inputs = inputs,
            actions = actions,
            exitButton = OpusDialogs.actionButton(
                locale.raw("dialog.close"),
                width = 140,
                action = OpusDialogs.closeAction(context),
            ),
            columns = 2,
        )
    }
}
