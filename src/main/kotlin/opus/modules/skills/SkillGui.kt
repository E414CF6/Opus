package opus.modules.skills

import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.database.LeaderboardEntry
import opus.core.gui.MenuItems
import opus.core.gui.MenuWindow
import opus.core.player.OpusPlayerState
import opus.core.text.TextFormatter
import opus.modules.hud.HudDialogs
import opus.modules.party.PartyService
import opus.skills.SkillType
import opus.skills.SuperAbilityType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Interactive inventory GUIs for browsing skills, sub-skill ranks, cooldowns, and leaderboards.
 */
object SkillGui {

    private val borderGlass = MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, " ")

    fun openHub(
        context: OpusContext,
        viewer: Player,
        state: OpusPlayerState,
        readOnlyInspect: Boolean = false,
    ) {
        val title = if (readOnlyInspect) {
            context.locale.component("gui.inspect-title", mapOf("player" to state.username))
        } else {
            context.locale.component("gui.hub-title")
        }
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
                    context.locale.raw("gui.hub-power", "power" to power),
                    context.locale.raw(
                        "gui.hub-mastered",
                        "count" to mastered,
                        "total" to SkillType.nonChildSkills.size
                    ),
                    context.locale.raw("gui.hub-hud", "mode" to state.hudMode.name),
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
                "<gray>${context.locale.categoryName(skill.category)}</gray>",
                context.locale.raw("gui.level-line", "level" to level),
            )
            if (!skill.isChild) {
                val pct = if (needed <= 0) 100 else ((xp / needed) * 100.0).toInt().coerceIn(0, 100)
                lore += context.locale.raw(
                    "gui.xp-bar-line",
                    "bar" to TextFormatter.progressBar(xp, needed.toDouble(), 10),
                    "percent" to pct,
                )
                lore += context.locale.raw("gui.xp-value-line", "xp" to xp.toInt(), "needed" to needed)
            } else {
                val parents = skill.parentSkills().joinToString(" + ") { context.locale.skillName(it) }
                lore += "<dark_gray>($parents)</dark_gray>"
            }
            lore += ""
            lore += context.locale.raw("gui.click-details")

            menu.set(
                slot,
                MenuItems.item(skill.icon, "<aqua><bold>${context.locale.skillName(skill)}</bold></aqua>", lore),
            ) { click ->
                openSkillDetail(context, click.player, state, skill, readOnlyInspect)
            }
        }

        if (!readOnlyInspect) {
            menu.set(
                45,
                MenuItems.item(
                    Material.COMPARATOR,
                    context.locale.raw("gui.hub-btn-settings"),
                    listOf(context.locale.raw("gui.hub-btn-settings-lore")),
                ),
            ) { click ->
                HudDialogs.openSettingsDialog(context, click.player) { p ->
                    openHub(context, p, context.players.getCached(p))
                }
            }

            menu.set(
                46,
                MenuItems.item(
                    Material.CLOCK,
                    context.locale.raw("gui.hub-btn-cooldowns"),
                    listOf(context.locale.raw("gui.hub-btn-cooldowns-lore")),
                ),
            ) { click ->
                openCooldowns(context, click.player, state)
            }

            menu.set(
                47,
                MenuItems.item(
                    Material.GOLD_INGOT,
                    context.locale.raw("gui.hub-btn-top"),
                    listOf(context.locale.raw("gui.hub-btn-top-lore")),
                ),
            ) { click ->
                openLeaderboardAsync(context, click.player, null, 1)
            }

            menu.set(
                50,
                MenuItems.item(
                    Material.BOOK,
                    context.locale.raw("gui.hub-btn-guide"),
                    listOf(context.locale.raw("gui.hub-btn-guide-lore")),
                ),
            ) { click ->
                SkillDialogs.openGuideDialog(context, click.player, state)
            }

            context.getService<PartyService>()?.let { partyService ->
                menu.set(
                    51,
                    MenuItems.item(
                        Material.BEACON,
                        context.locale.raw("gui.hub-btn-party"),
                        listOf(context.locale.raw("gui.hub-btn-party-lore")),
                    ),
                ) { click ->
                    partyService.openPartyPortal(click.player)
                }
            }

            if (viewer.hasPermission("opus.admin")) {
                menu.set(
                    53,
                    MenuItems.item(
                        Material.COMMAND_BLOCK,
                        context.locale.raw("gui.hub-btn-admin"),
                        listOf(context.locale.raw("gui.hub-btn-admin-lore")),
                    ),
                ) { click ->
                    SkillDialogs.openAdminDialog(context, click.player)
                }
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

    fun openSkillDetail(
        context: OpusContext,
        viewer: Player,
        state: OpusPlayerState,
        skill: SkillType,
        readOnlyInspect: Boolean = false,
    ) {
        val skillName = context.locale.skillName(skill)
        val title = context.locale.component("gui.detail-title", mapOf("skill" to skillName))
        val menu = MenuWindow(title, 5)
        menu.fillBorder(borderGlass)

        val level = state.getLevel(skill)
        val xp = state.getXp(skill)
        val needed =
            if (skill.isChild) 0 else context.formula.xpToNextLevel(level, context.config.retroMode, state.powerLevel())

        val headerLore = mutableListOf(context.locale.raw("gui.detail-level", "level" to level))
        if (!skill.isChild) {
            headerLore += TextFormatter.progressBar(xp, needed.toDouble(), 14)
            headerLore += context.locale.raw("gui.xp-value-line", "xp" to xp.toInt(), "needed" to needed)
        }
        skill.superAbility()?.let { ability ->
            val now = System.currentTimeMillis()
            val rem = state.getCooldownRemainingMillis(ability, now)
            val cdText = when {
                state.isAbilityActive(ability, now) -> context.locale.raw("dialog.cooldown-active-now")
                rem <= 0L -> context.locale.raw("gui.ability-ready")
                else -> context.locale.raw("gui.ability-cooldown-time", "time" to TextFormatter.formatDuration(rem))
            }
            headerLore += ""
            headerLore += context.locale.raw("gui.detail-ability", "ability" to context.locale.abilityName(ability))
            headerLore += context.locale.raw("gui.detail-cooldown", "state" to cdText)
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
                context.locale.raw("gui.subskill-rank", "rank" to rank, "max" to sub.maxRanks),
            )
            if (nextReq != null) {
                lore += context.locale.raw("gui.subskill-next-rank", "level" to nextReq)
            } else {
                lore += context.locale.raw("gui.subskill-max-rank")
            }
            menu.set(
                slot,
                MenuItems.item(
                    mat,
                    "${if (unlocked) "<green>" else "<gray>"}${context.locale.subSkillName(sub)}",
                    lore,
                ),
            )
        }

        menu.set(
            38,
            MenuItems.item(Material.ENCHANTED_BOOK, context.locale.raw("gui.detail-btn-dialog")),
        ) { click ->
            SkillDialogs.openSkillGuideDialog(context, click.player, state, skill)
        }
        menu.set(
            39,
            MenuItems.item(Material.ARROW, context.locale.raw("gui.back")),
        ) { click ->
            openHub(context, click.player, state, readOnlyInspect)
        }
        if (!skill.isChild) {
            menu.set(
                40,
                MenuItems.item(Material.GOLD_INGOT, context.locale.raw("gui.hub-btn-top")),
            ) { click ->
                openLeaderboardAsync(context, click.player, skill, 1)
            }
        }
        menu.set(
            41,
            MenuItems.item(Material.BARRIER, context.locale.raw("gui.close")),
        ) { click ->
            click.player.closeInventory()
        }

        menu.open(viewer)
    }

    fun openCooldowns(context: OpusContext, viewer: Player, state: OpusPlayerState) {
        val title = context.locale.component("gui.cooldowns-title")
        val menu = MenuWindow(title, 4)
        menu.fillBorder(borderGlass)

        val now = System.currentTimeMillis()
        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23)
        SuperAbilityType.entries.forEachIndexed { idx, ability ->
            val slot = slots.getOrNull(idx) ?: return@forEachIndexed
            val rem = state.getCooldownRemainingMillis(ability, now)
            val active = state.isAbilityActive(ability, now)
            val status = when {
                active -> context.locale.raw("dialog.cooldown-active-now")
                rem <= 0L -> context.locale.raw("gui.ability-ready")
                else -> context.locale.raw("gui.ability-cooldown-time", "time" to TextFormatter.formatDuration(rem))
            }
            menu.set(
                slot,
                MenuItems.item(
                    ability.skill.icon,
                    "<gold><bold>${context.locale.abilityName(ability)}</bold></gold>",
                    listOf(
                        "<gray>${context.locale.skillName(ability.skill)}</gray>",
                        context.locale.raw("gui.detail-cooldown", "state" to status),
                        "",
                        context.locale.raw("gui.click-details"),
                    ),
                ),
            ) { click ->
                openSkillDetail(context, click.player, state, ability.skill)
            }
        }

        menu.set(29, MenuItems.item(Material.BOOK, context.locale.raw("gui.detail-btn-dialog"))) { click ->
            SkillDialogs.openCooldownsDialog(context, click.player, state)
        }
        menu.set(31, MenuItems.item(Material.ARROW, context.locale.raw("gui.back"))) { click ->
            openHub(context, click.player, state)
        }
        menu.set(33, MenuItems.item(Material.BARRIER, context.locale.raw("gui.close"))) { click ->
            click.player.closeInventory()
        }
        menu.open(viewer)
    }

    fun openLeaderboardAsync(
        context: OpusContext,
        viewer: Player,
        skill: SkillType?,
        page: Int = 1,
    ) {
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * 10
        CommandHelper.dispatchAsync(
            context,
            viewer,
            asyncWork = { context.playerRepository.readTopSkill(skill, 10, offset) },
            onResult = { entries ->
                if (viewer.isOnline) {
                    openLeaderboard(context, viewer, skill, entries, safePage)
                }
            },
        )
    }

    fun openLeaderboard(
        context: OpusContext,
        viewer: Player,
        skill: SkillType?,
        entries: List<LeaderboardEntry>,
        page: Int = 1,
    ) {
        val title = context.locale.component("gui.top-title")
        val menu = MenuWindow(title, 6)
        menu.fillBorder(borderGlass)

        val label = skill?.let { context.locale.skillName(it) } ?: context.locale.raw("gui.power-level-label")
        menu.set(
            4,
            MenuItems.item(
                skill?.icon ?: Material.GOLD_BLOCK,
                "<gold><bold>$label</bold></gold>",
                listOf(context.locale.raw("gui.top-page", "page" to page)),
            ),
        )

        val offset = (page - 1) * 10
        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21)
        entries.take(10).forEachIndexed { idx, entry ->
            val slot = slots[idx]
            val rank = offset + idx + 1
            val mat = when (rank) {
                1 -> Material.DIAMOND
                2 -> Material.GOLD_INGOT
                3 -> Material.IRON_INGOT
                else -> Material.PAPER
            }
            menu.set(
                slot,
                MenuItems.item(
                    mat,
                    context.locale.raw("gui.top-entry-name", "rank" to rank, "player" to entry.username),
                    listOf(
                        context.locale.raw("gui.top-entry-level", "level" to entry.level),
                        context.locale.raw("gui.top-click-inspect"),
                    ),
                ),
            ) { click ->
                CommandHelper.dispatchAsync(
                    context,
                    click.player,
                    asyncWork = {
                        context.players.findByName(entry.username)
                            ?: Bukkit.getPlayerExact(entry.username)?.let { context.players.getCached(it) }
                    },
                    onResult = { targetState ->
                        if (targetState != null && click.player.isOnline) {
                            openHub(
                                context,
                                click.player,
                                targetState,
                                readOnlyInspect = targetState.uuid != click.player.uniqueId,
                            )
                        }
                    },
                )
            }
        }

        if (page > 1) {
            menu.set(45, MenuItems.item(Material.ARROW, context.locale.raw("gui.prev-page"))) { click ->
                openLeaderboardAsync(context, click.player, skill, page - 1)
            }
        }
        menu.set(
            48,
            MenuItems.item(
                Material.HOPPER,
                context.locale.raw("gui.top-filter"),
                listOf("<gray>$label</gray>"),
            ),
        ) { click ->
            openLeaderboardFilter(context, click.player)
        }
        menu.set(49, MenuItems.item(Material.NETHER_STAR, context.locale.raw("gui.back"))) { click ->
            openHub(context, click.player, context.players.getCached(click.player))
        }
        menu.set(50, MenuItems.item(Material.BARRIER, context.locale.raw("gui.close"))) {
            it.player.closeInventory()
        }
        if (entries.size >= 10) {
            menu.set(53, MenuItems.item(Material.ARROW, context.locale.raw("gui.next-page"))) { click ->
                openLeaderboardAsync(context, click.player, skill, page + 1)
            }
        }
        menu.open(viewer)
    }

    fun openLeaderboardFilter(context: OpusContext, viewer: Player) {
        val title = context.locale.component("gui.top-filter-title")
        val menu = MenuWindow(title, 5)
        menu.fillBorder(borderGlass)

        menu.set(
            4,
            MenuItems.item(
                Material.GOLD_BLOCK,
                "<gold><bold>${context.locale.raw("gui.power-level-label")}</bold></gold>"
            ),
        ) { click ->
            openLeaderboardAsync(context, click.player, null, 1)
        }

        val slots = listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28,
        )
        SkillType.nonChildSkills.forEachIndexed { idx, skill ->
            val slot = slots.getOrNull(idx) ?: return@forEachIndexed
            menu.set(
                slot,
                MenuItems.item(skill.icon, "<aqua><bold>${context.locale.skillName(skill)}</bold></aqua>"),
            ) { click ->
                openLeaderboardAsync(context, click.player, skill, 1)
            }
        }

        menu.set(40, MenuItems.item(Material.BARRIER, context.locale.raw("gui.close"))) {
            it.player.closeInventory()
        }
        menu.open(viewer)
    }
}
