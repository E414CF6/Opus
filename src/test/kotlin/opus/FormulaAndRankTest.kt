package opus

import opus.core.config.ExperienceConfig
import opus.core.config.FormulaCurve
import opus.core.config.RanksConfig
import opus.core.player.OpusPlayerState
import opus.skills.FormulaService
import opus.skills.SkillType
import opus.skills.SubSkillType
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.*

class FormulaAndRankTest {

    private fun loadExpConfig(): ExperienceConfig {
        val yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/experience.yml").toFile())
        return ExperienceConfig(initialYaml = yaml)
    }

    private fun loadRanksConfig(): RanksConfig {
        val yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/ranks.yml").toFile())
        return RanksConfig(initialYaml = yaml)
    }

    @Test
    fun `Standard level XP equals sum of 10 Retro levels`() {
        val exp = loadExpConfig()
        val formula = FormulaService(exp)

        val stdLv0 = formula.xpToNextLevel(0, retroMode = false)
        var retroSum = 0
        for (x in 1..10) {
            retroSum += formula.xpToNextLevel(x, retroMode = true)
        }
        assertEquals(retroSum, stdLv0)
        assertTrue(stdLv0 > 10000)

        val (lvl, rem) = formula.levelAndRemainderForTotalXp(stdLv0.toDouble() + 250.0, 100, retroMode = false)
        assertEquals(1, lvl)
        assertEquals(250.0, rem, 0.001)
    }

    @Test
    fun `Exponential curve computes strictly positive increasing XP`() {
        val yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/experience.yml").toFile())
        yaml.set("formula.curve", "EXPONENTIAL")
        val exp = ExperienceConfig(initialYaml = yaml)
        assertEquals(FormulaCurve.EXPONENTIAL, exp.curve)

        val formula = FormulaService(exp)
        val lv1 = formula.xpToNextLevel(1, retroMode = true)
        val lv50 = formula.xpToNextLevel(50, retroMode = true)
        assertTrue(lv50 > lv1)
    }

    @Test
    fun `Child skills average parent skill levels and power level excludes child skills`() {
        val state = OpusPlayerState(UUID.randomUUID(), "Miner")
        state.setSkill(SkillType.MINING, 60, 0.0)
        state.setSkill(SkillType.REPAIR, 40, 0.0)
        state.setSkill(SkillType.FISHING, 20, 0.0)

        assertEquals(50, state.getLevel(SkillType.SMELTING))
        assertEquals(30, state.getLevel(SkillType.SALVAGE))
        assertEquals(120, state.powerLevel())
    }

    @Test
    fun `RanksConfig loads all SubSkills for both Standard and Retro modes`() {
        val ranks = loadRanksConfig()
        for (sub in SubSkillType.entries) {
            val std = ranks.thresholds(sub, retroMode = false)
            val retro = ranks.thresholds(sub, retroMode = true)
            assertTrue(std.isNotEmpty(), "Missing standard rank thresholds for $sub")
            assertTrue(retro.isNotEmpty(), "Missing retro rank thresholds for $sub")
            assertEquals(sub.maxRanks, std.size, "Rank count mismatch (standard) for $sub")
            assertEquals(sub.maxRanks, retro.size, "Rank count mismatch (retro) for $sub")
        }

        assertEquals(0, ranks.rankForLevel(SubSkillType.ALCHEMY_CONCOCTIONS, 0, retroMode = false) - 1)
        assertEquals(8, ranks.rankForLevel(SubSkillType.ALCHEMY_CONCOCTIONS, 100, retroMode = false))
        assertEquals(8, ranks.rankForLevel(SubSkillType.ALCHEMY_CONCOCTIONS, 1000, retroMode = true))
    }
}
