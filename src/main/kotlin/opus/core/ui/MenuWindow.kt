package opus.core.ui

import net.kyori.adventure.text.Component
import opus.core.CoreHost
import opus.core.compat.PlatformCompat
import opus.core.locale.LocaleService
import opus.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

data class MenuClickContext(
    val player: Player,
    val slot: Int,
    val clickType: ClickType,
    val event: InventoryClickEvent,
)

class MenuButton(
    val item: ItemStack,
    val onClick: (MenuClickContext) -> Unit = {},
)

/**
 * [InventoryHolder]-backed menu window guaranteeing safe click routing, optional editable slots,
 * close callbacks, and item-extraction protection.
 */
class MenuWindow(
    val title: Component,
    val rows: Int,
) : InventoryHolder {

    private val buttons = HashMap<Int, MenuButton>()
    private val backing: Inventory = Bukkit.createInventory(this, rows.coerceIn(1, 6) * 9, title)

    val editableSlots: MutableSet<Int> = HashSet()
    var allowBottomInventoryClick: Boolean = false
    var onClose: ((InventoryCloseEvent) -> Unit)? = null
    var onEditableChanged: ((Player) -> Unit)? = null

    override fun getInventory(): Inventory = backing

    fun hasButton(slot: Int): Boolean = buttons.containsKey(slot)

    fun set(slot: Int, item: ItemStack, onClick: (MenuClickContext) -> Unit = {}) {
        if (slot !in 0 until backing.size) return
        buttons[slot] = MenuButton(item, onClick)
        backing.setItem(slot, item)
    }

    fun clearSlot(slot: Int) {
        if (slot !in 0 until backing.size) return
        buttons.remove(slot)
        backing.setItem(slot, null)
    }

    fun fillBorder(item: ItemStack) {
        val total = backing.size
        val cols = 9
        for (slot in 0 until total) {
            val row = slot / cols
            val col = slot % cols
            if (row == 0 || row == rows - 1 || col == 0 || col == cols - 1) {
                if (!buttons.containsKey(slot) && slot !in editableSlots) {
                    set(slot, item)
                }
            }
        }
    }

    fun fillRow(row: Int, item: ItemStack) {
        if (row !in 0 until rows) return
        val start = row * 9
        for (slot in start until start + 9) {
            if (!buttons.containsKey(slot) && slot !in editableSlots) {
                set(slot, item)
            }
        }
    }

    fun fillEmpty(item: ItemStack) {
        for (slot in 0 until backing.size) {
            if (!buttons.containsKey(slot) && slot !in editableSlots && backing.getItem(slot) == null) {
                set(slot, item)
            }
        }
    }

    fun setFooterNav(
        locale: LocaleService,
        page: Int,
        totalPages: Int,
        prevSlot: Int = (rows - 1) * 9 + 3,
        nextSlot: Int = (rows - 1) * 9 + 5,
        onPrev: () -> Unit,
        onNext: () -> Unit,
    ) {
        if (page > 1) {
            set(prevSlot, MenuItems.item(Material.ARROW, locale.raw("gui.prev-page"))) {
                onPrev()
            }
        }
        if (page < totalPages) {
            set(nextSlot, MenuItems.item(Material.ARROW, locale.raw("gui.next-page"))) {
                onNext()
            }
        }
    }

    fun setFooterNav(
        host: CoreHost,
        page: Int,
        totalPages: Int,
        prevSlot: Int = (rows - 1) * 9 + 3,
        nextSlot: Int = (rows - 1) * 9 + 5,
        onPrev: () -> Unit,
        onNext: () -> Unit,
    ) = setFooterNav(host.locale, page, totalPages, prevSlot, nextSlot, onPrev, onNext)

    fun open(player: Player) {
        player.openInventory(backing)
    }

    private fun runEntityCallback(player: Player, action: (Player) -> Unit) {
        val plugin = runCatching { JavaPlugin.getProvidingPlugin(MenuWindow::class.java) }.getOrNull() ?: return
        if (PlatformCompat.supportsFoliaSchedulers) {
            player.scheduler.run(plugin, { action(player) }, null)
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { action(player) })
        }
    }

    internal fun handleClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val raw = event.rawSlot
        val topSize = event.view.topInventory.size

        if (editableSlots.isNotEmpty()) {
            if (raw >= topSize) {
                if (!allowBottomInventoryClick) {
                    event.isCancelled = true
                } else if (event.isShiftClick) {
                    onEditableChanged?.let { cb -> runEntityCallback(player, cb) }
                }
                return
            }
            if (raw in editableSlots) {
                onEditableChanged?.let { cb -> runEntityCallback(player, cb) }
                return
            }
            event.isCancelled = true
            buttons[raw]?.onClick?.invoke(MenuClickContext(player, raw, event.click, event))
            return
        }

        event.isCancelled = true
        val button = buttons[raw] ?: return
        button.onClick(MenuClickContext(player, raw, event.click, event))
    }

    internal fun handleDrag(event: InventoryDragEvent) {
        if (editableSlots.isEmpty()) {
            event.isCancelled = true
            return
        }
        val topSize = event.view.topInventory.size
        val touchesProtectedTop = event.rawSlots.any { it < topSize && it !in editableSlots }
        if (touchesProtectedTop) {
            event.isCancelled = true
            return
        }
        val player = event.whoClicked as? Player ?: return
        onEditableChanged?.let { cb -> runEntityCallback(player, cb) }
    }

    internal fun handleClose(event: InventoryCloseEvent) {
        onClose?.invoke(event)
    }

    companion object {
        val CONTENT_SLOTS: List<Int> = (10..16) + (19..25) + (28..34) + (37..43)
    }
}

