package opus.core.command

import net.kyori.adventure.bossbar.BossBar
import opus.core.CoreHost
import opus.core.compat.PlatformCompat
import opus.core.ui.MenuItems
import opus.core.ui.MenuWindow
import org.bukkit.Material
import org.bukkit.entity.Player

enum class AliasFilter {
    ALL,
    REGULAR,
    CUSTOM,
}

/**
 * Paginated [MenuWindow] GUI for inspecting, toggling, and managing regular and custom aliases.
 */
object AliasGui {

    private val borderGlass = MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " ")
    private const val BOSSBAR_CHANNEL = "alias_admin_feedback"

    fun open(
        host: CoreHost,
        player: Player,
        filter: AliasFilter = AliasFilter.ALL,
        page: Int = 1,
        onBack: ((Player) -> Unit)? = null,
    ) {
        if (!player.hasPermission(host.adminPermission)) {
            host.locale.send(player, "general.no-permission")
            return
        }
        val aliasService = host.getService(CommandAliasService::class.java) ?: return

        val regular = aliasService.getRegularAliases().values.toList()
        val custom = aliasService.getCustomAliasEntries().values.toList()
        val entries = when (filter) {
            AliasFilter.ALL -> regular + custom
            AliasFilter.REGULAR -> regular
            AliasFilter.CUSTOM -> custom
        }

        val perPage = 28
        val totalPages = ((entries.size + perPage - 1) / perPage).coerceAtLeast(1)
        val safePage = page.coerceIn(1, totalPages)
        val slice = entries.drop((safePage - 1) * perPage).take(perPage)

        val locale = host.locale
        val title = locale.component(
            "gui.alias-title",
            mapOf("page" to safePage.toString(), "total" to totalPages.toString()),
        )
        val menu = MenuWindow(title, 6)
        menu.fillBorder(borderGlass)

        val allLabel = locale.raw("gui.alias-filter-all")
        val regLabel = locale.raw("gui.alias-filter-regular")
        val cusLabel = locale.raw("gui.alias-filter-custom")
        menu.set(
            2,
            MenuItems.item(
                if (filter == AliasFilter.ALL) Material.NETHER_STAR else Material.PAPER,
                if (filter == AliasFilter.ALL) "<aqua><bold>$allLabel</bold></aqua>" else "<gray>$allLabel</gray>",
            ),
        ) { click ->
            open(host, click.player, AliasFilter.ALL, 1, onBack)
        }
        menu.set(
            4,
            MenuItems.item(
                if (filter == AliasFilter.REGULAR) Material.COMMAND_BLOCK else Material.REPEATER,
                if (filter == AliasFilter.REGULAR) "<aqua><bold>$regLabel</bold></aqua>" else "<gray>$regLabel</gray>",
            ),
        ) { click ->
            open(host, click.player, AliasFilter.REGULAR, 1, onBack)
        }
        menu.set(
            6,
            MenuItems.item(
                if (filter == AliasFilter.CUSTOM) Material.WRITABLE_BOOK else Material.BOOK,
                if (filter == AliasFilter.CUSTOM) "<aqua><bold>$cusLabel</bold></aqua>" else "<gray>$cusLabel</gray>",
            ),
        ) { click ->
            open(host, click.player, AliasFilter.CUSTOM, 1, onBack)
        }

        val slots = MenuWindow.CONTENT_SLOTS

        slice.forEachIndexed { idx, entry ->
            val slot = slots.getOrNull(idx) ?: return@forEachIndexed
            val onText = locale.raw("general.state-on")
            val offText = locale.raw("general.state-off")

            val material = when {
                !entry.enabled -> Material.GRAY_DYE
                entry.type == CommandAliasType.BASE -> Material.EMERALD_BLOCK
                entry.type == CommandAliasType.SUBBASE -> Material.GOLD_INGOT
                else -> Material.ENCHANTED_BOOK
            }
            val typeTag = when (entry.type) {
                CommandAliasType.BASE -> "<green>BASE</green>"
                CommandAliasType.SUBBASE -> "<yellow>SUBBASE</yellow>"
                CommandAliasType.CUSTOM -> "<light_purple>CUSTOM</light_purple>"
            }
            val lore = buildList {
                add(locale.raw("gui.alias-entry-type", "type" to typeTag))
                add(locale.raw("gui.alias-entry-enabled", "state" to if (entry.enabled) onText else offText))
                add(locale.raw("gui.alias-entry-tab", "state" to if (entry.tabComplete) onText else offText))
                if (entry.type == CommandAliasType.BASE) {
                    add(
                        locale.raw(
                            "gui.alias-entry-base-disabled",
                            "state" to if (entry.baseDisabled) onText else offText,
                        )
                    )
                }
                entry.commands.take(3).forEach { cmd ->
                    add(locale.raw("gui.alias-entry-cmd", "cmd" to cmd))
                }
                if (entry.commands.size > 3) {
                    add("<dark_gray>+${entry.commands.size - 3} more...</dark_gray>")
                }
                add("")
                if (entry.type == CommandAliasType.CUSTOM) {
                    add(locale.raw("gui.alias-hint-custom"))
                } else {
                    add(locale.raw("gui.alias-hint-left"))
                    if (entry.type == CommandAliasType.BASE) {
                        add(locale.raw("gui.alias-hint-right"))
                    }
                    add(locale.raw("gui.alias-hint-shift"))
                    add(locale.raw("gui.alias-hint-middle"))
                }
            }
            val name = "<aqua>/${entry.alias}</aqua>"
            menu.set(slot, MenuItems.item(material, name, lore)) { click ->
                val p = click.player
                if (entry.type == CommandAliasType.CUSTOM) {
                    AliasDialogs.openCustomAliasDialog(host, p, entry, onBack) {
                        open(host, p, filter, safePage, onBack)
                    }
                    return@set
                }
                when {
                    click.clickType.isShiftClick -> {
                        val updated = aliasService.updateRegularAlias(
                            entry.alias,
                            tabComplete = !entry.tabComplete,
                        )
                        if (updated != null) {
                            emitFeedback(host, p, "gui.alias-updated", "alias" to updated.alias)
                        }
                        open(host, p, filter, safePage, onBack)
                    }

                    click.clickType.isRightClick && entry.type == CommandAliasType.BASE -> {
                        val updated = aliasService.updateRegularAlias(
                            entry.alias,
                            baseDisabled = !entry.baseDisabled,
                        )
                        if (updated != null) {
                            emitFeedback(host, p, "gui.alias-updated", "alias" to updated.alias)
                        }
                        open(host, p, filter, safePage, onBack)
                    }

                    click.clickType.isLeftClick -> {
                        val updated = aliasService.updateRegularAlias(
                            entry.alias,
                            enabled = !entry.enabled,
                        )
                        if (updated != null) {
                            emitFeedback(host, p, "gui.alias-updated", "alias" to updated.alias)
                        }
                        open(host, p, filter, safePage, onBack)
                    }

                    else -> {
                        AliasDialogs.openRegularAliasDialog(host, p, entry, onBack) {
                            open(host, p, filter, safePage, onBack)
                        }
                    }
                }
            }
        }

        menu.set(45, MenuItems.item(Material.ANVIL, locale.raw("gui.alias-btn-create"))) { click ->
            AliasDialogs.openCustomAliasDialog(host, click.player, null, onBack) {
                open(host, click.player, filter, safePage, onBack)
            }
        }
        menu.set(46, MenuItems.item(Material.BOOK, locale.raw("gui.alias-btn-dialog"))) { click ->
            AliasDialogs.openOverviewDialog(host, click.player, onBack)
        }
        menu.setFooterNav(
            host = host,
            page = safePage,
            totalPages = totalPages,
            prevSlot = 48,
            nextSlot = 50,
            onPrev = { open(host, player, filter, safePage - 1, onBack) },
            onNext = { open(host, player, filter, safePage + 1, onBack) },
        )
        if (onBack != null) {
            menu.set(49, MenuItems.backButton(host)) { click ->
                onBack(click.player)
            }
        }
        menu.set(52, MenuItems.item(Material.CLOCK, locale.raw("gui.alias-btn-reload"))) { click ->
            aliasService.reload()
            emitFeedback(host, click.player, "gui.alias-reloaded")
            open(host, click.player, filter, safePage, onBack)
        }
        menu.set(53, MenuItems.closeButton(host)) { click ->
            click.player.closeInventory()
        }

        menu.open(player)
    }

    internal fun emitFeedback(
        host: CoreHost,
        player: Player,
        localeKey: String,
        vararg placeholders: Pair<String, Any?>,
    ) {
        host.actionBar.pushTransientLocale(player, localeKey, *placeholders)
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        host.bossBar.showTemporary(
            player = player,
            key = BOSSBAR_CHANNEL,
            title = host.locale.component(localeKey, map),
            progress = 1.0f,
            color = BossBar.Color.PURPLE,
            durationTicks = 60L,
        )
    }
}

