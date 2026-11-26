package opus.modules.party

import opus.core.storage.DatabaseDialect
import opus.core.storage.DatabaseManager
import java.util.UUID

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
    val level: Int = 0,
    val xp: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
)

data class PartyMemberRecord(
    val playerUuid: UUID,
    val partyId: UUID,
    val role: String = "MEMBER",
    val joinedAt: Long = System.currentTimeMillis(),
)

/**
 * Persistence repository for parties and party memberships.
 */
class PartyRepository(private val db: DatabaseManager) {

    private val partiesTable = db.table("parties")
    private val membersTable = db.table("party_members")

    fun initSchema() {
        db.initSchema { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `$partiesTable` (
                        `id` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `name` VARCHAR(64) NOT NULL UNIQUE,
                        `leader_uuid` VARCHAR(36) NOT NULL,
                        `password` VARCHAR(128),
                        `locked` BOOLEAN NOT NULL DEFAULT FALSE,
                        `exp_share_mode` VARCHAR(16) NOT NULL DEFAULT 'EQUAL',
                        `item_share_mode` VARCHAR(16) NOT NULL DEFAULT 'NONE',
                        `ally_party_id` VARCHAR(36),
                        `motd` TEXT,
                        `level` INT NOT NULL DEFAULT 0,
                        `xp` DOUBLE NOT NULL DEFAULT 0,
                        `created_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                db.ensureColumn(conn, partiesTable, "level", "INT NOT NULL DEFAULT 0")
                db.ensureColumn(conn, partiesTable, "xp", "DOUBLE NOT NULL DEFAULT 0")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `$membersTable` (
                        `player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
                        `party_id` VARCHAR(36) NOT NULL,
                        `role` VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
                        `joined_at` BIGINT NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                runCatching {
                    st.executeUpdate("CREATE INDEX `idx_${db.tablePrefix}party_members_pid` ON `$membersTable` (`party_id`);")
                }
            }
        }
    }

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
                                level = rs.getInt("level"),
                                xp = rs.getDouble("xp"),
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
                INSERT INTO `$partiesTable` (`id`, `name`, `leader_uuid`, `password`, `locked`, `exp_share_mode`, `item_share_mode`, `ally_party_id`, `motd`, `level`, `xp`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(`id`) DO UPDATE SET
                    `name` = excluded.`name`,
                    `leader_uuid` = excluded.`leader_uuid`,
                    `password` = excluded.`password`,
                    `locked` = excluded.`locked`,
                    `exp_share_mode` = excluded.`exp_share_mode`,
                    `item_share_mode` = excluded.`item_share_mode`,
                    `ally_party_id` = excluded.`ally_party_id`,
                    `motd` = excluded.`motd`,
                    `level` = excluded.`level`,
                    `xp` = excluded.`xp`;
            """.trimIndent()

            DatabaseDialect.MYSQL, DatabaseDialect.MARIADB -> """
                INSERT INTO `$partiesTable` (`id`, `name`, `leader_uuid`, `password`, `locked`, `exp_share_mode`, `item_share_mode`, `ally_party_id`, `motd`, `level`, `xp`, `created_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `name` = VALUES(`name`),
                    `leader_uuid` = VALUES(`leader_uuid`),
                    `password` = VALUES(`password`),
                    `locked` = VALUES(`locked`),
                    `exp_share_mode` = VALUES(`exp_share_mode`),
                    `item_share_mode` = VALUES(`item_share_mode`),
                    `ally_party_id` = VALUES(`ally_party_id`),
                    `motd` = VALUES(`motd`),
                    `level` = VALUES(`level`),
                    `xp` = VALUES(`xp`);
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
                ps.setInt(10, party.level)
                ps.setDouble(11, party.xp)
                ps.setLong(12, party.createdAt)
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