/**
 * Global listener dispatching clicks, drags, and close events on [MenuWindow] inventories.
 */
class MenuListener : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MenuWindow ?: return
        if (holder.editableSlots.isNotEmpty() || event.rawSlot < event.view.topInventory.size || event.isShiftClick) {
            holder.handleClick(event)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val holder = event.view.topInventory.holder as? MenuWindow ?: return
        holder.handleDrag(event)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val holder = event.view.topInventory.holder as? MenuWindow ?: return
        holder.handleClose(event)
    }
}

object MenuItems {
    fun item(
        material: Material,
        name: String,
        lore: List<String> = emptyList(),
        amount: Int = 1,
    ): ItemStack {
        val stack = ItemStack(material, amount.coerceIn(1, material.maxStackSize))
        val meta = stack.itemMeta ?: return stack
        meta.displayName(TextFormatter.format("<!italic>$name"))
        if (lore.isNotEmpty()) {
            meta.lore(lore.map { TextFormatter.format("<!italic>$it") })
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        stack.itemMeta = meta
        return stack
    }

    fun playerHead(
        uuid: UUID?,
        nameHint: String?,
        displayName: String,
        lore: List<String> = emptyList(),
    ): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        val meta = stack.itemMeta as? SkullMeta ?: return stack
        if (uuid != null) {
            meta.owningPlayer = Bukkit.getOfflinePlayer(uuid)
        } else if (!nameHint.isNullOrBlank()) {
            @Suppress("DEPRECATION")
            meta.owningPlayer = Bukkit.getOfflinePlayer(nameHint)
        }
        meta.displayName(TextFormatter.format("<!italic>$displayName"))
        if (lore.isNotEmpty()) {
            meta.lore(lore.map { TextFormatter.format("<!italic>$it") })
        }
        stack.itemMeta = meta
        return stack
    }

    fun toggle(
        material: Material,
        label: String,
        enabled: Boolean,
        locale: LocaleService,
        extraLore: List<String> = emptyList(),
    ): ItemStack {
        val stateText = locale.raw(if (enabled) "general.state-on" else "general.state-off")
        val lore = buildList {
            addAll(extraLore)
            add(locale.raw("gui.click-toggle"))
        }
        return item(
            material = material,
            name = "<aqua>$label</aqua> <gray>[</gray>$stateText<gray>]</gray>",
            lore = lore,
        )
    }

    fun toggle(
        material: Material,
        label: String,
        enabled: Boolean,
        host: CoreHost,
        extraLore: List<String> = emptyList(),
    ): ItemStack = toggle(material, label, enabled, host.locale, extraLore)

    fun backButton(locale: LocaleService): ItemStack =
        item(Material.ARROW, locale.raw("gui.back"))

    fun backButton(host: CoreHost): ItemStack = backButton(host.locale)

    fun closeButton(locale: LocaleService): ItemStack =
        item(Material.BARRIER, locale.raw("gui.close"))

    fun closeButton(host: CoreHost): ItemStack = closeButton(host.locale)
}
