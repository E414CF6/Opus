package opus.core.command

import net.kyori.adventure.bossbar.BossBar
import opus.core.OpusContext
import opus.core.gui.MenuItems
import opus.core.gui.MenuWindow
import opus.core.gui.OpusDialogs
import opus.modules.skills.SkillDialogs
import opus.skills.HudFeedbackHook
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

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
        context: OpusContext,
        player: Player,
        filter: AliasFilter = AliasFilter.ALL,
        page: Int = 1,
    ) {
        if (!player.hasPermission("opus.admin")) {
            context.locale.send(player, "general.no-permission")
            return
        }

        val regular = context.commandAliases.getRegularAliases().values.toList()
        val custom = context.commandAliases.getCustomAliasEntries().values.toList()
        val entries = when (filter) {
            AliasFilter.ALL -> regular + custom
            AliasFilter.REGULAR -> regular
            AliasFilter.CUSTOM -> custom
        }

        val perPage = 28
        val totalPages = ((entries.size + perPage - 1) / perPage).coerceAtLeast(1)
        val safePage = page.coerceIn(1, totalPages)
        val slice = entries.drop((safePage - 1) * perPage).take(perPage)

        val locale = context.locale
        val title = locale.component(
            "gui.alias-title",
            mapOf("page" to safePage.toString(), "total" to totalPages.toString()),
        )
        val menu = MenuWindow(title, 6)
        menu.fillBorder(borderGlass)

        // Filter bar in top row
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
            open(context, click.player, AliasFilter.ALL, 1)
        }
        menu.set(
            4,
            MenuItems.item(
                if (filter == AliasFilter.REGULAR) Material.COMMAND_BLOCK else Material.REPEATER,
                if (filter == AliasFilter.REGULAR) "<aqua><bold>$regLabel</bold></aqua>" else "<gray>$regLabel</gray>",
            ),
        ) { click ->
            open(context, click.player, AliasFilter.REGULAR, 1)
        }
        menu.set(
            6,
            MenuItems.item(
                if (filter == AliasFilter.CUSTOM) Material.WRITABLE_BOOK else Material.BOOK,
                if (filter == AliasFilter.CUSTOM) "<aqua><bold>$cusLabel</bold></aqua>" else "<gray>$cusLabel</gray>",
            ),
        ) { click ->
            open(context, click.player, AliasFilter.CUSTOM, 1)
        }

        val slots = buildList {
            for (row in 1..4) {
                for (col in 1..7) {
                    add(row * 9 + col)
                }
            }
        }

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
                    AliasDialogs.openCustomAliasDialog(context, p, entry) {
                        open(context, p, filter, safePage)
                    }
                    return@set
                }
                when {
                    click.clickType.isShiftClick -> {
                        val updated = context.commandAliases.updateRegularAlias(
                            entry.alias,
                            tabComplete = !entry.tabComplete,
                        )
                        if (updated != null) {
                            emitFeedback(
                                context,
                                p,
                                "gui.alias-updated",
                                "alias" to updated.alias,
                            )
                        }
                        open(context, p, filter, safePage)
                    }

                    click.clickType.isRightClick && entry.type == CommandAliasType.BASE -> {
                        val updated = context.commandAliases.updateRegularAlias(
                            entry.alias,
                            baseDisabled = !entry.baseDisabled,
                        )
                        if (updated != null) {
                            emitFeedback(
                                context,
                                p,
                                "gui.alias-updated",
                                "alias" to updated.alias,
                            )
                        }
                        open(context, p, filter, safePage)
                    }

                    click.clickType.isLeftClick -> {
                        val updated = context.commandAliases.updateRegularAlias(
                            entry.alias,
                            enabled = !entry.enabled,
                        )
                        if (updated != null) {
                            emitFeedback(
                                context,
                                p,
                                "gui.alias-updated",
                                "alias" to updated.alias,
                            )
                        }
                        open(context, p, filter, safePage)
                    }

                    else -> {
                        AliasDialogs.openRegularAliasDialog(context, p, entry) {
                            open(context, p, filter, safePage)
                        }
                    }
                }
            }
        }

        // Bottom controls
        menu.set(45, MenuItems.item(Material.ANVIL, locale.raw("gui.alias-btn-create"))) { click ->
            AliasDialogs.openCustomAliasDialog(context, click.player, null) {
                open(context, click.player, filter, safePage)
            }
        }
        menu.set(46, MenuItems.item(Material.BOOK, locale.raw("gui.alias-btn-dialog"))) { click ->
            AliasDialogs.openOverviewDialog(context, click.player)
        }
        menu.setFooterNav(
            context = context,
            page = safePage,
            totalPages = totalPages,
            prevSlot = 48,
            nextSlot = 50,
            onPrev = { open(context, player, filter, safePage - 1) },
            onNext = { open(context, player, filter, safePage + 1) },
        )
        menu.set(49, MenuItems.backButton(context)) { click ->
            SkillDialogs.openAdminDialog(context, click.player)
        }
        menu.set(52, MenuItems.item(Material.CLOCK, locale.raw("gui.alias-btn-reload"))) { click ->
            context.commandAliases.reload()
            emitFeedback(context, click.player, "gui.alias-reloaded")
            open(context, click.player, filter, safePage)
        }
        menu.set(53, MenuItems.closeButton(context)) { click ->
            click.player.closeInventory()
        }

        menu.open(player)
    }

    internal fun emitFeedback(
        context: OpusContext,
        player: Player,
        localeKey: String,
        vararg placeholders: Pair<String, Any?>,
    ) {
        context.actionBar.pushTransientLocale(player, localeKey, *placeholders)
        val map = placeholders.associate { it.first to (it.second?.toString() ?: "") }
        context.getService<HudFeedbackHook>()?.showChannelBossBar(
            player = player,
            channel = BOSSBAR_CHANNEL,
            title = context.locale.component(localeKey, map),
            color = BossBar.Color.PURPLE,
            progress = 1.0f,
            durationTicks = 60L,
        )
    }
}

