package opus.core.world

import opus.core.config.ExperienceConfig
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Enderman
import org.bukkit.entity.Endermite
import org.bukkit.entity.Entity
import org.bukkit.entity.Snowman
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Modern Paper 26.3 Chunk-PDC placed-block tracker replacing mcMMO's legacy region backup files.
 * Packs `(x & 15, y, z & 15)` into a 64-bit integer stored inside `Chunk.persistentDataContainer`.
 * All accesses happen on the chunk's owning region thread, making it 100% Folia-safe.
 */
class PlacedBlockTracker(
    private val plugin: Plugin,
    private val experienceConfig: ExperienceConfig? = null,
) : Listener {

    private val chunkKey = NamespacedKey(plugin, "placed_blocks")
    private val spawnReasonKey = NamespacedKey(plugin, "spawn_reason")

    fun isPlayerPlaced(block: Block): Boolean {
        val packed = pack(block.x, block.y, block.z)
        return readSet(block.chunk).contains(packed)
    }

    fun markPlaced(block: Block) {
        val chunk = block.chunk
        val packed = pack(block.x, block.y, block.z)
        val arr = chunk.persistentDataContainer.get(chunkKey, PersistentDataType.LONG_ARRAY)
        if (arr == null) {
            chunk.persistentDataContainer.set(chunkKey, PersistentDataType.LONG_ARRAY, longArrayOf(packed))
            return
        }
        if (arr.contains(packed)) return
        val next = arr.copyOf(arr.size + 1)
        next[arr.size] = packed
        chunk.persistentDataContainer.set(chunkKey, PersistentDataType.LONG_ARRAY, next)
    }

    fun clearPlaced(block: Block) {
        val chunk = block.chunk
        val packed = pack(block.x, block.y, block.z)
        val arr = chunk.persistentDataContainer.get(chunkKey, PersistentDataType.LONG_ARRAY) ?: return
        val idx = arr.indexOfFirst { it == packed }
        if (idx < 0) return
        if (arr.size == 1) {
            chunk.persistentDataContainer.remove(chunkKey)
            return
        }
        val next = LongArray(arr.size - 1)
        var w = 0
        for (i in arr.indices) {
            if (i != idx) next[w++] = arr[i]
        }
        chunk.persistentDataContainer.set(chunkKey, PersistentDataType.LONG_ARRAY, next)
    }

    fun getSpawnReason(entity: Entity): String? =
        entity.persistentDataContainer.get(spawnReasonKey, PersistentDataType.STRING)

    fun setSpawnReason(entity: Entity, reason: String) {
        entity.persistentDataContainer.set(spawnReasonKey, PersistentDataType.STRING, reason)
    }

    private fun readSet(chunk: Chunk): LongArray =
        chunk.persistentDataContainer.get(chunkKey, PersistentDataType.LONG_ARRAY) ?: EMPTY_ARRAY

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val type = event.block.type
        if (type == Material.FIRE || type == Material.AIR) return
        markPlaced(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMultiPlace(event: BlockMultiPlaceEvent) {
        for (state in event.replacedBlockStates) {
            markPlaced(state.block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        // Defer clear only if needed; callers check isPlayerPlaced at NORMAL/HIGH priority before MONITOR clears it
        clearPlaced(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        shiftBlocks(event.blocks, event.direction)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        shiftBlocks(event.blocks, event.direction)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        for (state in event.blocks) {
            clearPlaced(state.block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        if (experienceConfig?.lavaStoneCobbleGuard == false) return
        val newType = event.newState.type
        if (newType == Material.COBBLESTONE || newType == Material.STONE || newType == Material.OBSIDIAN || newType == Material.BASALT) {
            markPlaced(event.block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityBlockForm(event: EntityBlockFormEvent) {
        if (experienceConfig?.snowGolemExcavationGuard == false) return
        if (event.entity is Snowman && event.newState.type == Material.SNOW) {
            markPlaced(event.block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityTargetLiving(event: EntityTargetLivingEntityEvent) {
        if (experienceConfig?.endermanEndermiteGuard == false) return
        if (event.entity is Enderman && event.target is Endermite) {
            setSpawnReason(event.entity, "ENDERMITE_FARM")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        val reason = when (event.spawnReason) {
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER -> "SPAWNER"

            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG -> "SPAWN_EGG"

            CreatureSpawnEvent.SpawnReason.NETHER_PORTAL -> "NETHER_PORTAL"
            CreatureSpawnEvent.SpawnReason.BREEDING -> "BREEDING"
            else -> return
        }
        setSpawnReason(event.entity, reason)
    }

    private fun shiftBlocks(blocks: List<Block>, direction: BlockFace) {
        if (blocks.isEmpty()) return
        val placedMoved = blocks.filter { isPlayerPlaced(it) }
        if (placedMoved.isEmpty()) return
        for (b in placedMoved) {
            clearPlaced(b)
        }
        for (b in placedMoved) {
            markPlaced(b.getRelative(direction))
        }
    }

    companion object {
        private val EMPTY_ARRAY = LongArray(0)

        /**
         * Packs local chunk X (0..15), Z (0..15), and signed Y into a single 64-bit integer.
         */
        fun pack(x: Int, y: Int, z: Int): Long {
            val lx = (x and 0xF).toLong()
            val lz = (z and 0xF).toLong()
            val shiftedY = (y + 2048L) and 0xFFFFL
            return (shiftedY shl 8) or (lx shl 4) or lz
        }

        fun unpackLocal(packed: Long): Triple<Int, Int, Int> {
            val lz = (packed and 0xFL).toInt()
            val lx = ((packed ushr 4) and 0xFL).toInt()
            val y = (((packed ushr 8) and 0xFFFFL).toInt()) - 2048
            return Triple(lx, y, lz)
        }
    }
}
