package opus

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LocaleParityTest {

    private fun collectLeafKeys(yamlPath: Path): Set<String> {
        val cfg = YamlConfiguration.loadConfiguration(yamlPath.toFile())
        return cfg.getKeys(true)
            .filter { key -> !cfg.isConfigurationSection(key) }
            .toSet()
    }

    @Test
    fun `messages_en and messages_ko have identical leaf keys`() {
        val resourcesDir = Path.of("src/main/resources")
        val enKeys = collectLeafKeys(resourcesDir.resolve("messages_en.yml"))
        val koKeys = collectLeafKeys(resourcesDir.resolve("messages_ko.yml"))

        assertTrue(enKeys.isNotEmpty(), "messages_en.yml should not be empty")
        assertEquals(emptySet<String>(), enKeys - koKeys, "Keys missing in messages_ko.yml")
        assertEquals(emptySet<String>(), koKeys - enKeys, "Keys missing in messages_en.yml")
    }

    @Test
    fun `all literal locale keys referenced in Kotlin sources exist in locale files`() {
        val enKeys = collectLeafKeys(Path.of("src/main/resources/messages_en.yml"))
        val localeCallRegex = Regex(
            """locale\.(?:send|sendRaw|sendActionBar|component|prefixedComponent|raw)\(\s*(?:[^,\n]+,\s*)?"([a-z0-9_.-]+)""""
        )

        val missing = sortedSetOf<String>()
        Files.walk(Path.of("src/main/kotlin")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                for (match in localeCallRegex.findAll(text)) {
                    val key = match.groupValues[1]
                    if (key !in enKeys) {
                        missing += "$key (${file.fileName})"
                    }
                }
            }
        }

        assertEquals(emptySet<String>(), missing, "Missing locale keys referenced in source code")
    }
}