/**
 * Paper 26.3 [io.papermc.paper.dialog.Dialog] forms and confirmations for managing command aliases.
 */
object AliasDialogs {

    fun openOverviewDialog(context: OpusContext, admin: Player) {
        if (!admin.hasPermission("opus.admin")) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        val locale = context.locale
        val regular = context.commandAliases.getRegularAliases().values
        val enabledCount = regular.count { it.enabled }
        val customCount = context.commandAliases.getCustomAliases().size

        val body = listOf(
            OpusDialogs.itemBody(
                ItemStack(Material.NAME_TAG),
                descriptionRaw = locale.raw(
                    "general.aliases-summary",
                    "enabled" to enabledCount,
                    "total" to regular.size,
                    "custom" to customCount,
                ),
            ),
            OpusDialogs.plainBody(locale.raw("dialog.alias-overview-desc")),
        )

        val actions = listOf(
            OpusDialogs.actionButton(
                locale.raw("dialog.alias-btn-gui"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p -> AliasGui.open(context, p) },
            ),
            OpusDialogs.actionButton(
                locale.raw("dialog.alias-btn-create"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p ->
                    openCustomAliasDialog(context, p, null) { openOverviewDialog(context, p) }
                },
            ),
            OpusDialogs.actionButton(
                locale.raw("dialog.alias-btn-reload"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p ->
                    context.commandAliases.reload()
                    AliasGui.emitFeedback(context, p, "gui.alias-reloaded")
                    openOverviewDialog(context, p)
                },
            ),
            OpusDialogs.actionButton(
                locale.raw("gui.back"),
                width = 150,
                action = OpusDialogs.customAction(context) { _, p -> SkillDialogs.openAdminDialog(context, p) },
            ),
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = admin,
            title = locale.component("dialog.alias-overview-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openCustomAliasDialog(
        context: OpusContext,
        admin: Player,
        existing: CommandAliasEntry?,
        onDone: (() -> Unit)? = null,
    ) {
        if (!admin.hasPermission("opus.admin")) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        val locale = context.locale
        val inputs = listOf(
            OpusDialogs.textInput(
                key = "alias_name",
                labelRaw = locale.raw("dialog.alias-input-name"),
                initial = existing?.alias.orEmpty(),
                maxLength = 48,
            ),
            OpusDialogs.textInput(
                key = "commands",
                labelRaw = locale.raw("dialog.alias-input-commands"),
                initial = existing?.commands?.joinToString(" && ").orEmpty(),
                maxLength = 256,
            ),
            OpusDialogs.boolInput("enabled", locale.raw("dialog.alias-input-enabled"), existing?.enabled ?: true),
            OpusDialogs.boolInput("tab", locale.raw("dialog.alias-input-tab"), existing?.tabComplete ?: true),
            OpusDialogs.boolInput("perm", locale.raw("dialog.alias-input-perm"), existing?.requiresPerm ?: false),
            OpusDialogs.boolInput("exact", locale.raw("dialog.alias-input-exact"), existing?.exact ?: false),
        )

        val saveBtn = OpusDialogs.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
                val rawName = response.getText("alias_name")?.trim().orEmpty().removePrefix("/")
                val rawCmds = response.getText("commands")?.trim().orEmpty()
                if (rawName.isEmpty() || rawCmds.isEmpty()) {
                    locale.send(p, "dialog.alias-invalid-input")
                    context.actionBar.pushTransientLocale(p, "dialog.alias-invalid-input")
                    return@customAction
                }
                val cmdList = rawCmds.split("&&")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (cmdList.isEmpty()) {
                    locale.send(p, "dialog.alias-invalid-input")
                    return@customAction
                }
                if (existing != null && !existing.alias.equals(rawName, ignoreCase = true)) {
                    context.commandAliases.removeCustomAlias(existing.alias)
                }
                context.commandAliases.setCustomAlias(
                    name = rawName,
                    commands = cmdList,
                    enabled = response.getBoolean("enabled") ?: true,
                    tabComplete = response.getBoolean("tab") ?: true,
                    requiresPerm = response.getBoolean("perm") ?: false,
                    exact = response.getBoolean("exact") ?: false,
                    customTabs = existing?.customTabs ?: emptyList(),
                )
                locale.send(p, "commands.alias-created", "name" to rawName, "action" to cmdList.joinToString(" && "))
                AliasGui.emitFeedback(context, p, "commands.alias-created", "name" to rawName, "action" to cmdList.first())
                onDone?.invoke()
            },
        )

        val actions = buildList {
            add(saveBtn)
            if (existing != null) {
                add(
                    OpusDialogs.actionButton(
                        locale.raw("dialog.alias-btn-delete"),
                        width = 140,
                        action = OpusDialogs.customAction(context) { _, p ->
                            OpusDialogs.showConfirm(
                                context = context,
                                player = p,
                                title = locale.component("dialog.alias-delete-confirm-title"),
                                body = listOf(
                                    OpusDialogs.plainBody(
                                        locale.raw("dialog.alias-delete-confirm-body", "name" to existing.alias)
                                    )
                                ),
                                onConfirm = { cp ->
                                    if (context.commandAliases.removeCustomAlias(existing.alias)) {
                                        locale.send(cp, "commands.alias-deleted", "name" to existing.alias)
                                        AliasGui.emitFeedback(context, cp, "commands.alias-deleted", "name" to existing.alias)
                                    }
                                    onDone?.invoke()
                                },
                            )
                        },
                    )
                )
            }
            add(
                OpusDialogs.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = OpusDialogs.customAction(context) { _, p ->
                        if (onDone != null) onDone() else AliasGui.open(context, p)
                    },
                )
            )
        }

