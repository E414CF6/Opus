package opus.integration

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import opus.core.OpusContext
import org.bukkit.OfflinePlayer

/**
 * Registers `%opus_<key>%` placeholders with PlaceholderAPI when installed.
 */
class PlaceholderApiHook(
    private val context: OpusContext,
    private val placeholderService: PlaceholderService,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "opus"

    override fun getAuthor(): String = "Opus"

    override fun getVersion(): String = context.plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        placeholderService.resolveKey(player, params)
}
