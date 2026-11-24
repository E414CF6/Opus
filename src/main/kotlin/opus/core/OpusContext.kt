package opus.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import opus.core.config.ExperienceConfig
import opus.core.config.OpusConfig
import opus.core.config.RanksConfig
import opus.core.config.SkillsConfig
import opus.core.database.DatabaseManager
import opus.core.database.PartyRepository
import opus.core.database.PlayerSkillRepository
import opus.core.locale.LocaleService
import opus.core.module.ModuleRegistry
import opus.core.player.PlayerDataService
import opus.core.scheduler.OpusScheduler
import opus.core.scheduler.WarmupRunner
import opus.core.world.PlacedBlockTracker
import opus.skills.*
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Central service container wiring core infrastructure for all Opus modules.
 */
class OpusContext(val plugin: JavaPlugin) {

    val config = OpusConfig(plugin)
    val experienceConfig = ExperienceConfig(plugin)
    val ranksConfig = RanksConfig(plugin)
    val skillsConfig = SkillsConfig(plugin)
    val locale = LocaleService(plugin, config)
    val scheduler = OpusScheduler(plugin)
    val database: DatabaseManager = DatabaseManager.fromConfig(plugin.dataFolder, config)

    val playerRepository = PlayerSkillRepository(database)
    val partyRepository = PartyRepository(database)

    val players = PlayerDataService(playerRepository, scheduler, config)
    val placedBlocks = PlacedBlockTracker(plugin, experienceConfig)

    val formula = FormulaService(experienceConfig)
    val ranks = RankService(config, ranksConfig)
    val perks = PerksService(experienceConfig)
    val levelUpCommands = LevelUpCommandService(this)
    val probability = ProbabilityService(config, skillsConfig, perks)
    val progression = SkillProgressionService(this)

    val modules = ModuleRegistry(plugin, config)

    private val services = ConcurrentHashMap<Class<*>, Any>()

    fun <T : Any> registerService(type: Class<T>, instance: T) {
        services[type] = instance
    }

    inline fun <reified T : Any> registerService(instance: T) {
        registerService(T::class.java, instance)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getService(type: Class<T>): T? = services[type] as? T

    inline fun <reified T : Any> getService(): T? = getService(T::class.java)

    fun startAutoSave() {
        val interval = config.autoSaveIntervalSeconds
        scheduler.runAsyncTimer(interval, interval) {
            scheduler.asyncScope.launch {
                runCatching { players.flushDirty() }.onFailure {
                    if (it is CancellationException) {
                        ensureActive()
                    }
                }.onFailure { err ->
                    plugin.logger.warning("Autosave error: ${err.message}")
                }
            }
        }
    }

    fun reloadAll() {
        config.reload()
        experienceConfig.reload()
        ranksConfig.reload()
        skillsConfig.reload()
        levelUpCommands.reload()
        formula.clearCache()
        locale.reload()
        modules.reloadAll()
    }

    fun shutdown() {
        WarmupRunner.clearAll()
        modules.disableAll()
        players.flushAllBlocking()
        scheduler.shutdown()
        database.close()
    }
}
