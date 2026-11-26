package opus.modules.party

import opus.core.OpusContext
import opus.core.ui.PaperDialogKit
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Native Paper Dialog (`1.21.6+`) forms for party management.
 */
internal object PaperPartyDialogs {

    fun openLobbyDialog(context: OpusContext, service: PartyService, player: Player) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val pending = service.peekInvite(player)

        val body = buildList {
            add(k.plainBody(locale.raw("dialog.party-lobby-desc")))
            if (pending != null) {
                add(k.plainBody(locale.raw("dialog.party-pending-invite", "party" to pending.name)))
            }
        }

        val actions = buildList {
            if (pending != null) {
                add(
                    k.actionButton(
                        locale.raw("dialog.party-accept-invite", "party" to pending.name),
                        width = 160,
                        action = k.customAction { _, p ->
                            if (service.getPlayerParty(p.uniqueId) != null) return@customAction
                            val invited = service.consumeInvite(p)
                            if (invited == null) {
                                locale.send(p, "party.no-invite")
                                locale.sendActionBar(p, "party.no-invite")
                                return@customAction
                            }
                            service.joinParty(p, invited)
                            locale.send(p, "party.joined", "party" to invited.name)
                            locale.sendActionBar(p, "party.joined", "party" to invited.name)
                            service.openPartyGui(p, invited)
                        },
                    )
                )
            }
            add(
                k.actionButton(
                    locale.raw("dialog.party-btn-create"),
                    width = 150,
                    action = k.customAction { _, p -> openCreatePartyDialog(context, service, p) },
                )
            )
            add(
                k.actionButton(
                    locale.raw("dialog.party-btn-join"),
                    width = 150,
                    action = k.customAction { _, p -> openJoinPartyDialog(context, service, p) },
                )
            )
        }

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.party-lobby-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openCreatePartyDialog(context: OpusContext, service: PartyService, player: Player) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val inputs = listOf(
            k.textInput("name", locale.raw("dialog.party-input-name"), maxLength = 24),
            k.textInput("password", locale.raw("dialog.party-input-password"), maxLength = 32),
        )
        val createBtn = k.actionButton(
            locale.raw("dialog.party-btn-create"),
            width = 140,
            action = k.customAction { response, p ->
                val existing = service.getPlayerParty(p.uniqueId)
                if (existing != null) {
                    locale.send(p, "party.already-in-party", "party" to existing.name)
                    return@customAction
                }
                val name = response.getText("name")?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                    locale.sendActionBar(p, "dialog.party-name-required")
                    return@customAction
                }
                val pw = response.getText("password")?.trim()?.takeIf { it.isNotEmpty() }
                if (!service.createParty(p, name, pw)) {
                    locale.send(p, "party.already-exists", "party" to name)
                    locale.sendActionBar(p, "party.already-exists", "party" to name)
                    return@customAction
                }
                locale.send(p, "party.created", "party" to name)
                locale.sendActionBar(p, "party.created", "party" to name)
                service.getPlayerParty(p.uniqueId)?.let { service.openPartyGui(p, it) }
            },
        )
        val backBtn = k.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = k.customAction { _, p -> openLobbyDialog(context, service, p) },
        )
        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.party-create-title"),
            body = listOf(k.plainBody(locale.raw("dialog.party-create-desc"))),
            inputs = inputs,
            actions = listOf(createBtn, backBtn),
            columns = 2,
        )
    }

    fun openJoinPartyDialog(context: OpusContext, service: PartyService, player: Player) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val inputs = listOf(
            k.textInput("target", locale.raw("dialog.party-input-join-target"), maxLength = 32),
            k.textInput("password", locale.raw("dialog.party-input-password"), maxLength = 32),
        )
        val joinBtn = k.actionButton(
            locale.raw("dialog.party-btn-join"),
            width = 140,
            action = k.customAction { response, p ->
                val existing = service.getPlayerParty(p.uniqueId)
                if (existing != null) {
                    locale.send(p, "party.already-in-party", "party" to existing.name)
                    return@customAction
                }
                val query = response.getText("target")?.trim()?.takeIf { it.isNotEmpty() } ?: return@customAction
                val targetParty = service.findPartyByName(query)
                    ?: Bukkit.getPlayerExact(query)?.let { service.getPlayerParty(it.uniqueId) }
                if (targetParty == null) {
                    locale.send(p, "party.not-found", "party" to query)
                    locale.sendActionBar(p, "party.not-found", "party" to query)
                    return@customAction
                }
                if (targetParty.locked && targetParty.password == null) {
                    locale.send(p, "party.locked")
                    locale.sendActionBar(p, "party.locked")
                    return@customAction
                }
                val pw = response.getText("password")?.trim()?.takeIf { it.isNotEmpty() }
                if (targetParty.password != null && targetParty.password != pw) {
                    locale.send(p, "party.wrong-password")
                    locale.sendActionBar(p, "party.wrong-password")
                    return@customAction
                }
                service.joinParty(p, targetParty)
                locale.send(p, "party.joined", "party" to targetParty.name)
                locale.sendActionBar(p, "party.joined", "party" to targetParty.name)
                service.openPartyGui(p, targetParty)
            },
        )
        val backBtn = k.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = k.customAction { _, p -> openLobbyDialog(context, service, p) },
        )
        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.party-join-title"),
            body = listOf(k.plainBody(locale.raw("dialog.party-join-desc"))),
            inputs = inputs,
            actions = listOf(joinBtn, backBtn),
            columns = 2,
        )
    }

    fun openInviteDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val candidates = Bukkit.getOnlinePlayers()
            .filter { it != player && service.getPlayerParty(it.uniqueId) == null && player.canSee(it) }
            .take(6)

        val inputs = listOf(
            k.textInput("player", locale.raw("dialog.party-input-invite-player"), maxLength = 24),
        )

        val actions = buildList {
            add(
                k.actionButton(
                    locale.raw("dialog.party-btn-invite"),
                    width = 140,
                    action = k.customAction { response, p ->
                        val targetName =
                            response.getText("player")?.trim()?.takeIf { it.isNotEmpty() } ?: return@customAction
                        val target = Bukkit.getPlayerExact(targetName)
                        if (target == null) {
                            locale.send(p, "general.player-not-found", "player" to targetName)
                            locale.sendActionBar(p, "general.player-not-found", "player" to targetName)
                            return@customAction
                        }
                        service.invitePlayer(target, party)
                        locale.send(p, "party.invited", "player" to target.name)
                        locale.sendActionBar(p, "party.invited", "player" to target.name)
                        locale.send(target, "party.invite-received", "player" to p.name, "party" to party.name)
                        locale.sendActionBar(target, "party.invite-received", "player" to p.name, "party" to party.name)
                    },
                )
            )
            for (cand in candidates) {
                add(
                    k.actionButton(
                        "<green>+ ${cand.name}</green>",
                        width = 140,
                        action = k.customAction { _, p ->
                            if (cand.isOnline) {
                                service.invitePlayer(cand, party)
                                locale.send(p, "party.invited", "player" to cand.name)
                                locale.sendActionBar(p, "party.invited", "player" to cand.name)
                                locale.send(cand, "party.invite-received", "player" to p.name, "party" to party.name)
                                locale.sendActionBar(
                                    cand,
                                    "party.invite-received",
                                    "player" to p.name,
                                    "party" to party.name,
                                )
                            }
                            service.openPartyGui(p, party)
                        },
                    )
                )
            }
            add(
                k.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = k.customAction { _, p -> service.openPartyGui(p, party) },
                )
            )
        }

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.party-invite-title", "party" to party.name),
            body = listOf(k.plainBody(locale.raw("dialog.party-invite-desc"))),
            inputs = inputs,
            actions = actions,
            columns = 2,
        )
    }

    fun openSettingsDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val xpOptions = listOf(
            Triple("EQUAL", "EQUAL", party.expShareMode == "EQUAL"),
            Triple("NONE", "NONE", party.expShareMode != "EQUAL"),
        )
        val itemOptions = listOf(
            Triple("NONE", "NONE", party.itemShareMode == "NONE"),
            Triple("EQUAL", "EQUAL", party.itemShareMode == "EQUAL"),
            Triple("RANDOM", "RANDOM", party.itemShareMode == "RANDOM"),
        )

        val inputs = listOf(
            k.textInput("name", locale.raw("dialog.party-input-name"), initial = party.name, maxLength = 24),
            k.textInput("motd", locale.raw("dialog.party-input-motd"), initial = party.motd ?: "", maxLength = 80),
            k.textInput(
                "password",
                locale.raw("dialog.party-input-password"),
                initial = party.password ?: "",
                maxLength = 32
            ),
            k.boolInput("locked", locale.raw("dialog.party-input-locked"), party.locked),
            k.singleOptionInput("xpshare", locale.raw("dialog.party-input-xpshare"), xpOptions),
            k.singleOptionInput("itemshare", locale.raw("dialog.party-input-itemshare"), itemOptions),
        )

        val saveBtn = k.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = k.customAction { response, p ->
                if (!party.isLeaderOrOfficer(p.uniqueId)) return@customAction

                val newName = response.getText("name")?.trim()
                if (!newName.isNullOrBlank() && newName != party.name && party.leaderUuid == p.uniqueId) {
                    if (!service.renameParty(party, newName)) {
                        locale.send(p, "party.already-exists", "party" to newName)
                    } else {
                        for (member in party.onlinePlayers()) {
                            locale.send(member, "party.renamed", "party" to newName)
                        }
                    }
                }

                party.motd = response.getText("motd")?.trim()?.takeIf { it.isNotEmpty() }
                party.password = response.getText("password")?.trim()?.takeIf { it.isNotEmpty() }
                response.getBoolean("locked")?.let { party.locked = it }

                response.getText("xpshare")?.uppercase()?.let { mode ->
                    if (mode in setOf("EQUAL", "NONE") && mode != party.expShareMode) {
                        if (service.requireUnlocked(p, party, PartyFeature.XP_SHARE)) {
                            party.expShareMode = mode
                        }
                    }
                }

                response.getText("itemshare")?.uppercase()?.let { mode ->
                    if (mode in setOf("NONE", "EQUAL", "RANDOM") && mode != party.itemShareMode) {
                        if (service.requireUnlocked(p, party, PartyFeature.ITEM_SHARE)) {
                            party.itemShareMode = mode
                        }
                    }
                }

                service.saveParty(party)
                locale.sendActionBar(p, "dialog.party-settings-saved")
                service.openPartyGui(p, party)
            },
        )
        val backBtn = k.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = k.customAction { _, p -> service.openPartyGui(p, party) },
        )

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.party-settings-title", "party" to party.name),
            body = listOf(k.plainBody(locale.raw("dialog.party-settings-desc"))),
            inputs = inputs,
            actions = listOf(saveBtn, backBtn),
            columns = 2,
        )
    }

    fun openAllianceDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val currentAlly = party.allyPartyId?.let { service.findPartyById(it) }
        val pendingAlly = service.peekAllyInvite(party)

        val body = buildList {
            if (currentAlly != null) {
                add(k.plainBody(locale.raw("party.info-ally", "ally" to currentAlly.name)))
            } else {
                add(k.plainBody(locale.raw("dialog.party-ally-none")))
            }
            if (pendingAlly != null) {
                add(k.plainBody(locale.raw("dialog.party-ally-pending", "party" to pendingAlly.name)))
            }
        }

        val inputs = listOf(
            k.textInput("target_party", locale.raw("dialog.party-input-ally"), maxLength = 24),
        )

        val actions = buildList {
            if (pendingAlly != null) {
                add(
                    k.actionButton(
                        locale.raw("dialog.party-btn-ally-accept", "party" to pendingAlly.name),
                        width = 150,
                        action = k.customAction { _, p ->
                            if (service.acceptAllyInvite(party) == null) {
                                locale.send(p, "party.ally-no-invite")
                            }
                            service.openPartyGui(p, party)
                        },
                    )
                )
            }
            add(
                k.actionButton(
                    locale.raw("dialog.party-btn-ally-invite"),
                    width = 150,
                    action = k.customAction { response, p ->
                        val targetName =
                            response.getText("target_party")?.trim()?.takeIf { it.isNotEmpty() } ?: return@customAction
                        val other = service.findPartyByName(targetName)
                        if (other == null || other.id == party.id) {
                            locale.send(p, "party.not-found", "party" to targetName)
                            locale.sendActionBar(p, "party.not-found", "party" to targetName)
                            return@customAction
                        }
                        service.sendAllyInvite(party, other)
                        locale.send(p, "party.ally-invited", "party" to other.name)
                        locale.sendActionBar(p, "party.ally-invited", "party" to other.name)
                    },
                )
            )
            if (currentAlly != null) {
                add(
                    k.actionButton(
                        locale.raw("dialog.party-btn-ally-disband"),
                        width = 150,
                        action = k.customAction { _, p ->
                            service.disbandAlliance(party)
                            service.openPartyGui(p, party)
                        },
                    )
                )
            }
            add(
                k.actionButton(
                    locale.raw("gui.back"),
                    width = 150,
                    action = k.customAction { _, p -> service.openPartyGui(p, party) },
                )
            )
        }

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.party-ally-title", "party" to party.name),
            body = body,
            inputs = inputs,
            actions = actions,
            columns = 2,
        )
    }

    fun openMemberActionsDialog(
        context: OpusContext,
        service: PartyService,
        player: Player,
        party: Party,
        targetUuid: UUID,
        targetName: String,
    ) {
        val locale = context.locale
        val k = PaperDialogKit(context)
        val targetOnline = Bukkit.getPlayer(targetUuid)
        val actions = buildList {
            if (targetOnline != null && targetOnline != player) {
                add(
                    k.actionButton(
                        locale.raw("dialog.party-btn-ptp"),
                        width = 140,
                        action = k.customAction { _, p ->
                            if (targetOnline.isOnline) {
                                service.tryPartyTeleport(p, targetOnline)
                            }
                        },
                    )
                )
            }
            if (party.leaderUuid == player.uniqueId && targetUuid != player.uniqueId && targetOnline != null) {
                add(
                    k.actionButton(
                        locale.raw("dialog.party-btn-promote"),
                        width = 140,
                        action = k.customAction { _, p ->
                            context.dialogs.openConfirmation(
                                player = p,
                                titleRaw = locale.raw("dialog.party-promote-confirm-title"),
                                bodyRaw = locale.raw("dialog.party-promote-confirm-body", "player" to targetName),
                                onConfirm = { confirmed ->
                                    if (party.leaderUuid == confirmed.uniqueId && party.members.containsKey(targetUuid)) {
                                        party.leaderUuid = targetUuid
                                        service.saveParty(party)
                                        locale.send(confirmed, "party.leader-transferred", "player" to targetName)
                                        locale.sendActionBar(
                                            confirmed,
                                            "party.leader-transferred",
                                            "player" to targetName,
                                        )
                                    }
                                    service.openPartyGui(confirmed, party)
                                },
                                onCancel = { service.openPartyGui(it, party) },
                            )
                        },
                    )
                )
            }
            if (party.isLeaderOrOfficer(player.uniqueId) && targetUuid != party.leaderUuid && targetUuid != player.uniqueId) {
                add(
                    k.actionButton(
                        locale.raw("dialog.party-btn-kick"),
                        width = 140,
                        action = k.customAction { _, p ->
                            context.dialogs.openConfirmation(
                                player = p,
                                titleRaw = locale.raw("dialog.party-kick-confirm-title"),
                                bodyRaw = locale.raw("dialog.party-kick-confirm-body", "player" to targetName),
                                onConfirm = { confirmed ->
                                    if (party.isLeaderOrOfficer(confirmed.uniqueId) && party.members.containsKey(
                                            targetUuid
                                        )
                                    ) {
                                        service.kickMember(party, targetUuid)
                                        locale.send(confirmed, "party.member-kicked", "player" to targetName)
                                        locale.sendActionBar(confirmed, "party.member-kicked", "player" to targetName)
                                    }
                                    service.openPartyGui(confirmed, party)
                                },
                                onCancel = { service.openPartyGui(it, party) },
                            )
                        },
                    )
                )
            }
            add(
                k.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = k.customAction { _, p -> service.openPartyGui(p, party) },
                )
            )
        }

        k.showMultiAction(
            player = player,
            titleRaw = locale.raw("dialog.party-member-title", "player" to targetName),
            body = listOf(k.plainBody(locale.raw("dialog.party-member-desc", "player" to targetName))),
            actions = actions,
            columns = 2,
        )
    }
}
