package opus

import opus.skills.OpusPlayerState
import opus.modules.combat.CombatLimitBreak
import opus.modules.party.Party
import opus.modules.skills.HardcoreService
import opus.skills.SkillType
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class CombatAndHardcoreTest {

    @Test
    fun `CombatLimitBreak scales bonus by defender armor quality`() {
        assertEquals(0.0, CombatLimitBreak.calculateBonus(0, 1000), 1e-9)
        assertEquals(10.0, CombatLimitBreak.calculateBonus(10, 1000), 1e-9) // PvE
        assertEquals(10.0, CombatLimitBreak.calculateBonus(10, 16), 1e-9)   // Full Diamond (16)
        assertEquals(7.0, CombatLimitBreak.calculateBonus(10, 12), 1e-9)    // Full Iron (12) -> 7.5 truncated to 7
        assertEquals(5.0, CombatLimitBreak.calculateBonus(10, 8), 1e-9)     // Chainmail (8) -> 5.0
        assertEquals(2.0, CombatLimitBreak.calculateBonus(10, 4), 1e-9)     // Leather (4) -> 2.5 truncated to 2
        assertEquals(2.0, CombatLimitBreak.calculateBonus(10, 0), 1e-9)     // Unarmored -> 2.5 truncated to 2

        assertEquals(5, CombatLimitBreak.armorPieceQuality(Material.NETHERITE_CHESTPLATE))
        assertEquals(4, CombatLimitBreak.armorPieceQuality(Material.DIAMOND_HELMET))
        assertEquals(3, CombatLimitBreak.armorPieceQuality(Material.IRON_LEGGINGS))
        assertEquals(3, CombatLimitBreak.armorPieceQuality(Material.TURTLE_HELMET))
        assertEquals(2, CombatLimitBreak.armorPieceQuality(Material.GOLDEN_BOOTS))
        assertEquals(1, CombatLimitBreak.armorPieceQuality(Material.LEATHER_CHESTPLATE))
        assertEquals(0, CombatLimitBreak.armorPieceQuality(Material.AIR))
    }

    @Test
    fun `CombatLimitBreak derives Paper 26_3 attack strength scale accurately`() {
        assertEquals(
            1.0,
            CombatLimitBreak.deriveAttackStrengthScale(7.0, 7.0, isSweepAttack = false, adjustForCooldown = true),
            1e-9
        )
        assertEquals(
            0.5,
            CombatLimitBreak.deriveAttackStrengthScale(3.5, 7.0, isSweepAttack = false, adjustForCooldown = true),
            1e-9
        )
        assertEquals(
            1.0,
            CombatLimitBreak.deriveAttackStrengthScale(12.0, 7.0, isSweepAttack = false, adjustForCooldown = true),
            1e-9
        )
        assertEquals(
            1.0,
            CombatLimitBreak.deriveAttackStrengthScale(1.0, 7.0, isSweepAttack = true, adjustForCooldown = true),
            1e-9
        )
        assertEquals(
            1.0,
            CombatLimitBreak.deriveAttackStrengthScale(1.0, 7.0, isSweepAttack = false, adjustForCooldown = false),
            1e-9
        )
    }

    @Test
    fun `HardcoreService computes death stat loss and respects threshold and exclusions`() {
        val victim = OpusPlayerState(UUID.randomUUID(), "Victim")
        victim.setSkill(SkillType.MINING, 110, 500.0)     // above threshold 10 -> (100 * 5%) = 5.0 levels
        victim.setSkill(
            SkillType.SWORDS,
            15,
            200.0
        )      // above threshold 10 -> (5 * 5%) = 0.25 -> 0 lvl, floor(200*0.25)=50 XP
        victim.setSkill(SkillType.WOODCUTTING, 8, 100.0)  // below threshold 10 -> untouched
        victim.setSkill(SkillType.FISHING, 100, 100.0)    // excluded -> untouched

        val delta = HardcoreService.computeDeathStatLoss(
            state = victim,
            percentage = 5.0,
            threshold = 10,
            excludedSkills = setOf("fishing"),
        )

        assertEquals(5, delta.levelsChanged[SkillType.MINING])
        assertEquals(0.0, delta.xpChanged[SkillType.MINING] ?: 0.0, 1e-9)
        assertEquals(0, delta.levelsChanged[SkillType.SWORDS])
        assertEquals(50.0, delta.xpChanged[SkillType.SWORDS] ?: 0.0, 1e-9)
        assertNull(delta.levelsChanged[SkillType.WOODCUTTING])
        assertNull(delta.levelsChanged[SkillType.FISHING])
        assertEquals(5, delta.totalLevels)
    }

    @Test
    fun `HardcoreService computes vampirism and prevents farming much weaker players`() {
        val killer = OpusPlayerState(UUID.randomUUID(), "Killer")
        val victim = OpusPlayerState(UUID.randomUUID(), "Victim")

        killer.setSkill(SkillType.SWORDS, 100, 0.0)
        victim.setSkill(SkillType.SWORDS, 80, 0.0)   // >= 100/2 (50) -> eligible: 80 * 5% = 4 levels

        killer.setSkill(SkillType.AXES, 100, 0.0)
        victim.setSkill(SkillType.AXES, 40, 0.0)     // < 100/2 (50) -> protected from farming!

        val delta = HardcoreService.computeVampirism(
            killerState = killer,
            victimState = victim,
            percentage = 5.0,
            threshold = 0,
        )

        assertEquals(4, delta.levelsChanged[SkillType.SWORDS])
        assertNull(delta.levelsChanged[SkillType.AXES])
        assertEquals(4, delta.totalLevels)
    }

    @Test
    fun `Party addXp levels up across multiple thresholds`() {
        val party = Party(UUID.randomUUID(), "Vanguard", UUID.randomUUID())
        assertEquals(0, party.level)
        assertEquals(1000.0, party.xpNeeded(1000.0, 500.0), 1e-9)

        assertFalse(party.addXp(400.0, 1000.0, 500.0))
        assertEquals(0, party.level)
        assertEquals(400.0, party.xp, 1e-9)

        // Adding 2200 -> total 2600: Lv 0->1 (needs 1000, rem 1600), Lv 1->2 (needs 1500, rem 100)
        assertTrue(party.addXp(2200.0, 1000.0, 500.0))
        assertEquals(2, party.level)
        assertEquals(100.0, party.xp, 1e-9)
    }
}
