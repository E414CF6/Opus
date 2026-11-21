package opus.core.gui

import net.kyori.adventure.text.Component
import opus.core.text.TextFormatter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

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
 * [InventoryHolder]-backed menu window guaranteeing safe click routing and preventing item extraction.
 */
class MenuWindow(
    val title: Component,
    val rows: Int,
) : InventoryHolder {

    private val buttons = HashMap<Int, MenuButton>()
    private val backing: Inventory = Bukkit.createInventory(this, rows.coerceIn(1, 6) * 9, title)

    override fun getInventory(): Inventory = backing

    fun hasButton(slot: Int): Boolean = buttons.containsKey(slot)

    fun set(slot: Int, item: ItemStack, onClick: (MenuClickContext) -> Unit = {}) {
        if (slot !in 0 until backing.size) return
        buttons[slot] = MenuButton(item, onClick)
        backing.setItem(slot, item)
    }

    fun fillBorder(item: ItemStack) {
        val total = backing.size
        val cols = 9
        for (slot in 0 until total) {
            val row = slot / cols
            val col = slot % cols
            if (row == 0 || row == rows - 1 || col == 0 || col == cols - 1) {
                if (!buttons.containsKey(slot)) {
                    set(slot, item)
                }
            }
        }
    }

    fun open(player: Player) {
        player.openInventory(backing)
    }

    internal fun handleClick(event: InventoryClickEvent) {
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val button = buttons[event.rawSlot] ?: return
        button.onClick(MenuClickContext(player, event.rawSlot, event.click, event))
    }
}

/**
 * Global listener dispatching clicks on [MenuWindow] inventories.
 */
class MenuListener : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MenuWindow ?: return
        if (event.rawSlot < event.view.topInventory.size || event.isShiftClick) {
            holder.handleClick(event)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is MenuWindow) {
            event.isCancelled = true
        }
    }
}

object MenuItems {
    fun item(
        material: Material,
        name: String,
        lore: List<String> = emptyList(),
        amount: Int = 1,
    ): ItemStack {
        val stack = ItemStack(material, amount.coerceIn(1, 64))
        val meta = stack.itemMeta ?: return stack
        meta.displayName(TextFormatter.format("<!italic>$name"))
        if (lore.isNotEmpty()) {
            meta.lore(lore.map { TextFormatter.format("<!italic>$it") })
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        stack.itemMeta = meta
        return stack
    }
}
