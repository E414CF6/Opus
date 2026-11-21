package opus.core.text

/**
 * Parses human-readable duration strings such as `30s`, `15m`, `2h`, `7d` into milliseconds.
 */
object DurationParser {

    private val tokenRegex = Regex("(\\d+)([smhdw])", RegexOption.IGNORE_CASE)

    fun parseMillis(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return it * 1000L }

        val matches = tokenRegex.findAll(trimmed).toList()
        if (matches.isEmpty()) return null
        var total = 0L
        for (match in matches) {
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unitMillis = when (match.groupValues[2].lowercase()) {
                "s" -> 1_000L
                "m" -> 60_000L
                "h" -> 3_600_000L
                "d" -> 86_400_000L
                "w" -> 604_800_000L
                else -> return null
            }
            total += amount * unitMillis
        }
        return total
    }
}
