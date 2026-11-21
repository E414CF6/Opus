package opus.modules.crafting

import opus.core.OpusContext
import opus.core.module.OpusModule
import opus.core.text.TextFormatter
import opus.events.OpusRepairEvent
import opus.events.OpusSalvageEvent
import opus.events.XpGainReason
import opus.skills.SkillType
import opus.skills.SubSkillType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BrewingStand
import org.bukkit.block.TileState
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.*
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Handles Repair, Salvage, Smelting, Alchemy, and Chimaera Wing mechanics.
 */
class CraftingModule(private val context: OpusContext) : OpusModule {
    override val id = "crafting"
    override val description = "Repair, Salvage, Smelting, Alchemy, and Chimaera Wing"

    val repairConfig = RepairConfig(context.plugin)
    val alchemyConfig = AlchemyConfig(context.plugin)
    private val listener = CraftingListener(context, repairConfig, alchemyConfig)
    private val registeredMixKeys = mutableListOf<NamespacedKey>()

    override fun onEnable() {
        registerCustomPotionMixes()
    }

    override fun onReload() {
        repairConfig.reload()
        alchemyConfig.reload()
        unregisterCustomPotionMixes()
        registerCustomPotionMixes()
    }

    override fun onDisable() {
        unregisterCustomPotionMixes()
    }

    override fun listeners(): List<Listener> = listOf(listener)

    private fun registerCustomPotionMixes() {
        val awkwardIcon = ItemStack(Material.POTION).apply {
            (itemMeta as? PotionMeta)?.let { meta ->
                meta.basePotionType = PotionType.AWKWARD
                itemMeta = meta
            }
        }
        for (recipe in alchemyConfig.customRecipes.values) {
            val key = NamespacedKey(context.plugin, "concoction_${recipe.ingredient.name.lowercase()}")
            val result = ItemStack(Material.POTION).apply {
                val meta = itemMeta as? PotionMeta ?: return@apply
                meta.addCustomEffect(PotionEffect(recipe.effectType, recipe.durationTicks, recipe.amplifier), true)
                meta.color = recipe.color
                meta.displayName(TextFormatter.format("<!italic>${recipe.displayName}"))
                itemMeta = meta
            }
            val input = RecipeChoice.predicateChoice({ stack ->
                (stack.type == Material.POTION || stack.type == Material.SPLASH_POTION || stack.type == Material.LINGERING_POTION) &&
                        (stack.itemMeta as? PotionMeta)?.basePotionType == PotionType.AWKWARD
            }, awkwardIcon)
            val ingredient = RecipeChoice.MaterialChoice(recipe.ingredient)
            runCatching {
                Bukkit.removeRecipe(key)
                Bukkit.addRecipe(BrewingRecipe(key, result, input, ingredient))
                registeredMixKeys += key
            }
        }
    }

    private fun unregisterCustomPotionMixes() {
        for (key in registeredMixKeys) {
            runCatching { Bukkit.removeRecipe(key) }
        }
        registeredMixKeys.clear()
    }
}

