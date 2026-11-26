package opus.modules.party

import opus.core.OpusContext
import opus.core.compat.PlatformCompat
import opus.core.ui.MenuItems
import opus.core.ui.MenuWindow
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Party creation, invites, settings, alliances, and confirmations:
 * uses Paper Dialog (`1.21.6+`) or [MenuWindow] Chest GUI fallbacks (`1.20.4–1.21.5`).
 */
object PartyDialogs {

    fun openLobbyDialog(context: OpusContext, service: PartyService, player: Player) {
        if (PlatformCompat.supportsDialogs) {
            PaperPartyDialogs.openLobbyDialog(context, service, player)
            return
        }
        val locale = context.locale
        val pending = service.peekInvite(player)
        val menu = MenuWindow(locale.component("dialog.party-lobby-title"), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        if (pending != null) {
            menu.set(
                11,
                MenuItems.item(
                    Material.LIME_WOOL,
                    locale.raw("dialog.party-accept-invite", "party" to pending.name),
                ),
            ) { click ->
                val p = click.player
                if (service.getPlayerParty(p.uniqueId) != null) return@set
                val invited = service.consumeInvite(p) ?: run {
                    locale.send(p, "party.no-invite")
                    return@set
                }
                service.joinParty(p, invited)
                locale.send(p, "party.joined", "party" to invited.name)
                service.openPartyGui(p, invited)
            }
        }
        menu.set(
            13,
            MenuItems.item(
                Material.BEACON,
                locale.raw("dialog.party-btn-create"),
                listOf("<gray>/party create <name> [password]</gray>"),
            ),
        ) { click ->
            click.player.closeInventory()
            locale.sendDirectRaw(click.player, "<yellow>/party create <name> [password]</yellow>")
        }
        menu.set(
            15,
            MenuItems.item(
                Material.COMPASS,
                locale.raw("dialog.party-btn-join"),
                listOf("<gray>/party join <party|player> [password]</gray>"),
            ),
        ) { click ->
            click.player.closeInventory()
            locale.sendDirectRaw(click.player, "<yellow>/party join <party|player> [password]</yellow>")
        }
        menu.set(22, MenuItems.closeButton(context)) { it.player.closeInventory() }
        menu.open(player)
    }

    fun openCreatePartyDialog(context: OpusContext, service: PartyService, player: Player) {
        if (PlatformCompat.supportsDialogs) {
            PaperPartyDialogs.openCreatePartyDialog(context, service, player)
        } else {
            context.locale.sendDirectRaw(player, "<yellow>/party create <name> [password]</yellow>")
        }
    }

    fun openJoinPartyDialog(context: OpusContext, service: PartyService, player: Player) {
        if (PlatformCompat.supportsDialogs) {
            PaperPartyDialogs.openJoinPartyDialog(context, service, player)
        } else {
            context.locale.sendDirectRaw(player, "<yellow>/party join <party|player> [password]</yellow>")
        }
    }

    fun openInviteDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        if (PlatformCompat.supportsDialogs) {
            PaperPartyDialogs.openInviteDialog(context, service, player, party)
            return
        }
        val locale = context.locale
        val menu = MenuWindow(locale.component("dialog.party-invite-title", mapOf("party" to party.name)), 4)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val candidates = Bukkit.getOnlinePlayers()
            .filter { it != player && service.getPlayerParty(it.uniqueId) == null && player.canSee(it) }
            .take(14)
        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        candidates.forEachIndexed { idx, cand ->
            menu.set(
                slots[idx],
                MenuItems.playerHead(
                    cand.uniqueId,
                    cand.name,
                    "<green>+ ${cand.name}</green>",
                    listOf(locale.raw("dialog.party-btn-invite")),
                ),
            ) { click ->
                val p = click.player
                if (cand.isOnline) {
                    service.invitePlayer(cand, party)
                    locale.send(p, "party.invited", "player" to cand.name)
                    locale.send(cand, "party.invite-received", "player" to p.name, "party" to party.name)
                }
                service.openPartyGui(p, party)
            }
        }
        menu.set(31, MenuItems.backButton(context)) { service.openPartyGui(it.player, party) }
        menu.open(player)
    }

