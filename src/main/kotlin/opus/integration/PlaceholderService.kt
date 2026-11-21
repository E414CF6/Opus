package opus.integration

import kotlinx.coroutines.launch
import opus.core.OpusContext
import opus.core.database.LeaderboardEntry
import opus.modules.party.PartyService
import opus.skills.SkillType
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Opus placeholders (`%opus_*%`) with background-refreshed leaderboard caches.
 */
class PlaceholderService(private val context: OpusContext) {

    private val topCache = ConcurrentHashMap<String, List<LeaderboardEntry>>()

    fun isPapiEnabled(): Boolean = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")

    fun startCacheRefreshTask() {
        val interval = context.config.leaderboardCacheRefreshSeconds
        context.scheduler.runAsyncTimer(2L, interval) {
            context.scheduler.asyncScope.launch {
                val maxRank = context.config.papiMaxTrackedRank
                runCatching {
                    topCache["POWER"] = context.playerRepository.readTopSkill(null, maxRank)
                    for (skill in SkillType.nonChildSkills) {
                        topCache[skill.name] = context.playerRepository.readTopSkill(skill, maxRank)
                    }
                }
            }
        }
    }

    fun resolveKey(player: OfflinePlayer?, params: String): String? {
        val key = params.lowercase()
        val state = player?.uniqueId?.let { context.players.getCached(it) }
        val partyService = context.getService<PartyService>()

        if (key == "power_level") {
            return (state?.powerLevel() ?: 0).toString()
        }
        if (key == "party_name") {
            return player?.uniqueId?.let { partyService?.getPlayerParty(it)?.name } ?: ""
        }
        if (key == "party_members") {
            return player?.uniqueId?.let { partyService?.getPlayerParty(it)?.members?.size?.toString() } ?: "0"
        }

        if (key.startsWith("level_")) {
            val skill = SkillType.match(key.removePrefix("level_")) ?: return null
            return (state?.getLevel(skill) ?: 0).toString()
        }
        if (key.startsWith("xp_needed_")) {
            val skill = SkillType.match(key.removePrefix("xp_needed_")) ?: return null
            val lvl = state?.getLevel(skill) ?: 0
            return context.formula.xpToNextLevel(lvl, context.config.retroMode, state?.powerLevel() ?: 0).toString()
        }
        if (key.startsWith("xp_")) {
            val skill = SkillType.match(key.removePrefix("xp_")) ?: return null
            return (state?.getXp(skill)?.toInt() ?: 0).toString()
        }
        if (key.startsWith("rank_")) {
            val skill = SkillType.match(key.removePrefix("rank_")) ?: return null
            val list = topCache[skill.name].orEmpty()
            val uuid = player?.uniqueId ?: return ""
            val idx = list.indexOfFirst { it.uuid == uuid }
            return if (idx >= 0) (idx + 1).toString() else ""
        }
        if (key.startsWith("top_")) {
            // Format: top_<skill|power>_<pos>_<name|level>
            val parts = key.removePrefix("top_").split('_')
            if (parts.size >= 3) {
                val skillKey = if (parts[0] == "power") "POWER" else SkillType.match(parts[0])?.name ?: return null
                val pos = parts[1].toIntOrNull()?.takeIf { it >= 1 } ?: return ""
                val entry = topCache[skillKey]?.getOrNull(pos - 1) ?: return ""
                return when (parts[2]) {
                    "name" -> entry.username
                    "level" -> entry.level.toString()
                    else -> null
                }
            }
        }
        return null
    }
}
