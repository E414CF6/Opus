package opus

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.command.OpusCommandRegistrar
import opus.core.gui.MenuListener
import opus.integration.PlaceholderApiHook
import opus.integration.PlaceholderService
import opus.integration.WorldGuardHook
import opus.modules.combat.CombatModule
import opus.modules.crafting.CraftingModule
import opus.modules.gathering.GatheringModule
import opus.modules.hud.HudDialogs
import opus.modules.hud.HudModule
import opus.modules.party.PartyModule
import opus.modules.skills.SkillCoreModule
import opus.modules.skills.SkillDialogs
import opus.modules.skills.SkillGui
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Opus plugin entrypoint bootstrapping core RPG infrastructure and feature modules for Paper 26.3.
 */
class Opus : JavaPlugin() {

    private var context: OpusContext? = null
    private var papiHook: PlaceholderApiHook? = null
    private val worldGuardHook = WorldGuardHook(this)

    override fun onLoad() {
        worldGuardHook.registerFlagsOnLoad()
    }

    override fun onEnable() {
        val ctx = OpusContext(this)
        context = ctx
        Bukkit.getServicesManager().register(OpusContext::class.java, ctx, this, ServicePriority.Normal)

        worldGuardHook.refreshAvailability()
        ctx.registerService(worldGuardHook)

        val placeholderService = PlaceholderService(ctx)
        ctx.registerService(placeholderService)

        // Register global core listeners
        val pm = Bukkit.getPluginManager()
        pm.registerEvents(ctx.players, this)
        pm.registerEvents(ctx.placedBlocks, this)
        pm.registerEvents(ctx.commandAliases, this)
        pm.registerEvents(ctx.actionBar, this)
        pm.registerEvents(MenuListener(), this)
        ctx.actionBar.start()

        // Register modules in dependency order
        val hudModule = HudModule(ctx)
        val skillCoreModule = SkillCoreModule(ctx)
        val partyModule = PartyModule(ctx)
        val gatheringModule = GatheringModule(ctx)
        val combatModule = CombatModule(ctx)
        val craftingModule = CraftingModule(ctx)

        ctx.modules.register(hudModule)
        ctx.modules.register(skillCoreModule)
        ctx.modules.register(partyModule)
        ctx.modules.register(gatheringModule)
        ctx.modules.register(combatModule)
        ctx.modules.register(craftingModule)

        ctx.modules.enableAll()

        if (placeholderService.isPapiEnabled()) {
            placeholderService.startCacheRefreshTask()
            papiHook = PlaceholderApiHook(ctx, placeholderService).also {
                it.register()
                logger.info("Registered PlaceholderAPI expansion (%opus_*%).")
            }
        }

        // Register only the /opus (/mcmmo) root command directly; all module commands are attached
        // as /opus <subcommand> and exposed as standalone aliases via CommandAliasService (Obsidian / CMI-style).
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            ctx.commandRegistry.clear()
            val interceptingRegistrar = OpusCommandRegistrar(registrar, ctx.commandRegistry)
            ctx.modules.registerAllCommands(interceptingRegistrar)

            val rootNode = ctx.commandRegistry.buildRootNode(
                CommandHelper.buildRootCommand(
                    context = ctx,
                    onGui = { player -> SkillGui.openHub(ctx, player, ctx.players.getCached(player)) },
                    onSettings = { player -> HudDialogs.openSettingsDialog(ctx, player) },
                    onAdmin = { player -> SkillDialogs.openAdminDialog(ctx, player) },
                )
            )
            registrar.register(
                pluginMeta,
                rootNode,
                "Opus RPG core management command",
                listOf("mcmmo"),
            )
            ctx.commandAliases.initializeFromRegistry()
        }

        ctx.startAutoSave()
        logger.info("Opus ${pluginMeta.version} enabled (${ctx.modules.activeModules().size} modules active).")
    }

    override fun onDisable() {
        papiHook?.unregister()
        papiHook = null
        context?.shutdown()
        Bukkit.getServicesManager().unregisterAll(this)
        context = null
    }
}
