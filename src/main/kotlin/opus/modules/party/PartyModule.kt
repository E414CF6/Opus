package opus.modules.party

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.config.YamlConfigFile
import opus.core.database.PartyMemberRecord
import opus.core.database.PartyRecord
import opus.core.gui.MenuItems
import opus.core.gui.MenuWindow
import opus.core.module.OpusModule
import opus.core.player.ChatChannelMode
import opus.core.text.TextFormatter
import opus.modules.combat.PartyRelationHook
import opus.skills.PartyXpShareHook
import opus.skills.SkillType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

enum class PartyFeature(val configKey: String) {
    CHAT("chat"),
    TELEPORT("teleport"),
    ALLIANCE("alliance"),
    ITEM_SHARE("item-share"),
    XP_SHARE("xp-share"),
}

class PartyConfig(context: OpusContext) {
    private val file = YamlConfigFile(context.plugin, "party.yml")

    var enabled: Boolean = true
        private set
    var maxMembers: Int = 16
        private set
    var levelingEnabled: Boolean = true
        private set
    var baseXp: Double = 1000.0
        private set
    var xpPerLevel: Double = 500.0
        private set
    var featureUnlockLevels: Map<PartyFeature, Int> = PartyFeature.entries.associateWith { 0 }
        private set
    var defaultXpShare: String = "EQUAL"
        private set
    var xpShareRadius: Double = 75.0
        private set
    var defaultItemShare: String = "NONE"
        private set
    var itemShareRadius: Double = 75.0
        private set
    var friendlyFire: Boolean = false
        private set
    var allianceFriendlyFire: Boolean = false
        private set
    var teleportEnabled: Boolean = true
        private set
    var teleportRequireAccept: Boolean = true
        private set
    var teleportRequestTimeoutSec: Long = 60L
        private set
    var teleportWarmupSec: Long = 3L
        private set
    var teleportCooldownSec: Long = 120L
        private set
    var teleportCancelOnDamage: Boolean = true
        private set

    init {
        reload()
    }

    fun reload() {
        file.reload()
        val y = file.yaml
        enabled = y.getBoolean("enabled", true)
        maxMembers = y.getInt("max-members", 16).coerceAtLeast(2)
        levelingEnabled = y.getBoolean("leveling.enabled", true)
        baseXp = y.getDouble("leveling.base-xp", 1000.0).coerceAtLeast(1.0)
        xpPerLevel = y.getDouble("leveling.xp-per-level", 500.0).coerceAtLeast(0.0)
        featureUnlockLevels = PartyFeature.entries.associateWith { feature ->
            y.getInt("feature-unlock-levels.${feature.configKey}", 0).coerceAtLeast(0)
        }
        defaultXpShare = (y.getString("xp-share.default-mode", "EQUAL") ?: "EQUAL").uppercase()
        xpShareRadius = y.getDouble("xp-share.radius", 75.0).coerceAtLeast(1.0)
        defaultItemShare = (y.getString("item-share.default-mode", "NONE") ?: "NONE").uppercase()
        itemShareRadius = y.getDouble("item-share.radius", 75.0).coerceAtLeast(1.0)
        friendlyFire = y.getBoolean("friendly-fire", false)
        allianceFriendlyFire = y.getBoolean("alliance-friendly-fire", false)
        teleportEnabled = y.getBoolean("teleport.enabled", true)
        teleportRequireAccept = y.getBoolean("teleport.require-accept", true)
        teleportRequestTimeoutSec = y.getLong("teleport.request-timeout-seconds", 60L).coerceAtLeast(5L)
        teleportWarmupSec = y.getLong("teleport.warmup-seconds", 3L).coerceAtLeast(0L)
        teleportCooldownSec = y.getLong("teleport.cooldown-seconds", 120L).coerceAtLeast(0L)
        teleportCancelOnDamage = y.getBoolean("teleport.cancel-on-damage", true)
    }

    fun unlockLevel(feature: PartyFeature): Int = featureUnlockLevels[feature] ?: 0
}

class Party(
    val id: UUID,
    @Volatile var name: String,
    @Volatile var leaderUuid: UUID,
    @Volatile var password: String? = null,
    @Volatile var locked: Boolean = false,
    @Volatile var expShareMode: String = "EQUAL",
    @Volatile var itemShareMode: String = "NONE",
    @Volatile var allyPartyId: UUID? = null,
    @Volatile var motd: String? = null,
    @Volatile var level: Int = 0,
    @Volatile var xp: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val members = ConcurrentHashMap<UUID, PartyMemberRecord>()

    fun isLeaderOrOfficer(uuid: UUID): Boolean {
        if (leaderUuid == uuid) return true
        return members[uuid]?.role == "OFFICER"
    }

    fun onlinePlayers(): List<Player> = members.keys.mapNotNull { Bukkit.getPlayer(it) }

    fun xpNeeded(baseXp: Double, xpPerLevel: Double): Double =
        (baseXp + level.toDouble() * xpPerLevel).coerceAtLeast(1.0)

    /**
     * Adds party XP and processes any resulting level-ups.
     * Returns true if the party leveled up at least once.
     */
    @Synchronized
    fun addXp(amount: Double, baseXp: Double, xpPerLevel: Double): Boolean {
        if (amount <= 0.0) return false
        xp += amount
        var leveled = false
        while (true) {
            val needed = xpNeeded(baseXp, xpPerLevel)
            if (xp < needed) break
            xp -= needed
            level++
            leveled = true
        }
        return leveled
    }

    fun toRecord(): PartyRecord = PartyRecord(
        id = id,
        name = name,
        leaderUuid = leaderUuid,
        password = password,
        locked = locked,
        expShareMode = expShareMode,
        itemShareMode = itemShareMode,
        allyPartyId = allyPartyId,
        motd = motd,
        level = level,
        xp = xp,
        createdAt = createdAt,
    )
}

