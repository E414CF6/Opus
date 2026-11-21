package opus.core.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.regex.Pattern

/**
 * Converts MiniMessage strings (optionally mixed with legacy `&` or `&#RRGGBB` codes)
 * into Adventure [Component] instances and formats durations and progress bars.
 */
object TextFormatter {

    private val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val legacyAmpersand: LegacyComponentSerializer =
        LegacyComponentSerializer.builder().character('&').hexColors().build()

    private val legacyHexPattern = Pattern.compile("&#([0-9a-fA-F]{6})")
    private val legacyCodeMap = mapOf(
        '0' to "<black>",
        '1' to "<dark_blue>",
        '2' to "<dark_green>",
        '3' to "<dark_aqua>",
        '4' to "<dark_red>",
        '5' to "<dark_purple>",
        '6' to "<gold>",
        '7' to "<gray>",
        '8' to "<dark_gray>",
        '9' to "<blue>",
        'a' to "<green>",
        'b' to "<aqua>",
        'c' to "<red>",
        'd' to "<light_purple>",
        'e' to "<yellow>",
        'f' to "<white>",
        'k' to "<obfuscated>",
        'l' to "<bold>",
        'm' to "<strikethrough>",
        'n' to "<underlined>",
        'o' to "<italic>",
        'r' to "<reset>",
    )

    fun format(raw: String, placeholders: Map<String, String> = emptyMap()): Component {
        var resolved = raw
        if (placeholders.isNotEmpty()) {
            for ((key, value) in placeholders) {
                resolved = resolved.replace("{$key}", value)
            }
        }
        return miniMessage.deserialize(normalizeLegacyCodes(resolved))
    }

    fun fromLegacy(raw: String): Component = legacyAmpersand.deserialize(raw)

    fun escape(raw: String): String = miniMessage.escapeTags(raw)

    fun normalizeLegacyCodes(input: String): String {
        val hexMatcher = legacyHexPattern.matcher(input)
        val hexBuffer = StringBuffer(input.length)
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(hexBuffer, "<#${hexMatcher.group(1)}>")
        }
        hexMatcher.appendTail(hexBuffer)

        val text = hexBuffer.toString()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if ((ch == '&' || ch == '§') && i + 1 < text.length) {
                val code = text[i + 1].lowercaseChar()
                val tag = legacyCodeMap[code]
                if (tag != null) {
                    out.append(tag)
                    i += 2
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
        if (totalSeconds <= 0L) return "0s"
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            if (seconds > 0 || isEmpty()) append("${seconds}s")
        }.trim()
    }

    fun progressBar(current: Double, max: Double, length: Int = 12): String {
        val ratio = if (max <= 0.0) 1.0 else (current / max).coerceIn(0.0, 1.0)
        val filled = (ratio * length).toInt().coerceIn(0, length)
        val empty = length - filled
        return buildString {
            append("<green>")
            repeat(filled) { append('█') }
            append("</green><dark_gray>")
            repeat(empty) { append('░') }
            append("</dark_gray>")
        }
    }
}
