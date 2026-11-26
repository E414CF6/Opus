package opus.core.command

import opus.core.CoreHost
import opus.core.ui.DialogKit
import opus.core.ui.PaperDialogKit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Native Paper Dialog (`1.21.6+`) forms and confirmations for managing command aliases.
 * Loaded lazily only when [opus.core.compat.PlatformCompat.supportsDialogs] is true.
 */
internal object PaperAliasDialogs {

    private fun kit(host: CoreHost): PaperDialogKit =
        host.getService(DialogKit::class.java)?.paperBackend ?: PaperDialogKit(host)

    fun openOverviewDialog(
        host: CoreHost,
        admin: Player,
        onBack: ((Player) -> Unit)? = null,
    ) {
        val aliasService = host.getService(CommandAliasService::class.java) ?: return
        val k = kit(host)
        val locale = host.locale
        val regular = aliasService.getRegularAliases().values
        val enabledCount = regular.count { it.enabled }
        val customCount = aliasService.getCustomAliases().size

        val body = listOf(
            k.itemBody(
                ItemStack(Material.NAME_TAG),
                descriptionRaw = locale.raw(
                    "general.aliases-summary",
                    "enabled" to enabledCount,
                    "total" to regular.size,
                    "custom" to customCount,
                ),
            ),
            k.plainBody(locale.raw("dialog.alias-overview-desc")),
        )

        val actions = buildList {
            add(
                k.actionButton(
                    locale.raw("dialog.alias-btn-gui"),
                    width = 150,
                    action = k.customAction { _, p -> AliasGui.open(host, p, onBack = onBack) },
                )
            )
            add(
                k.actionButton(
                    locale.raw("dialog.alias-btn-create"),
                    width = 150,
                    action = k.customAction { _, p ->
                        openCustomAliasDialog(host, p, null, onBack) { openOverviewDialog(host, p, onBack) }
                    },
                )
            )
            add(
                k.actionButton(
                    locale.raw("dialog.alias-btn-reload"),
                    width = 150,
                    action = k.customAction { _, p ->
                        aliasService.reload()
                        AliasGui.emitFeedback(host, p, "gui.alias-reloaded")
                        openOverviewDialog(host, p, onBack)
                    },
                )
            )
            if (onBack != null) {
                add(
                    k.actionButton(
                        locale.raw("gui.back"),
                        width = 150,
                        action = k.customAction { _, p -> onBack(p) },
                    )
                )
            }
        }

        k.showMultiAction(
            player = admin,
            titleRaw = locale.raw("dialog.alias-overview-title"),
            body = body,
            actions = actions,
            columns = 2,
        )
    }

