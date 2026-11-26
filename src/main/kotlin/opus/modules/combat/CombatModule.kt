package opus.modules.combat

import opus.core.OpusContext
import opus.core.compat.PlatformCompat
import opus.core.module.FeatureModule
import opus.core.text.TextFormatter
import opus.events.XpGainReason
import opus.rpg
import opus.skills.SkillType
import opus.skills.SubSkillType
import opus.skills.SuperAbilityType
import opus.skills.subSkillName
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.*
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

interface PartyRelationHook {
    fun areAllied(first: UUID, second: UUID): Boolean
}

/**
 * Handles all 10 Combat & Support skills: Swords, Axes, Archery, Crossbows, Tridents,
 * Maces, Spears, Unarmed, Taming, and Acrobatics.
 */
class CombatModule(private val context: OpusContext) : FeatureModule {
    override val id = "combat"
    override val description =
        "Swords, Axes, Archery, Crossbows, Tridents, Maces, Spears, Unarmed, Taming, and Acrobatics"

    private val listener = CombatListener(context)

    override fun onDisable() {
        listener.cleanupAllSummons()
    }

    override fun listeners(): List<Listener> = listOf(listener)
}

class CombatListener(private val context: OpusContext) : Listener {

    private val rpg get() = context.rpg
    private val maxHealthAttr by lazy { PlatformCompat.resolveAttribute("MAX_HEALTH", "GENERIC_MAX_HEALTH") }
    private val attackDamageAttr by lazy { PlatformCompat.resolveAttribute("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE") }
    private val infinityEnchant by lazy { PlatformCompat.resolveEnchantment("INFINITY", "ARROW_INFINITE") }
    private val featherFallingEnchant by lazy {
        PlatformCompat.resolveEnchantment(
            "FEATHER_FALLING",
            "PROTECTION_FALL"
        )
    }
    private val nauseaEffect by lazy { PlatformCompat.resolvePotionEffect("NAUSEA", "CONFUSION") }
    private val slownessEffect by lazy { PlatformCompat.resolvePotionEffect("SLOWNESS", "SLOW") }
    private val speedEffect by lazy { PlatformCompat.resolvePotionEffect("SPEED") }
    private val explosionParticle by lazy { PlatformCompat.resolveParticle("EXPLOSION", "EXPLOSION_LARGE") }
    private val smokeParticle by lazy { PlatformCompat.resolveParticle("SMOKE", "SMOKE_NORMAL") }

    private val aoeGuard = ConcurrentHashMap.newKeySet<UUID>()
    private val dodgeXpPerMob = ConcurrentHashMap<UUID, Double>()
    private val activeRuptures = ConcurrentHashMap<UUID, Long>()
    private val callOfWildCooldowns = ConcurrentHashMap<UUID, Long>()
    private val arrowsStuckInEntity = ConcurrentHashMap<UUID, Int>()
    private val activeSummonsByOwner = ConcurrentHashMap<UUID, MutableSet<LivingEntity>>()
    private val transientSummonIds = ConcurrentHashMap.newKeySet<UUID>()

    private val bounceCountKey = NamespacedKey(context.plugin, "trickshot_bounces")
    private val explosiveShotKey = NamespacedKey(context.plugin, "explosive_shot")
    private val infinityArrowKey = NamespacedKey(context.plugin, "infinity_arrow")
    private val disarmOwnerKey = NamespacedKey(context.plugin, "disarm_owner")
    private val disarmExpiryKey = NamespacedKey(context.plugin, "disarm_expiry")

