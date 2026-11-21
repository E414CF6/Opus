package opus.modules.gathering

import opus.core.OpusContext
import opus.core.module.OpusModule
import opus.events.XpGainReason
import opus.skills.SkillType
import opus.skills.SubSkillType
import opus.skills.SuperAbilityType
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Handles all 5 Gathering skills: Mining, Woodcutting, Herbalism, Excavation, and Fishing.
 */
class GatheringModule(private val context: OpusContext) : OpusModule {
    override val id = "gathering"
    override val description = "Mining, Woodcutting, Herbalism, Excavation, and Fishing RPG mechanics"

    val treasures = TreasureConfig(context.plugin)
    private val plantCollapse = PlantCollapseService(context)
    private val listener = GatheringListener(context, treasures, plantCollapse)

    override fun onReload() {
        treasures.reload()
    }

    override fun onDisable() {
        plantCollapse.clearAll()
    }

    override fun listeners(): List<Listener> = listOf(listener)
}

class GatheringListener(
    private val context: OpusContext,
    private val treasures: TreasureConfig,
    private val plantCollapse: PlantCollapseService,
) : Listener {

    private data class FishSpotTracker(var location: Location, var count: Int)

    private val fishSpots = ConcurrentHashMap<UUID, FishSpotTracker>()
    private val blastDetonatorByTnt = ConcurrentHashMap<UUID, UUID>()
    private val reentrantGuard = ConcurrentHashMap.newKeySet<UUID>()

    private fun isPickaxe(mat: Material): Boolean = Tag.ITEMS_PICKAXES.isTagged(mat)
    private fun isAxe(mat: Material): Boolean = Tag.ITEMS_AXES.isTagged(mat)
    private fun isShovel(mat: Material): Boolean = Tag.ITEMS_SHOVELS.isTagged(mat)
    private fun isHoe(mat: Material): Boolean = Tag.ITEMS_HOES.isTagged(mat)
    private fun isSword(mat: Material): Boolean = Tag.ITEMS_SWORDS.isTagged(mat)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val item = event.item ?: ItemStack(Material.AIR)

        // Blast Mining remote TNT detonation with Flint & Steel
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            if (item.type == Material.FLINT_AND_STEEL && player.isSneaking) {
                if (tryBlastMining(player)) {
                    event.isCancelled = true
                    return
                }
            }
        }

        // Green Thumb & Shroom Thumb right-click interactions
        val clicked = event.clickedBlock
        if (event.action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (handleHerbalismInteract(player, clicked, item)) {
                return
            }
        }

        // Leaf Blower left-click instant break
        if (event.action == Action.LEFT_CLICK_BLOCK && clicked != null && isAxe(item.type)) {
            if (Tag.LEAVES.isTagged(clicked.type)) {
                val state = context.players.getCached(player)
                if (context.ranks.hasUnlocked(state, SubSkillType.WOODCUTTING_LEAF_BLOWER, player)) {
                    if (reentrantGuard.add(player.uniqueId)) {
                        try {
                            clicked.breakNaturally(item)
                            player.world.playSound(clicked.location, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.7f, 1.1f)
                        } finally {
                            reentrantGuard.remove(player.uniqueId)
                        }
                    }
                    return
                }
            }
        }

        // Tool readying on right click
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            if (clicked != null && (clicked.blockData is org.bukkit.block.data.Openable || clicked.state is org.bukkit.inventory.InventoryHolder) && !player.isSneaking) return
            val skill = when {
                isPickaxe(item.type) -> SkillType.MINING
                isAxe(item.type) -> SkillType.WOODCUTTING
                isShovel(item.type) -> SkillType.EXCAVATION
                isHoe(item.type) -> SkillType.HERBALISM
                else -> null
            } ?: return
            context.progression.readyTool(player, skill)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        plantCollapse.revokeClaim(event.block)
        if (!reentrantGuard.add(player.uniqueId)) return
        try {
            val block = event.block
            val tool = player.inventory.itemInMainHand
            val isPlaced = context.placedBlocks.isPlayerPlaced(block)
            val state = context.players.getCached(player)

            // Hylian Luck (sword breaking flowers/bushes/pots)
            if (isSword(tool.type) && !isPlaced && isHylianLuckBlock(block.type)) {
                handleHylianLuck(player, block)
            }

            // Check Super Ability activation on left-click break
            when {
                isPickaxe(tool.type) && context.experienceConfig.isSkillBlock(SkillType.MINING, block.type) -> {
                    if (context.progression.tryActivateAbility(player, SkillType.MINING)) {
                        applyHasteBuff(player, 1)
                    }
                    handleMining(player, block, tool, isPlaced)
                }

                isShovel(tool.type) && context.experienceConfig.isSkillBlock(SkillType.EXCAVATION, block.type) -> {
                    if (context.progression.tryActivateAbility(player, SkillType.EXCAVATION)) {
                        applyHasteBuff(player, 1)
                    }
                    handleExcavation(player, block, isPlaced)
                }

                isAxe(tool.type) && context.experienceConfig.isSkillBlock(SkillType.WOODCUTTING, block.type) -> {
                    if (context.progression.tryActivateAbility(player, SkillType.WOODCUTTING)) {
                        handleTreeFeller(player, block, tool)
                    } else {
                        handleWoodcutting(player, block, tool, isPlaced)
                    }
                }

                context.experienceConfig.isSkillBlock(SkillType.HERBALISM, block.type) -> {
                    if (isHoe(tool.type)) {
                        context.progression.tryActivateAbility(player, SkillType.HERBALISM)
                    }
                    handleHerbalism(player, block, tool, isPlaced)
                }
            }
        } finally {
            reentrantGuard.remove(player.uniqueId)
        }
    }

    private fun applyHasteBuff(player: Player, amplifier: Int) {
        val level = context.players.getCached(player).getLevel(SkillType.MINING)
        val durTicks = context.probability.abilityDurationSeconds(level) * 20
        player.addPotionEffect(PotionEffect(PotionEffectType.HASTE, durTicks, amplifier, false, false, true))
    }

    private fun handleMining(player: Player, block: Block, tool: ItemStack, isPlaced: Boolean) {
        if (isPlaced) return
        if (!context.skillsConfig.isPveEnabled(SkillType.MINING)) return
        val xp = context.experienceConfig.blockXp(SkillType.MINING, block.type)
        if (xp <= 0.0) return

        val state = context.players.getCached(player)
        val level = state.getLevel(SkillType.MINING)
        val superActive = state.isAbilityActive(SuperAbilityType.SUPER_BREAKER)

        // Flux Mining check (smelting child skill instant smelt)
        val fluxActive = handleFluxMining(player, block, tool)

        if (!fluxActive) {
            val silk = tool.containsEnchantment(Enchantment.SILK_TOUCH)
            val allowSilk = context.skillsConfig.boolean("mining.double-drops.silk-touch", true)
            if (!silk || allowSilk) {
                var multiplier = 1
                if (context.ranks.hasUnlocked(state, SubSkillType.MINING_DOUBLE_DROPS, player)) {
                    val maxLvl = context.skillsConfig.maxBonusLevel("mining.double-drops", context.config.retroMode)
                    val maxChance = context.skillsConfig.double("mining.double-drops.chance-max", 100.0)
                    val chance = context.probability.linearChancePercent(level, maxLvl, maxChance, player)
                    if (context.probability.rollPercent(chance)) multiplier = 2
                }
                if (context.ranks.hasUnlocked(state, SubSkillType.MINING_MOTHER_LODE, player)) {
                    val maxLvl =
                        context.skillsConfig.maxBonusLevel("mining.mother-lode", context.config.retroMode, 1000, 10000)
                    val maxChance = context.skillsConfig.double("mining.mother-lode.chance-max", 50.0)
                    val chance = context.probability.linearChancePercent(level, maxLvl, maxChance, player)
                    if (context.probability.rollPercent(chance)) multiplier = 3
                }
                if (superActive && context.skillsConfig.boolean("mining.super-breaker.allow-triple-drops", true)) {
                    multiplier = maxOf(multiplier, 3)
                }
                if (multiplier > 1) {
                    dropBonusCopies(block, tool, multiplier - 1)
                }
            }
        }

        context.progression.awardXp(player, SkillType.MINING, xp, XpGainReason.GATHERING)
    }

    private fun handleFluxMining(player: Player, block: Block, tool: ItemStack): Boolean {
        val state = context.players.getCached(player)
        if (!context.ranks.hasUnlocked(state, SubSkillType.SMELTING_FLUX_MINING, player)) return false
        val smelted = when (block.type) {
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE -> Material.IRON_INGOT
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE -> Material.GOLD_INGOT
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE -> Material.COPPER_INGOT
            Material.ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP
            else -> return false
        }
        val chance = context.skillsConfig.double("smelting.flux-mining.chance", 33.0)
        if (!context.probability.rollPercent(chance)) return false

        val dropAmount = if (tool.containsEnchantment(Enchantment.FORTUNE)) {
            1 + ThreadLocalRandom.current().nextInt(tool.getEnchantmentLevel(Enchantment.FORTUNE) + 1)
        } else 1
        block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), ItemStack(smelted, dropAmount))
        context.locale.sendActionBar(player, "gameplay.smelting-flux", "item" to smelted.name)
        context.progression.awardXp(
            player,
            SkillType.SMELTING,
            context.experienceConfig.smeltingXp(block.type),
            XpGainReason.CRAFTING
        )
        return true
    }

    private fun tryBlastMining(player: Player): Boolean {
        val state = context.players.getCached(player)
        if (!context.ranks.hasUnlocked(state, SubSkillType.MINING_BLAST_MINING, player)) return false
        val maxDist = context.skillsConfig.double("mining.blast-mining.remote-detonation-distance", 100.0)
        val targetBlock = player.getTargetBlockExact(maxDist.toInt().coerceIn(5, 120)) ?: return false
        if (targetBlock.type != Material.TNT) return false

        val ability = SuperAbilityType.SUPER_BREAKER
        val cd = state.getCooldownRemainingMillis(ability)
        if (cd > 0L && !player.hasPermission("opus.bypass.cooldown")) return false

        targetBlock.type = Material.AIR
        val tnt = targetBlock.world.spawn(targetBlock.location.add(0.5, 0.5, 0.5), TNTPrimed::class.java)
        tnt.fuseTicks = 0
        if (context.ranks.hasUnlocked(state, SubSkillType.MINING_BIGGER_BOMBS, player)) {
            val rank = context.ranks.getRank(state, SubSkillType.MINING_BLAST_MINING)
            val radiusMod = context.skillsConfig.doubleAtRank("mining.blast-mining.blast-radius-modifier", rank, 1.0)
            tnt.yield = (tnt.yield + radiusMod.toFloat()).coerceAtMost(10.0f)
        }
        blastDetonatorByTnt[tnt.uniqueId] = player.uniqueId
        context.locale.sendActionBar(player, "gameplay.mining-blast-detonate")
        return true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlastMiningExplode(event: EntityExplodeEvent) {
        val tnt = event.entity as? TNTPrimed ?: return
        val ownerUuid = blastDetonatorByTnt.remove(tnt.uniqueId) ?: return
        val player = org.bukkit.Bukkit.getPlayer(ownerUuid) ?: return
        val state = context.players.getCached(player)
        val rank = context.ranks.getRank(state, SubSkillType.MINING_BLAST_MINING)
        val oreBonus = context.skillsConfig.doubleAtRank("mining.blast-mining.ore-bonus", rank, 35.0)

        for (block in event.blockList()) {
            if (context.placedBlocks.isPlayerPlaced(block)) continue
            val xp = context.experienceConfig.blockXp(SkillType.MINING, block.type)
            if (xp > 0.0) {
                context.progression.awardXp(player, SkillType.MINING, xp, XpGainReason.GATHERING)
                if (context.probability.rollPercent(oreBonus)) {
                    for (drop in block.drops) {
                        block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), drop)
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlastDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION &&
            event.cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
        ) return
        val player = event.entity as? Player ?: return

        // Cap PvP blast mining damage against other players to 24.0
        if (event is EntityDamageByEntityEvent && event.damager is TNTPrimed) {
            val owner = blastDetonatorByTnt[event.damager.uniqueId]
            if (owner != null && owner != player.uniqueId) {
                event.damage = event.damage.coerceAtMost(24.0)
                return
            }
        }

        val state = context.players.getCached(player)
        if (!context.ranks.hasUnlocked(state, SubSkillType.MINING_DEMOLITIONS_EXPERTISE, player)) return
        val rank = context.ranks.getRank(state, SubSkillType.MINING_BLAST_MINING)
        if (rank <= 0) return
        val reductionPct = context.skillsConfig.doubleAtRank("mining.blast-mining.blast-damage-decrease", rank, 0.0)
        if (reductionPct > 0.0) {
            event.damage = (event.damage * (1.0 - reductionPct / 100.0)).coerceAtLeast(0.0)
            if (event.damage <= 0.0) {
                event.isCancelled = true
            }
        }
    }

    private fun handleWoodcutting(player: Player, block: Block, tool: ItemStack, isPlaced: Boolean) {
        if (isPlaced) return
        val xp = context.experienceConfig.blockXp(SkillType.WOODCUTTING, block.type)
        if (xp <= 0.0) return

        val state = context.players.getCached(player)
        val level = state.getLevel(SkillType.WOODCUTTING)
        var multiplier = 1

        if (context.ranks.hasUnlocked(state, SubSkillType.WOODCUTTING_HARVEST_LUMBER, player)) {
            val maxLvl = context.skillsConfig.maxBonusLevel("woodcutting.harvest-lumber", context.config.retroMode)
            val maxChance = context.skillsConfig.double("woodcutting.harvest-lumber.chance-max", 100.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player
                    )
                )
            ) {
                multiplier = 2
            }
        }
        if (context.ranks.hasUnlocked(state, SubSkillType.WOODCUTTING_CLEAN_CUTS, player)) {
            val maxLvl =
                context.skillsConfig.maxBonusLevel("woodcutting.clean-cuts", context.config.retroMode, 1000, 10000)
            val maxChance = context.skillsConfig.double("woodcutting.clean-cuts.chance-max", 50.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player
                    )
                )
            ) {
                multiplier = 3
            }
        }
        if (multiplier > 1) {
            dropBonusCopies(block, tool, multiplier - 1)
        }
        context.progression.awardXp(player, SkillType.WOODCUTTING, xp, XpGainReason.GATHERING)
    }

    private fun handleTreeFeller(player: Player, startBlock: Block, tool: ItemStack) {
        val maxLogs = context.skillsConfig.int("woodcutting.tree-feller.max-logs", 500)
        val visited = HashSet<Block>()
        val queue = ArrayDeque<Block>()
        queue.add(startBlock)
        visited.add(startBlock)

        val logs = mutableListOf<Block>()
        while (queue.isNotEmpty() && logs.size < maxLogs) {
            val current = queue.removeFirst()
            if (context.experienceConfig.isSkillBlock(SkillType.WOODCUTTING, current.type) &&
                !context.placedBlocks.isPlayerPlaced(current)
            ) {
                logs += current
                for (dx in -1..1) {
                    for (dy in 0..1) {
                        for (dz in -1..1) {
                            if (dx == 0 && dy == 0 && dz == 0) continue
                            val neighbor = current.getRelative(dx, dy, dz)
                            if (visited.add(neighbor) &&
                                (context.experienceConfig.isSkillBlock(SkillType.WOODCUTTING, neighbor.type) ||
                                        Tag.LEAVES.isTagged(neighbor.type))
                            ) {
                                queue.add(neighbor)
                            }
                        }
                    }
                }
            }
        }

        val state = context.players.getCached(player)
        val knockRank = context.ranks.getRank(state, SubSkillType.WOODCUTTING_KNOCK_ON_WOOD)
        val dropOrbs =
            knockRank > 0 && context.skillsConfig.boolean("woodcutting.tree-feller.add-xp-orbs-to-drops", true)
        var totalXp = 0.0

        for (log in logs) {
            val xp = context.experienceConfig.blockXp(SkillType.WOODCUTTING, log.type)
            totalXp += if (context.experienceConfig.treeFellerReducedXp && log != startBlock) xp * 0.5 else xp
            if (log != startBlock) {
                log.breakNaturally(tool)
            }
            if (dropOrbs && ThreadLocalRandom.current().nextInt(10) == 0) {
                log.world.spawn(log.location.add(0.5, 0.5, 0.5), ExperienceOrb::class.java).experience = knockRank
            }
        }
        context.progression.awardXp(player, SkillType.WOODCUTTING, totalXp, XpGainReason.GATHERING)
    }

    private fun handleHerbalism(player: Player, block: Block, tool: ItemStack, isPlaced: Boolean) {
        if (plantCollapse.isTallPlant(block.type)) {
            plantCollapse.trackColumnCollapse(player, block)
        }
        val data = block.blockData
        if (data is Ageable && data.age < data.maximumAge) return
        if (isPlaced && data !is Ageable) return

        val xp = context.experienceConfig.blockXp(SkillType.HERBALISM, block.type)
        if (xp <= 0.0) return

        val state = context.players.getCached(player)
        val level = state.getLevel(SkillType.HERBALISM)
        val greenTerra = state.isAbilityActive(SuperAbilityType.GREEN_TERRA)

        var multiplier = 1
        if (context.ranks.hasUnlocked(state, SubSkillType.HERBALISM_DOUBLE_DROPS, player)) {
            val maxLvl = context.skillsConfig.maxBonusLevel("herbalism.double-drops", context.config.retroMode)
            val maxChance = context.skillsConfig.double("herbalism.double-drops.chance-max", 100.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player
                    )
                )
            ) {
                multiplier = 2
            }
        }
        if (context.ranks.hasUnlocked(state, SubSkillType.HERBALISM_VERDANT_BOUNTY, player)) {
            val maxLvl =
                context.skillsConfig.maxBonusLevel("herbalism.verdant-bounty", context.config.retroMode, 1000, 10000)
            val maxChance = context.skillsConfig.double("herbalism.verdant-bounty.chance-max", 50.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player
                    )
                )
            ) {
                multiplier = 3
            }
        }
        if (greenTerra) {
            multiplier = maxOf(multiplier, 3)
        }
        if (multiplier > 1) {
            dropBonusCopies(block, tool, multiplier - 1)
        }

        // Green Thumb auto-replant for crops
        if (data is Ageable && context.ranks.hasUnlocked(state, SubSkillType.HERBALISM_GREEN_THUMB, player)) {
            val maxLvl = context.skillsConfig.maxBonusLevel("herbalism.green-thumb", context.config.retroMode)
            val maxChance = context.skillsConfig.double("herbalism.green-thumb.chance-max", 100.0)
            val chance =
                if (greenTerra) 100.0 else context.probability.linearChancePercent(level, maxLvl, maxChance, player)
            if (context.probability.rollPercent(chance)) {
                val cropType = block.type
                context.scheduler.runAtLocation(block.location) {
                    val fresh = block.blockData
                    if (block.type == Material.AIR) {
                        block.type = cropType
                        val ageable = block.blockData as? Ageable
                        if (ageable != null) {
                            ageable.age = 0
                            block.blockData = ageable
                        }
                    } else if (fresh is Ageable) {
                        fresh.age = 0
                        block.blockData = fresh
                    }
                }
            }
        }

        context.progression.awardXp(player, SkillType.HERBALISM, xp, XpGainReason.GATHERING)
    }

    private fun handleHerbalismInteract(player: Player, block: Block, item: ItemStack): Boolean {
        val state = context.players.getCached(player)
        val level = state.getLevel(SkillType.HERBALISM)

        if (item.type == Material.WHEAT_SEEDS &&
            context.ranks.hasUnlocked(state, SubSkillType.HERBALISM_GREEN_THUMB, player)
        ) {
            val target = when (block.type) {
                Material.COBBLESTONE -> Material.MOSSY_COBBLESTONE
                Material.STONE_BRICKS -> Material.MOSSY_STONE_BRICKS
                Material.DIRT -> Material.GRASS_BLOCK
                else -> null
            } ?: return false
            val maxLvl = context.skillsConfig.maxBonusLevel("herbalism.green-thumb", context.config.retroMode)
            val maxChance = context.skillsConfig.double("herbalism.green-thumb.chance-max", 100.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player
                    )
                )
            ) {
                block.type = target
                context.locale.sendActionBar(player, "gameplay.herbalism-green-thumb")
            }
            item.amount = (item.amount - 1).coerceAtLeast(0)
            return true
        }

        if ((item.type == Material.RED_MUSHROOM || item.type == Material.BROWN_MUSHROOM) &&
            (block.type == Material.DIRT || block.type == Material.GRASS_BLOCK) &&
            context.ranks.hasUnlocked(state, SubSkillType.HERBALISM_SHROOM_THUMB, player)
        ) {
            val maxLvl = context.skillsConfig.maxBonusLevel("herbalism.shroom-thumb", context.config.retroMode)
            val maxChance = context.skillsConfig.double("herbalism.shroom-thumb.chance-max", 50.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player
                    )
                )
            ) {
                block.type = Material.MYCELIUM
            }
            item.amount = (item.amount - 1).coerceAtLeast(0)
            return true
        }
        return false
    }

    private fun isHylianLuckBlock(mat: Material): Boolean =
        Tag.FLOWERS.isTagged(mat) || mat == Material.DEAD_BUSH || mat == Material.FERN || mat == Material.SHORT_GRASS

    private fun handleHylianLuck(player: Player, block: Block) {
        val state = context.players.getCached(player)
        if (!context.ranks.hasUnlocked(state, SubSkillType.HERBALISM_HYLIAN_LUCK, player)) return
        val maxLvl = context.skillsConfig.maxBonusLevel("herbalism.hylian-luck", context.config.retroMode)
        val maxChance = context.skillsConfig.double("herbalism.hylian-luck.chance-max", 10.0)
        val chance =
            context.probability.linearChancePercent(state.getLevel(SkillType.HERBALISM), maxLvl, maxChance, player)
        if (!context.probability.rollPercent(chance)) return

        val drop = when (ThreadLocalRandom.current().nextInt(4)) {
            0 -> Material.MELON_SEEDS
            1 -> Material.PUMPKIN_SEEDS
            2 -> Material.COCOA_BEANS
            else -> Material.EMERALD
        }
        block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), ItemStack(drop, 1))
    }

    private fun handleExcavation(player: Player, block: Block, isPlaced: Boolean) {
        if (isPlaced) return
        val xp = context.experienceConfig.blockXp(SkillType.EXCAVATION, block.type)
        if (xp <= 0.0) return

        val state = context.players.getCached(player)
        val level = state.getLevel(SkillType.EXCAVATION)
        val giga = state.isAbilityActive(SuperAbilityType.GIGA_DRILL_BREAKER)
        var totalXp = xp * (if (giga) 3.0 else 1.0)

        if (context.ranks.hasUnlocked(state, SubSkillType.EXCAVATION_ARCHAEOLOGY, player)) {
            val rolls = if (giga) 3 else 1
            for (entry in treasures.excavationTreasures) {
                if (block.type !in entry.blocks) continue
                val req = if (context.config.retroMode) entry.minLevelRetro else entry.minLevelStandard
                if (level < req) continue
                repeat(rolls) {
                    if (context.probability.rollPercent(entry.chancePercent)) {
                        block.world.dropItemNaturally(
                            block.location.add(0.5, 0.5, 0.5),
                            ItemStack(entry.material, entry.amount)
                        )
                        totalXp += entry.xp
                        context.locale.sendActionBar(
                            player,
                            "gameplay.excavation-treasure",
                            "item" to entry.material.name
                        )
                    }
                }
            }
        }

        context.progression.awardXp(player, SkillType.EXCAVATION, totalXp, XpGainReason.GATHERING)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        val player = event.player
        val state = context.players.getCached(player)

        when (event.state) {
            PlayerFishEvent.State.FISHING -> {
                // Master Angler faster bites
                val anglerRank = context.ranks.getRank(state, SubSkillType.FISHING_MASTER_ANGLER)
                if (anglerRank > 0) {
                    var minRed =
                        anglerRank * context.skillsConfig.int("fishing.master-angler.rank-min-wait-reduction", 10)
                    var maxRed =
                        anglerRank * context.skillsConfig.int("fishing.master-angler.rank-max-wait-reduction", 30)
                    if (player.vehicle is Boat) {
                        minRed += context.skillsConfig.int("fishing.master-angler.boat-min-wait-reduction", 10)
                        maxRed += context.skillsConfig.int("fishing.master-angler.boat-max-wait-reduction", 30)
                    }
                    val minCap = context.skillsConfig.int("fishing.master-angler.cap-min-wait", 40)
                    val maxCap = context.skillsConfig.int("fishing.master-angler.cap-max-wait", 100)
                    event.hook.minWaitTime = (event.hook.minWaitTime - minRed).coerceAtLeast(minCap)
                    event.hook.maxWaitTime =
                        (event.hook.maxWaitTime - maxRed).coerceAtLeast(maxOf(event.hook.minWaitTime, maxCap))
                }
            }

            PlayerFishEvent.State.IN_GROUND -> {
                // Ice Fishing
                if (context.ranks.hasUnlocked(state, SubSkillType.FISHING_ICE_FISHING, player)) {
                    val below = event.hook.location.block.getRelative(BlockFace.DOWN)
                    if (below.type == Material.ICE || below.type == Material.FROSTED_ICE) {
                        below.type = Material.WATER
                        context.locale.sendActionBar(player, "gameplay.fishing-ice")
                    }
                }
            }

            PlayerFishEvent.State.CAUGHT_ENTITY -> {
                val caught = event.caught as? LivingEntity ?: return
                if (!context.ranks.hasUnlocked(state, SubSkillType.FISHING_SHAKE, player)) return
                val shakeRank = context.ranks.getRank(state, SubSkillType.FISHING_SHAKE)
                val chance = context.skillsConfig.doubleAtRank("fishing.shake-chances", shakeRank, 15.0)
                if (!context.probability.rollPercent(chance)) return

                val dropMat = shakeLootFor(caught.type) ?: return
                caught.world.dropItemNaturally(caught.location, ItemStack(dropMat, 1))
                context.locale.sendActionBar(
                    player,
                    "gameplay.fishing-shake",
                    "item" to dropMat.name,
                    "mob" to caught.name,
                )
                context.progression.awardXp(
                    player,
                    SkillType.FISHING,
                    context.experienceConfig.fishingShakeXp,
                    XpGainReason.GATHERING
                )
            }

            PlayerFishEvent.State.CAUGHT_FISH -> {
                if (context.experienceConfig.fishingAntiExploit && isOverfishing(player, event.hook.location)) {
                    context.locale.sendActionBar(player, "gameplay.fishing-exploit")
                    return
                }
                val itemEntity = event.caught as? Item
                val caughtMat = itemEntity?.itemStack?.type ?: Material.COD
                var xp = context.experienceConfig.fishingXp(caughtMat)

                val hunterRank = context.ranks.getRank(state, SubSkillType.FISHING_TREASURE_HUNTER)
                if (hunterRank > 0 && context.probability.rollPercent(15.0 + hunterRank * 5.0)) {
                    val treasure = treasures.rollFishingTreasure(hunterRank)
                    if (treasure != null && itemEntity != null) {
                        val stack = ItemStack(treasure.material, treasure.amount)
                        if (stack.type == Material.ENCHANTED_BOOK ||
                            context.ranks.hasUnlocked(state, SubSkillType.FISHING_MAGIC_HUNTER, player)
                        ) {
                            applyMagicHunterEnchant(stack)
                        }
                        itemEntity.itemStack = stack
                        xp += treasure.xp
                        context.locale.sendActionBar(
                            player,
                            "gameplay.fishing-treasure",
                            "item" to stack.type.name,
                            "rarity" to treasure.rarity.name,
                        )
                    }
                }
                val xpMult =
                    context.skillsConfig.intAtRank("fishing.vanilla-xp-multipliers", hunterRank.coerceAtLeast(1), 1)
                if (xpMult > 1) {
                    event.expToDrop *= xpMult
                }
                context.progression.awardXp(player, SkillType.FISHING, xp, XpGainReason.GATHERING)
            }

            else -> Unit
        }
    }

    private fun isOverfishing(player: Player, hookLoc: Location): Boolean {
        val tracker = fishSpots[player.uniqueId]
        val range = context.experienceConfig.fishingMoveRange
        val limit = context.experienceConfig.fishingOverfishLimit
        if (tracker == null || tracker.location.world != hookLoc.world || tracker.location.distance(hookLoc) > range) {
            fishSpots[player.uniqueId] = FishSpotTracker(hookLoc.clone(), 1)
            return false
        }
        tracker.count++
        return tracker.count > limit
    }

    private fun applyMagicHunterEnchant(stack: ItemStack) {
        if (stack.type == Material.ENCHANTED_BOOK) {
            val meta = stack.itemMeta as? EnchantmentStorageMeta ?: return
            meta.addStoredEnchant(Enchantment.UNBREAKING, ThreadLocalRandom.current().nextInt(1, 4), true)
            stack.itemMeta = meta
            return
        }
        if (stack.type.maxDurability > 0) {
            stack.addUnsafeEnchantment(Enchantment.UNBREAKING, ThreadLocalRandom.current().nextInt(1, 3))
        }
    }

    private fun shakeLootFor(type: EntityType): Material? = when (type) {
        EntityType.SKELETON, EntityType.STRAY -> Material.BONE
        EntityType.ZOMBIE, EntityType.HUSK -> Material.ROTTEN_FLESH
        EntityType.CREEPER -> Material.GUNPOWDER
        EntityType.SPIDER, EntityType.CAVE_SPIDER -> Material.STRING
        EntityType.BLAZE -> Material.BLAZE_ROD
        EntityType.CHICKEN -> Material.FEATHER
        EntityType.SHEEP -> Material.WHITE_WOOL
        EntityType.COW -> Material.LEATHER
        EntityType.SQUID -> Material.INK_SAC
        EntityType.GLOW_SQUID -> Material.GLOW_INK_SAC
        else -> null
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDietFood(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        val item = event.item ?: return
        val delta = event.foodLevel - player.foodLevel
        if (delta <= 0) return

        val state = context.players.getCached(player)
        val bonus = when (item.type) {
            Material.COOKED_COD, Material.COOKED_SALMON, Material.COD, Material.SALMON ->
                context.ranks.getRank(state, SubSkillType.FISHING_FISHERMANS_DIET)

            Material.BREAD, Material.BAKED_POTATO, Material.CARROT, Material.APPLE, Material.MELON_SLICE, Material.BEETROOT ->
                context.ranks.getRank(state, SubSkillType.HERBALISM_FARMERS_DIET)

            else -> 0
        }
        if (bonus > 0) {
            event.foodLevel = (event.foodLevel + bonus).coerceAtMost(20)
        }
    }

    private fun dropBonusCopies(block: Block, tool: ItemStack, extraCopies: Int) {
        if (extraCopies <= 0) return
        val drops = block.getDrops(tool)
        if (drops.isEmpty()) return
        val dropLoc = block.location.add(0.5, 0.5, 0.5)
        repeat(extraCopies) {
            for (d in drops) {
                block.world.dropItemNaturally(dropLoc, d.clone())
            }
        }
    }
}