class CraftingListener(
    private val context: OpusContext,
    private val repairConfig: RepairConfig,
    private val alchemyConfig: AlchemyConfig,
) : Listener {

    private val containerOwnerKey = NamespacedKey(context.plugin, "container_owner")
    private val chimaeraCooldowns = ConcurrentHashMap<UUID, Long>()
    private val channelingChimaera = ConcurrentHashMap.newKeySet<UUID>()

    private fun setBlockOwner(block: Block, uuid: UUID) {
        val state = block.state as? TileState ?: return
        state.persistentDataContainer.set(containerOwnerKey, PersistentDataType.STRING, uuid.toString())
        state.update()
    }

    private fun getBlockOwner(block: Block): UUID? {
        val state = block.state as? TileState ?: return null
        val raw = state.persistentDataContainer.get(containerOwnerKey, PersistentDataType.STRING) ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val item = event.item ?: return

        // Chimaera Wing (Shift + Right Click with Feather)
        if (context.config.chimaeraEnabled &&
            item.type == Material.FEATHER &&
            player.isSneaking &&
            (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)
        ) {
            tryChimaeraWing(player, item)
            event.isCancelled = true
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return

        when (block.type) {
            Material.IRON_BLOCK -> {
                if (handleRepair(player, item)) {
                    event.isCancelled = true
                }
            }

            Material.GOLD_BLOCK -> {
                if (handleSalvage(player, item)) {
                    event.isCancelled = true
                }
            }

            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER -> {
                setBlockOwner(block, player.uniqueId)
            }

            Material.BREWING_STAND -> {
                setBlockOwner(block, player.uniqueId)
            }

            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        when (event.block.type) {
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.BREWING_STAND ->
                setBlockOwner(event.block, event.player.uniqueId)

            else -> Unit
        }
    }

    private fun handleRepair(player: Player, item: ItemStack): Boolean {
        val entry = repairConfig.findRepairable(item.type) ?: return false
        val meta = item.itemMeta as? Damageable ?: return false
        if (!repairConfig.allowRepairCustomModelData && meta.hasCustomModelDataComponent()) {
            context.locale.sendActionBar(player, "gameplay.custom-item-blocked")
            return true
        }
        val maxDurability = item.type.maxDurability.toInt()
        if (maxDurability <= 0) return false
        if (meta.damage <= 0) {
            context.locale.sendActionBar(player, "gameplay.repair-full")
            return true
        }

        val state = context.players.getCached(player)
        val level = state.getLevel(SkillType.REPAIR)
        val reqLevel = if (context.config.retroMode) entry.minimumLevel * 10 else entry.minimumLevel
        if (level < reqLevel) {
            context.locale.sendActionBar(
                player,
                "gameplay.repair-need-level",
                "level" to reqLevel,
                "item" to item.type.name,
            )
            return true
        }

        if (!consumeMaterial(player, entry.repairMaterial, 1)) {
            context.locale.sendActionBar(
                player,
                "gameplay.repair-need-material",
                "material" to entry.repairMaterial.name,
                "item" to item.type.name,
            )
            return true
        }

        var restore = (maxDurability.toDouble() / entry.minQuantity.toDouble()).roundToInt().coerceAtLeast(1)

        if (context.ranks.hasUnlocked(state, SubSkillType.REPAIR_REPAIR_MASTERY, player)) {
            val maxBonusPct = context.skillsConfig.double("repair.repair-mastery.max-bonus-percentage", 200.0)
            val maxLvl = context.skillsConfig.maxBonusLevel("repair.repair-mastery", context.config.retroMode)
            val bonusPct = context.probability.linearChancePercent(level, maxLvl, maxBonusPct)
            restore = (restore * (1.0 + bonusPct / 100.0)).roundToInt()
        }

        var isSuper = false
        if (context.ranks.hasUnlocked(state, SubSkillType.REPAIR_SUPER_REPAIR, player)) {
            val maxLvl = context.skillsConfig.maxBonusLevel("repair.super-repair", context.config.retroMode)
            val maxChance = context.skillsConfig.double("repair.super-repair.chance-max", 100.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player
                    )
                )
            ) {
                restore *= 2
                isSuper = true
            }
        }

        val actualRestore = restore.coerceAtMost(meta.damage)
        val repairEvent = OpusRepairEvent(player, item, actualRestore)
        Bukkit.getPluginManager().callEvent(repairEvent)
        if (repairEvent.isCancelled) return true

        meta.damage = (meta.damage - repairEvent.repairedAmount).coerceAtLeast(0)
        item.itemMeta = meta

        // Arcane Forging enchantment handling
        if (item.enchantments.isNotEmpty()) {
            applyArcaneForging(player, state, item)
        }

        player.world.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.1f)
        if (isSuper) {
            context.locale.sendActionBar(player, "gameplay.repair-super")
        } else {
            context.locale.sendActionBar(player, "gameplay.repair-success", "item" to item.type.name)
        }

        val base = context.experienceConfig.repairBaseXp
        val matMult = context.experienceConfig.repairMaterialMultiplier(entry.category)
        val ratio = repairEvent.repairedAmount.toDouble() / maxDurability.toDouble()
        val xp = base * matMult * entry.xpMultiplier * ratio
        context.progression.awardXp(player, SkillType.REPAIR, xp, XpGainReason.CRAFTING)
        return true
    }

    private fun applyArcaneForging(player: Player, state: opus.core.player.OpusPlayerState, item: ItemStack) {
        val rank = context.ranks.getRank(state, SubSkillType.REPAIR_ARCANE_FORGING)
        val keepChance =
            if (rank <= 0) 0.0 else context.skillsConfig.doubleAtRank("repair.arcane-forging.keep-chances", rank, 10.0)
        val downgradeChance = if (rank <= 0) 100.0 else context.skillsConfig.doubleAtRank(
            "repair.arcane-forging.downgrade-chances",
            rank,
            75.0
        )
        val mayLose = context.skillsConfig.boolean("repair.arcane-forging.may-lose-enchants", true)
        val downgradesEnabled = context.skillsConfig.boolean("repair.arcane-forging.downgrades-enabled", true)

        var anyLostOrDowngraded = false
        val enchants = item.enchantments.toMap()
        for ((ench, lvl) in enchants) {
            if (mayLose && !context.probability.rollPercent(keepChance)) {
                item.removeEnchantment(ench)
                anyLostOrDowngraded = true
            } else if (downgradesEnabled && lvl > 1 && context.probability.rollPercent(downgradeChance)) {
                item.removeEnchantment(ench)
                item.addUnsafeEnchantment(ench, lvl - 1)
                anyLostOrDowngraded = true
            }
        }
        if (anyLostOrDowngraded) {
            context.locale.sendActionBar(player, "gameplay.repair-enchant-lost")
        }
    }

    private fun handleSalvage(player: Player, item: ItemStack): Boolean {
        val entry = repairConfig.findSalvageable(item.type) ?: return false
        if (!repairConfig.allowSalvageCustomModelData && item.itemMeta?.hasCustomModelDataComponent() == true) {
            context.locale.sendActionBar(player, "gameplay.custom-item-blocked")
            return true
        }
        val state = context.players.getCached(player)
        val level = state.getLevel(SkillType.SALVAGE)
        val reqLevel = if (context.config.retroMode) entry.minimumLevel * 10 else entry.minimumLevel
        if (level < reqLevel) {
            context.locale.sendActionBar(
                player,
                "gameplay.salvage-need-level",
                "level" to reqLevel,
                "item" to item.type.name,
            )
            return true
        }

        val maxDurability = item.type.maxDurability.toInt().coerceAtLeast(1)
        val damage = (item.itemMeta as? Damageable)?.damage ?: 0
        val durabilityPct = ((maxDurability - damage).toDouble() / maxDurability.toDouble()).coerceIn(0.0, 1.0)
        val scrapRank = context.ranks.getRank(state, SubSkillType.SALVAGE_SCRAP_COLLECTOR)
        val yieldRatio = (durabilityPct * (0.5 + scrapRank * 0.0625)).coerceIn(0.1, 1.0)
        val outAmount = ceil(entry.maxQuantity * yieldRatio).toInt().coerceIn(1, entry.maxQuantity)

        val salvageEvent = OpusSalvageEvent(player, item, outAmount)
        Bukkit.getPluginManager().callEvent(salvageEvent)
        if (salvageEvent.isCancelled) return true

        val enchants = item.enchantments.toMap()
        player.inventory.itemInMainHand = ItemStack(Material.AIR)

        val leftovers = player.inventory.addItem(ItemStack(entry.salvageMaterial, salvageEvent.salvagedAmount))
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }

        if (enchants.isNotEmpty() && context.ranks.hasUnlocked(state, SubSkillType.SALVAGE_ARCANE_SALVAGE, player)) {
            extractArcaneSalvageBook(player, state, enchants)
        }

        player.world.playSound(player.location, Sound.BLOCK_ANVIL_BREAK, 0.7f, 1.2f)
        context.locale.sendActionBar(
            player,
            "gameplay.salvage-success",
            "item" to item.type.name,
            "amount" to salvageEvent.salvagedAmount,
            "material" to entry.salvageMaterial.name,
        )

        val xp = context.experienceConfig.salvageBaseXp * entry.xpMultiplier * durabilityPct
        context.progression.awardXp(player, SkillType.SALVAGE, xp, XpGainReason.CRAFTING)
        return true
    }

    private fun extractArcaneSalvageBook(
        player: Player,
        state: opus.core.player.OpusPlayerState,
        enchants: Map<Enchantment, Int>,
    ) {
        val rank = context.ranks.getRank(state, SubSkillType.SALVAGE_ARCANE_SALVAGE)
        val fullChance = context.skillsConfig.doubleAtRank("salvage.arcane-salvage.extract-full-chances", rank, 2.5)
        val partialChance =
            context.skillsConfig.doubleAtRank("salvage.arcane-salvage.extract-partial-chances", rank, 2.0)

        val book = ItemStack(Material.ENCHANTED_BOOK, 1)
        val meta = book.itemMeta as? EnchantmentStorageMeta ?: return
        var anyAdded = false

        for ((ench, lvl) in enchants) {
            when {
                context.probability.rollPercent(fullChance) -> {
                    meta.addStoredEnchant(ench, lvl, true)
                    anyAdded = true
                }

                lvl > 1 && context.probability.rollPercent(partialChance) -> {
                    meta.addStoredEnchant(ench, lvl - 1, true)
                    anyAdded = true
                }
            }
        }
        if (anyAdded) {
            book.itemMeta = meta
            val leftovers = player.inventory.addItem(book)
            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
            context.locale.sendActionBar(player, "gameplay.salvage-arcane")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFurnaceBurn(event: FurnaceBurnEvent) {
        val ownerUuid = getBlockOwner(event.block) ?: return
        val state = context.players.getCached(ownerUuid) ?: return
        val rank = context.ranks.getRank(state, SubSkillType.SMELTING_FUEL_EFFICIENCY)
        if (rank <= 0) return
        val mult = 1.0 + (rank * 0.33)
        event.burnTime = (event.burnTime * mult).roundToInt().coerceAtLeast(1)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFurnaceSmelt(event: FurnaceSmeltEvent) {
        val ownerUuid = getBlockOwner(event.block) ?: return
        val player = Bukkit.getPlayer(ownerUuid)
        val state = context.players.getCached(ownerUuid) ?: return
        val level = state.getLevel(SkillType.SMELTING)

        if (context.ranks.hasUnlocked(state, SubSkillType.SMELTING_SECOND_SMELT, player)) {
            val maxLvl = context.skillsConfig.maxBonusLevel("smelting.second-smelt", context.config.retroMode)
            val maxChance = context.skillsConfig.double("smelting.second-smelt.chance-max", 50.0)
            if (context.probability.rollPercent(
                    context.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        player,
                        skill = SkillType.SMELTING,
                    )
                )
            ) {
                val res = event.result.clone()
                res.amount = (res.amount * 2).coerceAtMost(res.maxStackSize)
                event.result = res
                if (player != null) {
                    context.locale.sendActionBar(player, "gameplay.smelting-second", "item" to res.type.name)
                }
            }
        }

        if (player != null) {
            val xp = context.experienceConfig.smeltingXp(event.source.type)
            if (xp > 0.0) {
                context.progression.awardXp(player, SkillType.SMELTING, xp, XpGainReason.CRAFTING)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        if (event.expToDrop <= 0) return
        val player = event.player
        val state = context.players.getCached(player)
        if (!context.ranks.hasUnlocked(state, SubSkillType.SMELTING_UNDERSTANDING_THE_ART, player)) return
        val rank = context.ranks.getRank(state, SubSkillType.SMELTING_UNDERSTANDING_THE_ART)
        val mult = context.skillsConfig.intAtRank("smelting.vanilla-xp-multipliers", rank, 1).coerceAtLeast(1)
        if (mult > 1) {
            event.expToDrop *= mult
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrewStandInteract(event: InventoryClickEvent) {
        val inv = event.view.topInventory as? BrewerInventory ?: return
        if (inv.type != InventoryType.BREWING) return
        val holder = inv.holder ?: return
        val player = event.whoClicked as? Player ?: return
        setBlockOwner(holder.block, player.uniqueId)

        val state = context.players.getCached(player)
        if (context.ranks.hasUnlocked(state, SubSkillType.ALCHEMY_CATALYSIS, player)) {
            val maxLvl = context.skillsConfig.maxBonusLevel("alchemy.catalysis", context.config.retroMode)
            val minSpeed = context.skillsConfig.double("alchemy.catalysis.min-speed", 1.0)
            val maxSpeed = context.skillsConfig.double("alchemy.catalysis.max-speed", 4.0)
            val ratio = (state.getLevel(SkillType.ALCHEMY).toDouble() / maxLvl.toDouble()).coerceIn(0.0, 1.0)
            val speed = minSpeed + (maxSpeed - minSpeed) * ratio
            val targetTicks = (400.0 / speed.coerceAtLeast(1.0)).roundToInt().coerceIn(40, 400)
            context.scheduler.runAtLocation(holder.location) {
                val fresh = holder.location.block.state as? BrewingStand ?: return@runAtLocation
                fresh.recipeBrewTime = targetTicks
                fresh.update()
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        val ownerUuid = getBlockOwner(event.block) ?: return
        val player = Bukkit.getPlayer(ownerUuid) ?: return
        val state = context.players.getCached(player)
        val ingredient = event.contents.ingredient ?: return

        val reqRank = alchemyConfig.ingredientRankRequired[ingredient.type] ?: 1
        val concRank = context.ranks.getRank(state, SubSkillType.ALCHEMY_CONCOCTIONS)
        if (concRank < reqRank) {
            event.isCancelled = true
            return
        }

        val custom = alchemyConfig.customRecipes[ingredient.type]
        if (custom != null && concRank >= custom.minRank) {
            val results = event.results
            for (i in results.indices) {
                val bottle = results[i] ?: continue
                if (bottle.type != Material.POTION && bottle.type != Material.SPLASH_POTION && bottle.type != Material.LINGERING_POTION) continue
                val meta = bottle.itemMeta as? PotionMeta ?: continue
                if (meta.basePotionType == PotionType.AWKWARD) {
                    meta.clearCustomEffects()
                    meta.addCustomEffect(PotionEffect(custom.effectType, custom.durationTicks, custom.amplifier), true)
                    meta.color = custom.color
                    meta.displayName(TextFormatter.format("<!italic>${custom.displayName}"))
                    bottle.itemMeta = meta
                }
            }
        }

        val stageXp = context.experienceConfig.alchemyStageXp[reqRank.coerceIn(1, 5)] ?: 666.0
        context.progression.awardXp(player, SkillType.ALCHEMY, stageXp, XpGainReason.CRAFTING)
    }

    private fun tryChimaeraWing(player: Player, item: ItemStack) {
        if (!channelingChimaera.add(player.uniqueId)) return
        val now = System.currentTimeMillis()
        val cdUntil = chimaeraCooldowns[player.uniqueId] ?: 0L
        if (now < cdUntil && !player.hasPermission("opus.bypass.cooldown")) {
            channelingChimaera.remove(player.uniqueId)
            context.locale.sendActionBar(
                player,
                "general.cooldown-active",
                "time" to TextFormatter.formatDuration(cdUntil - now),
            )
            return
        }
        val dest = player.respawnLocation
        if (dest == null) {
            channelingChimaera.remove(player.uniqueId)
            context.locale.sendActionBar(player, "gameplay.chimaera-no-bed")
            return
        }

        val warmupSec = context.config.chimaeraWarmupSeconds
        val startLoc = player.location.clone()
        val startHp = player.health
        context.locale.sendActionBar(player, "gameplay.chimaera-channeling", "seconds" to warmupSec)

        context.scheduler.runForEntityDelayed(player, warmupSec * 20L) {
            channelingChimaera.remove(player.uniqueId)
            if (!player.isOnline) return@runForEntityDelayed
            if (player.location.world != startLoc.world ||
                player.location.distanceSquared(startLoc) > 1.0 ||
                player.health < startHp
            ) {
                context.locale.sendActionBar(player, "gameplay.chimaera-cancelled")
                return@runForEntityDelayed
            }
            val cost = context.config.chimaeraFeatherCost
            if (cost > 0 && !consumeMaterial(player, Material.FEATHER, cost)) return@runForEntityDelayed

            chimaeraCooldowns[player.uniqueId] =
                System.currentTimeMillis() + context.config.chimaeraCooldownSeconds * 1000L
            player.teleportAsync(dest).thenAccept { ok ->
                if (ok) {
                    context.locale.sendActionBar(player, "gameplay.chimaera-success")
                    player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f)
                }
            }
        }
    }

    private fun consumeMaterial(player: Player, material: Material, amount: Int): Boolean {
        if (amount <= 0) return true
        val inv = player.inventory
        if (!inv.contains(material, amount)) return false
        var remaining = amount
        for (i in 0 until inv.size) {
            val stack = inv.getItem(i) ?: continue
            if (stack.type != material) continue
            val take = minOf(remaining, stack.amount)
            stack.amount -= take
            remaining -= take
            if (stack.amount <= 0) inv.setItem(i, null)
            if (remaining <= 0) break
        }
        return true
    }
}
