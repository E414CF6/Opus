package opus.skills

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.pow

/**
 * Computes XP requirements for Standard (0-100) and Retro (0-1000) curves with concurrent caching.
 */
class FormulaService(private val expConfig: ExperienceConfig) {

    private val retroCache = ConcurrentHashMap<Int, Int>()
    private val standardCache = ConcurrentHashMap<Int, Int>()

    fun clearCache() {
        retroCache.clear()
        standardCache.clear()
    }

    fun xpToNextLevel(level: Int, retroMode: Boolean, powerLevel: Int = 0): Int {
        val effectiveLevel = if (expConfig.cumulativeCurve && powerLevel > 0) powerLevel else level.coerceAtLeast(0)
        return if (retroMode) {
            retroCache.computeIfAbsent(effectiveLevel) { calculateRawXpNeeded(it) }
        } else {
            standardCache.computeIfAbsent(effectiveLevel) { key ->
                var sum = 0L
                val start = key * 10 + 1
                for (x in start until start + 10) {
                    sum += calculateRawXpNeeded(x)
                }
                sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
    }

    private fun calculateRawXpNeeded(level: Int): Int {
        val xp = when (expConfig.curve) {
            FormulaCurve.EXPONENTIAL -> {
                expConfig.expMultiplier * level.toDouble().pow(expConfig.expExponent) + expConfig.expBase
            }

            FormulaCurve.LINEAR -> {
                expConfig.linearBase + level * expConfig.linearMultiplier
            }
        }
        return floor(xp).toInt().coerceAtLeast(1)
    }

    fun totalXpForLevel(level: Int, retroMode: Boolean): Long {
        var total = 0L
        for (lvl in 0 until level.coerceAtLeast(0)) {
            total += xpToNextLevel(lvl, retroMode)
        }
        return total
    }

    fun levelAndRemainderForTotalXp(totalXp: Double, maxLevel: Int, retroMode: Boolean): Pair<Int, Double> {
        var remaining = totalXp.coerceAtLeast(0.0)
        var level = 0
        val cap = if (maxLevel <= 0) Int.MAX_VALUE else maxLevel
        while (level < cap) {
            val needed = xpToNextLevel(level, retroMode).toDouble()
            if (remaining < needed) break
            remaining -= needed
            level++
        }
        return level to remaining
    }
}
