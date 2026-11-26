package opus

import opus.skills.SkillCategory
import opus.skills.SkillType
import opus.skills.SubSkillType
import opus.skills.SuperAbilityType
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
    fun `en_us and ko_kr have identical leaf keys`() {
        val localesDir = Path.of("src/main/resources/locales")
        val enKeys = collectLeafKeys(localesDir.resolve("en_us.yml"))
        val koKeys = collectLeafKeys(localesDir.resolve("ko_kr.yml"))

        assertTrue(enKeys.isNotEmpty(), "locales/en_us.yml should not be empty")
        assertEquals(emptySet<String>(), enKeys - koKeys, "Keys missing in locales/ko_kr.yml")
        assertEquals(emptySet<String>(), koKeys - enKeys, "Keys missing in locales/en_us.yml")
    }

    @Test
    fun `all literal locale keys and skill enums exist in locale files`() {
        val enKeys = collectLeafKeys(Path.of("src/main/resources/locales/en_us.yml"))
        val audienceCallRegex = Regex(
            """locale\.(?:send|sendRaw|sendActionBar)\(\s*[^,\n]+,\s*"([a-z0-9_.-]+)""""
        )
        val directCallRegex = Regex(
            """locale\.(?:component|prefixedComponent|raw)\(\s*"([a-z0-9_.-]+)""""
        )
        val warmupKeyRegex = Regex(
            """(?:barTitleKey|actionBarKey|cancelledKey)\s*=\s*"([a-z0-9_.-]+)""""
        )

        val missing = sortedSetOf<String>()
        Files.walk(Path.of("src/main/kotlin")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                for (regex in listOf(audienceCallRegex, directCallRegex, warmupKeyRegex)) {
                    for (match in regex.findAll(text)) {
                        val key = match.groupValues[1]
                        if (key !in enKeys) {
                            missing += "$key (${file.fileName})"
                        }
                    }
                }
            }
        }

        for (cat in SkillCategory.entries) {
            val k = "categories.${cat.name.lowercase()}"
            if (k !in enKeys) missing += k
        }
        for (skill in SkillType.entries) {
            val k = "skills.${skill.key}"
            if (k !in enKeys) missing += k
        }
        for (ability in SuperAbilityType.entries) {
            val k = "abilities.${ability.name.lowercase()}"
            if (k !in enKeys) missing += k
        }
        for (sub in SubSkillType.entries) {
            val k = "subskills.${sub.name.lowercase()}"
            if (k !in enKeys) missing += k
        }

        assertEquals(emptySet<String>(), missing, "Missing locale keys referenced in source code")
    }

    @Test
    fun `opus core package stays strictly domain-agnostic with zero RPG or module imports`() {
        val coreRoot = Path.of("src/main/kotlin/opus/core")
        val forbiddenImport = Regex("""^import\s+opus\.(skills|modules|events|integration|rpg)\b""")
        val violations = mutableListOf<String>()

        Files.walk(coreRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                val rel = coreRoot.relativize(file)
                Files.readAllLines(file).forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (forbiddenImport.containsMatchIn(trimmed)) {
                        violations += "$rel:${idx + 1}: $trimmed"
                    }
                }
            }
        }

        assertEquals(emptyList<String>(), violations, "opus.core must remain domain-agnostic")
    }
}