/**
 * Manages parties, alliances, party XP/item sharing, party chat, and party teleportation.
 */
class PartyService(
    private val context: OpusContext,
    val partyConfig: PartyConfig,
) : PartyXpShareHook, PartyRelationHook, Listener {

    private val partiesById = ConcurrentHashMap<UUID, Party>()
    private val partyByName = ConcurrentHashMap<String, UUID>()
    private val partyByPlayer = ConcurrentHashMap<UUID, UUID>()
    private val pendingInvites = ConcurrentHashMap<UUID, UUID>()
    private val pendingAllyInvites = ConcurrentHashMap<UUID, UUID>() // targetPartyId -> senderPartyId
    private val ptpCooldowns = ConcurrentHashMap<UUID, Long>()
    private val ptpRequests = ConcurrentHashMap<UUID, Pair<UUID, Long>>() // targetUuid -> (requesterUuid, expiresAt)
    private val ptpDisabled = ConcurrentHashMap.newKeySet<UUID>()
    private val ptpAcceptAny = ConcurrentHashMap.newKeySet<UUID>()
    private val itemPickupGuard = ConcurrentHashMap.newKeySet<UUID>()

    fun loadBlocking() {
        runBlocking {
            val (parties, members) = context.partyRepository.loadAll()
            partiesById.clear()
            partyByName.clear()
            partyByPlayer.clear()
            for (rec in parties) {
                val p = Party(
                    id = rec.id,
                    name = rec.name,
                    leaderUuid = rec.leaderUuid,
                    password = rec.password,
                    locked = rec.locked,
                    expShareMode = rec.expShareMode,
                    itemShareMode = rec.itemShareMode,
                    allyPartyId = rec.allyPartyId,
                    motd = rec.motd,
                    level = rec.level,
                    xp = rec.xp,
                    createdAt = rec.createdAt,
                )
                partiesById[p.id] = p
                partyByName[p.name.lowercase()] = p.id
            }
            for (m in members) {
                val p = partiesById[m.partyId] ?: continue
                p.members[m.playerUuid] = m
                partyByPlayer[m.playerUuid] = p.id
            }
        }
    }

    fun saveAllBlocking() {
        runBlocking {
            for (party in partiesById.values) {
                context.partyRepository.upsertParty(party.toRecord())
            }
        }
    }

    fun getPlayerParty(uuid: UUID): Party? = partyByPlayer[uuid]?.let { partiesById[it] }

    fun findPartyById(id: UUID): Party? = partiesById[id]

    fun findPartyByName(name: String): Party? = partyByName[name.lowercase()]?.let { partiesById[it] }

    fun createParty(leader: Player, name: String, password: String? = null): Boolean {
        if (getPlayerParty(leader.uniqueId) != null) return false
        if (partyByName.containsKey(name.lowercase())) return false

        val id = UUID.randomUUID()
        val party = Party(
            id = id,
            name = name,
            leaderUuid = leader.uniqueId,
            password = password,
            locked = password != null,
            expShareMode = partyConfig.defaultXpShare,
            itemShareMode = partyConfig.defaultItemShare,
        )
        val member = PartyMemberRecord(leader.uniqueId, id, "LEADER")
        party.members[leader.uniqueId] = member
        partiesById[id] = party
        partyByName[name.lowercase()] = id
        partyByPlayer[leader.uniqueId] = id

        context.scheduler.asyncScope.launch {
            context.partyRepository.upsertParty(party.toRecord())
            context.partyRepository.upsertMember(member)
        }
        return true
    }

    fun joinParty(player: Player, party: Party) {
        val member = PartyMemberRecord(player.uniqueId, party.id, "MEMBER")
        party.members[player.uniqueId] = member
        partyByPlayer[player.uniqueId] = party.id
        pendingInvites.remove(player.uniqueId)

        for (online in party.onlinePlayers()) {
            if (online != player) {
                context.locale.send(online, "party.member-joined", "player" to player.name)
            }
        }
        context.scheduler.asyncScope.launch {
            context.partyRepository.upsertMember(member)
        }
    }

    fun leaveParty(player: Player) {
        val party = getPlayerParty(player.uniqueId) ?: return
        party.members.remove(player.uniqueId)
        partyByPlayer.remove(player.uniqueId)

        if (party.members.isEmpty()) {
            disbandParty(party)
            return
        }
        if (party.leaderUuid == player.uniqueId) {
            val nextLeader = party.members.keys.first()
            party.leaderUuid = nextLeader
            party.members[nextLeader]?.let { existing ->
                val promoted = existing.copy(role = "LEADER")
                party.members[nextLeader] = promoted
                context.scheduler.asyncScope.launch { context.partyRepository.upsertMember(promoted) }
            }
            context.scheduler.asyncScope.launch { context.partyRepository.upsertParty(party.toRecord()) }
        }
        for (online in party.onlinePlayers()) {
            context.locale.send(online, "party.member-left", "player" to player.name)
        }
        context.scheduler.asyncScope.launch {
            context.partyRepository.deleteMember(player.uniqueId)
        }
    }

    fun disbandParty(party: Party) {
        party.allyPartyId?.let { allyId ->
            partiesById[allyId]?.let { ally ->
                ally.allyPartyId = null
                context.scheduler.asyncScope.launch { context.partyRepository.upsertParty(ally.toRecord()) }
            }
        }
        pendingAllyInvites.remove(party.id)
        pendingAllyInvites.entries.removeIf { it.value == party.id }
        for (online in party.onlinePlayers()) {
            context.locale.send(online, "party.disbanded", "party" to party.name)
        }
        party.members.keys.forEach { partyByPlayer.remove(it) }
        party.members.clear()
        partiesById.remove(party.id)
        partyByName.remove(party.name.lowercase())
        context.scheduler.asyncScope.launch {
            context.partyRepository.deleteParty(party.id)
        }
    }

    fun renameParty(party: Party, newName: String): Boolean {
        val key = newName.lowercase()
        val existingId = partyByName[key]
        if (existingId != null && existingId != party.id) return false
        partyByName.remove(party.name.lowercase())
        party.name = newName
        partyByName[key] = party.id
        saveParty(party)
        return true
    }

    fun hasUnlocked(party: Party, feature: PartyFeature): Boolean {
        if (!partyConfig.levelingEnabled) return true
        return party.level >= partyConfig.unlockLevel(feature)
    }

    fun requireUnlocked(player: Player, party: Party, feature: PartyFeature): Boolean {
        if (hasUnlocked(party, feature)) return true
        context.locale.send(
            player,
            "party.feature-locked",
            "level" to partyConfig.unlockLevel(feature),
            "feature" to feature.configKey,
        )
        return false
    }

    fun sendAllyInvite(sender: Party, target: Party) {
        pendingAllyInvites[target.id] = sender.id
        val leader = Bukkit.getPlayer(target.leaderUuid)
        if (leader != null) {
            context.locale.send(leader, "party.ally-invite-received", "party" to sender.name)
        } else {
            for (online in target.onlinePlayers()) {
                context.locale.send(online, "party.ally-invite-received", "party" to sender.name)
            }
        }
    }

    fun acceptAllyInvite(target: Party): Party? {
        val senderId = pendingAllyInvites.remove(target.id) ?: return null
        val sender = partiesById[senderId] ?: return null
        if (sender.id == target.id) return null
        // Dissolve any prior alliances cleanly before forming the new one
        if (target.allyPartyId != null) disbandAlliance(target)
        if (sender.allyPartyId != null) disbandAlliance(sender)
        target.allyPartyId = sender.id
        sender.allyPartyId = target.id
        saveParty(target)
        saveParty(sender)
        for (online in target.onlinePlayers()) {
            context.locale.send(online, "party.ally-formed", "party" to sender.name)
        }
        for (online in sender.onlinePlayers()) {
            context.locale.send(online, "party.ally-formed", "party" to target.name)
        }
        return sender
    }

    fun disbandAlliance(party: Party): Boolean {
        val allyId = party.allyPartyId ?: return false
        party.allyPartyId = null
        saveParty(party)
        val ally = partiesById[allyId]
        if (ally != null && ally.allyPartyId == party.id) {
            ally.allyPartyId = null
            saveParty(ally)
            for (online in ally.onlinePlayers()) {
                context.locale.send(online, "party.ally-disbanded")
            }
        }
        for (online in party.onlinePlayers()) {
            context.locale.send(online, "party.ally-disbanded")
        }
        return true
    }

    fun kickMember(party: Party, targetUuid: UUID) {
        party.members.remove(targetUuid)
        partyByPlayer.remove(targetUuid)
        Bukkit.getPlayer(targetUuid)?.let { target ->
            context.locale.send(target, "party.kicked", "party" to party.name)
        }
        context.scheduler.asyncScope.launch {
            context.partyRepository.deleteMember(targetUuid)
        }
    }

    fun invitePlayer(target: Player, party: Party) {
        pendingInvites[target.uniqueId] = party.id
    }

    fun consumeInvite(player: Player): Party? {
        val id = pendingInvites.remove(player.uniqueId) ?: return null
        return partiesById[id]
    }

    fun saveParty(party: Party) {
        context.scheduler.asyncScope.launch {
            context.partyRepository.upsertParty(party.toRecord())
        }
    }

    override fun areAllied(first: UUID, second: UUID): Boolean {
        val p1 = getPlayerParty(first) ?: return false
        val p2 = getPlayerParty(second) ?: return false
        if (p1.id == p2.id) return !partyConfig.friendlyFire
        if (p1.allyPartyId == p2.id && p2.allyPartyId == p1.id) return !partyConfig.allianceFriendlyFire
        return false
    }

    override fun shareXp(
        source: Player,
        skill: SkillType,
        rawXp: Double,
        awardDirect: (Player, SkillType, Double) -> Unit,
    ) {
        if (!partyConfig.enabled) return
        val party = getPlayerParty(source.uniqueId) ?: return

        if (partyConfig.levelingEnabled && rawXp > 0.0) {
            val leveledUp = party.addXp(rawXp, partyConfig.baseXp, partyConfig.xpPerLevel)
            if (leveledUp) {
                saveParty(party)
                for (member in party.onlinePlayers()) {
                    context.locale.send(member, "party.level-up", "party" to party.name, "level" to party.level)
                }
            }
        }

        if (party.expShareMode != "EQUAL") return
        if (!hasUnlocked(party, PartyFeature.XP_SHARE)) return

        val radiusSq = partyConfig.xpShareRadius * partyConfig.xpShareRadius
        val nearby = party.onlinePlayers().filter {
            it != source && it.world == source.world && it.location.distanceSquared(source.location) <= radiusSq
        }
        if (nearby.isEmpty()) return

        val share = rawXp / (nearby.size + 1).toDouble()
        for (member in nearby) {
            awardDirect(member, skill, share)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        if (!partyConfig.enabled) return
        val player = event.entity as? Player ?: return
        if (!itemPickupGuard.add(player.uniqueId)) return
        try {
            val party = getPlayerParty(player.uniqueId) ?: return
            if (party.itemShareMode == "NONE") return
            if (!hasUnlocked(party, PartyFeature.ITEM_SHARE)) return

            val radiusSq = partyConfig.itemShareRadius * partyConfig.itemShareRadius
            val candidates = party.onlinePlayers().filter {
                it.world == player.world && it.location.distanceSquared(player.location) <= radiusSq
            }
            if (candidates.size <= 1) return

            val stack = event.item.itemStack.clone()
            val recipient = when (party.itemShareMode) {
                "RANDOM", "EQUAL" -> candidates[ThreadLocalRandom.current().nextInt(candidates.size)]
                else -> player
            }
            if (recipient != player) {
                event.isCancelled = true
                event.item.remove()
                val leftovers = recipient.inventory.addItem(stack)
                leftovers.values.forEach { recipient.world.dropItemNaturally(recipient.location, it) }
            }
        } finally {
            itemPickupGuard.remove(player.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val state = context.players.getCached(player)
        when (state.chatMode) {
            ChatChannelMode.PARTY -> {
                val party = getPlayerParty(player.uniqueId)
                if (party == null) {
                    state.chatMode = ChatChannelMode.PUBLIC
                    return
                }
                if (!requireUnlocked(player, party, PartyFeature.CHAT)) {
                    state.chatMode = ChatChannelMode.PUBLIC
                    return
                }
                event.isCancelled = true
                val plain = PlainTextComponentSerializer.plainText().serialize(event.message())
                sendPartyChat(player, party, plain)
            }

            ChatChannelMode.ADMIN -> {
                event.isCancelled = true
                val plain = PlainTextComponentSerializer.plainText().serialize(event.message())
                sendAdminChat(player, plain)
            }

            ChatChannelMode.PUBLIC -> Unit
        }
    }

    fun sendPartyChat(sender: Player, party: Party, message: String) {
        val comp = TextFormatter.format(
            "<dark_gray>[<aqua>${party.name}</aqua>]</dark_gray> <white>${sender.name}</white><gray>:</gray> <aqua>${
                TextFormatter.escape(
                    message
                )
            }</aqua>"
        )
        for (member in party.onlinePlayers()) {
            member.sendMessage(comp)
        }
        for (online in Bukkit.getOnlinePlayers()) {
            if (online !in party.onlinePlayers() && context.players.getCached(online).chatSpy) {
                online.sendMessage(TextFormatter.format("<dark_gray>[Spy:${party.name}]</dark_gray> ").append(comp))
            }
        }
    }

    fun sendAdminChat(sender: Player, message: String) {
        val comp = TextFormatter.format(
            "<dark_gray>[<red>Admin</red>]</dark_gray> <white>${sender.name}</white><gray>:</gray> <red>${
                TextFormatter.escape(
                    message
                )
            }</red>"
        )
        for (online in Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("opus.chat.adminchat") || online.hasPermission("opus.admin")) {
                online.sendMessage(comp)
            }
        }
        Bukkit.getConsoleSender().sendMessage(comp)
    }

    fun togglePtp(player: Player): Boolean {
        val enabled = if (ptpDisabled.contains(player.uniqueId)) {
            ptpDisabled.remove(player.uniqueId)
            true
        } else {
            ptpDisabled.add(player.uniqueId)
            false
        }
        return enabled
    }

    fun togglePtpAcceptAny(player: Player): Boolean {
        return if (ptpAcceptAny.contains(player.uniqueId)) {
            ptpAcceptAny.remove(player.uniqueId)
            false
        } else {
            ptpAcceptAny.add(player.uniqueId)
            true
        }
    }

    fun tryPartyTeleport(player: Player, target: Player) {
        if (!partyConfig.teleportEnabled) return
        val party = getPlayerParty(player.uniqueId)
        if (party == null || !party.members.containsKey(target.uniqueId)) {
            context.locale.send(player, "party.ptp-not-same-party", "player" to target.name)
            return
        }
        if (!requireUnlocked(player, party, PartyFeature.TELEPORT)) return
        if (target.uniqueId in ptpDisabled && !player.hasPermission("opus.bypass.partyteleport")) {
            context.locale.send(player, "party.ptp-disabled-target", "player" to target.name)
            return
        }

        val now = System.currentTimeMillis()
        val cdUntil = ptpCooldowns[player.uniqueId] ?: 0L
        if (now < cdUntil && !player.hasPermission("opus.bypass.partyteleport")) {
            context.locale.send(
                player,
                "general.cooldown-active",
                "time" to TextFormatter.formatDuration(cdUntil - now),
            )
            return
        }

        val requiresAccept = partyConfig.teleportRequireAccept &&
                target.uniqueId !in ptpAcceptAny &&
                !player.hasPermission("opus.bypass.partyteleport")

        if (requiresAccept) {
            val expiresAt = now + partyConfig.teleportRequestTimeoutSec * 1000L
            ptpRequests[target.uniqueId] = player.uniqueId to expiresAt
            context.locale.send(player, "party.ptp-request-sent", "player" to target.name)
            context.locale.send(target, "party.ptp-request-received", "player" to player.name)
            return
        }

        executePartyTeleport(player, target)
    }

    fun acceptPtpRequest(target: Player) {
        if (!partyConfig.teleportEnabled) return
        val req = ptpRequests.remove(target.uniqueId)
        val now = System.currentTimeMillis()
        if (req == null || now > req.second) {
            context.locale.send(target, "party.ptp-no-request")
            return
        }
        val requester = Bukkit.getPlayer(req.first)
        if (requester == null || !requester.isOnline) {
            context.locale.send(target, "party.ptp-no-request")
            return
        }
        val party = getPlayerParty(target.uniqueId)
        if (party == null || !party.members.containsKey(requester.uniqueId)) {
            context.locale.send(target, "party.ptp-no-request")
            return
        }
        executePartyTeleport(requester, target)
    }

    private fun executePartyTeleport(player: Player, target: Player) {
        val warmup = if (player.hasPermission("opus.bypass.partyteleport")) 0L else partyConfig.teleportWarmupSec
        val startLoc = player.location.clone()
        val startHp = player.health
        if (warmup > 0L) {
            context.locale.send(player, "party.ptp-starting", "player" to target.name, "seconds" to warmup)
        }
        context.scheduler.runForEntityDelayed(player, (warmup * 20L).coerceAtLeast(1L)) {
            if (!player.isOnline || !target.isOnline) return@runForEntityDelayed
            if (warmup > 0L && (player.location.world != startLoc.world ||
                        player.location.distanceSquared(startLoc) > 1.0 ||
                        (partyConfig.teleportCancelOnDamage && player.health < startHp))
            ) {
                context.locale.send(player, "party.ptp-cancelled")
                return@runForEntityDelayed
            }
            ptpCooldowns[player.uniqueId] = System.currentTimeMillis() + partyConfig.teleportCooldownSec * 1000L
            player.teleportAsync(target.location).thenAccept { ok ->
                if (ok) {
                    context.locale.send(player, "party.ptp-success", "player" to target.name)
                }
            }
        }
    }

    fun openPartyGui(player: Player, party: Party) {
        val title = context.locale.component("gui.party-title", mapOf("party" to party.name))
        val menu = MenuWindow(title, 4)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))

        val leaderName = Bukkit.getOfflinePlayer(party.leaderUuid).name ?: party.leaderUuid.toString().take(8)
        val lore = mutableListOf(
            "<gray>Level:</gray> <yellow>Lv.${party.level}</yellow> <dark_gray>(${party.xp.toInt()}/${
                party.xpNeeded(partyConfig.baseXp, partyConfig.xpPerLevel).toInt()
            })</dark_gray>",
            "<gray>Leader:</gray> <gold>$leaderName</gold>",
            "<gray>XP Share:</gray> <aqua>${party.expShareMode}</aqua>",
            "<gray>Item Share:</gray> <aqua>${party.itemShareMode}</aqua>",
            "<gray>Members:</gray> <white>${party.members.size}/${partyConfig.maxMembers}</white>",
        )
        party.allyPartyId?.let { allyId ->
            partiesById[allyId]?.let { ally ->
                lore += "<gray>Alliance:</gray> <light_purple>${ally.name}</light_purple>"
            }
        }
        menu.set(
            4,
            MenuItems.item(
                Material.BEACON,
                "<aqua><bold>${party.name}</bold></aqua>",
                lore,
            ),
        )

        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        party.members.values.take(slots.size).forEachIndexed { idx, rec ->
            val off = Bukkit.getOfflinePlayer(rec.playerUuid)
            val online = off.isOnline
            val name = off.name ?: rec.playerUuid.toString().take(8)
            menu.set(
                slots[idx],
                MenuItems.item(
                    if (online) Material.PLAYER_HEAD else Material.SKELETON_SKULL,
                    "${if (online) "<green>" else "<gray>"}$name",
                    listOf("<gray>Role:</gray> <gold>${rec.role}</gold>"),
                ),
            )
        }
        menu.set(31, MenuItems.item(Material.BARRIER, context.locale.raw("gui.close"))) { it.player.closeInventory() }
        menu.open(player)
    }
}

