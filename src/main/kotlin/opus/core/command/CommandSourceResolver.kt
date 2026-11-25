package opus.core.command

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.ComponentLike
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.lang.reflect.Method

/**
 * Resolves a Paper [CommandSourceStack] for a [CommandSender] when evaluating Brigadier requirements
 * or tab-completion suggestions outside of direct Brigadier command execution.
 */
object CommandSourceResolver {

    private val getListenerMethod: Method? by lazy {
        runCatching {
            val clazz = Class.forName("org.bukkit.craftbukkit.command.VanillaCommandWrapper")
            clazz.getMethod("getListener", CommandSender::class.java)
        }.getOrNull()
    }

    fun resolve(sender: CommandSender, location: Location? = null): CommandSourceStack {
        val nativeStack = runCatching {
            getListenerMethod?.invoke(null, sender) as? CommandSourceStack
        }.getOrNull()

        if (nativeStack != null) {
            return if (location != null) nativeStack.withLocation(location) else nativeStack
        }

        val resolvedLoc = location ?: when (sender) {
            is Entity -> sender.location
            is BlockCommandSender -> sender.block.location
            else -> Bukkit.getWorlds().firstOrNull()?.spawnLocation
                ?: Location(null, 0.0, 64.0, 0.0)
        }
        return FallbackCommandSourceStack(sender, resolvedLoc, sender as? Entity)
    }

    private class FallbackCommandSourceStack(
        private val sender: CommandSender,
        private val location: Location,
        private val executor: Entity?,
    ) : CommandSourceStack {

        override fun getLocation(): Location = location.clone()

        override fun getSender(): CommandSender = sender

        override fun getExecutor(): Entity? = executor

        override fun getPlayerOrThrow(): Player =
            (executor as? Player) ?: (sender as? Player)
            ?: throw NOT_PLAYER.create()

        override fun getEntityOrThrow(): Entity =
            executor ?: (sender as? Entity)
            ?: throw NOT_ENTITY.create()

        override fun withLocation(location: Location): CommandSourceStack =
            FallbackCommandSourceStack(sender, location, executor)

        override fun withExecutor(executor: Entity): CommandSourceStack =
            FallbackCommandSourceStack(sender, location, executor)

        override fun sendReply(message: ComponentLike) {
            sender.sendMessage(message)
        }

        override fun sendSuccess(message: ComponentLike, broadcastToOps: Boolean) {
            sender.sendMessage(message)
        }

        override fun sendFailure(message: ComponentLike) {
            sender.sendMessage(message)
        }

        companion object {
            private val NOT_PLAYER = SimpleCommandExceptionType(LiteralMessage("Requires a player"))
            private val NOT_ENTITY = SimpleCommandExceptionType(LiteralMessage("Requires an entity"))
        }
    }
}
