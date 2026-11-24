package opus.core.config

import org.bukkit.plugin.Plugin

/**
 * Typed configuration snapshot loaded from `config.yml`.
 */
class OpusConfig(private val plugin: Plugin) {

    private val configFile = YamlConfigFile(plugin, "config.yml")

    var language: String = "ko_kr"
        private set
    var storageMethod: String = "sqlite"
        private set
    var autoSaveIntervalSeconds: Long = 60L
        private set
    var forceSaveOnLogout: Boolean = true
        private set
    var sqliteFile: String = "opus.db"
        private set
    var mysqlHost: String = "localhost"
        private set
    var mysqlPort: Int = 3306
        private set
    var mysqlDatabase: String = "minecraft"
        private set
    var mysqlUsername: String = "root"
        private set
    var mysqlPassword: String = ""
        private set
    var mysqlTablePrefix: String = "opus_"
        private set
    var mysqlUseSsl: Boolean = false
        private set
    var mysqlPoolSize: Int = 10
        private set
    var registerStandaloneAliases: Boolean = true
        private set

    var retroMode: Boolean = false
        private set
    var startingLevel: Int = 0
        private set
    var powerLevelCap: Int = 0
        private set
    var truncateSkillsOnCap: Boolean = true
        private set
    var levelUpSounds: Boolean = true
        private set
    var verboseLogging: Boolean = false
        private set
    var worldBlacklist: Set<String> = emptySet()
        private set

    var levelUpBroadcastEnabled: Boolean = true
        private set
    var levelUpMilestoneInterval: Int = 25
        private set
    var powerLevelBroadcastEnabled: Boolean = true
        private set
    var powerLevelMilestoneInterval: Int = 100
        private set
    var broadcastOnlySameWorld: Boolean = false
        private set
    var broadcastOnlyParty: Boolean = false
        private set
    var broadcastSendToConsole: Boolean = true
        private set

    var chimaeraEnabled: Boolean = true
        private set
    var chimaeraWarmupSeconds: Long = 3L
        private set
    var chimaeraCooldownSeconds: Long = 60L
        private set
    var chimaeraFeatherCost: Int = 1
        private set

    var xpEventAnnounceOnJoin: Boolean = true
        private set

    @Volatile
    var hardcoreStatLossEnabled: Boolean = false

    @Volatile
    var hardcoreStatLossPercentage: Double = 5.0
    var hardcoreStatLossThreshold: Int = 10
        private set

    @Volatile
    var hardcoreVampirismEnabled: Boolean = false

    @Volatile
    var hardcoreVampirismPercentage: Double = 5.0
    var hardcoreVampirismThreshold: Int = 10
        private set
    var hardcoreExcludedSkills: Set<String> = emptySet()
        private set

    var defaultXpDisplay: String = "BOSSBAR"
        private set
    var bossBarDurationSeconds: Long = 4L
        private set
    var actionBarNotifications: Boolean = true
        private set
    var mobHealthBarEnabled: Boolean = true
        private set
    var mobHealthBarStyle: String = "HEARTS"
        private set
    var mobHealthBarDurationSeconds: Long = 3L
        private set
    var scoreboardEnabled: Boolean = true
        private set
    var scoreboardDefaultSeconds: Int = 15
        private set
    var scoreboardAllowKeep: Boolean = true
        private set
    var powerLevelBelowName: Boolean = false
        private set

    var papiMaxTrackedRank: Int = 100
        private set
    var leaderboardCacheRefreshSeconds: Long = 60L
        private set

    init {
        reload()
    }

    val raw get() = configFile.yaml