/**
 * Dialog entrypoints for command alias management, delegating to Paper Dialogs (`1.21.6+`)
 * or falling back to [AliasGui] on older versions.
 */
object AliasDialogs {

    fun openOverviewDialog(
        host: CoreHost,
        admin: Player,
        onBack: ((Player) -> Unit)? = null,
    ) {
        if (!admin.hasPermission(host.adminPermission)) {
            host.locale.send(admin, "general.no-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperAliasDialogs.openOverviewDialog(host, admin, onBack)
        } else {
            AliasGui.open(host, admin, onBack = onBack)
        }
    }

    fun openCustomAliasDialog(
        host: CoreHost,
        admin: Player,
        existing: CommandAliasEntry?,
        onBack: ((Player) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        if (!admin.hasPermission(host.adminPermission)) {
            host.locale.send(admin, "general.no-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperAliasDialogs.openCustomAliasDialog(host, admin, existing, onBack, onDone)
        } else {
            val short = host.rootAliases.firstOrNull() ?: host.rootCommandName
            host.locale.sendDirectRaw(
                admin,
                "<gray>Custom alias CLI:</gray> <white>/$short aliases create <name> <command...></white>",
            )
            AliasGui.open(host, admin, onBack = onBack)
        }
    }

    fun openRegularAliasDialog(
        host: CoreHost,
        admin: Player,
        entry: CommandAliasEntry,
        onBack: ((Player) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        if (!admin.hasPermission(host.adminPermission)) {
            host.locale.send(admin, "general.no-permission")
            return
        }
        if (PlatformCompat.supportsDialogs) {
            PaperAliasDialogs.openRegularAliasDialog(host, admin, entry, onBack, onDone)
        } else {
            AliasGui.open(host, admin, onBack = onBack)
        }
    }
}
