package opus.core.locale

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import opus.core.config.OpusConfig
import opus.core.config.YamlConfigFile
import opus.core.text.TextFormatter
import opus.skills.SkillCategory
import opus.skills.SkillType
import opus.skills.SubSkillType
import opus.skills.SuperAbilityType
import org.bukkit.plugin.Plugin

/**
 * Loads localized message templates (`locales/<lang>.yml`) and renders Adventure [Component] messages.
 */
class LocaleService(
    private val plugin: Plugin,
    private val config: OpusConfig,
) {
    private var primary: YamlConfigFile? = null
    private var fallback: YamlConfigFile = YamlConfigFile(plugin, "locales/en_us.yml")

    init {
        reload()
    }

    fun reload() {
        fallback = YamlConfigFile(plugin, "locales/en_us.yml")
        val lang = OpusConfig.normalizeLocale(config.language)
        primary = if (lang == "en_us") {
            fallback
        } else {
            YamlConfigFile(plugin, "locales/$lang.yml")
        }
    }

    fun raw(key: String): String {
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: key
    }

    fun raw(key: String, vararg placeholders: Pair<String, Any?>): String {
        var template = raw(key)
        for ((k, v) in placeholders) {
            template = template.replace("{$k}", v?.toString() ?: "")
        }
        return template
    }

    fun skillName(skill: SkillType): String {
        val key = "skills.${skill.key}"
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: skill.displayName(config.language)
    }

    fun subSkillName(sub: SubSkillType): String {
        val key = "subskills.${sub.name.lowercase()}"
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: sub.displayName(config.language)
    }

    fun abilityName(ability: SuperAbilityType): String {
        val key = "abilities.${ability.name.lowercase()}"
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: ability.displayName(config.language)
    }

    fun categoryName(category: SkillCategory): String {
        val key = "categories.${category.name.lowercase()}"
        return primary?.yaml?.getString(key) ?: fallback.yaml.getString(key) ?: if (config.language.startsWith(
                "ko",
                ignoreCase = true
            )
        ) category.koName else category.enName
    }

    fun prefix(): Component = TextFormatter.format(raw("prefix"))

    fun errorPrefix(): Component = TextFormatter.format(raw("error-prefix"))

    fun component(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        TextFormatter.format(raw(key), placeholders)

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
            template.startsWith("<red>") && template.endsWith("</red>") -> template.removePrefix("<red>")
                .removeSuffix("</red>")

            template.startsWith("<dark_red>") && template.endsWith("</dark_red>") -> template.removePrefix("<dark_red>")
                .removeSuffix("</dark_red>")

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

    fun sendActionBar(audience: Audience, key: String, vararg placeholders: Pair<String, Any?>) {
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        audience.sendActionBar(component(key, map))
    }
}
