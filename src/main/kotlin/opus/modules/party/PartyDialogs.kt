package opus.modules.party

import opus.core.OpusContext
import opus.core.gui.OpusDialogs
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*

/**
 * Paper 26.3 Dialogs for party creation, joining, invites, settings, alliances, and confirmations.
 */
object PartyDialogs {

    fun openLobbyDialog(context: OpusContext, service: PartyService, player: Player) {
        val locale = context.locale
        val pending = service.peekInvite(player)

        val body = buildList {
            add(OpusDialogs.plainBody(locale.raw("dialog.party-lobby-desc")))
            if (pending != null) {
                add(OpusDialogs.plainBody(locale.raw("dialog.party-pending-invite", "party" to pending.name)))
            }
        }

        val actions = buildList {
            if (pending != null) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.party-accept-invite", "party" to pending.name),
                        width = 160,
                        action = OpusDialogs.customAction(context) { _, p ->
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
                OpusDialogs.actionButton(
                    locale.raw("dialog.party-btn-create"),
                    width = 150,
                    action = OpusDialogs.customAction(context) { _, p ->
                        openCreatePartyDialog(context, service, p)
                    },
                )
            )
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.party-btn-join"),
                    width = 150,
                    action = OpusDialogs.customAction(context) { _, p ->
                        openJoinPartyDialog(context, service, p)
                    },
                )
            )
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.party-lobby-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openCreatePartyDialog(context: OpusContext, service: PartyService, player: Player) {
        val locale = context.locale
        val inputs = listOf(
            OpusDialogs.textInput("name", locale.raw("dialog.party-input-name"), maxLength = 24),
            OpusDialogs.textInput("password", locale.raw("dialog.party-input-password"), maxLength = 32),
        )
        val createBtn = OpusDialogs.actionButton(
            locale.raw("dialog.party-btn-create"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
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
        val backBtn = OpusDialogs.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p -> openLobbyDialog(context, service, p) },
        )
        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.party-create-title"),
            body = listOf(OpusDialogs.plainBody(locale.raw("dialog.party-create-desc"))),
            inputs = inputs,
            actions = listOf(createBtn, backBtn),
            columns = 2,
        )
    }

    fun openJoinPartyDialog(context: OpusContext, service: PartyService, player: Player) {
        val locale = context.locale
        val inputs = listOf(
            OpusDialogs.textInput("target", locale.raw("dialog.party-input-join-target"), maxLength = 32),
            OpusDialogs.textInput("password", locale.raw("dialog.party-input-password"), maxLength = 32),
        )
        val joinBtn = OpusDialogs.actionButton(
            locale.raw("dialog.party-btn-join"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
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
        val backBtn = OpusDialogs.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p -> openLobbyDialog(context, service, p) },
        )
        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.party-join-title"),
            body = listOf(OpusDialogs.plainBody(locale.raw("dialog.party-join-desc"))),
            inputs = inputs,
            actions = listOf(joinBtn, backBtn),
            columns = 2,
        )
    }

    fun openInviteDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        val candidates = Bukkit.getOnlinePlayers()
            .filter { it != player && service.getPlayerParty(it.uniqueId) == null && player.canSee(it) }
            .take(6)

        val inputs = listOf(
            OpusDialogs.textInput("player", locale.raw("dialog.party-input-invite-player"), maxLength = 24),
        )

        val actions = buildList {
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.party-btn-invite"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { response, p ->
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
                    OpusDialogs.actionButton(
                        "<green>+ ${cand.name}</green>",
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            if (cand.isOnline) {
                                service.invitePlayer(cand, party)
                                locale.send(p, "party.invited", "player" to cand.name)
                                locale.sendActionBar(p, "party.invited", "player" to cand.name)
                                locale.send(cand, "party.invite-received", "player" to p.name, "party" to party.name)
                                locale.sendActionBar(
                                    cand,
                                    "party.invite-received",
                                    "player" to p.name,
                                    "party" to party.name
                                )
                            }
                            service.openPartyGui(p, party)
                        },
                    )
                )
            }
            add(
                OpusDialogs.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p -> service.openPartyGui(p, party) },
                )
            )
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.party-invite-title", mapOf("party" to party.name)),
            body = listOf(OpusDialogs.plainBody(locale.raw("dialog.party-invite-desc"))),
            inputs = inputs,
            actions = actions,
            columns = 2,
        )
    }

    fun openSettingsDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        if (!party.isLeaderOrOfficer(player.uniqueId)) {
            locale.send(player, "party.no-leader-permission")
            locale.sendActionBar(player, "party.no-leader-permission")
            return
        }

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
            OpusDialogs.textInput("name", locale.raw("dialog.party-input-name"), initial = party.name, maxLength = 24),
            OpusDialogs.textInput(
                "motd",
                locale.raw("dialog.party-input-motd"),
                initial = party.motd ?: "",
                maxLength = 80
            ),
            OpusDialogs.textInput(
                "password",
                locale.raw("dialog.party-input-password"),
                initial = party.password ?: "",
                maxLength = 32
            ),
            OpusDialogs.boolInput("locked", locale.raw("dialog.party-input-locked"), party.locked),
            OpusDialogs.singleOptionInput("xpshare", locale.raw("dialog.party-input-xpshare"), xpOptions),
            OpusDialogs.singleOptionInput("itemshare", locale.raw("dialog.party-input-itemshare"), itemOptions),
        )

        val saveBtn = OpusDialogs.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
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

                val newMotd = response.getText("motd")?.trim()?.takeIf { it.isNotEmpty() }
                party.motd = newMotd

                val newPw = response.getText("password")?.trim()?.takeIf { it.isNotEmpty() }
                party.password = newPw

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
        val backBtn = OpusDialogs.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p -> service.openPartyGui(p, party) },
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.party-settings-title", mapOf("party" to party.name)),
            body = listOf(OpusDialogs.plainBody(locale.raw("dialog.party-settings-desc"))),
            inputs = inputs,
            actions = listOf(saveBtn, backBtn),
            columns = 2,
        )
    }

    fun openAllianceDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        if (party.leaderUuid != player.uniqueId) {
            locale.send(player, "party.no-leader-permission")
            locale.sendActionBar(player, "party.no-leader-permission")
            return
        }
        if (!service.requireUnlocked(player, party, PartyFeature.ALLIANCE)) return

        val currentAlly = party.allyPartyId?.let { service.findPartyById(it) }
        val pendingAlly = service.peekAllyInvite(party)

        val body = buildList {
            if (currentAlly != null) {
                add(OpusDialogs.plainBody(locale.raw("party.info-ally", "ally" to currentAlly.name)))
            } else {
                add(OpusDialogs.plainBody(locale.raw("dialog.party-ally-none")))
            }
            if (pendingAlly != null) {
                add(OpusDialogs.plainBody(locale.raw("dialog.party-ally-pending", "party" to pendingAlly.name)))
            }
        }

        val inputs = listOf(
            OpusDialogs.textInput("target_party", locale.raw("dialog.party-input-ally"), maxLength = 24),
        )

        val actions = buildList {
            if (pendingAlly != null) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.party-btn-ally-accept", "party" to pendingAlly.name),
                        width = 150,
                        action = OpusDialogs.customAction(context) { _, p ->
                            if (service.acceptAllyInvite(party) == null) {
                                locale.send(p, "party.ally-no-invite")
                            }
                            service.openPartyGui(p, party)
                        },
                    )
                )
            }
            add(
                OpusDialogs.actionButton(
                    locale.raw("dialog.party-btn-ally-invite"),
                    width = 150,
                    action = OpusDialogs.customAction(context) { response, p ->
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
                    OpusDialogs.actionButton(
                        locale.raw("dialog.party-btn-ally-disband"),
                        width = 150,
                        action = OpusDialogs.customAction(context) { _, p ->
                            service.disbandAlliance(party)
                            service.openPartyGui(p, party)
                        },
                    )
                )
            }
            add(
                OpusDialogs.actionButton(
                    locale.raw("gui.back"),
                    width = 150,
                    action = OpusDialogs.customAction(context) { _, p -> service.openPartyGui(p, party) },
                )
            )
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.party-ally-title", mapOf("party" to party.name)),
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
        val targetOnline = Bukkit.getPlayer(targetUuid)
        val actions = buildList {
            if (targetOnline != null && targetOnline != player) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.party-btn-ptp"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            if (targetOnline.isOnline) {
                                service.tryPartyTeleport(p, targetOnline)
                            }
                        },
                    )
                )
            }
            if (party.leaderUuid == player.uniqueId && targetUuid != player.uniqueId && targetOnline != null) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.party-btn-promote"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            OpusDialogs.showConfirm(
                                context = context,
                                player = p,
                                title = locale.component("dialog.party-promote-confirm-title"),
                                body = listOf(
                                    OpusDialogs.plainBody(
                                        locale.raw(
                                            "dialog.party-promote-confirm-body",
                                            "player" to targetName
                                        )
                                    )
                                ),
                                onConfirm = { confirmed ->
                                    if (party.leaderUuid == confirmed.uniqueId && party.members.containsKey(targetUuid)) {
                                        party.leaderUuid = targetUuid
                                        service.saveParty(party)
                                        locale.send(confirmed, "party.leader-transferred", "player" to targetName)
                                        locale.sendActionBar(
                                            confirmed,
                                            "party.leader-transferred",
                                            "player" to targetName
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
                    OpusDialogs.actionButton(
                        locale.raw("dialog.party-btn-kick"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            OpusDialogs.showConfirm(
                                context = context,
                                player = p,
                                title = locale.component("dialog.party-kick-confirm-title"),
                                body = listOf(
                                    OpusDialogs.plainBody(
                                        locale.raw(
                                            "dialog.party-kick-confirm-body",
                                            "player" to targetName
                                        )
                                    )
                                ),
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
                OpusDialogs.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p -> service.openPartyGui(p, party) },
                )
            )
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = player,
            title = locale.component("dialog.party-member-title", mapOf("player" to targetName)),
            body = listOf(OpusDialogs.plainBody(locale.raw("dialog.party-member-desc", "player" to targetName))),
            actions = actions,
            columns = 2,
        )
    }

    fun openLeaveConfirmDialog(context: OpusContext, service: PartyService, player: Player, party: Party) {
        val locale = context.locale
        val isLeader = party.leaderUuid == player.uniqueId
        if (isLeader) {
            OpusDialogs.showConfirm(
                context = context,
                player = player,
                title = locale.component("dialog.party-disband-confirm-title"),
                body = listOf(
                    OpusDialogs.plainBody(
                        locale.raw(
                            "dialog.party-disband-confirm-body",
                            "party" to party.name
                        )
                    )
                ),
                onConfirm = { p ->
                    if (party.leaderUuid == p.uniqueId) {
                        service.disbandParty(party)
                    }
                },
                onCancel = { service.openPartyGui(it, party) },
            )
        } else {
            OpusDialogs.showConfirm(
                context = context,
                player = player,
                title = locale.component("dialog.party-leave-confirm-title"),
                body = listOf(
                    OpusDialogs.plainBody(
                        locale.raw(
                            "dialog.party-leave-confirm-body",
                            "party" to party.name
                        )
                    )
                ),
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
