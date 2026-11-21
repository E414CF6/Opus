package opus

import kotlinx.coroutines.runBlocking
import opus.core.database.*
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class DatabaseRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `PlayerSkillRepository saves loads ranks and purges players`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("opus_players.db").toFile())
        db.use {
            val repo = PlayerSkillRepository(db)
            val aliceId = UUID.randomUUID()
            val bobId = UUID.randomUUID()
            val futureCd = System.currentTimeMillis() + 60_000L

            val alice = PlayerRecord(
                uuid = aliceId,
                username = "Alice",
                lastLogin = System.currentTimeMillis(),
                hudMode = "BOTH",
                scoreboardMode = "KEEP",
                notifyAbilities = false,
                levelUpSound = true,
                chatMode = "PARTY",
                skills = mapOf(
                    SkillType.MINING to SkillProgressRecord(42, 350.5),
                    SkillType.SWORDS to SkillProgressRecord(18, 120.0),
                ),
                cooldowns = mapOf(SuperAbilityType.SUPER_BREAKER to futureCd),
            )
            val bob = PlayerRecord(
                uuid = bobId,
                username = "Bob",
                lastLogin = 1000L,
                skills = mapOf(
                    SkillType.MINING to SkillProgressRecord(15, 10.0),
                ),
            )

            repo.saveAll(listOf(alice, bob))

            val loadedAlice = repo.findByUuid(aliceId)
            assertNotNull(loadedAlice)
            assertEquals("Alice", loadedAlice!!.username)
            assertEquals("BOTH", loadedAlice.hudMode)
            assertEquals(42, loadedAlice.skills[SkillType.MINING]?.level)
            assertEquals(350.5, loadedAlice.skills[SkillType.MINING]?.xp ?: 0.0, 0.001)
            assertEquals(futureCd, loadedAlice.cooldowns[SuperAbilityType.SUPER_BREAKER])

            val byName = repo.findByName("aLiCe")
            assertEquals(aliceId, byName?.uuid)

            val topMining = repo.readTopSkill(SkillType.MINING, 10)
            assertEquals(listOf("Alice", "Bob"), topMining.map { it.username })
            assertEquals(1, repo.findRank(aliceId, SkillType.MINING))
            assertEquals(2, repo.findRank(bobId, SkillType.MINING))

            val topPower = repo.readTopSkill(null, 10)
            assertEquals("Alice", topPower.first().username)
            assertEquals(60, topPower.first().level)

            // Purge inactive older than 100_000L should remove Bob (lastLogin=1000L)
            val purged = repo.purgeInactive(100_000L)
            assertEquals(1, purged)
            assertNull(repo.findByUuid(bobId))
            assertNotNull(repo.findByUuid(aliceId))

            assertTrue(repo.deletePlayer(aliceId))
            assertNull(repo.findByUuid(aliceId))
        }
    }

    @Test
    fun `PartyRepository persists parties alliances and members`() = runBlocking {
        val db = DatabaseManager.forSqlite(tempDir.resolve("opus_parties.db").toFile())
        db.use {
            val repo = PartyRepository(db)
            val partyId = UUID.randomUUID()
            val leaderId = UUID.randomUUID()
            val memberId = UUID.randomUUID()

            val party = PartyRecord(
                id = partyId,
                name = "Vanguard",
                leaderUuid = leaderId,
                password = "secret",
                locked = true,
                expShareMode = "EQUAL",
                itemShareMode = "RANDOM",
                motd = "Welcome!",
            )
            repo.upsertParty(party)
            repo.upsertMember(PartyMemberRecord(leaderId, partyId, "LEADER"))
            repo.upsertMember(PartyMemberRecord(memberId, partyId, "MEMBER"))

            val (parties, members) = repo.loadAll()
            assertEquals(1, parties.size)
            assertEquals("Vanguard", parties.first().name)
            assertEquals("Welcome!", parties.first().motd)
            assertEquals(2, members.size)

            repo.deleteMember(memberId)
            assertEquals(1, repo.loadAll().second.size)

            repo.deleteParty(partyId)
            val (afterParties, afterMembers) = repo.loadAll()
            assertTrue(afterParties.isEmpty())
            assertTrue(afterMembers.isEmpty())
        }
    }
}