    fun reload() {
        configFile.reload()
        val y = configFile.yaml
        language = normalizeLocale(y.getString("language", "ko_kr") ?: "ko_kr")
        storageMethod = (y.getString("storage.method", "sqlite") ?: "sqlite").lowercase()
        autoSaveIntervalSeconds = y.getLong("storage.auto-save-interval-seconds", 60L).coerceAtLeast(10L)
        forceSaveOnLogout = y.getBoolean("storage.force-save-on-logout", true)
        sqliteFile = y.getString("storage.sqlite.file", "opus.db") ?: "opus.db"
        mysqlHost = y.getString("storage.mysql.host", "localhost") ?: "localhost"
        mysqlPort = y.getInt("storage.mysql.port", 3306)
        mysqlDatabase = y.getString("storage.mysql.database", "minecraft") ?: "minecraft"
        mysqlUsername = y.getString("storage.mysql.username", "root") ?: "root"
        mysqlPassword = y.getString("storage.mysql.password", "") ?: ""
        mysqlTablePrefix = y.getString("storage.mysql.table-prefix", "opus_") ?: "opus_"
        mysqlUseSsl = y.getBoolean("storage.mysql.use-ssl", false)
        mysqlPoolSize = y.getInt("storage.mysql.pool-size", 10).coerceIn(1, 50)
        registerStandaloneAliases = y.getBoolean("commands.register-standalone-aliases", true)

        retroMode = y.getBoolean("general.retro-mode", false)
        startingLevel = y.getInt("general.starting-level", 0).coerceAtLeast(0)
        powerLevelCap = y.getInt("general.power-level-cap", 0).coerceAtLeast(0)
        truncateSkillsOnCap = y.getBoolean("general.truncate-skills-on-cap", true)
        levelUpSounds = y.getBoolean("general.levelup-sounds", true)
        verboseLogging = y.getBoolean("general.verbose-logging", false)
        worldBlacklist = y.getStringList("general.world-blacklist").map { it.lowercase() }.toSet()

        levelUpBroadcastEnabled = y.getBoolean("general.levelup-broadcasts.enabled", true)
        levelUpMilestoneInterval = y.getInt("general.levelup-broadcasts.milestone-interval", 25).coerceAtLeast(1)
        powerLevelBroadcastEnabled = y.getBoolean("general.levelup-broadcasts.powerlevel-enabled", true)
        powerLevelMilestoneInterval =
            y.getInt("general.levelup-broadcasts.powerlevel-milestone-interval", 100).coerceAtLeast(1)
        broadcastOnlySameWorld = y.getBoolean("general.levelup-broadcasts.only-same-world", false)
        broadcastOnlyParty = y.getBoolean("general.levelup-broadcasts.only-party-members", false)
        broadcastSendToConsole = y.getBoolean("general.levelup-broadcasts.send-to-console", true)

        chimaeraEnabled = y.getBoolean("general.chimaera-wing.enabled", true)
        chimaeraWarmupSeconds = y.getLong("general.chimaera-wing.warmup-seconds", 3L).coerceAtLeast(1L)
        chimaeraCooldownSeconds = y.getLong("general.chimaera-wing.cooldown-seconds", 60L).coerceAtLeast(0L)
        chimaeraFeatherCost = y.getInt("general.chimaera-wing.feather-cost", 1).coerceAtLeast(0)

        xpEventAnnounceOnJoin = y.getBoolean("general.xprate-event-announce-on-join", true)
        hardcoreStatLossEnabled = y.getBoolean("general.hardcore.stat-loss-enabled", false)
        hardcoreStatLossPercentage = y.getDouble("general.hardcore.stat-loss-percentage", 5.0).coerceIn(0.0, 100.0)
        hardcoreStatLossThreshold = y.getInt("general.hardcore.stat-loss-level-threshold", 10).coerceAtLeast(0)
        hardcoreVampirismEnabled = y.getBoolean("general.hardcore.vampirism-enabled", false)
        hardcoreVampirismPercentage = y.getDouble("general.hardcore.vampirism-percentage", 5.0).coerceIn(0.0, 100.0)
        hardcoreVampirismThreshold = y.getInt("general.hardcore.vampirism-level-threshold", 10).coerceAtLeast(0)
        hardcoreExcludedSkills = y.getStringList("general.hardcore.excluded-skills").map { it.lowercase() }.toSet()

        defaultXpDisplay = (y.getString("hud.default-xp-display", "BOSSBAR") ?: "BOSSBAR").uppercase()
        bossBarDurationSeconds = y.getLong("hud.bossbar-duration-seconds", 4L).coerceAtLeast(1L)
        actionBarNotifications = y.getBoolean("hud.actionbar-notifications", true)
        mobHealthBarEnabled = y.getBoolean("hud.mob-healthbar.enabled", true)
        mobHealthBarStyle = (y.getString("hud.mob-healthbar.style", "HEARTS") ?: "HEARTS").uppercase()
        mobHealthBarDurationSeconds = y.getLong("hud.mob-healthbar.duration-seconds", 3L).coerceAtLeast(1L)
        scoreboardEnabled = y.getBoolean("hud.scoreboard.enabled", true)
        scoreboardDefaultSeconds = y.getInt("hud.scoreboard.default-display-seconds", 15).coerceAtLeast(3)
        scoreboardAllowKeep = y.getBoolean("hud.scoreboard.allow-keep", true)
        powerLevelBelowName = y.getBoolean("hud.scoreboard.power-level-below-name", false)

        papiMaxTrackedRank = y.getInt("leaderboards.papi-max-tracked-rank", 100).coerceIn(10, 1000)
        leaderboardCacheRefreshSeconds = y.getLong("leaderboards.cache-refresh-seconds", 60L).coerceAtLeast(30L)
    }

    fun isModuleEnabled(moduleId: String, defaultEnabled: Boolean = true): Boolean =
        raw.getBoolean("modules.$moduleId", defaultEnabled)

    fun save() = configFile.save()

    companion object {
        fun normalizeLocale(raw: String): String {
            return when (val cleaned = raw.trim().lowercase().replace('-', '_')) {
                "en", "en_us" -> "en_us"
                "ko", "ko_kr" -> "ko_kr"
                "" -> "en_us"
                else -> cleaned
            }
        }
    }
}
