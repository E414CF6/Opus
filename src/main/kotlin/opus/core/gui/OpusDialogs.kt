package opus.core.gui

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
import opus.core.OpusContext
import opus.core.text.TextFormatter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration

/**
 * Reusable Paper 26.3 Dialog builders and Folia-safe callback dispatchers for Opus.
 */
object OpusDialogs {

    private val defaultCallbackOptions: ClickCallback.Options =
        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()

    fun customAction(
        context: OpusContext,
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
            context.scheduler.runForEntity(player) {
                if (player.isOnline) {
                    handler(response, player)
                }
            }
        }, opts)
    }

    fun closeAction(context: OpusContext, onClose: ((Player) -> Unit)? = null): DialogAction {
        return customAction(context) { _, player ->
            onClose?.invoke(player)
        }
    }

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

    fun plainBody(component: Component, width: Int = 280): DialogBody = DialogBody.plainMessage(component, width)

    fun plainBody(raw: String, width: Int = 280): DialogBody = DialogBody.plainMessage(TextFormatter.format(raw), width)

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
        DialogInput.text(key, TextFormatter.format("<!italic>$labelRaw")).initial(initial).maxLength(maxLength)
            .width(width).labelVisible(true).build()

    fun boolInput(
        key: String,
        labelRaw: String,
        initial: Boolean,
        onTrue: String = "ON",
        onFalse: String = "OFF",
    ): DialogInput = DialogInput.bool(key, TextFormatter.format("<!italic>$labelRaw")).initial(initial).onTrue(onTrue)
        .onFalse(onFalse).build()

    fun singleOptionInput(
        key: String,
        labelRaw: String,
        options: List<Triple<String, String, Boolean>>,
        width: Int = 220,
    ): DialogInput {
        val entries = options.map { (id, displayRaw, initial) ->
            SingleOptionDialogInput.OptionEntry.create(id, TextFormatter.format("<!italic>$displayRaw"), initial)
        }
        return DialogInput.singleOption(key, TextFormatter.format("<!italic>$labelRaw"), entries).width(width)
            .labelVisible(true).build()
    }

    fun numberRangeInput(
        key: String,
        labelRaw: String,
        start: Float,
        end: Float,
        initial: Float,
        step: Float = 0.5f,
        width: Int = 220,
        labelFormat: String = "%s: %s",
    ): DialogInput = DialogInput.numberRange(key, TextFormatter.format("<!italic>$labelRaw"), start, end)
        .initial(initial.coerceIn(start, end)).step(step).width(width).labelFormat(labelFormat).build()

    fun showNotice(
        context: OpusContext,
        player: Player,
        title: Component,
        body: List<DialogBody>,
        buttonLabelRaw: String = context.locale.raw("dialog.close"),
        onClose: ((Player) -> Unit)? = null,
    ) {
        val dialog = Dialog.create { factory ->
            factory.empty().base(
                DialogBase.builder(title).canCloseWithEscape(true).pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).build()
            ).type(
                DialogType.notice(
                    actionButton(buttonLabelRaw, width = 150, action = closeAction(context, onClose))
                )
            )
        }
        player.showDialog(dialog)
    }

    fun showConfirm(
        context: OpusContext,
        player: Player,
        title: Component,
        body: List<DialogBody>,
        confirmLabelRaw: String = context.locale.raw("dialog.confirm"),
        cancelLabelRaw: String = context.locale.raw("dialog.cancel"),
        onConfirm: (Player) -> Unit,
        onCancel: ((Player) -> Unit)? = null,
    ) {
        val dialog = Dialog.create { factory ->
            factory.empty().base(
                DialogBase.builder(title).canCloseWithEscape(true).pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).build()
            ).type(
                DialogType.confirmation(
                    actionButton(
                        confirmLabelRaw,
                        width = 140,
                        action = customAction(context) { _, p -> onConfirm(p) },
                    ),
                    actionButton(
                        cancelLabelRaw,
                        width = 140,
                        action = closeAction(context, onCancel),
                    ),
                )
            )
        }
        player.showDialog(dialog)
    }

    fun showMultiAction(
        context: OpusContext,
        player: Player,
        title: Component,
        body: List<DialogBody> = emptyList(),
        inputs: List<DialogInput> = emptyList(),
        actions: List<ActionButton>,
        exitButton: ActionButton = actionButton(
            context.locale.raw("dialog.close"),
            width = 150,
            action = closeAction(context),
        ),
        columns: Int = 2,
    ) {
        val dialog = Dialog.create { factory ->
            factory.empty().base(
                DialogBase.builder(title).canCloseWithEscape(true).pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).inputs(inputs).build()
            ).type(
                DialogType.multiAction(actions).exitAction(exitButton).columns(columns.coerceIn(1, 4)).build()
            )
        }
        player.showDialog(dialog)
    }
}
