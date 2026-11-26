package opus.core.ui

import opus.core.CoreHost
import opus.core.compat.PlatformCompat
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Dialog abstraction isolating Paper `Dialog` API (`1.21.6+`) from older Paper (`1.20.4–1.21.5`)
 * with automatic [MenuWindow] Chest GUI fallbacks.
 */
class DialogKit(private val host: CoreHost) {

    val supportsNativeDialogs: Boolean
        get() = PlatformCompat.supportsDialogs

    internal val paperBackend: PaperDialogKit? by lazy {
        if (supportsNativeDialogs) PaperDialogKit(host) else null
    }

    fun openConfirmation(
        player: Player,
        titleRaw: String,
        bodyRaw: String,
        confirmLabelRaw: String = host.locale.raw("dialog.confirm"),
        cancelLabelRaw: String = host.locale.raw("dialog.cancel"),
        onConfirm: (Player) -> Unit,
        onCancel: ((Player) -> Unit)? = null,
    ) {
        val backend = paperBackend
        if (backend != null) {
            backend.openConfirmation(player, titleRaw, bodyRaw, confirmLabelRaw, cancelLabelRaw, onConfirm, onCancel)
            return
        }
        openConfirmationMenu(player, titleRaw, bodyRaw, confirmLabelRaw, cancelLabelRaw, onConfirm, onCancel)
    }

    fun openConfirmationMenu(
        player: Player,
        titleRaw: String,
        bodyRaw: String,
        confirmLabelRaw: String = host.locale.raw("dialog.confirm"),
        cancelLabelRaw: String = host.locale.raw("dialog.cancel"),
        onConfirm: (Player) -> Unit,
        onCancel: ((Player) -> Unit)? = null,
    ) {
        val menu = MenuWindow(host.locale.renderRaw(titleRaw), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))
        menu.set(
            11,
            MenuItems.item(
                Material.LIME_CONCRETE,
                confirmLabelRaw,
                bodyRaw.split('\n').map { "<gray>$it</gray>" },
            ),
        ) { click ->
            click.player.closeInventory()
            onConfirm(click.player)
        }
        menu.set(
            13,
            MenuItems.item(
                Material.PAPER,
                "<yellow>$titleRaw</yellow>",
                bodyRaw.split('\n'),
            ),
        )
        menu.set(
            15,
            MenuItems.item(
                Material.RED_CONCRETE,
                cancelLabelRaw,
            ),
        ) { click ->
            click.player.closeInventory()
            onCancel?.invoke(click.player)
        }
        menu.open(player)
    }

    fun openNotice(
        player: Player,
        titleRaw: String,
        icon: ItemStack = ItemStack(Material.PAPER),
        headerRaw: String,
        extraLines: List<String> = emptyList(),
        onClose: ((Player) -> Unit)? = null,
    ) {
        val backend = paperBackend
        if (backend != null) {
            val body = buildList {
                add(backend.itemBody(icon, descriptionRaw = headerRaw))
                extraLines.forEach { add(backend.plainBody(it)) }
            }
            backend.showNotice(player, titleRaw, body, onClose = onClose)
            return
        }
        val menu = MenuWindow(host.locale.renderRaw(titleRaw), 3)
        menu.fillBorder(MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " "))
        val infoItem = MenuItems.item(icon.type, headerRaw, extraLines)
        menu.set(13, infoItem)
        menu.set(22, MenuItems.closeButton(host)) { click ->
            click.player.closeInventory()
            onClose?.invoke(click.player)
        }
        menu.open(player)
    }
}
