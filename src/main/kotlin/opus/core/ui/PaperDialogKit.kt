package opus.core.ui

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import opus.core.CoreHost
import opus.core.text.TextFormatter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration

/**
 * Native Paper Dialog (`1.21.6+`) builders & Folia-safe callback helpers.
 * Only loaded lazily when [opus.core.compat.PlatformCompat.supportsDialogs] is true.
 */
class PaperDialogKit(private val host: CoreHost) {

    private val defaultCallbackOptions: ClickCallback.Options =
        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()

    fun customAction(
        uses: Int = 1,
        handler: (DialogResponseView, Player) -> Unit,
    ): DialogAction {
        val opts = if (uses == 1) {
            defaultCallbackOptions
        } else {
            ClickCallback.Options.builder().uses(uses).lifetime(Duration.ofMinutes(10)).build()
        }
        return DialogAction.customClick({ response, audience ->
            val player = audience as? Player ?: return@customClick
            host.scheduler.runForEntity(player) {
                if (player.isOnline) {
                    handler(response, player)
                }
            }
        }, opts)
    }

    fun closeAction(onClose: ((Player) -> Unit)? = null): DialogAction =
        customAction { _, player -> onClose?.invoke(player) }

    fun actionButton(
        label: Component,
        tooltip: Component? = null,
        width: Int = 150,
        action: DialogAction,
    ): ActionButton {
        val builder = ActionButton.builder(label).width(width).action(action)
        if (tooltip != null) {
            builder.tooltip(tooltip)
        }
        return builder.build()
    }

    fun actionButton(
        labelRaw: String,
        tooltipRaw: String? = null,
        width: Int = 150,
        action: DialogAction,
    ): ActionButton = actionButton(
        label = TextFormatter.format("<!italic>$labelRaw"),
        tooltip = tooltipRaw?.let { TextFormatter.format("<!italic>$it") },
        width = width,
        action = action,
    )

    fun plainBody(component: Component, width: Int = 280): DialogBody =
        DialogBody.plainMessage(component, width)

    fun plainBody(raw: String, width: Int = 280): DialogBody =
        DialogBody.plainMessage(TextFormatter.format(raw), width)

    fun itemBody(
        item: ItemStack,
        descriptionRaw: String? = null,
        showDecorations: Boolean = true,
        showTooltip: Boolean = true,
        width: Int = 16,
        height: Int = 16,
    ): DialogBody {
        val builder =
            DialogBody.item(item).showDecorations(showDecorations).showTooltip(showTooltip).width(width).height(height)
        if (descriptionRaw != null) {
            builder.description(DialogBody.plainMessage(TextFormatter.format(descriptionRaw), 240))
        }
        return builder.build()
    }

    fun textInput(
        key: String,
        labelRaw: String,
        initial: String = "",
        maxLength: Int = 64,
        width: Int = 220,
    ): DialogInput =
        DialogInput.text(key, TextFormatter.format("<!italic>$labelRaw"))
            .initial(initial)
            .maxLength(maxLength)
            .width(width)
            .labelVisible(true)
            .build()

    fun boolInput(
        key: String,
        labelRaw: String,
        initial: Boolean,
        onTrue: String = "ON",
        onFalse: String = "OFF",
    ): DialogInput =
        DialogInput.bool(key, TextFormatter.format("<!italic>$labelRaw"))
            .initial(initial)
            .onTrue(onTrue)
            .onFalse(onFalse)
            .build()

    fun singleOptionInput(
        key: String,
        labelRaw: String,
        options: List<Triple<String, String, Boolean>>,
        width: Int = 220,
    ): DialogInput {
        val entries = options.map { (id, display, selected) ->
            SingleOptionDialogInput.OptionEntry.create(id, TextFormatter.format("<!italic>$display"), selected)
        }
        return DialogInput.singleOption(key, TextFormatter.format("<!italic>$labelRaw"), entries)
            .width(width)
            .labelVisible(true)
            .build()
    }

    fun numberRangeInput(
        key: String,
        labelRaw: String,
        start: Float,
        end: Float,
        initial: Float,
        step: Float = 0.5f,
        width: Int = 220,
    ): DialogInput =
        DialogInput.numberRange(key, TextFormatter.format("<!italic>$labelRaw"), start, end)
            .initial(initial)
            .step(step)
            .width(width)
            .build()

    fun showMultiAction(
        player: Player,
        titleRaw: String,
        body: List<DialogBody> = emptyList(),
        inputs: List<DialogInput> = emptyList(),
        actions: List<ActionButton>,
        columns: Int = 2,
        exitLabelRaw: String = host.locale.raw("dialog.close"),
        onExit: ((Player) -> Unit)? = null,
    ) {
        val exitButton = actionButton(exitLabelRaw, width = 120, action = closeAction(onExit))
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(TextFormatter.format("<!italic>$titleRaw"))
                        .canCloseWithEscape(true)
                        .body(body)
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.multiAction(actions, exitButton, columns))
        }
        player.showDialog(dialog)
    }

    fun showNotice(
        player: Player,
        titleRaw: String,
        body: List<DialogBody>,
        buttonLabelRaw: String = host.locale.raw("dialog.close"),
        onClose: ((Player) -> Unit)? = null,
    ) {
        val btn = actionButton(buttonLabelRaw, width = 150, action = closeAction(onClose))
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(TextFormatter.format("<!italic>$titleRaw"))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build()
                )
                .type(DialogType.notice(btn))
        }
        player.showDialog(dialog)
    }

    fun openConfirmation(
        player: Player,
        titleRaw: String,
        bodyRaw: String,
        confirmLabelRaw: String,
        cancelLabelRaw: String,
        onConfirm: (Player) -> Unit,
        onCancel: ((Player) -> Unit)? = null,
    ) {
        val yesButton = actionButton("<red>$confirmLabelRaw</red>", width = 130, action = customAction { _, p ->
            onConfirm(p)
        })
        val noButton = actionButton("<gray>$cancelLabelRaw</gray>", width = 130, action = customAction { _, p ->
            onCancel?.invoke(p)
        })
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(TextFormatter.format("<!italic>$titleRaw"))
                        .canCloseWithEscape(true)
                        .body(listOf(plainBody(bodyRaw)))
                        .build()
                )
                .type(DialogType.confirmation(yesButton, noButton))
        }
        player.showDialog(dialog)
    }
}
