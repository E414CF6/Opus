package opus.core.compat

import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.block.BrewingStand
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffectType
import java.lang.reflect.Method
import java.util.Locale

/**
 * Runtime compatibility shims allowing Java 17 bytecode compiled against Paper 26.3 API
 * to run cleanly across Paper/Folia `1.20.4–26.3+`.
 */
object PlatformCompat {

    val supportsDialogs: Boolean by lazy {
        hasClass("io.papermc.paper.dialog.Dialog") &&
                hasClass("io.papermc.paper.registry.data.dialog.DialogBase") &&
                runCatching {
                    Audience::class.java.methods.any { it.name == "showDialog" }
                }.getOrDefault(false)
    }

    val supportsFoliaSchedulers: Boolean by lazy {
        hasClass("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler") &&
                runCatching {
                    Bukkit::class.java.methods.any { it.name == "getGlobalRegionScheduler" }
                }.getOrDefault(false)
    }

    val supportsPaperAsyncChat: Boolean by lazy {
        hasClass("io.papermc.paper.event.player.AsyncChatEvent")
    }

    val supportsBrewingRecipe: Boolean by lazy {
        hasClass("org.bukkit.inventory.BrewingRecipe")
    }

    private val itemsSpearsTag: Tag<Material>? by lazy {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            Tag::class.java.getField("ITEMS_SPEARS").get(null) as? Tag<Material>
        }.getOrNull()
    }

    private val arrowGetWeaponMethod: Method? by lazy {
        runCatching { Arrow::class.java.getMethod("getWeapon") }.getOrNull()
    }

    private val arrowSetWeaponMethod: Method? by lazy {
        runCatching { Arrow::class.java.getMethod("setWeapon", ItemStack::class.java) }.getOrNull()
    }

    private val hasCustomModelDataComponentMethod: Method? by lazy {
        runCatching { ItemMeta::class.java.getMethod("hasCustomModelDataComponent") }.getOrNull()
    }

    private val getRespawnLocationMethod: Method? by lazy {
        runCatching { Player::class.java.getMethod("getRespawnLocation") }.getOrNull()
    }

    private val setRecipeBrewTimeMethod: Method? by lazy {
        runCatching {
            BrewingStand::class.java.getMethod(
                "setRecipeBrewTime",
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    fun getRespawnLocation(player: Player): Location? {
        val m = getRespawnLocationMethod
        if (m != null) {
            return runCatching { m.invoke(player) as? Location }.getOrNull()
        }
        @Suppress("DEPRECATION")
        return runCatching { player.bedSpawnLocation }.getOrNull()
    }

    fun setBrewingStandTime(stand: BrewingStand, ticks: Int) {
        val m = setRecipeBrewTimeMethod
        if (m != null) {
            val ok = runCatching {
                m.invoke(stand, ticks)
                true
            }.getOrDefault(false)
            if (ok) return
        }
        stand.brewingTime = ticks
    }

    fun hasCustomModelData(meta: ItemMeta?): Boolean {
        if (meta == null) return false
        val compMethod = hasCustomModelDataComponentMethod
        if (compMethod != null) {
            val res = runCatching { compMethod.invoke(meta) as? Boolean }.getOrNull()
            if (res == true) return true
        }
        @Suppress("DEPRECATION")
        return runCatching { meta.hasCustomModelData() }.getOrDefault(false)
    }

    private val registrySoundsField = runCatching {
        Registry::class.java.getField("SOUNDS")
    }.getOrNull()

    private val registryMobEffectField = runCatching {
        Registry::class.java.getField("MOB_EFFECT")
    }.getOrNull()

    fun resolvePotionEffect(vararg candidateNames: String): PotionEffectType? {
        for (name in candidateNames) {
            val clean = name.trim().uppercase(Locale.ROOT)
            val fromField = runCatching {
                PotionEffectType::class.java.getField(clean).get(null) as? PotionEffectType
            }.getOrNull()
            if (fromField != null) return fromField

            val key = NamespacedKey.minecraft(clean.lowercase(Locale.ROOT))
            val resolved = resolvePotionEffectByKey(key)
            if (resolved != null) return resolved
        }
        return null
    }

    fun resolvePotionEffectByKey(key: NamespacedKey): PotionEffectType? {
        val mobEffectField = registryMobEffectField
        if (mobEffectField != null) {
            @Suppress("UNCHECKED_CAST")
            val reg = runCatching { mobEffectField.get(null) as? Registry<PotionEffectType> }.getOrNull()
            val found = reg?.get(key)
            if (found != null) return found
        }
        @Suppress("DEPRECATION")
        return runCatching { PotionEffectType.getByKey(key) }.getOrNull()
            ?: runCatching { PotionEffectType.getByName(key.key.uppercase(Locale.ROOT)) }.getOrNull()
    }

    fun hasClass(name: String): Boolean =
        runCatching {
            Class.forName(name, false, PlatformCompat::class.java.classLoader)
            true
        }.getOrDefault(false)

    fun serverVersionLabel(): String =
        runCatching { Bukkit.getMinecraftVersion() }.getOrElse {
            runCatching { Bukkit.getBukkitVersion() }.getOrDefault("unknown")
        }

    fun pluginVersion(plugin: Plugin): String =
        runCatching { plugin.pluginMeta.version }.getOrElse {
            @Suppress("DEPRECATION")
            plugin.description.version
        }

    fun resolveMaterial(name: String, fallback: Material): Material =
        Material.matchMaterial(name.uppercase(Locale.ROOT)) ?: fallback

    fun isMace(material: Material): Boolean = material.name == "MACE"

    fun isSpear(material: Material): Boolean =
        itemsSpearsTag?.isTagged(material) ?: material.name.endsWith("_SPEAR")

    fun resolveAttribute(vararg candidateNames: String): Attribute? {
        for (name in candidateNames) {
            val clean = name.trim().uppercase(Locale.ROOT)
            val fromField = runCatching {
                Attribute::class.java.getField(clean).get(null) as? Attribute
            }.getOrNull()
            if (fromField != null) return fromField

            val keyName = clean.lowercase(Locale.ROOT)
            val fromRegistry = runCatching {
                NamespacedKey.minecraft(keyName).let { Registry.ATTRIBUTE.get(it) }
            }.getOrNull()
            if (fromRegistry != null) return fromRegistry
        }
        return null
    }

    fun resolveEnchantment(vararg candidateNames: String): Enchantment? {
        for (name in candidateNames) {
            val clean = name.trim().uppercase(Locale.ROOT)
            val fromField = runCatching {
                Enchantment::class.java.getField(clean).get(null) as? Enchantment
            }.getOrNull()
            if (fromField != null) return fromField

            val keyName = clean.lowercase(Locale.ROOT)

            @Suppress("DEPRECATION")
            val fromKey = runCatching {
                Enchantment.getByKey(NamespacedKey.minecraft(keyName))
            }.getOrNull()
            if (fromKey != null) return fromKey
        }
        return null
    }

    fun resolveParticle(vararg candidateNames: String): Particle? {
        for (name in candidateNames) {
            val clean = name.trim().uppercase(Locale.ROOT)
            val found = runCatching { Particle.valueOf(clean) }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    fun resolveSound(rawKey: String): Sound? {
        val cleaned = rawKey.trim().lowercase(Locale.ROOT).replace('_', '.')
        val namespaced = if (':' in cleaned) cleaned else "minecraft:$cleaned"
        val nsKey = NamespacedKey.fromString(namespaced)
        val soundsField = registrySoundsField
        if (nsKey != null && soundsField != null) {
            @Suppress("UNCHECKED_CAST")
            val registry = runCatching { soundsField.get(null) as? Registry<Sound> }.getOrNull()
            val resolved = registry?.get(nsKey)
            if (resolved != null) return resolved
        }
        val enumName = rawKey.trim().substringAfter(':').uppercase(Locale.ROOT).replace('.', '_')
        return runCatching {
            Sound::class.java.getMethod("valueOf", String::class.java).invoke(null, enumName) as? Sound
        }.getOrNull()
    }

    fun getArrowWeaponType(arrow: Arrow): Material? {
        val method = arrowGetWeaponMethod
        if (method != null) {
            val stack = runCatching { method.invoke(arrow) as? ItemStack }.getOrNull()
            if (stack != null) return stack.type
        }
        val raw = NamespacedKey.fromString("opus:arrow_weapon_type")?.let { key ->
            arrow.persistentDataContainer.get(key, PersistentDataType.STRING)
        } ?: return null
        return Material.matchMaterial(raw)
    }

    fun setArrowWeapon(arrow: Arrow, item: ItemStack?) {
        val method = arrowSetWeaponMethod
        if (method != null) {
            val invoked = runCatching {
                method.invoke(arrow, item)
                true
            }.getOrDefault(false)
            if (invoked && item != null) {
                NamespacedKey.fromString("opus:arrow_weapon_type")?.let { key ->
                    arrow.persistentDataContainer.set(key, PersistentDataType.STRING, item.type.name)
                }
                return
            }
        }
        if (item != null) {
            NamespacedKey.fromString("opus:arrow_weapon_type")?.let { key ->
                arrow.persistentDataContainer.set(key, PersistentDataType.STRING, item.type.name)
            }
        }
    }
}