    fun openCustomAliasDialog(
        host: CoreHost,
        admin: Player,
        existing: CommandAliasEntry?,
        onBack: ((Player) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        val aliasService = host.getService(CommandAliasService::class.java) ?: return
        val k = kit(host)
        val dialogs = host.getService(DialogKit::class.java) ?: DialogKit(host)
        val locale = host.locale
        val inputs = listOf(
            k.textInput(
                key = "alias_name",
                labelRaw = locale.raw("dialog.alias-input-name"),
                initial = existing?.alias.orEmpty(),
                maxLength = 48,
            ),
            k.textInput(
                key = "commands",
                labelRaw = locale.raw("dialog.alias-input-commands"),
                initial = existing?.commands?.joinToString(" && ").orEmpty(),
                maxLength = 256,
            ),
            k.boolInput("enabled", locale.raw("dialog.alias-input-enabled"), existing?.enabled ?: true),
            k.boolInput("tab", locale.raw("dialog.alias-input-tab"), existing?.tabComplete ?: true),
            k.boolInput("perm", locale.raw("dialog.alias-input-perm"), existing?.requiresPerm ?: false),
            k.boolInput("exact", locale.raw("dialog.alias-input-exact"), existing?.exact ?: false),
        )

        val saveBtn = k.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = k.customAction { response, p ->
                val rawName = response.getText("alias_name")?.trim().orEmpty().removePrefix("/")
                val rawCmds = response.getText("commands")?.trim().orEmpty()
                if (rawName.isEmpty() || rawCmds.isEmpty()) {
                    locale.send(p, "dialog.alias-invalid-input")
                    host.actionBar.pushTransientLocale(p, "dialog.alias-invalid-input")
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
                    aliasService.removeCustomAlias(existing.alias)
                }
                aliasService.setCustomAlias(
                    name = rawName,
                    commands = cmdList,
                    enabled = response.getBoolean("enabled") ?: true,
                    tabComplete = response.getBoolean("tab") ?: true,
                    requiresPerm = response.getBoolean("perm") ?: false,
                    exact = response.getBoolean("exact") ?: false,
                    customTabs = existing?.customTabs ?: emptyList(),
                )
                locale.send(p, "commands.alias-created", "name" to rawName, "action" to cmdList.joinToString(" && "))
                AliasGui.emitFeedback(host, p, "commands.alias-created", "name" to rawName, "action" to cmdList.first())
                onDone?.invoke()
            },
        )

        val actions = buildList {
            add(saveBtn)
            if (existing != null) {
                add(
                    k.actionButton(
                        locale.raw("dialog.alias-btn-delete"),
                        width = 140,
                        action = k.customAction { _, p ->
                            dialogs.openConfirmation(
                                player = p,
                                titleRaw = locale.raw("dialog.alias-delete-confirm-title"),
                                bodyRaw = locale.raw("dialog.alias-delete-confirm-body", "name" to existing.alias),
                                onConfirm = { cp ->
                                    if (aliasService.removeCustomAlias(existing.alias)) {
                                        locale.send(cp, "commands.alias-deleted", "name" to existing.alias)
                                        AliasGui.emitFeedback(
                                            host,
                                            cp,
                                            "commands.alias-deleted",
                                            "name" to existing.alias
                                        )
                                    }
                                    onDone?.invoke()
                                },
                            )
                        },
                    )
                )
            }
            add(
                k.actionButton(
                    locale.raw("gui.back"),
                    width = 140,
                    action = k.customAction { _, p ->
                        if (onDone != null) onDone() else AliasGui.open(host, p, onBack = onBack)
                    },
                )
            )
        }

        k.showMultiAction(
            player = admin,
            titleRaw = locale.raw(if (existing != null) "dialog.alias-edit-title" else "dialog.alias-create-title"),
            body = listOf(k.plainBody(locale.raw("dialog.alias-custom-help"))),
            inputs = inputs,
            actions = actions,
            columns = 2,
        )
    }

    fun openRegularAliasDialog(
        host: CoreHost,
        admin: Player,
        entry: CommandAliasEntry,
        onBack: ((Player) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        val aliasService = host.getService(CommandAliasService::class.java) ?: return
        val k = kit(host)
        val locale = host.locale
        val inputs = buildList {
            add(
                k.textInput(
                    key = "command",
                    labelRaw = locale.raw("dialog.alias-input-commands"),
                    initial = entry.commands.firstOrNull().orEmpty(),
                    maxLength = 160,
                )
            )
            add(k.boolInput("enabled", locale.raw("dialog.alias-input-enabled"), entry.enabled))
            add(k.boolInput("tab", locale.raw("dialog.alias-input-tab"), entry.tabComplete))
            if (entry.type == CommandAliasType.BASE) {
                add(
                    k.boolInput(
                        "base_disabled",
                        locale.raw("dialog.alias-input-base-disabled"),
                        entry.baseDisabled,
                    )
                )
            }
        }

        val saveBtn = k.actionButton(
            locale.raw("dialog.save"),
            width = 140,
            action = k.customAction { response, p ->
                val cmd = response.getText("command")?.trim()
                val updated = aliasService.updateRegularAlias(
                    aliasKey = entry.alias,
                    enabled = response.getBoolean("enabled"),
                    tabComplete = response.getBoolean("tab"),
                    baseDisabled = if (entry.type == CommandAliasType.BASE) response.getBoolean("base_disabled") else null,
                    commandTemplate = cmd,
                )
                if (updated != null) {
                    AliasGui.emitFeedback(host, p, "gui.alias-updated", "alias" to updated.alias)
                }
                onDone?.invoke()
            },
        )
        val backBtn = k.actionButton(
            locale.raw("gui.back"),
            width = 140,
            action = k.customAction { _, p ->
                if (onDone != null) onDone() else AliasGui.open(host, p, onBack = onBack)
            },
        )

        k.showMultiAction(
            player = admin,
            titleRaw = locale.raw("dialog.alias-regular-title", "alias" to entry.alias),
            inputs = inputs,
            actions = listOf(saveBtn, backBtn),
            columns = 2,
        )
    }
}
