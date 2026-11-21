package opus.modules.skills

import opus.core.OpusContext
import opus.core.database.LeaderboardEntry
import opus.core.gui.MenuItems
import opus.core.gui.MenuWindow
import opus.core.player.OpusPlayerState
import opus.core.text.TextFormatter
import opus.skills.SkillType
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Interactive inventory GUIs for browsing skills, sub-skill ranks, and leaderboards.
 */
object SkillGui {

    private val borderGlass = MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " ")

    fun openHub(context: OpusContext, viewer: Player, state: OpusPlayerState) {
        val lang = context.config.language
        val title = context.locale.component("gui.hub-title")
        val menu = MenuWindow(title, 6)
        menu.fillBorder(borderGlass)

        val power = state.powerLevel()
        val mastered = context.ranks.masteredCount(state)
        menu.set(
            4,
            MenuItems.item(
                Material.NETHER_STAR,
                "<gradient:#38bdf8:#818cf8><bold>${state.username}</bold></gradient>",
                listOf(
                    "<gray>Power Level:</gray> <gold>$power</gold>",
                    "<gray>Mastered:</gray> <aqua>$mastered/${SkillType.nonChildSkills.size}</aqua>",
                    "<gray>HUD:</gray> <white>${state.hudMode.name}</white>",
                ),
            ),
        )

        val slots = listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32,
        )
        SkillType.entries.forEachIndexed { idx, skill ->
            val slot = slots.getOrNull(idx) ?: return@forEachIndexed
            val level = state.getLevel(skill)
            val xp = state.getXp(skill)
            val needed = if (skill.isChild) 0 else context.formula.xpToNextLevel(level, context.config.retroMode, power)
            val lore = mutableListOf(
                "<gray>${if (lang == "ko") skill.category.koName else skill.category.enName}</gray>",
                "<gold>Lv.$level</gold>",
            )
            if (!skill.isChild) {
                val pct = if (needed <= 0) 100 else ((xp / needed) * 100.0).toInt().coerceIn(0, 100)
                lore += "${TextFormatter.progressBar(xp, needed.toDouble(), 10)} <gray>$pct%</gray>"
                lore += "<gray>${xp.toInt()} / $needed XP</gray>"
            } else {
                val parents = skill.parentSkills().joinToString(" + ") { it.displayName(lang) }
                lore += "<dark_gray>($parents)</dark_gray>"
            }
            lore += ""
            lore += "<yellow>▸ Click for details</yellow>"

            menu.set(
                slot,
                MenuItems.item(skill.icon, "<aqua><bold>${skill.displayName(lang)}</bold></aqua>", lore),
            ) { click ->
                openSkillDetail(context, click.player, state, skill)
            }
        }

        menu.set(
            49,
            MenuItems.item(Material.BARRIER, context.locale.raw("gui.close")),
        ) { click ->
            click.player.closeInventory()
        }

        menu.open(viewer)
    }

    fun openSkillDetail(context: OpusContext, viewer: Player, state: OpusPlayerState, skill: SkillType) {
        val lang = context.config.language
        val skillName = skill.displayName(lang)
        val title = context.locale.component("gui.detail-title", mapOf("skill" to skillName))
        val menu = MenuWindow(title, 5)
        menu.fillBorder(borderGlass)

        val level = state.getLevel(skill)
        val xp = state.getXp(skill)
        val needed =
            if (skill.isChild) 0 else context.formula.xpToNextLevel(level, context.config.retroMode, state.powerLevel())

        val headerLore = mutableListOf("<gold>Level: $level</gold>")
        if (!skill.isChild) {
            headerLore += TextFormatter.progressBar(xp, needed.toDouble(), 14)
            headerLore += "<gray>${xp.toInt()} / $needed XP</gray>"
        }
        skill.superAbility()?.let { ability ->
            val rem = state.getCooldownRemainingMillis(ability)
            val cdText = if (rem <= 0L) "<green>Ready</green>" else "<red>${TextFormatter.formatDuration(rem)}</red>"
            headerLore += ""
            headerLore += "<gold>⚡ ${ability.displayName(lang)}</gold>"
            headerLore += "<gray>Cooldown:</gray> $cdText"
        }

        menu.set(4, MenuItems.item(skill.icon, "<aqua><bold>$skillName</bold></aqua>", headerLore))

        val subSlots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        skill.subSkills().forEachIndexed { idx, sub ->
            val slot = subSlots.getOrNull(idx) ?: return@forEachIndexed
            val rank = context.ranks.getRank(state, sub)
            val unlocked = rank > 0
            val nextReq = context.ranks.nextUnlockLevel(state, sub)
            val mat = if (unlocked) Material.EMERALD else Material.COAL
            val lore = mutableListOf(
                "<gray>Rank:</gray> <aqua>$rank / ${sub.maxRanks}</aqua>",
            )
            if (nextReq != null) {
                lore += "<gray>Next Rank:</gray> <yellow>Lv.$nextReq</yellow>"
            } else {
                lore += "<green>✔ Max Rank</green>"
            }
            menu.set(
                slot,
                MenuItems.item(
                    mat,
                    "${if (unlocked) "<green>" else "<gray>"}${sub.displayName(lang)}",
                    lore,
                ),
            )
        }

        menu.set(
            39,
            MenuItems.item(Material.ARROW, context.locale.raw("gui.back")),
        ) { click ->
            openHub(context, click.player, state)
        }
        menu.set(
            41,
            MenuItems.item(Material.BARRIER, context.locale.raw("gui.close")),
        ) { click ->
            click.player.closeInventory()
        }

        menu.open(viewer)
    }

    fun openLeaderboard(
        context: OpusContext,
        viewer: Player,
        skill: SkillType?,
        entries: List<LeaderboardEntry>,
    ) {
        val lang = context.config.language
        val title = context.locale.component("gui.top-title")
        val menu = MenuWindow(title, 4)
        menu.fillBorder(borderGlass)

        val label = skill?.displayName(lang) ?: if (lang == "ko") "총합 레벨" else "Power Level"
        menu.set(4, MenuItems.item(skill?.icon ?: Material.GOLD_BLOCK, "<gold><bold>$label</bold></gold>"))

        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21)
        entries.take(10).forEachIndexed { idx, entry ->
            val slot = slots[idx]
            val mat = when (idx) {
                0 -> Material.DIAMOND
                1 -> Material.GOLD_INGOT
                2 -> Material.IRON_INGOT
                else -> Material.PAPER
            }
            menu.set(
                slot,
                MenuItems.item(
                    mat,
                    "<yellow>#${idx + 1}</yellow> <white>${entry.username}</white>",
                    listOf("<aqua>Lv.${entry.level}</aqua>"),
                ),
            )
        }

        menu.set(31, MenuItems.item(Material.BARRIER, context.locale.raw("gui.close"))) {
            it.player.closeInventory()
        }
        menu.open(viewer)
    }
}
