package opus

import opus.skills.ExperienceConfig
import opus.modules.crafting.RepairConfig
import opus.modules.gathering.TreasureConfig
import opus.skills.SkillType
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ConfigAndLootTest {

    @Test
    fun `experience yml maps Paper 26_3 blocks and entities cleanly`() {
        val yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/experience.yml").toFile())
        val cfg = ExperienceConfig(initialYaml = yaml)

        assertTrue(cfg.blockXp(SkillType.MINING, Material.ANCIENT_DEBRIS) > 0.0)
        assertTrue(cfg.blockXp(SkillType.MINING, Material.COPPER_ORE) > 0.0)
        assertTrue(cfg.blockXp(SkillType.WOODCUTTING, Material.PALE_OAK_LOG) > 0.0)
        assertTrue(cfg.blockXp(SkillType.HERBALISM, Material.PITCHER_PLANT) > 0.0)
        assertTrue(cfg.blockXp(SkillType.EXCAVATION, Material.MUD) > 0.0)
        assertTrue(cfg.tamingXp(EntityType.NAUTILUS) > 0.0)
        assertTrue(cfg.combatMultiplier(EntityType.WARDEN) > 1.0)
        assertTrue(cfg.combatMultiplier(EntityType.BREEZE) > 1.0)
    }

    @Test
    fun `treasures yml parses excavation and fishing tables`() {
        val yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/treasures.yml").toFile())
        val cfg = TreasureConfig(initialYaml = yaml)

        assertTrue(cfg.excavationTreasures.isNotEmpty())
        assertNotNull(cfg.rollFishingTreasure(1))
        assertNotNull(cfg.rollFishingTreasure(8))
    }

    @Test
    fun `repair yml resolves explicit overrides and infers Copper and Spear gear`() {
        val yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/repair.yml").toFile())
        val cfg = RepairConfig(initialYaml = yaml)

        val mace = cfg.findRepairable(Material.MACE)
        assertNotNull(mace)
        assertEquals(Material.BREEZE_ROD, mace!!.repairMaterial)

        val copperSword = cfg.findRepairable(Material.COPPER_SWORD)
        assertNotNull(copperSword)
        assertEquals(Material.COPPER_INGOT, copperSword!!.repairMaterial)

        val diamondSpear = cfg.findRepairable(Material.DIAMOND_SPEAR)
        assertNotNull(diamondSpear)
        assertEquals(Material.DIAMOND, diamondSpear!!.repairMaterial)

        val copperSalvage = cfg.findSalvageable(Material.COPPER_CHESTPLATE)
        assertNotNull(copperSalvage)
        assertEquals(Material.COPPER_INGOT, copperSalvage!!.salvageMaterial)
        assertEquals(8, copperSalvage.maxQuantity)
    }
}
