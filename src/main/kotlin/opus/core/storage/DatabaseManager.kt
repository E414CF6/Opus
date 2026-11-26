package opus.core.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection

enum class DatabaseDialect {
    SQLITE, MYSQL, MARIADB,
}

/**
 * Domain-agnostic HikariCP database pool supporting SQLite, MySQL, and MariaDB.
 */
class DatabaseManager(
    val dialect: DatabaseDialect,
    val tablePrefix: String,
    private val dataSource: HikariDataSource,
) : AutoCloseable {

    fun table(name: String): String = "$tablePrefix$name"

    fun initSchema(block: (Connection) -> Unit) {
        querySync(block)
    }

    suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    fun <T> querySync(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun <T> withTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (ex: Throwable) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }

    fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        if (dialect == DatabaseDialect.SQLITE) {
            var exists = false
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`$table`);").use { rs ->
                    while (rs.next()) {
                        if (rs.getString("name").equals(column, ignoreCase = true)) {
                            exists = true
                            break
                        }
                    }
                }
                if (!exists) {
                    st.execute("ALTER TABLE `$table` ADD COLUMN `$column` $definition;")
                }
            }
        } else {
            runCatching {
                conn.createStatement().use { st ->
                    st.executeUpdate("ALTER TABLE `$table` ADD COLUMN `$column` $definition;")
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
        fun forSqlite(
            file: File,
            tablePrefix: String = "opus_",
            poolSize: Int = 1,
            poolName: String = "Opus-SQLite",
        ): DatabaseManager {
            file.parentFile?.mkdirs()
            val hc = HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = poolSize.coerceAtLeast(1)
                minimumIdle = 1
                this.poolName = poolName
                connectionTestQuery = "SELECT 1"
                addDataSourceProperty("foreign_keys", "true")
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("synchronous", "NORMAL")
                addDataSourceProperty("busy_timeout", "10000")
            }
            return DatabaseManager(DatabaseDialect.SQLITE, tablePrefix, HikariDataSource(hc)).apply {
                querySync { conn ->
                    conn.createStatement().use { st ->
                        st.execute("PRAGMA journal_mode=WAL;")
                        st.execute("PRAGMA synchronous=NORMAL;")
                        st.execute("PRAGMA foreign_keys=ON;")
                    }
                }
            }
        }

        fun forMySql(
            dialect: DatabaseDialect,
            host: String,
            port: Int,
            database: String,
            username: String,
            password: String,
            tablePrefix: String,
            useSsl: Boolean,
            poolSize: Int,
            poolName: String = "Opus-${dialect.name}",
        ): DatabaseManager {
            val sslParam = if (useSsl) "useSSL=true" else "useSSL=false&allowPublicKeyRetrieval=true"
            val jdbc = "jdbc:mysql://$host:$port/$database?$sslParam&characterEncoding=utf8"
            val hc = HikariConfig().apply {
                jdbcUrl = jdbc
                driverClassName = "com.mysql.cj.jdbc.Driver"
                this.username = username
                this.password = password
                maximumPoolSize = poolSize.coerceAtLeast(1)
                this.poolName = poolName
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }
            return DatabaseManager(dialect, tablePrefix, HikariDataSource(hc))
        }
    }
}
