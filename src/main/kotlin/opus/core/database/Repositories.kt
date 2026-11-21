package opus.core.database

import opus.skills.SkillType
import opus.skills.SuperAbilityType
import java.sql.ResultSet
import java.util.*

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

data class PartyRecord(
    val id: UUID,
    val name: String,
    val leaderUuid: UUID,
    val password: String? = null,
    val locked: Boolean = false,
    val expShareMode: String = "EQUAL",
    val itemShareMode: String = "NONE",
    val allyPartyId: UUID? = null,
    val motd: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class PartyMemberRecord(
    val playerUuid: UUID,
    val partyId: UUID,
    val role: String = "MEMBER",
    val joinedAt: Long = System.currentTimeMillis(),
)

/**
 * Persistence repository for player RPG profiles, skill levels/XP, and ability cooldowns.
 */
class PlayerSkillRepository(private val db: DatabaseManager) {

    private val playersTable = db.table("players")
    private val skillsTable = db.table("skill_levels")
    private val cooldownsTable = db.table("cooldowns")

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

/**
 * Persistence repository for parties and party memberships.
 */
class PartyRepository(private val db: DatabaseManager) {

    private val partiesTable = db.table("parties")
    private val membersTable = db.table("party_members")

    suspend fun loadAll(): Pair<List<PartyRecord>, List<PartyMemberRecord>> = db.query { conn ->
        val parties = conn.prepareStatement("SELECT * FROM `$partiesTable`;").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PartyRecord(
                                id = UUID.fromString(rs.getString("id")),
                                name = rs.getString("name"),
                                leaderUuid = UUID.fromString(rs.getString("leader_uuid")),
                                password = rs.getString("password"),
                                locked = rs.getBoolean("locked"),
                                expShareMode = rs.getString("exp_share_mode") ?: "EQUAL",
                                itemShareMode = rs.getString("item_share_mode") ?: "NONE",
                                allyPartyId = rs.getString("ally_party_id")?.let { UUID.fromString(it) },
                                motd = rs.getString("motd"),
                                createdAt = rs.getLong("created_at"),
                            )
                        )
                    }
                }
            }
        }
        val members = conn.prepareStatement("SELECT * FROM `$membersTable`;").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PartyMemberRecord(
                                playerUuid = UUID.fromString(rs.getString("player_uuid")),
                                partyId = UUID.fromString(rs.getString("party_id")),
                                role = rs.getString("role") ?: "MEMBER",
                                joinedAt = rs.getLong("joined_at"),
                            )
                        )
                    }
                }
            }
        }
        parties to members
    }

    suspend fun upsertParty(party: PartyRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$partiesTable` (`id`, `name`, `leader_uuid`, `password`, `locked`, `exp_share_mode`, `item_share_mode`, `ally_party_id`, `motd`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(`id`) DO UPDATE SET
                    `name` = excluded.`name`,
                    `leader_uuid` = excluded.`leader_uuid`,
                    `password` = excluded.`password`,
                    `locked` = excluded.`locked`,
                    `exp_share_mode` = excluded.`exp_share_mode`,
                    `item_share_mode` = excluded.`item_share_mode`,
                    `ally_party_id` = excluded.`ally_party_id`,
                    `motd` = excluded.`motd`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$partiesTable` (`id`, `name`, `leader_uuid`, `password`, `locked`, `exp_share_mode`, `item_share_mode`, `ally_party_id`, `motd`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `name` = VALUES(`name`),
                    `leader_uuid` = VALUES(`leader_uuid`),
                    `password` = VALUES(`password`),
                    `locked` = VALUES(`locked`),
                    `exp_share_mode` = VALUES(`exp_share_mode`),
                    `item_share_mode` = VALUES(`item_share_mode`),
                    `ally_party_id` = VALUES(`ally_party_id`),
                    `motd` = VALUES(`motd`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, party.id.toString())
                ps.setString(2, party.name)
                ps.setString(3, party.leaderUuid.toString())
                ps.setString(4, party.password)
                ps.setBoolean(5, party.locked)
                ps.setString(6, party.expShareMode)
                ps.setString(7, party.itemShareMode)
                ps.setString(8, party.allyPartyId?.toString())
                ps.setString(9, party.motd)
                ps.setLong(10, party.createdAt)
                ps.executeUpdate()
            }
        }
    }

    suspend fun deleteParty(partyId: UUID) {
        db.query { conn ->
            conn.prepareStatement("DELETE FROM `$membersTable` WHERE `party_id` = ?;").use { ps ->
                ps.setString(1, partyId.toString())
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM `$partiesTable` WHERE `id` = ?;").use { ps ->
                ps.setString(1, partyId.toString())
                ps.executeUpdate()
            }
        }
    }

    suspend fun upsertMember(member: PartyMemberRecord) {
        val sql = when (db.dialect) {
            DatabaseDialect.SQLITE -> """
                INSERT INTO `$membersTable` (`player_uuid`, `party_id`, `role`, `joined_at`)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(`player_uuid`) DO UPDATE SET
                    `party_id` = excluded.`party_id`,
                    `role` = excluded.`role`,
                    `joined_at` = excluded.`joined_at`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$membersTable` (`player_uuid`, `party_id`, `role`, `joined_at`)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `party_id` = VALUES(`party_id`),
                    `role` = VALUES(`role`),
                    `joined_at` = VALUES(`joined_at`);
            """.trimIndent()
        }
        db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, member.playerUuid.toString())
                ps.setString(2, member.partyId.toString())
                ps.setString(3, member.role)
                ps.setLong(4, member.joinedAt)
                ps.executeUpdate()
            }
        }
    }

    suspend fun deleteMember(playerUuid: UUID) {
        db.query { conn ->
            conn.prepareStatement("DELETE FROM `$membersTable` WHERE `player_uuid` = ?;").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.executeUpdate()
            }
        }
    }
}
