package opus

import opus.skills.ExperienceConfig
import opus.skills.LevelUpCommandService
import opus.skills.LevelUpRunAs
import opus.skills.OpusPlayerState
import opus.skills.PerksService
import opus.skills.SkillType
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.util.*

class PerksAndLevelUpTest {

    @Test
    fun `PerksService prioritizes customboost and highest XP tier`() {
        val yaml = YamlConfiguration.loadConfiguration(
            StringReader(
                """
                multipliers:
                  custom-xp-perk-boost: 1.75
                """.trimIndent(),
            ),
        )
        val expConfig = ExperienceConfig(initialYaml = yaml)
        val perks = PerksService(expConfig)

        assertEquals(1.0, perks.resolveXpMultiplier(SkillType.MINING) { false }, 1e-9)
        assertEquals(
            4.0,
            perks.resolveXpMultiplier(SkillType.MINING) {
                it == "opus.perks.xp.double.mining" || it == "opus.perks.xp.quadruple.all"
            },
            1e-9,
        )
        assertEquals(
            1.75,
            perks.resolveXpMultiplier(SkillType.MINING) {
                it == "opus.perks.xp.customboost.mining" || it == "opus.perks.xp.quadruple.all"
            },
            1e-9,
        )
        assertEquals(
            1.25,
            perks.resolveXpMultiplier(SkillType.WOODCUTTING) { it == "opus.perks.xp.125percent.woodcutting" },
            1e-9,
        )
    }

    @Test
    fun `PerksService computes cooldown duration and lucky perks`() {
        val perks = PerksService(ExperienceConfig(initialYaml = YamlConfiguration()))

        assertEquals(120, perks.applyCooldownPerk(240) { it == "opus.perks.cooldowns.halved" })
        assertEquals(160, perks.applyCooldownPerk(240) { it == "opus.perks.cooldowns.thirded" })
        assertEquals(180, perks.applyCooldownPerk(240) { it == "opus.perks.cooldowns.quartered" })
        assertEquals(240, perks.applyCooldownPerk(240) { false })

        assertEquals(22, perks.applyActivationDurationPerk(10) { it == "opus.perks.activationtime.twelveseconds" })
        assertEquals(18, perks.applyActivationDurationPerk(10) { it == "opus.perks.activationtime.eightseconds" })
        assertEquals(14, perks.applyActivationDurationPerk(10) { it == "opus.perks.activationtime.fourseconds" })
        assertEquals(10, perks.applyActivationDurationPerk(10) { false })

        assertTrue(perks.hasLuckyPerk(SkillType.EXCAVATION) { it == "opus.perks.lucky.excavation" })
        assertTrue(perks.hasLuckyPerk(SkillType.FISHING) { it == "opus.perks.lucky.all" })
        assertFalse(perks.hasLuckyPerk(SkillType.MINING) { it == "opus.perks.lucky.swords" })
    }

    @Test
    fun `LevelUpCommandService triggers multi-level and power-level milestones with placeholders`() {
        val yaml = YamlConfiguration.loadConfiguration(
            StringReader(
                """
                level_up_commands:
                  mining_milestones:
                    enabled: true
                    skills: [mining]
                    levels: [10, 50, 100]
                    run_as: CONSOLE
                    commands:
                      - "give {@player} diamond 1 # {@skill} Lv.{@level} (Mining={@mining_level})"
                  global_hundred:
                    enabled: true
                    skills: [all]
                    levels: [100]
                    run_as: PLAYER
                    commands:
                      - "me reached {@level} in {@skill}!"
                  power_milestone:
                    enabled: true
                    power_levels: [100, 200]
                    run_as: CONSOLE
                    commands:
                      - "say {@player} reached power {@power_level}!"
                  disabled_entry:
                    enabled: false
                    skills: [all]
                    levels: [10, 50, 100]
                    run_as: CONSOLE
                    commands:
                      - "should not run"
                """.trimIndent(),
            ),
        )
        val service = LevelUpCommandService(initialYaml = yaml)
        val state = OpusPlayerState(UUID.randomUUID(), "Steve")
        state.setSkill(SkillType.MINING, 100, 0.0)

        // Jump from level 1 -> 100 and power 50 -> 149 (crosses 10, 50, 100 skill and 100 power)
        val cmds = service.resolveCommandsForLevelUp(
            playerName = "Steve",
            state = state,
            skill = SkillType.MINING,
            oldLevel = 1,
            newLevel = 100,
            oldPower = 50,
            newPower = 149,
            language = "en",
        )

        assertEquals(5, cmds.size)
        assertEquals(LevelUpRunAs.CONSOLE, cmds[0].runAs)
        assertEquals("give Steve diamond 1 # Mining Lv.10 (Mining=100)", cmds[0].command)
        assertEquals("give Steve diamond 1 # Mining Lv.50 (Mining=100)", cmds[1].command)
        assertEquals("give Steve diamond 1 # Mining Lv.100 (Mining=100)", cmds[2].command)
        assertEquals(LevelUpRunAs.PLAYER, cmds[3].runAs)
        assertEquals("me reached 100 in Mining!", cmds[3].command)
        assertEquals(LevelUpRunAs.CONSOLE, cmds[4].runAs)
        assertEquals("say Steve reached power 100!", cmds[4].command)
    }
}
