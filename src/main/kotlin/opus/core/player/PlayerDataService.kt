package opus.core.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import opus.core.config.OpusConfig
import opus.core.database.PlayerRecord
import opus.core.database.PlayerSkillRepository
import opus.core.database.SkillProgressRecord
import opus.core.scheduler.OpusScheduler
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

enum class XpHudMode {
    BOSSBAR, ACTIONBAR, BOTH, OFF,
}

enum class ScoreboardMode {
    DEFAULT, KEEP, OFF,
}

enum class ChatChannelMode {
    PUBLIC, PARTY, ADMIN,
}

data class SkillProgress(
    @Volatile var level: Int = 0,
    @Volatile var xp: Double = 0.0,
)

/**
 * Thread-safe in-memory state for a player's RPG progression, active super abilities, and preferences.
 */
class OpusPlayerState(
    val uuid: UUID,
    @Volatile var username: String,
    @Volatile var lastLogin: Long = System.currentTimeMillis(),
    @Volatile var hudMode: XpHudMode = XpHudMode.BOSSBAR,
    @Volatile var scoreboardMode: ScoreboardMode = ScoreboardMode.DEFAULT,
    @Volatile var notifyAbilities: Boolean = true,
    @Volatile var levelUpSound: Boolean = true,
    @Volatile var chatMode: ChatChannelMode = ChatChannelMode.PUBLIC,
) {
    private val skills = ConcurrentHashMap<SkillType, SkillProgress>()
    private val cooldowns = ConcurrentHashMap<SuperAbilityType, Long>()
    private val activeAbilities = ConcurrentHashMap<SuperAbilityType, Long>()
    private val readiedTools = ConcurrentHashMap<SkillType, Long>()
    private val xpWindowStart = ConcurrentHashMap<SkillType, Long>()
    private val xpWindowEarned = ConcurrentHashMap<SkillType, Double>()

    @Volatile
    var abilityReadyingEnabled: Boolean = true

    @Volatile
    var debugMode: Boolean = false

    @Volatile
    var godMode: Boolean = false

    @Volatile
    var chatSpy: Boolean = false

    @Volatile
    var dirty: Boolean = false

    fun markDirty() {
        dirty = true
    }

    fun getRawProgress(skill: SkillType): SkillProgress {
        return skills.computeIfAbsent(skill) { SkillProgress() }
    }

    fun getLevel(skill: SkillType): Int {
        if (skill.isChild) {
            val parents = skill.parentSkills()
            if (parents.isEmpty()) return 0
            return parents.sumOf { getLevel(it) } / parents.size
        }
        return skills[skill]?.level ?: 0
    }

    fun getXp(skill: SkillType): Double {
        if (skill.isChild) return 0.0
        return skills[skill]?.xp ?: 0.0
    }

    fun setSkill(skill: SkillType, level: Int, xp: Double) {
        if (skill.isChild) return
        val prog = getRawProgress(skill)
        prog.level = level.coerceAtLeast(0)
        prog.xp = xp.coerceAtLeast(0.0)
        markDirty()
    }

    fun powerLevel(): Int = SkillType.nonChildSkills.sumOf { getLevel(it) }

    fun getCooldownRemainingMillis(ability: SuperAbilityType, now: Long = System.currentTimeMillis()): Long {
        val expires = cooldowns[ability] ?: return 0L
        return (expires - now).coerceAtLeast(0L)
    }

    fun setCooldown(ability: SuperAbilityType, durationMillis: Long, now: Long = System.currentTimeMillis()) {
        if (durationMillis <= 0L) {
            cooldowns.remove(ability)
        } else {
            cooldowns[ability] = now + durationMillis
        }
        markDirty()
    }

    fun clearAllCooldowns() {
        cooldowns.clear()
        markDirty()
    }

    fun isAbilityActive(ability: SuperAbilityType, now: Long = System.currentTimeMillis()): Boolean {
        val expires = activeAbilities[ability] ?: return false
        if (now >= expires) {
            activeAbilities.remove(ability)
            return false
        }
        return true
    }

    fun activateAbility(ability: SuperAbilityType, durationMillis: Long, now: Long = System.currentTimeMillis()) {
        activeAbilities[ability] = now + durationMillis
        readiedTools.remove(ability.skill)
    }

    fun deactivateAbility(ability: SuperAbilityType) {
        activeAbilities.remove(ability)
    }

    fun activeAbilitiesSnapshot(now: Long = System.currentTimeMillis()): List<SuperAbilityType> =
        activeAbilities.entries.filter { it.value > now }.map { it.key }

    fun isToolReadied(skill: SkillType, now: Long = System.currentTimeMillis()): Boolean {
        val expires = readiedTools[skill] ?: return false
        if (now >= expires) {
            readiedTools.remove(skill)
            return false
        }
        return true
    }

    fun readyTool(skill: SkillType, windowMillis: Long = 4000L, now: Long = System.currentTimeMillis()) {
        readiedTools[skill] = now + windowMillis
    }

    fun unreadyTool(skill: SkillType) {
        readiedTools.remove(skill)
    }

    fun trackDiminishedWindow(
        skill: SkillType, windowMillis: Long, gained: Double, now: Long = System.currentTimeMillis()
    ): Double {
        val start = xpWindowStart[skill] ?: 0L
        if (now - start > windowMillis) {
            xpWindowStart[skill] = now
            xpWindowEarned[skill] = gained
            return gained
        }
        val total = (xpWindowEarned[skill] ?: 0.0) + gained
        xpWindowEarned[skill] = total
        return total
    }

    fun toRecord(): PlayerRecord {
        val skillMap = SkillType.nonChildSkills.associateWith { skill ->
            val p = skills[skill]
            SkillProgressRecord(p?.level ?: 0, p?.xp ?: 0.0)
        }
        val now = System.currentTimeMillis()
        val activeCooldowns = cooldowns.filterValues { it > now }.toMap()
        return PlayerRecord(
            uuid = uuid,
            username = username,
            lastLogin = lastLogin,
            hudMode = hudMode.name,
            scoreboardMode = scoreboardMode.name,
            notifyAbilities = notifyAbilities,
            levelUpSound = levelUpSound,
            chatMode = chatMode.name,
            skills = skillMap,
            cooldowns = activeCooldowns,
        )
    }

    companion object {
        fun fromRecord(record: PlayerRecord, startingLevel: Int = 0): OpusPlayerState {
            val state = OpusPlayerState(
                uuid = record.uuid,
                username = record.username,
                lastLogin = record.lastLogin,
                hudMode = runCatching { XpHudMode.valueOf(record.hudMode) }.getOrDefault(XpHudMode.BOSSBAR),
                scoreboardMode = runCatching { ScoreboardMode.valueOf(record.scoreboardMode) }.getOrDefault(
                    ScoreboardMode.DEFAULT
                ),
                notifyAbilities = record.notifyAbilities,
                levelUpSound = record.levelUpSound,
                chatMode = runCatching { ChatChannelMode.valueOf(record.chatMode) }.getOrDefault(ChatChannelMode.PUBLIC),
            )
            for (skill in SkillType.nonChildSkills) {
                val rec = record.skills[skill]
                state.skills[skill] = SkillProgress(rec?.level ?: startingLevel, rec?.xp ?: 0.0)
            }
            val now = System.currentTimeMillis()
            for ((ability, expires) in record.cooldowns) {
                if (expires > now) {
                    state.cooldowns[ability] = expires
                }
            }
            return state
        }
    }
}

