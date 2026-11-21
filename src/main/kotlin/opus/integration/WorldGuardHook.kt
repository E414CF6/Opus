package opus.integration

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Soft reflection-based WorldGuard hook checking optional custom region flags (`opus`, `opus-xp`, `opus-hardcore`).
 * Fails open (`true`) whenever WorldGuard is absent or a flag is not registered.
 */
class WorldGuardHook(private val plugin: Plugin) {

    private var available: Boolean = false
    private var opusFlag: Any? = null
    private var xpFlag: Any? = null
    private var hardcoreFlag: Any? = null

    fun registerFlagsOnLoad() {
        runCatching {
            val wgClass = Class.forName("com.sk89q.worldguard.WorldGuard")
            val wgInstance = wgClass.getMethod("getInstance").invoke(null)
            val registry = wgClass.getMethod("getFlagRegistry").invoke(wgInstance)
            val stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag")
            val ctor = stateFlagClass.getConstructor(String::class.java, Boolean::class.javaPrimitiveType)
            val registerMethod = registry.javaClass.getMethod(
                "register",
                Class.forName("com.sk89q.worldguard.protection.flags.Flag"),
            )
            val getMethod = registry.javaClass.getMethod("get", String::class.java)

            fun registerOrGet(name: String): Any? = runCatching {
                val created = ctor.newInstance(name, true)
                registerMethod.invoke(registry, created)
                created
            }.getOrElse {
                getMethod.invoke(registry, name)
            }

            opusFlag = registerOrGet("opus")
            xpFlag = registerOrGet("opus-xp")
            hardcoreFlag = registerOrGet("opus-hardcore")
            available = true
            plugin.logger.info("Registered WorldGuard region flags (opus, opus-xp, opus-hardcore).")
        }
    }

    fun refreshAvailability() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            available = false
            return
        }
        if (opusFlag == null) {
            runCatching {
                val wgClass = Class.forName("com.sk89q.worldguard.WorldGuard")
                val wgInstance = wgClass.getMethod("getInstance").invoke(null)
                val registry = wgClass.getMethod("getFlagRegistry").invoke(wgInstance)
                val getMethod = registry.javaClass.getMethod("get", String::class.java)
                opusFlag = getMethod.invoke(registry, "opus")
                xpFlag = getMethod.invoke(registry, "opus-xp")
                hardcoreFlag = getMethod.invoke(registry, "opus-hardcore")
                available = true
            }
        } else {
            available = true
        }
    }

    fun isEnabledAt(player: Player): Boolean = testFlag(player, opusFlag)

    fun isXpAllowedAt(player: Player): Boolean = isEnabledAt(player) && testFlag(player, xpFlag)

    fun isHardcoreAllowedAt(player: Player): Boolean = isEnabledAt(player) && testFlag(player, hardcoreFlag)

    private fun testFlag(player: Player, flag: Any?): Boolean {
        if (!available || flag == null) return true
        return runCatching {
            val wgClass = Class.forName("com.sk89q.worldguard.WorldGuard")
            val wgInstance = wgClass.getMethod("getInstance").invoke(null)
            val platform = wgClass.getMethod("getPlatform").invoke(wgInstance)
            val container = platform.javaClass.getMethod("getRegionContainer").invoke(platform)
            val query = container.javaClass.getMethod("createQuery").invoke(container)

            val adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
            val weLoc = adapterClass.getMethod("adapt", org.bukkit.Location::class.java).invoke(null, player.location)

            val wgBukkitPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin")
            val wgPlugin = wgBukkitPluginClass.getMethod("inst").invoke(null)
            val localPlayer = wgBukkitPluginClass.getMethod("wrapPlayer", Player::class.java).invoke(wgPlugin, player)

            val stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag")
            val stateFlagArray = java.lang.reflect.Array.newInstance(stateFlagClass, 1)
            java.lang.reflect.Array.set(stateFlagArray, 0, flag)

            val testState = query.javaClass.getMethod(
                "testState",
                Class.forName("com.sk89q.worldedit.util.Location"),
                Class.forName("com.sk89q.worldguard.protection.RegionAssociable"),
                stateFlagArray.javaClass,
            )
            testState.invoke(query, weLoc, localPlayer, stateFlagArray) as? Boolean ?: true
        }.getOrDefault(true)
    }
}
