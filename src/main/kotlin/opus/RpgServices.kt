package opus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import opus.core.CoreHost
import opus.core.OpusContext
import opus.integration.PlaceholderService
import opus.modules.party.PartyRepository
import opus.skills.ExperienceConfig
import opus.skills.FormulaService
import opus.skills.LevelUpCommandService
import opus.skills.PerksService
import opus.skills.PlacedBlockTracker
import opus.skills.PlayerDataService
import opus.skills.PlayerSkillRepository
import opus.skills.ProbabilityService
import opus.skills.RankService
import opus.skills.RanksConfig
import opus.skills.SkillProgressionService
import opus.skills.SkillsConfig

/**
 * Coordinates Opus's RPG domain repositories, configs, caches, and progression engines outside `opus.core`.
 */
class RpgServices(val context: OpusContext) {

    val experienceConfig = ExperienceConfig(context.plugin)
    val ranksConfig = RanksConfig(context.plugin)
    val skillsConfig = SkillsConfig(context.plugin)

    val playerRepository = PlayerSkillRepository(context.database).apply { initSchema() }
    val partyRepository = PartyRepository(context.database).apply { initSchema() }

    val players = PlayerDataService(playerRepository, context.scheduler, context.config)
    val placedBlocks = PlacedBlockTracker(context.plugin, experienceConfig)

    val formula = FormulaService(experienceConfig)
    val ranks = RankService(context.config, ranksConfig)
    val perks = PerksService(experienceConfig)
    val levelUpCommands = LevelUpCommandService(context, context.plugin)
    val probability = ProbabilityService(context.config, skillsConfig, perks)
    val progression = SkillProgressionService(context)

    init {
        context.registerReloadHook { reload() }
        context.registerPlaceholderResolver { player, input ->
            context.getService<PlaceholderService>()?.applyPlaceholders(player, input) ?: input
        }
        context.registerConditionEvaluator { expr, player ->
            if (expr.startsWith("power>=", ignoreCase = true)) {
                val min = expr.substring(7).trim().toIntOrNull() ?: return@registerConditionEvaluator false
                player != null && players.getCached(player).powerLevel() >= min
            } else {
                null
            }
        }
    }

    fun startAutoSave() {
        val interval = context.config.autoSaveIntervalSeconds
        context.scheduler.runAsyncTimer(interval, interval) {
            context.scheduler.asyncScope.launch {
                runCatching { players.flushDirty() }.onFailure {
                    if (it is CancellationException) {
                        ensureActive()
                    }
                }.onFailure { err ->
                    context.plugin.logger.warning("Autosave error: ${err.message}")
                }
            }
        }
    }

    fun reload() {
        experienceConfig.reload()
        ranksConfig.reload()
        skillsConfig.reload()
        levelUpCommands.reload()
        formula.clearCache()
    }

    fun flushBeforeShutdown() {
        players.flushAllBlocking()
    }
}

val CoreHost.rpg: RpgServices
    get() = getService(RpgServices::class.java)
        ?: error("RpgServices not initialized on host")
