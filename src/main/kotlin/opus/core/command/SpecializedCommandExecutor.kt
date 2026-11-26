package opus.core.command

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.title.Title
import opus.core.CoreHost
import opus.core.compat.PlatformCompat
import opus.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import java.time.Duration

/**
 * Shared command/action dispatcher for custom aliases and milestone commands.
 * Supports prefixes: `console:`, `player:`, `op:`, `msg:`/`message:`, `broadcast:`/`bc:`,
 * `actionbar:`, `bossbar:`, `title:`, `sound:`, `teleport:`/`tp:`, delays (`delay!<sec>`),
 * and inline conditions (`if:<cond>? <action>`).
 */
object SpecializedCommandExecutor {

    fun executeAll(
        host: CoreHost,
        rawLines: List<String>,
        player: Player? = null,
        extraPlaceholders: Map<String, String> = emptyMap(),
    ) {
        if (rawLines.isEmpty()) return
        executeFromIndex(host, rawLines, 0, player, extraPlaceholders)
    }

    private fun executeFromIndex(
        host: CoreHost,
        lines: List<String>,
        index: Int,
        player: Player?,
        extraPlaceholders: Map<String, String>,
    ) {
        var i = index
        while (i < lines.size) {
            val raw = lines[i].trim()
            i++
            if (raw.isEmpty() || raw.startsWith("#")) continue

            val delayTicks = parseDelayTicks(raw)
            if (delayTicks != null) {
                val nextIdx = i
                if (nextIdx >= lines.size) return
                if (player != null && player.isOnline) {
                    host.scheduler.runForEntityDelayed(player, delayTicks) {
                        executeFromIndex(host, lines, nextIdx, player, extraPlaceholders)
                    }
                } else {
                    host.scheduler.runGlobalDelayed(delayTicks) {
                        executeFromIndex(host, lines, nextIdx, player, extraPlaceholders)
                    }
                }
                return
            }

            executeSingleLine(host, raw, player, extraPlaceholders)
        }
    }

    internal fun executeSingleLine(
        host: CoreHost,
        rawLine: String,
        player: Player?,
        extraPlaceholders: Map<String, String> = emptyMap(),
    ) {
        var line = rawLine.trim()
        if (line.startsWith("if:", ignoreCase = true) && line.contains('?')) {
            val splitIdx = line.indexOf('?')
            val condition = line.substring(3, splitIdx).trim()
            if (!evaluateCondition(host, condition, player)) return
            line = line.substring(splitIdx + 1).trim()
        }

        val resolved = resolvePlaceholders(host, line, player, extraPlaceholders)
        val lower = resolved.lowercase()

        when {
            lower.startsWith("console:") -> {
                val cmd = resolved.substring(8).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    host.scheduler.runGlobal {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    }
                }
            }

            lower.startsWith("player:") -> {
                val target = player ?: return
                val cmd = resolved.substring(7).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    host.scheduler.runForEntity(target) {
                        Bukkit.dispatchCommand(target, cmd)
                    }
                }
            }

