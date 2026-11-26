package opus.skills

import opus.core.storage.DatabaseDialect
import opus.core.storage.DatabaseManager
import java.sql.ResultSet
import java.util.UUID

data class SkillProgressRecord(
    val level: Int = 0,
    val xp: Double = 0.0,
)

data class PlayerRecord(
    val uuid: UUID,
    val username: String,
    val lastLogin: Long = System.currentTimeMillis(),
    val hudMode: String = "BOSSBAR",
    val scoreboardMode: String = "DEFAULT",
    val notifyAbilities: Boolean = true,
    val levelUpSound: Boolean = true,
    val chatMode: String = "PUBLIC",
    val skills: Map<SkillType, SkillProgressRecord> = emptyMap(),
    val cooldowns: Map<SuperAbilityType, Long> = emptyMap(),
)

data class LeaderboardEntry(
    val uuid: UUID,
    val username: String,
    val level: Int,
)

/**
 * Persistence repository for player RPG profiles, skill levels/XP, and ability cooldowns.
 */
class PlayerSkillRepository(private val db: DatabaseManager) {

    private val playersTable = db.table("players")
    private val skillsTable = db.table("skill_levels")
    private val cooldownsTable = db.table("cooldowns")

    fun initSchema() {
        db.initSchema { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `$playersTable` (
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
                    CREATE TABLE IF NOT EXISTS `$skillsTable` (
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
                    CREATE TABLE IF NOT EXISTS `$cooldownsTable` (
                        `uuid` VARCHAR(36) NOT NULL,
                        `ability` VARCHAR(48) NOT NULL,
                        `expires_at` BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (`uuid`, `ability`)
                    );
                    """.trimIndent()
                )
                runCatching {
                    st.executeUpdate("CREATE INDEX `idx_${db.tablePrefix}skill_top` ON `$skillsTable` (`skill`, `level` DESC);")
                }
            }
        }
    }

    suspend fun findByUuid(uuid: UUID): PlayerRecord? = db.query { conn ->
        val header = conn.prepareStatement("SELECT * FROM `$playersTable` WHERE `uuid` = ? LIMIT 1;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@query null
                mapPlayerHeader(rs)
            }
        }
        val skills = HashMap<SkillType, SkillProgressRecord>()
        conn.prepareStatement("SELECT `skill`, `level`, `xp` FROM `$skillsTable` WHERE `uuid` = ?;").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val skill = runCatching { SkillType.valueOf(rs.getString("skill")) }.getOrNull() ?: continue
                    skills[skill] = SkillProgressRecord(rs.getInt("level"), rs.getDouble("xp"))
                }
            }
        }
        val cooldowns = HashMap<SuperAbilityType, Long>()
        val now = System.currentTimeMillis()
        conn.prepareStatement("SELECT `ability`, `expires_at` FROM `$cooldownsTable` WHERE `uuid` = ? AND `expires_at` > ?;")
            .use { ps ->
                ps.setString(1, uuid.toString())
                ps.setLong(2, now)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val ability =
                            runCatching { SuperAbilityType.valueOf(rs.getString("ability")) }.getOrNull() ?: continue
                        cooldowns[ability] = rs.getLong("expires_at")
                    }
                }
            }
        header.copy(skills = skills, cooldowns = cooldowns)
    }

    suspend fun findByName(username: String): PlayerRecord? {
        val uuid = db.query { conn ->
            conn.prepareStatement(
                "SELECT `uuid` FROM `$playersTable` WHERE LOWER(`username`) = LOWER(?) ORDER BY `last_login` DESC LIMIT 1;"
            ).use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (rs.next()) UUID.fromString(rs.getString("uuid")) else null
                }
            }
        } ?: return null
        return findByUuid(uuid)
    }

    suspend fun saveAll(records: Collection<PlayerRecord>) {
        if (records.isEmpty()) return
        val now = System.currentTimeMillis()
        val playerSql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$playersTable` (`uuid`, `username`, `last_login`, `hud_mode`, `scoreboard_mode`, `notify_abilities`, `levelup_sound`, `chat_mode`, `updated_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(`uuid`) DO UPDATE SET
                    `username` = excluded.`username`,
                    `last_login` = excluded.`last_login`,
                    `hud_mode` = excluded.`hud_mode`,
                    `scoreboard_mode` = excluded.`scoreboard_mode`,
                    `notify_abilities` = excluded.`notify_abilities`,
                    `levelup_sound` = excluded.`levelup_sound`,
                    `chat_mode` = excluded.`chat_mode`,
                    `updated_at` = excluded.`updated_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$playersTable` (`uuid`, `username`, `last_login`, `hud_mode`, `scoreboard_mode`, `notify_abilities`, `levelup_sound`, `chat_mode`, `updated_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `username` = VALUES(`username`),
                    `last_login` = VALUES(`last_login`),
                    `hud_mode` = VALUES(`hud_mode`),
                    `scoreboard_mode` = VALUES(`scoreboard_mode`),
                    `notify_abilities` = VALUES(`notify_abilities`),
                    `levelup_sound` = VALUES(`levelup_sound`),
                    `chat_mode` = VALUES(`chat_mode`),
                    `updated_at` = VALUES(`updated_at`);
            """.trimIndent()
        }
        val skillSql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$skillsTable` (`uuid`, `skill`, `level`, `xp`, `updated_at`)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(`uuid`, `skill`) DO UPDATE SET
                    `level` = excluded.`level`,
                    `xp` = excluded.`xp`,
                    `updated_at` = excluded.`updated_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$skillsTable` (`uuid`, `skill`, `level`, `xp`, `updated_at`)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `level` = VALUES(`level`),
                    `xp` = VALUES(`xp`),
                    `updated_at` = VALUES(`updated_at`);
            """.trimIndent()
        }
        val cooldownSql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$cooldownsTable` (`uuid`, `ability`, `expires_at`)
                VALUES (?, ?, ?)
                ON CONFLICT(`uuid`, `ability`) DO UPDATE SET
                    `expires_at` = excluded.`expires_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$cooldownsTable` (`uuid`, `ability`, `expires_at`)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `expires_at` = VALUES(`expires_at`);
            """.trimIndent()
        }

        db.query { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(playerSql).use { ps ->
                    for (r in records) {
                        ps.setString(1, r.uuid.toString())
                        ps.setString(2, r.username)
                        ps.setLong(3, r.lastLogin)
                        ps.setString(4, r.hudMode)
                        ps.setString(5, r.scoreboardMode)
                        ps.setBoolean(6, r.notifyAbilities)
                        ps.setBoolean(7, r.levelUpSound)
                        ps.setString(8, r.chatMode)
                        ps.setLong(9, now)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.prepareStatement(skillSql).use { ps ->
                    for (r in records) {
                        for ((skill, prog) in r.skills) {
                            if (skill.isChild) continue
                            ps.setString(1, r.uuid.toString())
                            ps.setString(2, skill.name)
                            ps.setInt(3, prog.level)
                            ps.setDouble(4, prog.xp)
                            ps.setLong(5, now)
                            ps.addBatch()
                        }
                    }
                    ps.executeBatch()
                }
                conn.prepareStatement(cooldownSql).use { ps ->
                    for (r in records) {
                        for ((ability, expiresAt) in r.cooldowns) {
                            ps.setString(1, r.uuid.toString())
                            ps.setString(2, ability.name)
                            ps.setLong(3, expiresAt)
                            ps.addBatch()
                        }
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Throwable) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    suspend fun deletePlayer(uuid: UUID): Boolean = db.query { conn ->
        conn.prepareStatement("DELETE FROM `$skillsTable` WHERE `uuid` = ?;").use {
            it.setString(1, uuid.toString())
            it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM `$cooldownsTable` WHERE `uuid` = ?;").use {
            it.setString(1, uuid.toString())
            it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM `$playersTable` WHERE `uuid` = ?;").use {
            it.setString(1, uuid.toString())
            it.executeUpdate() > 0
        }
    }

    suspend fun purgeInactive(cutoffMillis: Long): Int = db.query { conn ->
        val toDelete = mutableListOf<String>()
        conn.prepareStatement(
            """
            SELECT p.`uuid` FROM `$playersTable` p
            LEFT JOIN `$skillsTable` s ON p.`uuid` = s.`uuid`
            GROUP BY p.`uuid`, p.`last_login`
            HAVING (p.`last_login` < ? AND p.`last_login` > 0) OR COALESCE(SUM(s.`level`), 0) = 0;
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, cutoffMillis)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    toDelete += rs.getString("uuid")
                }
            }
        }
        if (toDelete.isEmpty()) return@query 0
        for (id in toDelete) {
            conn.prepareStatement("DELETE FROM `$skillsTable` WHERE `uuid` = ?;").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM `$cooldownsTable` WHERE `uuid` = ?;").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM `$playersTable` WHERE `uuid` = ?;").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
        toDelete.size
    }

    suspend fun readTopSkill(skill: SkillType?, limit: Int, offset: Int = 0): List<LeaderboardEntry> =
        db.query { conn ->
            val sql = if (skill == null) {
                """
            SELECT p.`uuid`, p.`username`, COALESCE(SUM(s.`level`), 0) AS total_level
            FROM `$playersTable` p
            LEFT JOIN `$skillsTable` s ON p.`uuid` = s.`uuid`
            GROUP BY p.`uuid`, p.`username`
            HAVING total_level > 0
            ORDER BY total_level DESC, p.`username` ASC
            LIMIT ? OFFSET ?;
            """.trimIndent()
            } else {
                """
            SELECT p.`uuid`, p.`username`, s.`level` AS total_level
            FROM `$skillsTable` s
            JOIN `$playersTable` p ON s.`uuid` = p.`uuid`
            WHERE s.`skill` = ? AND s.`level` > 0
            ORDER BY s.`level` DESC, s.`xp` DESC, p.`username` ASC
            LIMIT ? OFFSET ?;
            """.trimIndent()
            }
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (skill != null) ps.setString(idx++, skill.name)
                ps.setInt(idx++, limit)
                ps.setInt(idx, offset)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LeaderboardEntry(
                                    uuid = UUID.fromString(rs.getString("uuid")),
                                    username = rs.getString("username"),
                                    level = rs.getInt("total_level"),
                                )
                            )
                        }
                    }
                }
            }
        }

    suspend fun findRank(uuid: UUID, skill: SkillType?): Int? = db.query { conn ->
        val list = readTopSkillSync(conn, skill, 10000)
        val idx = list.indexOfFirst { it.uuid == uuid }
        if (idx >= 0) idx + 1 else null
    }

    private fun readTopSkillSync(conn: java.sql.Connection, skill: SkillType?, limit: Int): List<LeaderboardEntry> {
        val sql = if (skill == null) {
            """
            SELECT p.`uuid`, p.`username`, COALESCE(SUM(s.`level`), 0) AS total_level
            FROM `$playersTable` p
            LEFT JOIN `$skillsTable` s ON p.`uuid` = s.`uuid`
            GROUP BY p.`uuid`, p.`username`
            HAVING total_level > 0
            ORDER BY total_level DESC, p.`username` ASC
            LIMIT ?;
            """.trimIndent()
        } else {
            """
            SELECT p.`uuid`, p.`username`, s.`level` AS total_level
            FROM `$skillsTable` s
            JOIN `$playersTable` p ON s.`uuid` = p.`uuid`
            WHERE s.`skill` = ? AND s.`level` > 0
            ORDER BY s.`level` DESC, s.`xp` DESC, p.`username` ASC
            LIMIT ?;
            """.trimIndent()
        }
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            if (skill != null) ps.setString(idx++, skill.name)
            ps.setInt(idx, limit)
            ps.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(
                            LeaderboardEntry(
                                uuid = UUID.fromString(rs.getString("uuid")),
                                username = rs.getString("username"),
                                level = rs.getInt("total_level"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun mapPlayerHeader(rs: ResultSet): PlayerRecord = PlayerRecord(
        uuid = UUID.fromString(rs.getString("uuid")),
        username = rs.getString("username"),
        lastLogin = rs.getLong("last_login"),
        hudMode = rs.getString("hud_mode") ?: "BOSSBAR",
        scoreboardMode = rs.getString("scoreboard_mode") ?: "DEFAULT",
        notifyAbilities = rs.getBoolean("notify_abilities"),
        levelUpSound = rs.getBoolean("levelup_sound"),
        chatMode = rs.getString("chat_mode") ?: "PUBLIC",
    )
}