    fun openSettingsDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        if (!party.isLeaderOrOfficer(player.uniqueId)) {
            locale.send(player, "party.no-leader-permission")
            locale.sendActionBar(player, "party.no-leader-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperPartyDialogs.openSettingsDialog(context, service, player, party)
            return
        }
        val menu = MenuWindow(locale.component("dialog.party-settings-title", mapOf("party" to party.name)), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))
        menu.set(
            11,
            MenuItems.toggle(Material.IRON_DOOR, locale.raw("dialog.party-input-locked"), party.locked, context),
        ) { click ->
            party.locked = !party.locked
            service.saveParty(party)
            openSettingsDialog(context, service, click.player, party)
        }
        menu.set(
            13,
            MenuItems.item(
                Material.EXPERIENCE_BOTTLE,
                locale.raw("dialog.party-input-xpshare"),
                listOf("<aqua>${party.expShareMode}</aqua>", locale.raw("gui.click-toggle")),
            ),
        ) { click ->
            if (service.requireUnlocked(click.player, party, PartyFeature.XP_SHARE)) {
                party.expShareMode = if (party.expShareMode == "EQUAL") "NONE" else "EQUAL"
                service.saveParty(party)
                openSettingsDialog(context, service, click.player, party)
            }
        }
        menu.set(
            15,
            MenuItems.item(
                Material.CHEST,
                locale.raw("dialog.party-input-itemshare"),
                listOf("<aqua>${party.itemShareMode}</aqua>", locale.raw("gui.click-toggle")),
            ),
        ) { click ->
            if (service.requireUnlocked(click.player, party, PartyFeature.ITEM_SHARE)) {
                party.itemShareMode = when (party.itemShareMode) {
                    "NONE" -> "EQUAL"
                    "EQUAL" -> "RANDOM"
                    else -> "NONE"
                }
                service.saveParty(party)
                openSettingsDialog(context, service, click.player, party)
            }
        }
        menu.set(22, MenuItems.backButton(context)) { service.openPartyGui(it.player, party) }
        menu.open(player)
    }

    fun openAllianceDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        if (party.leaderUuid != player.uniqueId) {
            locale.send(player, "party.no-leader-permission")
            locale.sendActionBar(player, "party.no-leader-permission")
            return
        }
        if (!service.requireUnlocked(player, party, PartyFeature.ALLIANCE)) return
        if (PlatformCompat.supportsDialogs) {
            PaperPartyDialogs.openAllianceDialog(context, service, player, party)
        } else {
            locale.sendDirectRaw(player, "<yellow>/party ally <invite <party>|accept|disband></yellow>")
        }
    }

    fun openMemberActionsDialog(
        context: OpusContext,
        service: PartyService,
        player: Player,
        party: Party,
        targetUuid: UUID,
        targetName: String,
    ) {
        if (PlatformCompat.supportsDialogs) {
            PaperPartyDialogs.openMemberActionsDialog(context, service, player, party, targetUuid, targetName)
            return
        }
        val locale = context.locale
        val targetOnline = Bukkit.getPlayer(targetUuid)
        val menu = MenuWindow(locale.component("dialog.party-member-title", mapOf("player" to targetName)), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        if (targetOnline != null && targetOnline != player) {
            menu.set(
                11,
                MenuItems.item(Material.ENDER_PEARL, locale.raw("dialog.party-btn-ptp")),
            ) { click ->
                click.player.closeInventory()
                if (targetOnline.isOnline) {
                    service.tryPartyTeleport(click.player, targetOnline)
                }
            }
        }
        if (party.leaderUuid == player.uniqueId && targetUuid != player.uniqueId && targetOnline != null) {
            menu.set(
                13,
                MenuItems.item(Material.GOLDEN_HELMET, locale.raw("dialog.party-btn-promote")),
            ) { click ->
                context.dialogs.openConfirmation(
                    player = click.player,
                    titleRaw = locale.raw("dialog.party-promote-confirm-title"),
                    bodyRaw = locale.raw("dialog.party-promote-confirm-body", "player" to targetName),
                    onConfirm = { confirmed ->
                        if (party.leaderUuid == confirmed.uniqueId && party.members.containsKey(targetUuid)) {
                            party.leaderUuid = targetUuid
                            service.saveParty(party)
                            locale.send(confirmed, "party.leader-transferred", "player" to targetName)
                        }
                        service.openPartyGui(confirmed, party)
                    },
                    onCancel = { service.openPartyGui(it, party) },
                )
            }
        }
        if (party.isLeaderOrOfficer(player.uniqueId) && targetUuid != party.leaderUuid && targetUuid != player.uniqueId) {
            menu.set(
                15,
                MenuItems.item(Material.REDSTONE, locale.raw("dialog.party-btn-kick")),
            ) { click ->
                context.dialogs.openConfirmation(
                    player = click.player,
                    titleRaw = locale.raw("dialog.party-kick-confirm-title"),
                    bodyRaw = locale.raw("dialog.party-kick-confirm-body", "player" to targetName),
                    onConfirm = { confirmed ->
                        if (party.isLeaderOrOfficer(confirmed.uniqueId) && party.members.containsKey(targetUuid)) {
                            service.kickMember(party, targetUuid)
                            locale.send(confirmed, "party.member-kicked", "player" to targetName)
                        }
                        service.openPartyGui(confirmed, party)
                    },
                    onCancel = { service.openPartyGui(it, party) },
                )
            }
        }
        menu.set(22, MenuItems.backButton(context)) { service.openPartyGui(it.player, party) }
        menu.open(player)
    }

    fun openLeaveConfirmDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        val isLeader = party.leaderUuid == player.uniqueId
        if (isLeader) {
            context.dialogs.openConfirmation(
                player = player,
                titleRaw = locale.raw("dialog.party-disband-confirm-title"),
                bodyRaw = locale.raw("dialog.party-disband-confirm-body", "party" to party.name),
                onConfirm = { p ->
                    if (party.leaderUuid == p.uniqueId) {
                        service.disbandParty(party)
                    }
                },
                onCancel = { service.openPartyGui(it, party) },
            )
        } else {
            context.dialogs.openConfirmation(
                player = player,
                titleRaw = locale.raw("dialog.party-leave-confirm-title"),
                bodyRaw = locale.raw("dialog.party-leave-confirm-body", "party" to party.name),
                onConfirm = { p ->
                    val name = party.name
                    service.leaveParty(p)
                    locale.send(p, "party.left", "party" to name)
                    locale.sendActionBar(p, "party.left", "party" to name)
                },
                onCancel = { service.openPartyGui(it, party) },
            )
        }
    }
}
