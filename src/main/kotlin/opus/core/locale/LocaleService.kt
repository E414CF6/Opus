package opus.core.locale

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import opus.core.config.YamlConfigFile
import opus.core.text.TextFormatter
import org.bukkit.plugin.Plugin

/**
 * Loads localized message templates (`locales/<lang>.yml`) and renders Adventure [Component] messages.
 */
class LocaleService(
    private val plugin: Plugin,
    private val languageProvider: () -> String,
    private val normalizeLocaleFn: (String) -> String = ::normalizeLocale,
) {
    private var primary: YamlConfigFile? = null
    private var fallback: YamlConfigFile = YamlConfigFile(plugin, "locales/en_us.yml")

    init {
        reload()
    }

    val currentLanguage: String
        get() = languageProvider()

    fun reload() {
        fallback = YamlConfigFile(plugin, "locales/en_us.yml")
        val lang = normalizeLocaleFn(languageProvider())
        primary = if (lang == "en_us") {
            fallback
        } else {
            YamlConfigFile(plugin, "locales/$lang.yml")
        }
    }

    fun raw(key: String): String {
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: key
    }

    fun rawOrNull(key: String): String? {
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key)
    }

    fun raw(key: String, vararg placeholders: Pair<String, Any?>): String {
        var template = raw(key)
        for ((k, v) in placeholders) {
            template = template.replace("{$k}", v?.toString() ?: "")
        }
        return template
    }

    fun prefix(): Component = TextFormatter.format(raw("prefix"))

    fun errorPrefix(): Component = TextFormatter.format(raw("error-prefix"))

    fun component(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        TextFormatter.format(raw(key), placeholders)

    fun render(key: String, placeholders: Map<String, Any> = emptyMap()): Component =
        component(key, placeholders.mapValues { it.value.toString() })

    fun renderRaw(miniMessageText: String): Component =
        TextFormatter.format(miniMessageText)

    fun prefixedComponent(key: String, placeholders: Map<String, String> = emptyMap()): Component {
        val template = raw(key)
        val isError = template.startsWith("<red>") || template.startsWith("<dark_red>")
        if (isError) {
            val cleaned = stripOuterRedTag(template)
            return errorPrefix().append(TextFormatter.format("<white>$cleaned</white>", placeholders))
        }
        return prefix().append(TextFormatter.format(template, placeholders))
    }

    private fun stripOuterRedTag(template: String): String {
        return when {
            template.startsWith("<red>") && template.endsWith("</red>") ->
                template.removePrefix("<red>").removeSuffix("</red>")

            template.startsWith("<dark_red>") && template.endsWith("</dark_red>") ->
                template.removePrefix("<dark_red>").removeSuffix("</dark_red>")

            else -> template.removePrefix("<red>").removePrefix("<dark_red>")
        }
    }

    fun send(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendMessage(prefixedComponent(key, map))
    }

    fun sendRaw(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendMessage(component(key, map))
    }

    fun sendDirectRaw(audience: Audience, miniMessageText: String, withPrefix: Boolean = true) {
        val msg = renderRaw(miniMessageText)
        if (withPrefix) {
            audience.sendMessage(prefix().append(msg))
        } else {
            audience.sendMessage(msg)
        }
    }

    fun sendActionBar(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendActionBar(component(key, map))
    }

    companion object {
        fun normalizeLocale(raw: String): String {
            return when (val cleaned = raw.trim().lowercase().replace('-', '_')) {
                "en", "en_us" -> "en_us"
                "ko", "ko_kr" -> "ko_kr"
                "" -> "en_us"
                else -> cleaned
            }
        }
    }
}
