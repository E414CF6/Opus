package opus.core.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Wrapper around [YamlConfiguration] that automatically creates missing files from plugin resources
 * and merges default keys without overwriting user customizations.
 */
class YamlConfigFile(
    private val plugin: Plugin,
    private val relativePath: String,
    private val copyDefaults: Boolean = true,
) {
    val file: File = File(plugin.dataFolder, relativePath)
    lateinit var yaml: YamlConfiguration
        private set

    init {
        reload()
    }

    fun reload() {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            if (plugin.getResource(relativePath) != null) {
                plugin.saveResource(relativePath, false)
            } else {
                file.createNewFile()
            }
        }
        val loaded = YamlConfiguration.loadConfiguration(file)
        if (copyDefaults) {
            plugin.getResource(relativePath)?.use { stream ->
                val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
                loaded.setDefaults(defaults)
                loaded.options().copyDefaults(true)
            }
        }
        yaml = loaded
        if (copyDefaults) {
            save()
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        yaml.save(file)
    }
}
