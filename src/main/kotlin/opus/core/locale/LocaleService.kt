package opus.core.locale

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import opus.core.config.OpusConfig
import opus.core.config.YamlConfigFile
import opus.core.text.TextFormatter
import org.bukkit.plugin.Plugin

/**
 * Loads localized message templates (`messages_<lang>.yml`) and renders Adventure [Component] messages.
 */
class LocaleService(
    private val plugin: Plugin,
    private val config: OpusConfig,
) {
    private var primary: YamlConfigFile? = null
    private var fallback: YamlConfigFile = YamlConfigFile(plugin, "messages_en.yml")

    init {
        reload()
    }

    fun reload() {
        fallback = YamlConfigFile(plugin, "messages_en.yml")
        val lang = config.language.lowercase()
        primary = if (lang == "en") {
            fallback
        } else {
            YamlConfigFile(plugin, "messages_$lang.yml")
        }
    }

    fun raw(key: String): String {
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: key
    }

    fun prefix(): Component = TextFormatter.format(raw("prefix"))

    fun component(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        TextFormatter.format(raw(key), placeholders)

    fun prefixedComponent(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        prefix().append(component(key, placeholders))

    fun send(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendMessage(prefixedComponent(key, map))
    }

    fun sendRaw(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendMessage(component(key, map))
    }

    fun sendActionBar(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendActionBar(component(key, map))
    }
}
