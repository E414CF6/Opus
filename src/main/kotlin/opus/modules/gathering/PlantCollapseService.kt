package opus.modules.gathering

import opus.core.OpusContext
import opus.events.XpGainReason
import opus.rpg
import opus.skills.SkillType
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies cascading multi-block plant breaks (sugar cane, cactus, bamboo, kelp) on subsequent region ticks
 * before paying Herbalism XP, preventing break-and-replace column XP exploits while rewarding full harvests.
 */
class PlantCollapseService(private val context: OpusContext) {

    private data class BlockPos(val worldId: UUID, val x: Int, val y: Int, val z: Int) {
        companion object {
            fun of(block: Block) = BlockPos(block.world.uid, block.x, block.y, block.z)
        }
    }

    private data class PendingBlock(
        val block: Block,
        val pos: BlockPos,
        val expectedType: Material,
        val xp: Double,
    )

    private val claimedPositions = ConcurrentHashMap<BlockPos, UUID>()

    fun isTallPlant(material: Material): Boolean = when (material) {
        Material.SUGAR_CANE,
        Material.CACTUS,
        Material.BAMBOO,
        Material.KELP,
        Material.KELP_PLANT -> true

        else -> false
    }

    fun revokeClaim(block: Block) {
        claimedPositions.remove(BlockPos.of(block))
    }

    fun clearAll() {
        claimedPositions.clear()
    }

    fun trackColumnCollapse(player: Player, origin: Block) {
        val type = origin.type
        if (!isTallPlant(type)) return

        val maxBlocks = if (context.rpg.experienceConfig.limitTallPlantFarming) {
            when (type) {
                Material.SUGAR_CANE, Material.CACTUS -> 3
                Material.BAMBOO -> 16
                else -> 25
            }
        } else {
            64
        }

        val taskId = UUID.randomUUID()
        val pending = mutableListOf<PendingBlock>()
        var cursor = origin.getRelative(BlockFace.UP)
        var scanned = 0
        while (scanned < maxBlocks) {
            val mat = cursor.type
            val matches =
                mat == type || (type == Material.KELP_PLANT && mat == Material.KELP) || (type == Material.KELP && mat == Material.KELP_PLANT)
            if (!matches) break
            scanned++
            if (!context.rpg.placedBlocks.isPlayerPlaced(cursor)) {
                val xp = context.rpg.experienceConfig.blockXp(SkillType.HERBALISM, mat)
                if (xp > 0.0) {
                    val pos = BlockPos.of(cursor)
                    if (claimedPositions.putIfAbsent(pos, taskId) == null) {
                        pending += PendingBlock(cursor, pos, mat, xp)
                    }
                }
            }
            cursor = cursor.getRelative(BlockFace.UP)
        }

        if (pending.isEmpty()) return

        val maxRuns = pending.size + 4
        var runs = 0
        var accumulatedXp = 0.0

        context.scheduler.runAtLocationTimer(origin.location, 1L, 1L) { scheduled ->
            runs++
            val it = pending.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (claimedPositions[entry.pos] != taskId) {
                    it.remove()
                    continue
                }
                if (entry.block.type != entry.expectedType) {
                    accumulatedXp += entry.xp
                    context.rpg.placedBlocks.clearPlaced(entry.block)
                    claimedPositions.remove(entry.pos, taskId)
                    it.remove()
                }
            }
            if (pending.isEmpty() || runs >= maxRuns) {
                for (rem in pending) {
                    claimedPositions.remove(rem.pos, taskId)
                }
                pending.clear()
                scheduled.cancel()
                if (accumulatedXp > 0.0 && player.isOnline) {
                    context.rpg.progression.awardXp(player, SkillType.HERBALISM, accumulatedXp, XpGainReason.GATHERING)
                }
            }
        }
    }
}
