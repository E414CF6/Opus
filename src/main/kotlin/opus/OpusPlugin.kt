package opus

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import opus.core.OpusContext
import opus.core.command.CommandHelper
import opus.core.gui.MenuListener
import opus.integration.PlaceholderApiHook
import opus.integration.PlaceholderService
import opus.modules.combat.CombatModule
import opus.modules.crafting.CraftingModule
import opus.modules.gathering.GatheringModule
import opus.modules.hud.HudModule
import opus.modules.party.PartyModule
import opus.modules.skills.SkillCoreModule
import opus.modules.skills.SkillGui
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Opus plugin entrypoint bootstrapping core RPG infrastructure and feature modules for Paper 26.3.
 */
class OpusPlugin : JavaPlugin() {

    private var context: OpusContext? = null
    private var papiHook: PlaceholderApiHook? = null

    override fun onEnable() {
        val ctx = OpusContext(this)
        context = ctx
        Bukkit.getServicesManager().register(OpusContext::class.java, ctx, this, ServicePriority.Normal)

        val placeholderService = PlaceholderService(ctx)
        ctx.registerService(placeholderService)

        // Register global core listeners
        val pm = Bukkit.getPluginManager()
        pm.registerEvents(ctx.players, this)
        pm.registerEvents(ctx.placedBlocks, this)
        pm.registerEvents(MenuListener(), this)

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

        // Register Brigadier commands via Paper LifecycleEvents
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            CommandHelper.registerRootCommand(registrar, ctx) { player ->
                SkillGui.openHub(ctx, player, ctx.players.getCached(player))
            }
            ctx.modules.registerAllCommands(registrar)
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