            lower.startsWith("op:") -> {
                val target = player ?: return
                val cmd = resolved.substring(3).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    host.scheduler.runForEntity(target) {
                        val wasOp = target.isOp
                        try {
                            if (!wasOp) target.isOp = true
                            Bukkit.dispatchCommand(target, cmd)
                        } finally {
                            if (!wasOp) target.isOp = false
                        }
                    }
                }
            }

            lower.startsWith("msg:") || lower.startsWith("message:") -> {
                val payload = resolved.substringAfter(':').trim()
                val comp = TextFormatter.format(payload)
                if (player != null) {
                    player.sendMessage(comp)
                } else {
                    Bukkit.getConsoleSender().sendMessage(comp)
                }
            }

            lower.startsWith("broadcast:") || lower.startsWith("bc:") -> {
                val payload = resolved.substringAfter(':').trim()
                Bukkit.broadcast(TextFormatter.format(payload))
            }

            lower.startsWith("actionbar:") -> {
                val target = player ?: return
                val payload = resolved.substring(10).trim()
                host.actionBar.pushTransientRaw(target, payload)
            }

            lower.startsWith("bossbar:") -> {
                val target = player ?: return
                val parts = resolved.substring(8).split('|', limit = 3)
                val color = runCatching {
                    BossBar.Color.valueOf(parts[0].trim().uppercase())
                }.getOrDefault(BossBar.Color.YELLOW)
                val seconds = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, 300) ?: 5
                val msg = parts.getOrNull(2)?.trim() ?: parts.last().trim()
                host.bossBar.showTemporary(
                    player = target,
                    key = "spec_${target.uniqueId}",
                    title = TextFormatter.format(msg),
                    progress = 1.0f,
                    color = color,
                    durationTicks = seconds * 20L,
                )
            }

            lower.startsWith("title:") -> {
                val target = player ?: return
                val parts = resolved.substring(6).split('|', limit = 2)
                val titleComp = TextFormatter.format(parts[0].trim())
                val subComp = TextFormatter.format(parts.getOrElse(1) { "" }.trim())
                target.showTitle(
                    Title.title(
                        titleComp,
                        subComp,
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(250)),
                    )
                )
            }

            lower.startsWith("sound:") -> {
                val target = player ?: return
                val parts = resolved.substring(6).split('|')
                val sound = PlatformCompat.resolveSound(parts[0]) ?: return
                val volume = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 10f) ?: 1.0f
                val pitch = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f
                target.playSound(target.location, sound, SoundCategory.MASTER, volume, pitch)
            }

            lower.startsWith("teleport:") || lower.startsWith("tp:") -> {
                val target = player ?: return
                val payload = resolved.substringAfter(':').trim()
                val loc = parseSimpleLocation(payload) ?: return
                target.teleportAsync(loc)
            }

            else -> {
                val cmd = resolved.removePrefix("/")
                if (cmd.isNotEmpty()) {
                    host.scheduler.runGlobal {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    }
                }
            }
        }
    }

    internal fun parseDelayTicks(line: String): Long? {
        val trimmed = line.trim().lowercase()
        val raw = when {
            trimmed.startsWith("delay!") -> trimmed.substring(6).trim()
            trimmed.startsWith("delay:") -> trimmed.substring(6).trim()
            else -> return null
        }
        val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * 20.0).toLong().coerceAtLeast(1L)
    }

    internal fun evaluateCondition(
        host: CoreHost?,
        condition: String,
        player: Player?,
        onlineCountProvider: () -> Int = { Bukkit.getOnlinePlayers().size },
    ): Boolean {
        val negate = condition.startsWith("!")
        val expr = if (negate) condition.substring(1).trim() else condition.trim()
        val result = when {
            expr.startsWith("perm:", ignoreCase = true) -> {
                val perm = expr.substring(5).trim()
                player?.hasPermission(perm) == true
            }

            expr.startsWith("world:", ignoreCase = true) -> {
                val world = expr.substring(6).trim()
                player?.world?.name.equals(world, ignoreCase = true)
            }

            expr.startsWith("online>=", ignoreCase = true) -> {
                val min = expr.substring(8).trim().toIntOrNull() ?: return false
                onlineCountProvider() >= min
            }

            expr.startsWith("online<", ignoreCase = true) -> {
                val max = expr.substring(7).trim().toIntOrNull() ?: return false
                onlineCountProvider() < max
            }

            else -> host?.evaluateDynamicCondition(expr, player) ?: false
        }
        return if (negate) !result else result
    }

    private fun resolvePlaceholders(
        host: CoreHost,
        input: String,
        player: Player?,
        extra: Map<String, String>,
    ): String {
        var out = input
        for ((k, v) in extra) {
            out = out.replace("{$k}", v)
        }
        if (player != null) {
            val loc = player.location
            out = out.replace("{world}", loc.world.name)
                .replace("{x}", loc.blockX.toString())
                .replace("{y}", loc.blockY.toString())
                .replace("{z}", loc.blockZ.toString())
        }
        val dynamicResolved = host.resolveDynamicPlaceholders(player, out)
        return dynamicResolved
            .replace("{player}", player?.name ?: "CONSOLE")
            .replace("{online}", Bukkit.getOnlinePlayers().size.toString())
            .replace("{max_players}", Bukkit.getMaxPlayers().toString())
    }

    private fun parseSimpleLocation(raw: String): Location? {
        val parts = raw.split(';')
        if (parts.size < 4) return null
        val world = Bukkit.getWorld(parts[0].trim()) ?: return null
        val x = parts[1].toDoubleOrNull() ?: return null
        val y = parts[2].toDoubleOrNull() ?: return null
        val z = parts[3].toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
        val pitch = parts.getOrNull(5)?.toFloatOrNull() ?: 0f
        return Location(world, x, y, z, yaw, pitch)
    }
}