class PartyModule(private val context: OpusContext) : OpusModule {
    override val id = "party"
    override val description = "Parties, alliances, XP/item sharing, party chat, and party teleportation"

    val partyConfig = PartyConfig(context)
    val service = PartyService(context, partyConfig)

    override fun onEnable() {
        service.loadBlocking()
        context.registerService(service)
        context.registerService(PartyXpShareHook::class.java, service)
        context.registerService(PartyRelationHook::class.java, service)
    }

    override fun onDisable() {
        service.saveAllBlocking()
    }

    override fun onReload() {
        partyConfig.reload()
    }

    override fun listeners(): List<Listener> = listOf(service)

    override fun registerCommands(registrar: Commands) {
        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.party.user",
                    executor = { ctx, args ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                            ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                        if (!partyConfig.enabled) {
                            context.locale.send(player, "party.disabled")
                            return@simpleArgsCommand Command.SINGLE_SUCCESS
                        }
                        handlePartyCommand(player, args)
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { _, parts ->
                        if (parts.size <= 1) {
                            listOf(
                                "create",
                                "join",
                                "invite",
                                "accept",
                                "kick",
                                "leave",
                                "leader",
                                "rename",
                                "disband",
                                "lock",
                                "unlock",
                                "password",
                                "motd",
                                "xpshare",
                                "itemshare",
                                "ally",
                                "disbandally",
                                "info",
                                "gui"
                            )
                                .filter { it.startsWith(parts.lastOrNull() ?: "", ignoreCase = true) }
                        } else if (parts.size == 2 && parts[0].equals("ally", ignoreCase = true)) {
                            listOf("invite", "accept", "disband")
                                .filter { it.startsWith(parts[1], ignoreCase = true) }
                        } else emptyList()
                    },
                )
            },
            "party",
            "Create and manage RPG parties",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.chat.partychat", executor = { ctx, args ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    val party = service.getPlayerParty(player.uniqueId)
                    if (party == null) {
                        context.locale.send(player, "party.not-in-party")
                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                    }
                    if (!service.requireUnlocked(player, party, PartyFeature.CHAT)) {
                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                    }
                    if (args.isNotEmpty()) {
                        service.sendPartyChat(player, party, args.joinToString(" "))
                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                    }
                    val state = context.players.getCached(player)
                    state.chatMode =
                        if (state.chatMode == ChatChannelMode.PARTY) ChatChannelMode.PUBLIC else ChatChannelMode.PARTY
                    state.markDirty()
                    context.locale.send(
                        player,
                        "party.chat-toggled",
                        "state" to if (state.chatMode == ChatChannelMode.PARTY) "ON" else "OFF"
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "partychat",
            "Toggle or send party chat",
            listOf("pc", "p"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.chat.adminchat", executor = { ctx, args ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    if (args.isNotEmpty()) {
                        service.sendAdminChat(player, args.joinToString(" "))
                        return@simpleArgsCommand Command.SINGLE_SUCCESS
                    }
                    val state = context.players.getCached(player)
                    state.chatMode =
                        if (state.chatMode == ChatChannelMode.ADMIN) ChatChannelMode.PUBLIC else ChatChannelMode.ADMIN
                    state.markDirty()
                    context.locale.send(
                        player,
                        "party.admin-chat-toggled",
                        "state" to if (state.chatMode == ChatChannelMode.ADMIN) "ON" else "OFF"
                    )
                    Command.SINGLE_SUCCESS
                })
            },
            "adminchat",
            "Toggle or send admin chat",
            listOf("ac", "a"),
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(name, "opus.chat.spy", executor = { ctx, _ ->
                    val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                        ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                    val state = context.players.getCached(player)
                    state.chatSpy = !state.chatSpy
                    context.locale.send(player, "party.chat-spy-toggled", "state" to if (state.chatSpy) "ON" else "OFF")
                    Command.SINGLE_SUCCESS
                })
            },
            "mcchatspy",
            "Toggle party chat spy",
        )

        CommandHelper.registerWithOptionalAliases(
            registrar,
            context,
            { name ->
                CommandHelper.simpleArgsCommand(
                    name,
                    "opus.party.user",
                    executor = { ctx, args ->
                        val player = CommandHelper.requirePlayer(context, ctx.source.sender)
                            ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                        val arg = args.firstOrNull() ?: return@simpleArgsCommand Command.SINGLE_SUCCESS
                        when (arg.lowercase()) {
                            "accept" -> service.acceptPtpRequest(player)
                            "toggle" -> {
                                val enabled = service.togglePtp(player)
                                context.locale.send(
                                    player,
                                    "party.ptp-toggle-changed",
                                    "state" to if (enabled) "ON" else "OFF",
                                )
                            }

                            "acceptany" -> {
                                val on = service.togglePtpAcceptAny(player)
                                context.locale.send(
                                    player,
                                    "party.ptp-acceptany-changed",
                                    "state" to if (on) "ON" else "OFF",
                                )
                            }

                            else -> {
                                val target = Bukkit.getPlayerExact(arg)
                                if (target == null) {
                                    context.locale.send(player, "general.player-not-found", "player" to arg)
                                    return@simpleArgsCommand Command.SINGLE_SUCCESS
                                }
                                service.tryPartyTeleport(player, target)
                            }
                        }
                        Command.SINGLE_SUCCESS
                    },
                    tabSuggester = { sender, parts ->
                        if (parts.size <= 1) {
                            val prefix = parts.lastOrNull() ?: ""
                            val specials = listOf("accept", "toggle", "acceptany")
                                .filter { it.startsWith(prefix, ignoreCase = true) }
                            specials + CommandHelper.suggestOnlinePlayers(sender, prefix)
                        } else emptyList()
                    },
                )
            },
            "ptp",
            "Teleport to a party member",
        )
    }

    private fun handlePartyCommand(player: Player, args: Array<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "info"
        val current = service.getPlayerParty(player.uniqueId)

        when (sub) {
            "create" -> {
                if (current != null) {
                    context.locale.send(player, "party.already-in-party", "party" to current.name)
                    return
                }
                val name = args.getOrNull(1) ?: return
                val pw = args.getOrNull(2)
                if (!service.createParty(player, name, pw)) {
                    context.locale.send(player, "party.already-exists", "party" to name)
                    return
                }
                context.locale.send(player, "party.created", "party" to name)
            }

            "join" -> {
                if (current != null) {
                    context.locale.send(player, "party.already-in-party", "party" to current.name)
                    return
                }
                val nameOrPlayer = args.getOrNull(1) ?: return
                val targetParty = service.findPartyByName(nameOrPlayer)
                    ?: Bukkit.getPlayerExact(nameOrPlayer)?.let { service.getPlayerParty(it.uniqueId) }
                if (targetParty == null) {
                    context.locale.send(player, "party.not-found", "party" to nameOrPlayer)
                    return
                }
                if (targetParty.locked && targetParty.password == null) {
                    context.locale.send(player, "party.locked")
                    return
                }
                val pw = args.getOrNull(2)
                if (targetParty.password != null && targetParty.password != pw) {
                    context.locale.send(player, "party.wrong-password")
                    return
                }
                service.joinParty(player, targetParty)
                context.locale.send(player, "party.joined", "party" to targetParty.name)
            }

            "accept" -> {
                if (current != null) {
                    context.locale.send(player, "party.already-in-party", "party" to current.name)
                    return
                }
                val invited = service.consumeInvite(player)
                if (invited == null) {
                    context.locale.send(player, "party.no-invite")
                    return
                }
                service.joinParty(player, invited)
                context.locale.send(player, "party.joined", "party" to invited.name)
            }

            "invite" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                val targetName = args.getOrNull(1) ?: return
                val target = Bukkit.getPlayerExact(targetName)
                if (target == null) {
                    context.locale.send(player, "general.player-not-found", "player" to targetName)
                    return
                }
                service.invitePlayer(target, current)
                context.locale.send(player, "party.invited", "player" to target.name)
                context.locale.send(target, "party.invite-received", "player" to player.name, "party" to current.name)
            }

            "leave", "quit" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                val name = current.name
                service.leaveParty(player)
                context.locale.send(player, "party.left", "party" to name)
            }

            "disband" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (current.leaderUuid != player.uniqueId) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                service.disbandParty(current)
            }

            "kick" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (!current.isLeaderOrOfficer(player.uniqueId)) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                val targetName = args.getOrNull(1) ?: return
                val target = Bukkit.getOfflinePlayer(targetName)
                if (!current.members.containsKey(target.uniqueId) || target.uniqueId == current.leaderUuid) return
                service.kickMember(current, target.uniqueId)
                context.locale.send(player, "party.member-kicked", "player" to targetName)
            }

            "leader" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (current.leaderUuid != player.uniqueId) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                val targetName = args.getOrNull(1) ?: return
                val target = Bukkit.getPlayerExact(targetName) ?: return
                if (!current.members.containsKey(target.uniqueId)) return
                current.leaderUuid = target.uniqueId
                service.saveParty(current)
                context.locale.send(player, "party.leader-transferred", "player" to target.name)
            }

            "lock", "unlock" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (!current.isLeaderOrOfficer(player.uniqueId)) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                current.locked = sub == "lock"
                service.saveParty(current)
                context.locale.send(
                    player,
                    "party.lock-changed",
                    "state" to if (current.locked) "LOCKED" else "UNLOCKED"
                )
            }

            "password" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (!current.isLeaderOrOfficer(player.uniqueId)) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                current.password = args.getOrNull(1)?.takeUnless { it.equals("none", ignoreCase = true) }
                service.saveParty(current)
                context.locale.send(player, "party.password-changed")
            }

            "motd" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (!current.isLeaderOrOfficer(player.uniqueId)) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                val text = args.drop(1).joinToString(" ")
                current.motd = text.ifBlank { null }
                service.saveParty(current)
                context.locale.send(player, "party.motd-changed", "motd" to (current.motd ?: "-"))
            }

            "rename" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (current.leaderUuid != player.uniqueId) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                val newName = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                if (!service.renameParty(current, newName)) {
                    context.locale.send(player, "party.already-exists", "party" to newName)
                    return
                }
                for (member in current.onlinePlayers()) {
                    context.locale.send(member, "party.renamed", "party" to newName)
                }
            }

            "xpshare" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (!current.isLeaderOrOfficer(player.uniqueId)) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                if (!service.requireUnlocked(player, current, PartyFeature.XP_SHARE)) return
                val mode = (args.getOrNull(1) ?: "EQUAL").uppercase()
                if (mode != "NONE" && mode != "EQUAL") return
                current.expShareMode = mode
                service.saveParty(current)
                context.locale.send(player, "party.xpshare-changed", "mode" to mode)
            }

            "itemshare" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (!current.isLeaderOrOfficer(player.uniqueId)) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                if (!service.requireUnlocked(player, current, PartyFeature.ITEM_SHARE)) return
                val mode = (args.getOrNull(1) ?: "NONE").uppercase()
                if (mode !in setOf("NONE", "EQUAL", "RANDOM")) return
                current.itemShareMode = mode
                service.saveParty(current)
                context.locale.send(player, "party.itemshare-changed", "mode" to mode)
            }

            "ally" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (current.leaderUuid != player.uniqueId) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                if (!service.requireUnlocked(player, current, PartyFeature.ALLIANCE)) return
                val action = args.getOrNull(1)?.lowercase() ?: return
                when (action) {
                    "accept" -> {
                        if (service.acceptAllyInvite(current) == null) {
                            context.locale.send(player, "party.ally-no-invite")
                        }
                    }

                    "disband" -> {
                        service.disbandAlliance(current)
                    }

                    "invite" -> {
                        val targetName = args.getOrNull(2) ?: return
                        val other = service.findPartyByName(targetName)
                        if (other == null || other.id == current.id) {
                            context.locale.send(player, "party.not-found", "party" to targetName)
                            return
                        }
                        service.sendAllyInvite(current, other)
                        context.locale.send(player, "party.ally-invited", "party" to other.name)
                    }

                    else -> {
                        // Shorthand `/party ally <partyName>` sends an alliance invite
                        val other = service.findPartyByName(action)
                        if (other == null || other.id == current.id) {
                            context.locale.send(player, "party.not-found", "party" to action)
                            return
                        }
                        service.sendAllyInvite(current, other)
                        context.locale.send(player, "party.ally-invited", "party" to other.name)
                    }
                }
            }

            "disbandally" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                if (current.leaderUuid != player.uniqueId) {
                    context.locale.send(player, "party.no-leader-permission")
                    return
                }
                service.disbandAlliance(current)
            }

            "gui" -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                service.openPartyGui(player, current)
            }

            else -> {
                if (current == null) {
                    context.locale.send(player, "party.not-in-party")
                    return
                }
                val leaderName = Bukkit.getOfflinePlayer(current.leaderUuid).name ?: "?"
                val memberNames = current.members.keys.joinToString(", ") { uuid ->
                    Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)
                }
                context.locale.sendRaw(
                    player,
                    "party.info-header",
                    "party" to current.name,
                    "level" to current.level,
                )
                context.locale.sendRaw(
                    player,
                    "party.info-meta",
                    "leader" to leaderName,
                    "xpshare" to current.expShareMode,
                    "itemshare" to current.itemShareMode,
                    "count" to current.members.size,
                    "max" to partyConfig.maxMembers,
                )
                current.allyPartyId?.let { allyId ->
                    service.findPartyById(allyId)?.let { ally ->
                        context.locale.sendRaw(player, "party.info-ally", "ally" to ally.name)
                    }
                }
                current.motd?.let { context.locale.sendRaw(player, "party.info-motd", "motd" to it) }
                context.locale.sendRaw(player, "party.info-members", "members" to memberNames)
            }
        }
    }
}
