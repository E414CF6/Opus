package opus.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import opus.core.config.OpusConfig
import java.io.File
import java.sql.Connection

enum class DatabaseDialect {
    SQLITE,
    MYSQL,
    MARIADB,
}

/**
 * Manages the HikariCP connection pool and schema initialization for SQLite, MySQL, and MariaDB.
 */
class DatabaseManager(
    val dialect: DatabaseDialect,
    val tablePrefix: String,
    private val dataSource: HikariDataSource,
) : AutoCloseable {

    fun table(name: String): String = "$tablePrefix$name"

    suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    fun <T> querySync(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun initSchemaSafely() {
        querySync { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("players")}` (
                        `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `username` VARCHAR(32) NOT NULL,
                        `last_login` BIGINT NOT NULL DEFAULT 0,
                        `hud_mode` VARCHAR(16) NOT NULL DEFAULT 'BOSSBAR',
                        `scoreboard_mode` VARCHAR(16) NOT NULL DEFAULT 'DEFAULT',
                        `notify_abilities` BOOLEAN NOT NULL DEFAULT TRUE,
                        `levelup_sound` BOOLEAN NOT NULL DEFAULT TRUE,
                        `chat_mode` VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
                        `updated_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("skill_levels")}` (
                        `uuid` VARCHAR(36) NOT NULL,
                        `skill` VARCHAR(32) NOT NULL,
                        `level` INT NOT NULL DEFAULT 0,
                        `xp` DOUBLE NOT NULL DEFAULT 0,
                        `updated_at` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `skill`)
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("cooldowns")}` (
                        `uuid` VARCHAR(36) NOT NULL,
                        `ability` VARCHAR(48) NOT NULL,
                        `expires_at` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `ability`)
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("parties")}` (
                        `id` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `name` VARCHAR(64) NOT NULL UNIQUE,
                        `leader_uuid` VARCHAR(36) NOT NULL,
                        `password` VARCHAR(128),
                        `locked` BOOLEAN NOT NULL DEFAULT FALSE,
                        `exp_share_mode` VARCHAR(16) NOT NULL DEFAULT 'EQUAL',
                        `item_share_mode` VARCHAR(16) NOT NULL DEFAULT 'NONE',
                        `ally_party_id` VARCHAR(36),
                        `motd` TEXT,
                        `created_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `${table("party_members")}` (
                        `player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `party_id` VARCHAR(36) NOT NULL,
                        `role` VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
                        `joined_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                runCatching {
                    st.executeUpdate("CREATE INDEX `idx_${tablePrefix}skill_top` ON `${table("skill_levels")}` (`skill`, `level` DESC);")
                }
                runCatching {
                    st.executeUpdate("CREATE INDEX `idx_${tablePrefix}party_members_pid` ON `${table("party_members")}` (`party_id`);")
                }
            }
        }
    }

    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }

    companion object {
        fun forSqlite(file: File, tablePrefix: String = "opus_"): DatabaseManager {
            file.parentFile?.mkdirs()
            val hc = HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1
                poolName = "Opus-SQLite"
                connectionTestQuery = "SELECT 1"
            }
            return DatabaseManager(DatabaseDialect.SQLITE, tablePrefix, HikariDataSource(hc)).apply {
                initSchemaSafely()
            }
        }

        fun fromConfig(dataFolder: File, config: OpusConfig): DatabaseManager {
            val dialect = when (config.storageMethod) {
                "mysql" -> DatabaseDialect.MYSQL
                "mariadb" -> DatabaseDialect.MARIADB
                else -> DatabaseDialect.SQLITE
            }
            if (dialect == DatabaseDialect.SQLITE) {
                return forSqlite(File(dataFolder, config.sqliteFile), config.mysqlTablePrefix)
            }
            val host = config.mysqlHost
            val port = config.mysqlPort
            val dbName = config.mysqlDatabase
            val sslParam = if (config.mysqlUseSsl) "useSSL=true" else "useSSL=false&allowPublicKeyRetrieval=true"
            val jdbc = "jdbc:mysql://$host:$port/$dbName?$sslParam&characterEncoding=utf8"
            val hc = HikariConfig().apply {
                jdbcUrl = jdbc
                driverClassName = "com.mysql.cj.jdbc.Driver"
                username = config.mysqlUsername
                password = config.mysqlPassword
                maximumPoolSize = config.mysqlPoolSize
                poolName = "Opus-${dialect.name}"
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }
            return DatabaseManager(dialect, config.mysqlTablePrefix, HikariDataSource(hc)).apply {
                initSchemaSafely()
            }
        }
    }
}
