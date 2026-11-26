package opus

import opus.core.OpusContext
import opus.core.command.RootCommandExecutor
import opus.core.compat.PlatformCompat
import opus.core.ui.MenuListener
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
 * Opus plugin entrypoint bootstrapping domain-agnostic core infrastructure,
 * RPG services, and modular gameplay systems across Paper/Spigot `1.20.4–26.3+` and Folia.
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
        val rpg = RpgServices(ctx)
        ctx.registerService(rpg)
        Bukkit.getServicesManager().register(OpusContext::class.java, ctx, this, ServicePriority.Normal)
        context = ctx

        worldGuardHook.refreshAvailability()
        ctx.registerService(worldGuardHook)

        val placeholderService = PlaceholderService(ctx)
        ctx.registerService(placeholderService)

        val pm = Bukkit.getPluginManager()
        pm.registerEvents(rpg.players, this)
        pm.registerEvents(rpg.placedBlocks, this)
        pm.registerEvents(ctx.commandAliases, this)
        pm.registerEvents(ctx.actionBar, this)
        pm.registerEvents(MenuListener(), this)
        ctx.actionBar.start()

        // Register modules in dependency order
        ctx.modules.register(HudModule(ctx))
        ctx.modules.register(SkillCoreModule(ctx))
        ctx.modules.register(PartyModule(ctx))
        ctx.modules.register(GatheringModule(ctx))
        ctx.modules.register(CombatModule(ctx))
        ctx.modules.register(CraftingModule(ctx))
        ctx.modules.enableAll()

        if (pm.getPlugin("PlaceholderAPI") != null) {
            papiHook = PlaceholderApiHook(ctx, placeholderService).also {
                if (it.register()) {
                    logger.info("Registered PlaceholderAPI expansion (%opus_*%).")
                }
            }
        }

        val rootExecutor = RootCommandExecutor(
            host = ctx,
            commandAliases = ctx.commandAliases,
            userPermission = "opus.command",
            openDashboard = { player -> SkillGui.openHub(ctx, player, rpg.players.getCached(player)) },
            openSettings = { player ->
                HudDialogs.openSettingsDialog(ctx, player) { opener ->
                    SkillGui.openHub(ctx, opener, rpg.players.getCached(opener))
                }
            },
            openAdminPanel = { player -> SkillDialogs.openAdminDialog(ctx, player) },
            infoReporter = { sender ->
                val modeKey = if (ctx.config.retroMode) "commands.opus-mode-retro" else "commands.opus-mode-standard"
                ctx.locale.send(
                    sender,
                    "commands.opus-info",
                    "version" to PlatformCompat.pluginVersion(this),
                    "db" to ctx.database.dialect.name,
                    "mode" to ctx.locale.raw(modeKey),
                    "lang" to ctx.config.language,
                    "modules" to ctx.modules.activeModules().size,
                )
            },
            modulesReporter = { sender ->
                for (mod in ctx.modules.activeModules()) {
                    ctx.locale.sendRaw(
                        sender,
                        "commands.opus-module-entry",
                        "id" to mod.id,
                        "description" to mod.description,
                    )
                }
            },
        )
        rootExecutor.registerCoreSubcommands()
        ctx.modules.registerAllSubcommands(ctx.commandRegistry)

        val rootCmd = getCommand("opus")
        if (rootCmd != null) {
            rootCmd.setExecutor(rootExecutor)
            rootCmd.tabCompleter = rootExecutor
        } else {
            logger.severe("Root command 'opus' missing from plugin.yml; commands could not be bound!")
        }

        ctx.commandAliases.initializeFromRegistry()
        rpg.startAutoSave()

        logger.info("Opus ${PlatformCompat.pluginVersion(this)} enabled (${ctx.modules.activeModules().size} modules active).")
    }

    override fun onDisable() {
        papiHook?.unregister()
        papiHook = null
        context?.getService<RpgServices>()?.flushBeforeShutdown()
        context?.shutdown()
        context = null
        Bukkit.getServicesManager().unregisterAll(this)
    }
}