    fun cleanupAllSummons() {
        for (set in activeSummonsByOwner.values) {
            for (entity in set) {
                transientSummonIds.remove(entity.uniqueId)
                if (entity.isValid) {
                    entity.remove()
                }
            }
        }
        activeSummonsByOwner.clear()
        transientSummonIds.clear()
        arrowsStuckInEntity.clear()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val summons = activeSummonsByOwner.remove(event.player.uniqueId) ?: return
        for (entity in summons) {
            transientSummonIds.remove(entity.uniqueId)
            if (entity.isValid) {
                context.scheduler.runForEntity(entity) { entity.remove() }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSummonBreed(event: EntityBreedEvent) {
        if (!rpg.skillsConfig.boolean("taming.call-of-the-wild.prevent-breeding", true)) return
        if (event.mother.uniqueId in transientSummonIds || event.father.uniqueId in transientSummonIds) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDisarmAntiTheftPickup(event: EntityPickupItemEvent) {
        val itemEntity = event.item
        val ownerStr = itemEntity.persistentDataContainer.get(disarmOwnerKey, PersistentDataType.STRING) ?: return
        val expiry = itemEntity.persistentDataContainer.get(disarmExpiryKey, PersistentDataType.LONG) ?: 0L
        if (System.currentTimeMillis() > expiry) {
            itemEntity.persistentDataContainer.remove(disarmOwnerKey)
            itemEntity.persistentDataContainer.remove(disarmExpiryKey)
            return
        }
        if (event.entity.uniqueId.toString() != ownerStr) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        transientSummonIds.remove(entity.uniqueId)
        val arrows = arrowsStuckInEntity.remove(entity.uniqueId) ?: return
        if (arrows > 0) {
            event.drops.add(ItemStack(Material.ARROW, arrows))
            entity.killer?.let { killer ->
                context.locale.sendActionBar(killer, "gameplay.archery-retrieve")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val item = event.item ?: ItemStack(Material.AIR)

        // Taming: Call of the Wild (Sneak + Left click with Bone, Cod/Salmon, or Golden Apple)
        if ((event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK) && player.isSneaking) {
            if (tryCallOfTheWild(player, item)) {
                event.isCancelled = true
                return
            }
        }

        // Super Ability readying on Right Click
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val clicked = event.clickedBlock
            if (clicked != null && (clicked.blockData is org.bukkit.block.data.Openable || clicked.state is org.bukkit.inventory.InventoryHolder) && !player.isSneaking) return
            val skill = when {
                item.type == Material.AIR -> SkillType.UNARMED
                Tag.ITEMS_SWORDS.isTagged(item.type) -> SkillType.SWORDS
                Tag.ITEMS_AXES.isTagged(item.type) -> SkillType.AXES
                item.type == Material.BOW -> SkillType.ARCHERY
                item.type == Material.CROSSBOW -> SkillType.CROSSBOWS
                item.type == Material.TRIDENT -> SkillType.TRIDENTS
                PlatformCompat.isMace(item.type) -> SkillType.MACES
                PlatformCompat.isSpear(item.type) -> SkillType.SPEARS
                else -> null
            } ?: return
            rpg.progression.readyTool(player, skill)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreakUnarmed(event: BlockBreakEvent) {
        val player = event.player
        val state = rpg.players.getCached(player)
        val hand = player.inventory.itemInMainHand
        if (hand.type != Material.AIR) return
        if (!state.isAbilityActive(SuperAbilityType.BERSERK)) return
        if (!rpg.ranks.hasUnlocked(state, SubSkillType.UNARMED_BLOCK_CRACKER, player)) return

        val block = event.block
        val cracked = when (block.type) {
            Material.STONE_BRICKS -> Material.CRACKED_STONE_BRICKS
            Material.DEEPSLATE_BRICKS -> Material.CRACKED_DEEPSLATE_BRICKS
            Material.DEEPSLATE_TILES -> Material.CRACKED_DEEPSLATE_TILES
            Material.NETHER_BRICKS -> Material.CRACKED_NETHER_BRICKS
            Material.POLISHED_BLACKSTONE_BRICKS -> Material.CRACKED_POLISHED_BLACKSTONE_BRICKS
            else -> null
        }
        if (cracked != null) {
            event.isCancelled = true
            block.type = cracked
            block.world.playSound(block.location, Sound.BLOCK_STONE_BREAK, 0.8f, 1.0f)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val target = event.rightClicked as? Tameable ?: return
        if (!target.isTamed) return
        val state = rpg.players.getCached(player)
        if (!rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_BEAST_LORE, player)) return

        val item = player.inventory.itemInMainHand.type
        if (item != Material.BONE && item != Material.AIR) return
        val maxHp = maxHealthAttr?.let { (target as? LivingEntity)?.getAttribute(it)?.value } ?: 20.0
        val hp = (target as? LivingEntity)?.health ?: 20.0
        val ownerName = target.owner?.name ?: "Unknown"
        context.locale.sendActionBar(
            player,
            "gameplay.taming-beast-lore",
            "mob" to target.name,
            "owner" to ownerName,
            "hp" to "%.1f".format(hp),
            "max_hp" to "%.1f".format(maxHp),
        )
        if (player.isSneaking) {
            val tamingPerks = listOf(
                SubSkillType.TAMING_THICK_FUR,
                SubSkillType.TAMING_SHOCK_PROOF,
                SubSkillType.TAMING_ENVIRONMENTALLY_AWARE,
                SubSkillType.TAMING_HOLY_HOUND,
                SubSkillType.TAMING_GORE,
                SubSkillType.TAMING_SHARPENED_CLAWS,
                SubSkillType.TAMING_FAST_FOOD_SERVICE,
                SubSkillType.TAMING_PUMMEL,
            ).filter { rpg.ranks.hasUnlocked(state, it, player) }
                .joinToString(", ") { context.locale.subSkillName(it) }
                .ifEmpty { "-" }

            context.dialogs.openNotice(
                player = player,
                titleRaw = context.locale.raw("dialog.beast-lore-title", "mob" to target.name),
                icon = ItemStack(Material.BONE),
                headerRaw = context.locale.raw(
                    "gameplay.taming-beast-lore",
                    "mob" to target.name,
                    "owner" to ownerName,
                    "hp" to "%.1f".format(hp),
                    "max_hp" to "%.1f".format(maxHp),
                ),
                extraLines = listOf(
                    context.locale.raw("dialog.beast-lore-perks", "perks" to tamingPerks),
                ),
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityTame(event: EntityTameEvent) {
        val owner = event.owner as? Player ?: return
        val xp = rpg.experienceConfig.tamingXp(event.entityType)
        rpg.progression.awardXp(owner, SkillType.TAMING, xp, XpGainReason.OTHER)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onShootBow(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return
        val proj = event.projectile as? AbstractArrow ?: return

        if (bow.type == Material.BOW) {
            if (infinityEnchant?.let { bow.containsEnchantment(it) } == true) {
                proj.persistentDataContainer.set(infinityArrowKey, PersistentDataType.BYTE, 1)
            }
            if (rpg.progression.tryActivateAbility(player, SkillType.ARCHERY)) {
                proj.persistentDataContainer.set(explosiveShotKey, PersistentDataType.BYTE, 1)
            }
        } else if (bow.type == Material.CROSSBOW) {
            if (proj is Arrow) {
                PlatformCompat.setArrowWeapon(proj, bow)
            }
            if (rpg.progression.tryActivateAbility(player, SkillType.CROSSBOWS)) {
                // Super Shotgun: fire 2 extra angled bolts
                val vel = proj.velocity
                for (angle in listOf(-0.12, 0.12)) {
                    val extra = player.world.spawnArrow(
                        proj.location,
                        vel.clone().rotateAroundY(angle),
                        vel.length().toFloat(),
                        0.5f
                    )
                    extra.shooter = player
                    PlatformCompat.setArrowWeapon(extra, ItemStack(Material.CROSSBOW))
                    extra.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? Arrow ?: return
        val shooter = arrow.shooter as? Player ?: return

        if (arrow.persistentDataContainer.has(explosiveShotKey, PersistentDataType.BYTE)) {
            arrow.world.createExplosion(arrow.location, 2.5f, false, false, shooter)
            arrow.remove()
            return
        }

        val hitBlock = event.hitBlock ?: return
        val face = event.hitBlockFace ?: return
        if (PlatformCompat.getArrowWeaponType(arrow) != Material.CROSSBOW) return

        val state = rpg.players.getCached(shooter)
        if (!rpg.ranks.hasUnlocked(state, SubSkillType.CROSSBOWS_TRICK_SHOT, shooter)) return

        val maxBounces = rpg.ranks.getRank(state, SubSkillType.CROSSBOWS_TRICK_SHOT)
        val bounces = arrow.persistentDataContainer.get(bounceCountKey, PersistentDataType.INTEGER) ?: 0
        if (bounces >= maxBounces) return

        val normal = face.direction
        val v = arrow.velocity
        val reflected = v.clone().subtract(normal.clone().multiply(2.0 * v.dot(normal)))
        if (bounces == 0 && reflected.angle(normal) < Math.PI / 4.0) return

        val reflectedArrow =
            arrow.world.spawnArrow(arrow.location, reflected, reflected.length().toFloat().coerceAtLeast(0.8f), 1.0f)
        reflectedArrow.shooter = shooter
        reflectedArrow.isCritical = arrow.isCritical
        reflectedArrow.pierceLevel = arrow.pierceLevel
        reflectedArrow.pickupStatus = arrow.pickupStatus
        PlatformCompat.setArrowWeapon(reflectedArrow, ItemStack(Material.CROSSBOW))
        arrow.basePotionType?.let {
            reflectedArrow.itemStack = ItemStack(Material.TIPPED_ARROW)
            reflectedArrow.basePotionType = it
        }
        for (eff in arrow.customEffects) {
            reflectedArrow.addCustomEffect(eff, true)
        }
        reflectedArrow.persistentDataContainer.set(bounceCountKey, PersistentDataType.INTEGER, bounces + 1)
        arrow.remove()
        context.locale.sendActionBar(shooter, "gameplay.crossbows-trick-shot")
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val entity = event.entity

        // Opus God Mode protection
        if (entity is Player && rpg.players.getCached(entity).godMode) {
            event.isCancelled = true
            return
        }

        // Acrobatics Roll & Graceful Roll on fall damage
        if (entity is Player && event.cause == EntityDamageEvent.DamageCause.FALL) {
            handleAcrobaticsFall(entity, event)
            return
        }

        // Taming wolf damage defenses (Thick Fur, Shock Proof, Environmentally Aware, Holy Hound)
        if (entity is Wolf && entity.isTamed) {
            val owner = entity.owner as? Player ?: return
            val state = rpg.players.getCached(owner)
            when (event.cause) {
                EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION -> {
                    if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_SHOCK_PROOF, owner)) {
                        val mod = rpg.skillsConfig.double("taming.shock-proof.modifier", 6.0).coerceAtLeast(1.0)
                        event.damage /= mod
                    }
                }

                EntityDamageEvent.DamageCause.FIRE,
                EntityDamageEvent.DamageCause.FIRE_TICK,
                EntityDamageEvent.DamageCause.LAVA -> {
                    if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_THICK_FUR, owner)) {
                        val mod = rpg.skillsConfig.double("taming.thick-fur.modifier", 2.0).coerceAtLeast(1.0)
                        event.damage /= mod
                    }
                    if (event.cause == EntityDamageEvent.DamageCause.LAVA &&
                        rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_ENVIRONMENTALLY_AWARE, owner)
                    ) {
                        event.isCancelled = true
                        entity.teleport(owner.location)
                    }
                }

                EntityDamageEvent.DamageCause.FALL,
                EntityDamageEvent.DamageCause.CONTACT -> {
                    if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_ENVIRONMENTALLY_AWARE, owner)) {
                        event.isCancelled = true
                    }
                }

                EntityDamageEvent.DamageCause.MAGIC,
                EntityDamageEvent.DamageCause.POISON,
                EntityDamageEvent.DamageCause.WITHER -> {
                    if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_HOLY_HOUND, owner)) {
                        event.isCancelled = true
                        val maxHp = maxHealthAttr?.let { entity.getAttribute(it)?.value } ?: 20.0
                        entity.health = (entity.health + event.damage * 0.5).coerceAtMost(maxHp)
                    }
                }

                else -> Unit
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCombatDamage(event: EntityDamageByEntityEvent) {
        val defender = event.entity as? LivingEntity ?: return
        if (isInvalidCombatEntity(defender) || isInvalidCombatEntity(event.damager)) return

        // Opus God Mode protection
        if (defender is Player && rpg.players.getCached(defender).godMode) {
            event.isCancelled = true
            return
        }

        // Defender Acrobatics Dodge / Swords Counter Attack / Unarmed Arrow Deflect
        if (defender is Player) {
            if (handleDefenderAbilities(defender, event)) return
        }

        // Wolf attack skills (Taming)
        if (event.damager is Wolf) {
            val wolf = event.damager as Wolf
            val owner = wolf.owner as? Player
            if (owner != null && wolf.isTamed) {
                handleTamedWolfAttack(owner, wolf, defender, event)
            }
            return
        }

        // Projectile attack skills (Archery / Crossbows / Tridents)
        if (event.damager is Projectile) {
            val proj = event.damager as Projectile
            val shooter = proj.shooter as? Player ?: return
            if (defender is Player && isFriendlyPartyMember(shooter, defender)) {
                event.isCancelled = true
                return
            }
            handleProjectileAttack(shooter, proj, defender, event)
            return
        }

        // Melee attack skills (Swords, Axes, Maces, Spears, Tridents, Unarmed)
        val attacker = event.damager as? Player ?: return
        if (!aoeGuard.add(attacker.uniqueId)) return
        try {
            if (defender is Player && isFriendlyPartyMember(attacker, defender)) {
                event.isCancelled = true
                return
            }
            handleMeleeAttack(attacker, defender, event)
        } finally {
            aoeGuard.remove(attacker.uniqueId)
        }
    }

    private fun isFriendlyPartyMember(a: Player, b: Player): Boolean {
        if (a == b) return false
        val hook = context.getService<PartyRelationHook>() ?: return false
        return hook.areAllied(a.uniqueId, b.uniqueId)
    }

    private fun handleAcrobaticsFall(player: Player, event: EntityDamageEvent) {
        val state = rpg.players.getCached(player)
        if (!rpg.ranks.hasUnlocked(state, SubSkillType.ACROBATICS_ROLL, player)) return
        val level = state.getLevel(SkillType.ACROBATICS)
        val maxLvl = rpg.skillsConfig.maxBonusLevel("acrobatics.roll", context.config.retroMode)
        val maxChance = rpg.skillsConfig.double("acrobatics.roll.chance-max", 100.0)
        var chance = rpg.probability.linearChancePercent(level, maxLvl, maxChance, player)
        val isGraceful = player.isSneaking
        if (isGraceful) chance = (chance * 2.0).coerceAtMost(100.0)

        if (rpg.probability.rollPercent(chance)) {
            val threshold = if (isGraceful) {
                rpg.skillsConfig.double("acrobatics.roll.graceful-damage-threshold", 14.0)
            } else {
                rpg.skillsConfig.double("acrobatics.roll.damage-threshold", 7.0)
            }
            val reduced = (event.damage - threshold).coerceAtLeast(0.0)
            if (reduced <= 0.0) {
                event.isCancelled = true
            } else {
                event.damage = reduced
            }
            context.locale.sendActionBar(
                player,
                if (isGraceful) "gameplay.acrobatics-graceful-roll" else "gameplay.acrobatics-roll",
            )
            var xp = event.damage.coerceAtLeast(1.0) * rpg.experienceConfig.acrobaticsRollXp * 0.2
            if (hasFeatherFalling(player)) xp *= rpg.experienceConfig.acrobaticsFeatherFallingMultiplier
            rpg.progression.awardXp(player, SkillType.ACROBATICS, xp, XpGainReason.OTHER)
        } else {
            var xp = event.damage * rpg.experienceConfig.acrobaticsFallXp * 0.15
            if (hasFeatherFalling(player)) xp *= rpg.experienceConfig.acrobaticsFeatherFallingMultiplier
            rpg.progression.awardXp(player, SkillType.ACROBATICS, xp, XpGainReason.OTHER)
        }
    }

    private fun hasFeatherFalling(player: Player): Boolean {
        val ench = featherFallingEnchant ?: return false
        return player.inventory.boots.containsEnchantment(ench)
    }

    private fun handleDefenderAbilities(defender: Player, event: EntityDamageByEntityEvent): Boolean {
        val state = rpg.players.getCached(defender)

        // Unarmed Arrow Deflect
        if (event.damager is AbstractArrow &&
            defender.inventory.itemInMainHand.type == Material.AIR &&
            rpg.ranks.hasUnlocked(state, SubSkillType.UNARMED_ARROW_DEFLECT, defender)
        ) {
            val maxLvl = rpg.skillsConfig.maxBonusLevel("unarmed.arrow-deflect", context.config.retroMode)
            val maxChance = rpg.skillsConfig.double("unarmed.arrow-deflect.chance-max", 50.0)
            val chance =
                rpg.probability.linearChancePercent(state.getLevel(SkillType.UNARMED), maxLvl, maxChance, defender)
            if (rpg.probability.rollPercent(chance)) {
                event.isCancelled = true
                context.locale.sendActionBar(defender, "gameplay.unarmed-deflect")
                return true
            }
        }

        // Swords Counter Attack
        val damagerLiving = event.damager as? LivingEntity
        if (damagerLiving != null &&
            Tag.ITEMS_SWORDS.isTagged(defender.inventory.itemInMainHand.type) &&
            rpg.ranks.hasUnlocked(state, SubSkillType.SWORDS_COUNTER_ATTACK, defender)
        ) {
            val maxLvl = rpg.skillsConfig.maxBonusLevel("swords.counter-attack", context.config.retroMode)
            val maxChance = rpg.skillsConfig.double("swords.counter-attack.chance-max", 30.0)
            val chance =
                rpg.probability.linearChancePercent(state.getLevel(SkillType.SWORDS), maxLvl, maxChance, defender)
            if (rpg.probability.rollPercent(chance)) {
                val mod = rpg.skillsConfig.double("swords.counter-attack.damage-modifier", 2.0).coerceAtLeast(1.0)
                val reflected = (event.damage / mod).coerceAtMost(damagerLiving.health * 0.5)
                if (reflected > 0.0) {
                    damagerLiving.damage(reflected, defender)
                    context.locale.sendActionBar(defender, "gameplay.swords-counter")
                }
            }
        }

        // Acrobatics Dodge
        if (rpg.ranks.hasUnlocked(state, SubSkillType.ACROBATICS_DODGE, defender)) {
            val maxLvl = rpg.skillsConfig.maxBonusLevel("acrobatics.dodge", context.config.retroMode)
            val maxChance = rpg.skillsConfig.double("acrobatics.dodge.chance-max", 20.0)
            val chance = rpg.probability.linearChancePercent(
                state.getLevel(SkillType.ACROBATICS),
                maxLvl,
                maxChance,
                defender
            )
            if (rpg.probability.rollPercent(chance)) {
                val mod = rpg.skillsConfig.double("acrobatics.dodge.damage-modifier", 2.0).coerceAtLeast(1.0)
                event.damage /= mod
                context.locale.sendActionBar(defender, "gameplay.acrobatics-dodge")
                val rawXp = rpg.experienceConfig.acrobaticsDodgeXp * 0.25
                val damagerId = event.damager.uniqueId
                if (!rpg.experienceConfig.dodgeXpFarmingGuard) {
                    rpg.progression.awardXp(defender, SkillType.ACROBATICS, rawXp, XpGainReason.OTHER)
                } else {
                    val used = dodgeXpPerMob[damagerId] ?: 0.0
                    if (used < rpg.experienceConfig.dodgeXpCapPerMob) {
                        dodgeXpPerMob[damagerId] = used + rawXp
                        rpg.progression.awardXp(defender, SkillType.ACROBATICS, rawXp, XpGainReason.OTHER)
                    }
                }
            }
        }
        return false
    }

    private fun handleTamedWolfAttack(
        owner: Player,
        wolf: Wolf,
        target: LivingEntity,
        event: EntityDamageByEntityEvent
    ) {
        val state = rpg.players.getCached(owner)
        val level = state.getLevel(SkillType.TAMING)

        if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_SHARPENED_CLAWS, owner)) {
            event.damage += rpg.skillsConfig.double("taming.sharpened-claws.bonus", 2.0)
        }
        if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_GORE, owner)) {
            val maxLvl = rpg.skillsConfig.maxBonusLevel("taming.gore", context.config.retroMode)
            val maxChance = rpg.skillsConfig.double("taming.gore.chance-max", 100.0)
            if (rpg.probability.rollPercent(
                    rpg.probability.linearChancePercent(
                        level,
                        maxLvl,
                        maxChance,
                        owner
                    )
                )
            ) {
                event.damage *= rpg.skillsConfig.double("taming.gore.modifier", 2.0)
                applyRupture(owner, target, 1)
                context.locale.sendActionBar(owner, "gameplay.taming-gore")
            }
        }
        if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_FAST_FOOD_SERVICE, owner)) {
            val chance = rpg.skillsConfig.double("taming.fast-food-service.chance", 50.0)
            if (rpg.probability.rollPercent(chance)) {
                val maxHp = maxHealthAttr?.let { wolf.getAttribute(it)?.value } ?: 20.0
                wolf.health = (wolf.health + 1.0).coerceAtMost(maxHp)
            }
        }
        if (rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_PUMMEL, owner)) {
            val chance = rpg.skillsConfig.double("taming.pummel.chance", 10.0)
            if (rpg.probability.rollPercent(chance)) {
                val dir = target.location.toVector().subtract(wolf.location.toVector()).normalize()
                target.velocity = dir.multiply(0.8).setY(0.3)
            }
        }
        awardCombatXp(owner, SkillType.TAMING, target, event.damage)
    }

    private fun handleProjectileAttack(
        shooter: Player,
        proj: Projectile,
        target: LivingEntity,
        event: EntityDamageByEntityEvent
    ) {
        val state = rpg.players.getCached(shooter)

        if (proj is Trident) {
            if (!canTriggerCombat(SkillType.TRIDENTS, target)) return
            val impaleRank = rpg.ranks.getRank(state, SubSkillType.TRIDENTS_IMPALE)
            if (impaleRank > 0) {
                event.damage += rpg.skillsConfig.double("tridents.impale.base-damage", 1.0) +
                        impaleRank * rpg.skillsConfig.double("tridents.impale.rank-damage-multiplier", 0.5)
            }
            event.damage += limitBreakBonus(shooter, target, SubSkillType.TRIDENTS_TRIDENTS_LIMIT_BREAK, 1.0)
            awardCombatXp(shooter, SkillType.TRIDENTS, target, event.damage)
            return
        }

        if (proj is Arrow && PlatformCompat.getArrowWeaponType(proj) == Material.CROSSBOW) {
            if (!canTriggerCombat(SkillType.CROSSBOWS, target)) return
            val rank = rpg.ranks.getRank(state, SubSkillType.CROSSBOWS_POWERED_SHOT)
            if (rank > 0) {
                val pct =
                    rank * rpg.skillsConfig.double("crossbows.powered-shot.rank-damage-multiplier", 10.0) / 100.0
                val maxDmg = rpg.skillsConfig.double("crossbows.powered-shot.max-damage", 9.0)
                event.damage += (event.damage * pct).coerceAtMost(maxDmg)
            }
            event.damage += limitBreakBonus(shooter, target, SubSkillType.CROSSBOWS_CROSSBOWS_LIMIT_BREAK, 1.0)
            awardCombatXp(shooter, SkillType.CROSSBOWS, target, event.damage)
            return
        }

        if (proj is AbstractArrow) {
            if (!canTriggerCombat(SkillType.ARCHERY, target)) return
            val rank = rpg.ranks.getRank(state, SubSkillType.ARCHERY_SKILL_SHOT)
            if (rank > 0) {
                val pct = rank * rpg.skillsConfig.double("archery.skill-shot.rank-damage-multiplier", 10.0) / 100.0
                val maxDmg = rpg.skillsConfig.double("archery.skill-shot.max-damage", 9.0)
                event.damage += (event.damage * pct).coerceAtMost(maxDmg)
            }
            event.damage += limitBreakBonus(shooter, target, SubSkillType.ARCHERY_ARCHERY_LIMIT_BREAK, 1.0)
            if (target is Player && rpg.ranks.hasUnlocked(state, SubSkillType.ARCHERY_DAZE, shooter)) {
                val maxLvl = rpg.skillsConfig.maxBonusLevel("archery.daze", context.config.retroMode)
                val maxChance = rpg.skillsConfig.double("archery.daze.chance-max", 50.0)
                if (rpg.probability.rollPercent(
                        rpg.probability.linearChancePercent(
                            state.getLevel(SkillType.ARCHERY),
                            maxLvl,
                            maxChance,
                            shooter,
                            skill = SkillType.ARCHERY,
                        )
                    )
                ) {
                    event.damage += rpg.skillsConfig.double("archery.daze.bonus-damage", 4.0)
                    nauseaEffect?.let { target.addPotionEffect(PotionEffect(it, 120, 0)) }
                    context.locale.sendActionBar(shooter, "gameplay.archery-daze")
                }
            }
            if (target !is Player &&
                !proj.persistentDataContainer.has(infinityArrowKey, PersistentDataType.BYTE) &&
                rpg.ranks.hasUnlocked(state, SubSkillType.ARCHERY_ARROW_RETRIEVAL, shooter)
            ) {
                val maxLvl = rpg.skillsConfig.maxBonusLevel("archery.arrow-retrieval", context.config.retroMode)
                val maxChance = rpg.skillsConfig.double("archery.arrow-retrieval.chance-max", 100.0)
                if (rpg.probability.rollPercent(
                        rpg.probability.linearChancePercent(
                            state.getLevel(SkillType.ARCHERY),
                            maxLvl,
                            maxChance,
                            shooter,
                            skill = SkillType.ARCHERY,
                        )
                    )
                ) {
                    arrowsStuckInEntity.merge(target.uniqueId, 1, Int::plus)
                }
            }
            val distBonus =
                1.0 + shooter.location.distance(target.location) * rpg.experienceConfig.archeryDistanceMultiplier
            awardCombatXp(shooter, SkillType.ARCHERY, target, event.damage * distBonus)
        }
    }

    private fun handleMeleeAttack(attacker: Player, target: LivingEntity, event: EntityDamageByEntityEvent) {
        val hand = attacker.inventory.itemInMainHand.type
        val state = rpg.players.getCached(attacker)
        val maxBaseDmg = attackDamageAttr?.let { attacker.getAttribute(it)?.value } ?: 1.0
        val strength = CombatLimitBreak.deriveAttackStrengthScale(
            rawEventDamage = event.damage,
            maxBaseAttackDamage = maxBaseDmg,
            isSweepAttack = event.cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
            adjustForCooldown = rpg.skillsConfig.adjustForAttackCooldown,
        )

        when {
            hand == Material.AIR -> {
                if (!canTriggerCombat(SkillType.UNARMED, target)) return
                val berserk = rpg.progression.tryActivateAbility(attacker, SkillType.UNARMED) ||
                        state.isAbilityActive(SuperAbilityType.BERSERK)
                val rank = rpg.ranks.getRank(state, SubSkillType.UNARMED_STEEL_ARM_STYLE)
                if (rank > 0) {
                    event.damage += rpg.skillsConfig.doubleAtRank(
                        "unarmed.steel-arm-style.rank-damage",
                        rank,
                        1.0
                    ) * strength
                }
                event.damage += limitBreakBonus(attacker, target, SubSkillType.UNARMED_UNARMED_LIMIT_BREAK, strength)
                if (berserk) {
                    event.damage *= 1.5
                }
                if (target is Player && rpg.ranks.hasUnlocked(state, SubSkillType.UNARMED_DISARM, attacker)) {
                    val maxLvl = rpg.skillsConfig.maxBonusLevel("unarmed.disarm", context.config.retroMode)
                    val maxChance = rpg.skillsConfig.double("unarmed.disarm.chance-max", 33.0)
                    val chance = rpg.probability.linearChancePercent(
                        state.getLevel(SkillType.UNARMED),
                        maxLvl,
                        maxChance,
                        attacker,
                        strength,
                        skill = SkillType.UNARMED,
                    )
                    if (rpg.probability.rollPercent(chance)) {
                        tryDisarm(attacker, target)
                    }
                }
                awardCombatXp(attacker, SkillType.UNARMED, target, event.damage)
            }

            Tag.ITEMS_SWORDS.isTagged(hand) -> {
                if (!canTriggerCombat(SkillType.SWORDS, target)) return
                val serrated = rpg.progression.tryActivateAbility(attacker, SkillType.SWORDS) ||
                        state.isAbilityActive(SuperAbilityType.SERRATED_STRIKES)
                val stabRank = rpg.ranks.getRank(state, SubSkillType.SWORDS_STAB)
                if (stabRank > 0) {
                    event.damage += (rpg.skillsConfig.double("swords.stab.base-damage", 1.0) +
                            stabRank * rpg.skillsConfig.double("swords.stab.per-rank-multiplier", 1.5)) * strength
                }
                event.damage += limitBreakBonus(attacker, target, SubSkillType.SWORDS_SWORDS_LIMIT_BREAK, strength)
                val ruptureRank = rpg.ranks.getRank(state, SubSkillType.SWORDS_RUPTURE)
                if (ruptureRank > 0) {
                    val chance =
                        rpg.skillsConfig.doubleAtRank("swords.rupture.chances", ruptureRank, 15.0) * strength
                    if (rpg.probability.rollPercent(chance)) {
                        applyRupture(attacker, target, ruptureRank)
                        context.locale.sendActionBar(attacker, "gameplay.swords-rupture")
                    }
                }
                if (serrated) {
                    val mod =
                        rpg.skillsConfig.double("swords.serrated-strikes.damage-modifier", 4.0).coerceAtLeast(1.0)
                    dealCleaveDamage(attacker, target, event.damage / mod, 2.5)
                    applyRupture(attacker, target, ruptureRank.coerceAtLeast(1))
                }
                awardCombatXp(attacker, SkillType.SWORDS, target, event.damage)
            }

            Tag.ITEMS_AXES.isTagged(hand) -> {
                if (!canTriggerCombat(SkillType.AXES, target)) return
                val splitter = rpg.progression.tryActivateAbility(attacker, SkillType.AXES) ||
                        state.isAbilityActive(SuperAbilityType.SKULL_SPLITTER)
                val masteryRank = rpg.ranks.getRank(state, SubSkillType.AXES_AXE_MASTERY)
                if (masteryRank > 0) {
                    event.damage += masteryRank * rpg.skillsConfig.double(
                        "axes.axe-mastery.rank-damage-multiplier",
                        1.0
                    ) * strength
                }
                event.damage += limitBreakBonus(attacker, target, SubSkillType.AXES_AXES_LIMIT_BREAK, strength)
                if (rpg.ranks.hasUnlocked(state, SubSkillType.AXES_CRITICAL_STRIKES, attacker)) {
                    val maxLvl = rpg.skillsConfig.maxBonusLevel("axes.critical-strikes", context.config.retroMode)
                    val maxChance = rpg.skillsConfig.double("axes.critical-strikes.chance-max", 37.5)
                    val chance = rpg.probability.linearChancePercent(
                        state.getLevel(SkillType.AXES),
                        maxLvl,
                        maxChance,
                        attacker,
                        strength
                    )
                    if (rpg.probability.rollPercent(chance)) {
                        val mult = if (target is Player) {
                            rpg.skillsConfig.double("axes.critical-strikes.pvp-modifier", 1.5)
                        } else {
                            rpg.skillsConfig.double("axes.critical-strikes.pve-modifier", 2.0)
                        }
                        event.damage *= mult
                        context.locale.sendActionBar(attacker, "gameplay.axes-critical")
                    }
                }
                if (rpg.ranks.hasUnlocked(state, SubSkillType.AXES_GREATER_IMPACT, attacker)) {
                    val chance = rpg.skillsConfig.double("axes.greater-impact.chance", 25.0) * strength
                    if (rpg.probability.rollPercent(chance)) {
                        event.damage += rpg.skillsConfig.double("axes.greater-impact.bonus-damage", 2.0)
                        val kb = rpg.skillsConfig.double("axes.greater-impact.knockback-modifier", 1.5)
                        val dir = target.location.toVector().subtract(attacker.location.toVector()).normalize()
                        target.velocity = dir.multiply(kb * 0.6).setY(0.35)
                        context.locale.sendActionBar(attacker, "gameplay.axes-greater-impact")
                    }
                }
                val armorImpactRank = rpg.ranks.getRank(state, SubSkillType.AXES_ARMOR_IMPACT)
                if (armorImpactRank > 0 && rpg.probability.rollPercent(
                        rpg.skillsConfig.double(
                            "axes.armor-impact.chance",
                            25.0
                        ) * strength
                    )
                ) {
                    applyArmorImpact(target, armorImpactRank)
                    context.locale.sendActionBar(attacker, "gameplay.axes-armor-impact")
                }
                if (splitter) {
                    val mod = rpg.skillsConfig.double("axes.skull-splitter.damage-modifier", 2.0).coerceAtLeast(1.0)
                    dealCleaveDamage(attacker, target, event.damage / mod, 2.5)
                }
                awardCombatXp(attacker, SkillType.AXES, target, event.damage)
            }

            PlatformCompat.isMace(hand) -> {
                if (!canTriggerCombat(SkillType.MACES, target)) return
                val slam = rpg.progression.tryActivateAbility(attacker, SkillType.MACES) ||
                        state.isAbilityActive(SuperAbilityType.MACES_SUPER_ABILITY)
                val crushRank = rpg.ranks.getRank(state, SubSkillType.MACES_CRUSH)
                if (crushRank > 0) {
                    event.damage += (rpg.skillsConfig.double("maces.crush.base-damage", 0.5) +
                            crushRank * rpg.skillsConfig.double(
                        "maces.crush.rank-damage-multiplier",
                        1.0
                    )) * strength
                }
                event.damage += limitBreakBonus(attacker, target, SubSkillType.MACES_MACES_LIMIT_BREAK, strength)
                val crippleRank = rpg.ranks.getRank(state, SubSkillType.MACES_CRIPPLE)
                if (crippleRank > 0) {
                    val chance =
                        rpg.skillsConfig.doubleAtRank("maces.cripple.chances", crippleRank, 10.0) * strength
                    if (rpg.probability.rollPercent(chance)) {
                        val dur = if (target is Player) 20 else 60
                        slownessEffect?.let { eff ->
                            target.addPotionEffect(
                                PotionEffect(
                                    eff,
                                    dur,
                                    if (target is Player) 1 else 2
                                )
                            )
                        }
                        target.world.spawnParticle(Particle.CRIT, target.location.add(0.0, 1.0, 0.0), 10)
                        context.locale.sendActionBar(attacker, "gameplay.maces-cripple")
                    }
                }
                if (slam) {
                    dealCleaveDamage(attacker, target, event.damage * 0.5, 3.0)
                    explosionParticle?.let { target.world.spawnParticle(it, target.location, 1) }
                }
                awardCombatXp(attacker, SkillType.MACES, target, event.damage)
            }

            PlatformCompat.isSpear(hand) -> {
                if (!canTriggerCombat(SkillType.SPEARS, target)) return
                val thrust = rpg.progression.tryActivateAbility(attacker, SkillType.SPEARS) ||
                        state.isAbilityActive(SuperAbilityType.SPEARS_SUPER_ABILITY)
                val masteryRank = rpg.ranks.getRank(state, SubSkillType.SPEARS_SPEAR_MASTERY)
                if (masteryRank > 0) {
                    event.damage += masteryRank * rpg.skillsConfig.double(
                        "spears.spear-mastery.rank-damage-multiplier",
                        0.4
                    ) * strength
                }
                event.damage += limitBreakBonus(attacker, target, SubSkillType.SPEARS_SPEARS_LIMIT_BREAK, strength)
                val momentumRank = rpg.ranks.getRank(state, SubSkillType.SPEARS_MOMENTUM)
                if (momentumRank > 0) {
                    val chance =
                        rpg.skillsConfig.doubleAtRank("spears.momentum.chances", momentumRank, 5.0) * strength
                    if (rpg.probability.rollPercent(chance)) {
                        speedEffect?.let { attacker.addPotionEffect(PotionEffect(it, 20 * (momentumRank * 2), 1)) }
                        context.locale.sendActionBar(attacker, "gameplay.spears-momentum")
                    }
                }
                if (thrust) {
                    event.damage *= 1.35
                }
                awardCombatXp(attacker, SkillType.SPEARS, target, event.damage)
            }

            hand == Material.TRIDENT -> {
                if (!canTriggerCombat(SkillType.TRIDENTS, target)) return
                val surge = rpg.progression.tryActivateAbility(attacker, SkillType.TRIDENTS) ||
                        state.isAbilityActive(SuperAbilityType.TRIDENTS_SUPER_ABILITY)
                val impaleRank = rpg.ranks.getRank(state, SubSkillType.TRIDENTS_IMPALE)
                if (impaleRank > 0) {
                    event.damage += (rpg.skillsConfig.double("tridents.impale.base-damage", 1.0) +
                            impaleRank * rpg.skillsConfig.double(
                        "tridents.impale.rank-damage-multiplier",
                        0.5
                    )) * strength
                }
                event.damage += limitBreakBonus(attacker, target, SubSkillType.TRIDENTS_TRIDENTS_LIMIT_BREAK, strength)
                if (surge) {
                    event.damage *= 1.35
                }
                awardCombatXp(attacker, SkillType.TRIDENTS, target, event.damage)
            }
        }
    }

    private fun limitBreakBonus(
        attacker: Player,
        target: LivingEntity,
        subSkill: SubSkillType,
        strength: Double
    ): Double {
        if (target !is Player && !rpg.skillsConfig.limitBreakAllowPve) return 0.0
        val state = rpg.players.getCached(attacker)
        if (!rpg.ranks.hasUnlocked(state, subSkill, attacker)) return 0.0
        val rank = rpg.ranks.getRank(state, subSkill)
        val armorQuality = if (target is Player) CombatLimitBreak.armorQualityOf(target) else 1000
        return CombatLimitBreak.calculateBonus(rank, armorQuality) * strength
    }

    private fun isInvalidCombatEntity(entity: Entity): Boolean {
        val exp = rpg.experienceConfig
        if (exp.preventArmorStandInteraction && entity is ArmorStand) return true
        if (exp.preventMannequinInteraction && entity.type.name == "MANNEQUIN") return true
        if (exp.preventPluginNpcInteraction && entity.hasMetadata("NPC")) return true
        return false
    }

    private fun canTriggerCombat(skill: SkillType, target: LivingEntity): Boolean {
        val isPvp = target is Player || (target is Tameable && target.isTamed)
        return if (isPvp) rpg.skillsConfig.isPvpEnabled(skill) else rpg.skillsConfig.isPveEnabled(skill)
    }

    private fun awardCombatXp(player: Player, skill: SkillType, target: LivingEntity, damage: Double) {
        val reason = if (target is Player) XpGainReason.PVP else XpGainReason.PVE
        if (reason == XpGainReason.PVP && !rpg.experienceConfig.pvpMultiplier.let { it > 0.0 }) return

        val spawnReason = rpg.placedBlocks.getSpawnReason(target)
        val spawnMult = when (spawnReason) {
            "SPAWNER" -> rpg.experienceConfig.spawnerMultiplier
            "SPAWN_EGG" -> rpg.experienceConfig.spawnEggMultiplier
            "NETHER_PORTAL" -> rpg.experienceConfig.netherPortalMultiplier
            "BREEDING" -> rpg.experienceConfig.breedingMultiplier
            "ENDERMITE_FARM" -> 0.0
            else -> if (target is Tameable && target.isTamed) rpg.experienceConfig.playerTamedMultiplier else 1.0
        }
        if (spawnMult <= 0.0) return

        val cappedDmg = if (rpg.experienceConfig.combatXpCeilingEnabled) {
            damage.coerceAtMost(rpg.experienceConfig.combatXpDamageLimit)
        } else {
            damage
        }
        val entityMult = rpg.experienceConfig.combatMultiplier(target.type)
        val rawXp = cappedDmg * 10.0 * entityMult * spawnMult
        rpg.progression.awardXp(player, skill, rawXp, reason)
    }

    private fun applyRupture(attacker: Player, target: LivingEntity, rank: Int) {
        val durationSec = rpg.skillsConfig.int("swords.rupture.duration-seconds", 5).coerceAtLeast(1)
        val dmgPerTick = if (target is Player) {
            rpg.skillsConfig.doubleAtRank("swords.rupture.player-tick-damage", rank, 0.1)
        } else {
            rpg.skillsConfig.doubleAtRank("swords.rupture.mob-tick-damage", rank, 0.5)
        }
        val expiresAt = System.currentTimeMillis() + durationSec * 1000L
        val alreadyRunning = activeRuptures.containsKey(target.uniqueId)
        activeRuptures[target.uniqueId] = expiresAt
        if (alreadyRunning) return

        context.scheduler.runForEntityTimer(
            target,
            10L,
            10L,
            retired = { activeRuptures.remove(target.uniqueId) }) { task ->
            val exp = activeRuptures[target.uniqueId]
            if (exp == null || System.currentTimeMillis() >= exp || target.isDead || !target.isValid) {
                activeRuptures.remove(target.uniqueId)
                task.cancel()
                return@runForEntityTimer
            }
            if (target.health > dmgPerTick) {
                target.health = (target.health - dmgPerTick).coerceAtLeast(1.0)
                target.world.spawnParticle(Particle.DAMAGE_INDICATOR, target.location.add(0.0, 1.0, 0.0), 3)
            } else {
                activeRuptures.remove(target.uniqueId)
                task.cancel()
                target.damage(dmgPerTick, attacker)
            }
        }
    }

    private fun dealCleaveDamage(attacker: Player, primaryTarget: LivingEntity, damage: Double, radius: Double) {
        for (nearby in primaryTarget.getNearbyEntities(radius, radius, radius)) {
            if (nearby is LivingEntity && nearby != attacker && nearby != primaryTarget) {
                if (nearby is Player && isFriendlyPartyMember(attacker, nearby)) continue
                nearby.damage(damage, attacker)
            }
        }
    }

    private fun applyArmorImpact(target: LivingEntity, rank: Int) {
        val equipment = target.equipment ?: return
        val dmg =
            (rank * rpg.skillsConfig.double("axes.armor-impact.damage-per-rank", 6.5)).toInt().coerceAtLeast(1)
        for (piece in equipment.armorContents) {
            if (piece == null || piece.type == Material.AIR) continue
            val meta = piece.itemMeta as? Damageable ?: continue
            val max = piece.type.maxDurability.toInt()
            if (max <= 0) continue
            val cap = (max * 0.2).toInt().coerceAtLeast(1)
            meta.damage = (meta.damage + dmg.coerceAtMost(cap)).coerceAtMost(max)
            piece.itemMeta = meta
        }
    }

    private fun tryDisarm(attacker: Player, target: Player) {
        val targetState = rpg.players.getCached(target)
        if (rpg.ranks.hasUnlocked(targetState, SubSkillType.UNARMED_IRON_GRIP, target)) {
            val maxLvl = rpg.skillsConfig.maxBonusLevel("unarmed.iron-grip", context.config.retroMode)
            val maxChance = rpg.skillsConfig.double("unarmed.iron-grip.chance-max", 100.0)
            val resist = rpg.probability.linearChancePercent(
                targetState.getLevel(SkillType.UNARMED),
                maxLvl,
                maxChance,
                target
            )
            if (rpg.probability.rollPercent(resist)) {
                context.locale.sendActionBar(target, "gameplay.unarmed-iron-grip")
                return
            }
        }
        val held = target.inventory.itemInMainHand
        if (held.type == Material.AIR) return
        val clone = held.clone()
        target.inventory.setItemInMainHand(ItemStack(Material.AIR))
        val dropped = target.world.dropItemNaturally(target.location, clone)
        if (rpg.skillsConfig.boolean("unarmed.disarm.anti-theft", false)) {
            dropped.persistentDataContainer.set(disarmOwnerKey, PersistentDataType.STRING, target.uniqueId.toString())
            dropped.persistentDataContainer.set(
                disarmExpiryKey,
                PersistentDataType.LONG,
                System.currentTimeMillis() + 5000L
            )
        }
        context.locale.sendActionBar(attacker, "gameplay.unarmed-disarm")
    }

    private fun tryCallOfTheWild(player: Player, item: ItemStack): Boolean {
        val state = rpg.players.getCached(player)
        if (!rpg.ranks.hasUnlocked(state, SubSkillType.TAMING_CALL_OF_THE_WILD, player)) return false

        val (entityType, requiredAmount, mobName) = when (item.type) {
            Material.BONE -> Triple(
                EntityType.WOLF,
                rpg.skillsConfig.int("taming.call-of-the-wild.wolf-bones", 10),
                "Wolf"
            )

            Material.COD, Material.SALMON -> Triple(
                EntityType.CAT,
                rpg.skillsConfig.int("taming.call-of-the-wild.ocelot-fish", 10),
                "Cat"
            )

            Material.APPLE, Material.GOLDEN_APPLE -> Triple(
                EntityType.HORSE,
                rpg.skillsConfig.int("taming.call-of-the-wild.horse-apples", 10),
                "Horse"
            )

            else -> return false
        }

        val now = System.currentTimeMillis()
        val cdSec = rpg.skillsConfig.int("taming.call-of-the-wild.cooldown-seconds", 30)
        val nextAllowed = callOfWildCooldowns[player.uniqueId] ?: 0L
        if (now < nextAllowed) {
            context.locale.sendActionBar(
                player,
                "general.cooldown-active",
                "time" to TextFormatter.formatDuration(nextAllowed - now),
            )
            return true
        }

        val maxSummons = rpg.skillsConfig.int("taming.call-of-the-wild.max-summons", 2).coerceAtLeast(1)
        val ownerSummons = activeSummonsByOwner.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }
        ownerSummons.removeIf { !it.isValid || it.isDead }
        val activeOfType = ownerSummons.count { it.type == entityType }
        if (activeOfType >= maxSummons) {
            context.locale.sendActionBar(
                player,
                "gameplay.taming-summon-limit",
                "mob" to mobName,
                "max" to maxSummons,
            )
            return true
        }

        if (item.amount < requiredAmount) {
            context.locale.sendActionBar(
                player,
                "gameplay.taming-need-items",
                "amount" to requiredAmount,
                "item" to item.type.name,
                "mob" to mobName,
            )
            return true
        }

        item.amount -= requiredAmount
        callOfWildCooldowns[player.uniqueId] = now + cdSec * 1000L
        val spawned = player.world.spawnEntity(player.location, entityType)
        if (spawned is Tameable) {
            spawned.isTamed = true
            spawned.owner = player
        }
        if (spawned is AbstractHorse) {
            spawned.jumpStrength = ThreadLocalRandom.current().nextDouble(0.7, 1.0)
        }
        if (spawned is LivingEntity) {
            ownerSummons.add(spawned)
            transientSummonIds.add(spawned.uniqueId)
            val lifeSec =
                rpg.skillsConfig.int("taming.call-of-the-wild.summon-lifetime-seconds", 240).coerceAtLeast(10)
            context.scheduler.runForEntityDelayed(spawned, lifeSec * 20L) {
                ownerSummons.remove(spawned)
                transientSummonIds.remove(spawned.uniqueId)
                if (spawned.isValid) {
                    spawned.world.playSound(spawned.location, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.0f)
                    smokeParticle?.let { spawned.world.spawnParticle(it, spawned.location.add(0.0, 0.5, 0.0), 8) }
                    spawned.remove()
                    if (player.isOnline) {
                        context.locale.sendActionBar(player, "gameplay.taming-summon-expired", "mob" to mobName)
                    }
                }
            }
        }
        context.locale.sendActionBar(player, "gameplay.taming-summoned", "mob" to mobName)
        return true
    }
}