        OpusDialogs.showMultiAction(
            context = context,
            player = admin,
            title = locale.component(
                if (existing != null) "dialog.alias-edit-title" else "dialog.alias-create-title"
            ),
            body = listOf(OpusDialogs.plainBody(locale.raw("dialog.alias-custom-help"))),
            inputs = inputs,
            actions = actions,
            columns = 2,
        )
    }

    fun openRegularAliasDialog(
        context: OpusContext,
        admin: Player,
        entry: CommandAliasEntry,
        onDone: (() -> Unit)? = null,
    ) {
        if (!admin.hasPermission("opus.admin")) {
            context.locale.send(admin, "general.no-permission")
            return
        }
        val locale = context.locale
        val inputs = buildList {
            add(
                OpusDialogs.textInput(
                    key = "command",
                    labelRaw = locale.raw("dialog.alias-input-commands"),
                    initial = entry.commands.firstOrNull().orEmpty(),
                    maxLength = 160,
                )
            )
            add(OpusDialogs.boolInput("enabled", locale.raw("dialog.alias-input-enabled"), entry.enabled))
            add(OpusDialogs.boolInput("tab", locale.raw("dialog.alias-input-tab"), entry.tabComplete))
            if (entry.type == CommandAliasType.BASE) {
                add(
                    OpusDialogs.boolInput(
                        "base_disabled",
                        locale.raw("dialog.alias-input-base-disabled"),
                        entry.baseDisabled,
                    )
                )
            }
        }

        val saveBtn = OpusDialogs.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = OpusDialogs.customAction(context) { response, p ->
                val cmd = response.getText("command")?.trim()
                val updated = context.commandAliases.updateRegularAlias(
                    aliasKey = entry.alias,
                    enabled = response.getBoolean("enabled"),
                    tabComplete = response.getBoolean("tab"),
                    baseDisabled = if (entry.type == CommandAliasType.BASE) response.getBoolean("base_disabled") else null,
                    commandTemplate = cmd,
                )
                if (updated != null) {
                    AliasGui.emitFeedback(context, p, "gui.alias-updated", "alias" to updated.alias)
                }
                onDone?.invoke()
            },
        )
        val backBtn = OpusDialogs.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = OpusDialogs.customAction(context) { _, p ->
                if (onDone != null) onDone() else AliasGui.open(context, p)
            },
        )

        OpusDialogs.showMultiAction(
            context = context,
            player = admin,
            title = locale.component("dialog.alias-regular-title", mapOf("alias" to entry.alias)),
            inputs = inputs,
            actions = listOf(saveBtn, backBtn),
            columns = 2,
        )
    }
}