/**
 * Thread-safe player state cache with async database loading, periodic dirty flush, and logout persistence.
 */
class PlayerDataService(
    private val repository: PlayerSkillRepository,
    private val scheduler: OpusScheduler,
    private val config: OpusConfig,
) : Listener {

    private val byUuid = ConcurrentHashMap<UUID, OpusPlayerState>()
    private val uuidByName = ConcurrentHashMap<String, UUID>()

    fun getCached(uuid: UUID): OpusPlayerState? = byUuid[uuid]

    fun getCached(player: Player): OpusPlayerState {
        val existing = byUuid[player.uniqueId]
        if (existing != null) {
            if (existing.username != player.name) {
                uuidByName.remove(existing.username.lowercase())
                existing.username = player.name
                uuidByName[player.name.lowercase()] = player.uniqueId
                existing.markDirty()
            }
            return existing
        }
        val created = createDefaultState(player.uniqueId, player.name).apply { markDirty() }
        byUuid[player.uniqueId] = created
        uuidByName[player.name.lowercase()] = player.uniqueId
        return created
    }

    suspend fun getOrLoad(uuid: UUID, fallbackName: String? = null): OpusPlayerState? {
        byUuid[uuid]?.let { return it }
        val loaded = repository.findByUuid(uuid)
        if (loaded != null) {
            val state = OpusPlayerState.fromRecord(loaded, config.startingLevel)
            byUuid[uuid] = state
            uuidByName[state.username.lowercase()] = uuid
            return state
        }
        if (fallbackName != null) {
            val created = createDefaultState(uuid, fallbackName).apply { markDirty() }
            byUuid[uuid] = created
            uuidByName[fallbackName.lowercase()] = uuid
            return created
        }
        return null
    }

    suspend fun findByName(name: String): OpusPlayerState? {
        uuidByName[name.lowercase()]?.let { uuid ->
            byUuid[uuid]?.let { return it }
        }
        val loaded = repository.findByName(name) ?: return null
        val state = OpusPlayerState.fromRecord(loaded, config.startingLevel)
        byUuid[state.uuid] = state
        uuidByName[state.username.lowercase()] = state.uuid
        return state
    }

    suspend fun saveState(state: OpusPlayerState) {
        repository.saveAll(listOf(state.toRecord()))
        state.dirty = false
    }

    suspend fun removePlayer(uuid: UUID): Boolean {
        byUuid.remove(uuid)?.let { uuidByName.remove(it.username.lowercase()) }
        return repository.deletePlayer(uuid)
    }

    suspend fun flushDirty(): Int {
        val dirtyStates = byUuid.values.filter { it.dirty }
        if (dirtyStates.isEmpty()) return 0
        repository.saveAll(dirtyStates.map { it.toRecord() })
        dirtyStates.forEach { it.dirty = false }
        return dirtyStates.size
    }

    fun flushAllBlocking() {
        val allDirty = byUuid.values.filter { it.dirty }
        if (allDirty.isEmpty()) return
        runBlocking {
            repository.saveAll(allDirty.map { it.toRecord() })
            allDirty.forEach { it.dirty = false }
        }
    }

    fun allOnlineCached(): Collection<OpusPlayerState> = byUuid.values

    private fun createDefaultState(uuid: UUID, username: String): OpusPlayerState {
        val hudMode = runCatching { XpHudMode.valueOf(config.defaultXpDisplay) }.getOrDefault(XpHudMode.BOSSBAR)
        val state = OpusPlayerState(uuid = uuid, username = username, hudMode = hudMode)
        for (skill in SkillType.nonChildSkills) {
            state.setSkill(skill, config.startingLevel, 0.0)
        }
        return state
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val now = System.currentTimeMillis()
        scheduler.asyncScope.launch {
            val loaded = repository.findByUuid(player.uniqueId)
            val state = if (loaded != null) {
                OpusPlayerState.fromRecord(loaded, config.startingLevel)
            } else {
                createDefaultState(player.uniqueId, player.name)
            }
            if (state.username != player.name) {
                uuidByName.remove(state.username.lowercase())
                state.username = player.name
            }
            state.lastLogin = now
            state.markDirty()
            byUuid[player.uniqueId] = state
            uuidByName[player.name.lowercase()] = player.uniqueId
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val state = byUuid[player.uniqueId] ?: return
        state.activeAbilitiesSnapshot().forEach { state.deactivateAbility(it) }
        if (config.forceSaveOnLogout) {
            scheduler.asyncScope.launch {
                runCatching {
                    repository.saveAll(listOf(state.toRecord()))
                    state.dirty = false
                }
            }
        }
    }
}
