package opus.modules.combat

import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Pure combat formulas for Limit Break armor-quality scaling and Paper 26.3 attack-strength derivation.
 */
object CombatLimitBreak {

    /**
     * Computes Limit Break raw bonus damage adjusted by target armor quality level.
     * PvE mobs pass armorQualityLevel = 1000 (no nerf).
     */
    fun calculateBonus(rank: Int, armorQualityLevel: Int): Double {
        if (rank <= 0) return 0.0
        var bonus = rank.toDouble()
        when {
            armorQualityLevel <= 4 -> bonus *= 0.25
            armorQualityLevel <= 8 -> bonus *= 0.50
            armorQualityLevel <= 12 -> bonus *= 0.75
        }
        return bonus.toInt().toDouble()
    }

    /**
     * Sums armor quality score across a defending player's equipped armor pieces.
     */
    fun armorQualityOf(player: Player): Int =
        player.inventory.armorContents.sumOf { piece ->
            if (piece == null) 0 else armorPieceQuality(piece.type)
        }

    fun armorPieceQuality(material: Material): Int {
        val name = material.name
        return when {
            name.startsWith("NETHERITE_") -> 5
            name.startsWith("DIAMOND_") -> 4
            name.startsWith("IRON_") || name.startsWith("COPPER_") || name.startsWith("TURTLE_") -> 3
            name.startsWith("CHAINMAIL_") || name.startsWith("GOLDEN_") -> 2
            name.startsWith("LEATHER_") -> 1
            else -> 0
        }
    }

    /**
     * Paper 26.1.2+ resets the attack cooldown ticker before EntityDamageByEntityEvent fires.
     * Recover the committed charge scale from `rawEventDamage / maxBaseAttackDamage` clamped to `[0.0, 1.0]`.
     */
    fun deriveAttackStrengthScale(
        rawEventDamage: Double,
        maxBaseAttackDamage: Double,
        isSweepAttack: Boolean,
        adjustForCooldown: Boolean,
    ): Double {
        if (!adjustForCooldown || isSweepAttack || maxBaseAttackDamage <= 0.0) return 1.0
        return (rawEventDamage / maxBaseAttackDamage).coerceIn(0.0, 1.0)
    }
}
