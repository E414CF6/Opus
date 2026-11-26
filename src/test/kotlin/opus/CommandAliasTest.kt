package opus

import net.kyori.adventure.text.Component
import opus.core.command.*
import opus.core.ui.ActionBarPriority
import opus.core.ui.ActionBarService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class CommandAliasTest {

    @Test
    fun `AliasMatcher resolves regular, subbase, multi-word, disabled, and wildcard aliases`() {
        val aliases = linkedMapOf(
            "skills" to CommandAliasEntry(
                alias = "skills",
                commands = listOf("opus skills $1-"),
                enabled = true,
                type = CommandAliasType.BASE,
                targetSubcommand = "skills",
            ),
            "mcstats" to CommandAliasEntry(
                alias = "mcstats",
                commands = listOf("opus skills $1-"),
                enabled = true,
                type = CommandAliasType.SUBBASE,
                targetSubcommand = "skills",
            ),
            "stats" to CommandAliasEntry(
                alias = "stats",
                commands = listOf("opus skills $1-"),
                enabled = false,
                type = CommandAliasType.SUBBASE,
                targetSubcommand = "skills",
            ),
            "party" to CommandAliasEntry(
                alias = "party",
                commands = listOf("opus party $1-"),
                enabled = true,
                type = CommandAliasType.CUSTOM,
            ),
            "party vip" to CommandAliasEntry(
                alias = "party vip",
                commands = listOf("msg: VIP party info"),
                enabled = true,
                type = CommandAliasType.CUSTOM,
            ),
            "rpg ?" to CommandAliasEntry(
                alias = "rpg ?",
                commands = listOf("msg: RPG help"),
                enabled = true,
                type = CommandAliasType.CUSTOM,
            ),
            "ping" to CommandAliasEntry(
                alias = "ping",
                commands = listOf("actionbar: Pong!"),
                enabled = true,
                exact = true,
                type = CommandAliasType.CUSTOM,
            ),
        )

        assertEquals("skills", AliasMatcher.findBestMatch("skills", aliases)?.alias)
        assertEquals("skills", AliasMatcher.findBestMatch("skills gui", aliases)?.alias)
        assertEquals("mcstats", AliasMatcher.findBestMatch("mcstats text", aliases)?.alias)
        assertNull(AliasMatcher.findBestMatch("stats", aliases))
        assertEquals("party vip", AliasMatcher.findBestMatch("party vip", aliases)?.alias)
        assertEquals("party", AliasMatcher.findBestMatch("party create Alpha", aliases)?.alias)
        assertEquals("rpg ?", AliasMatcher.findBestMatch("rpg unknown arg", aliases)?.alias)
        assertEquals("ping", AliasMatcher.findBestMatch("ping", aliases)?.alias)
        assertNull(AliasMatcher.findBestMatch("ping extra", aliases))
    }

    @Test
    fun `AliasMatcher expands positional arguments and trims empty trailing placeholders`() {
        val baseEntry = CommandAliasEntry(
            alias = "skills",
            commands = listOf("opus skills $1-"),
            type = CommandAliasType.BASE,
            targetSubcommand = "skills",
        )
        assertEquals(listOf("opus skills"), AliasMatcher.expandCommands(baseEntry, "skills", "Alex"))
        assertEquals(listOf("opus skills gui"), AliasMatcher.expandCommands(baseEntry, "skills gui", "Alex"))

        val customEntry = CommandAliasEntry(
            alias = "boost",
            commands = listOf("opus addxp $1 mining $2", "actionbar: {player} boosted $1 ($2-)"),
            type = CommandAliasType.CUSTOM,
        )
        assertEquals(
            listOf("opus addxp Steve mining 500", "actionbar: Alex boosted Steve (500 now)"),
            AliasMatcher.expandCommands(customEntry, "boost Steve 500 now", "Alex"),
        )
        assertFalse(AliasMatcher.isSpecializedAlias(listOf("opus skills gui")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("actionbar: Hello")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("bossbar: YELLOW|5|Hello")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("opus skills", "opus party")))
    }

    @Test
    fun `CommandRegistry stores subcommands, aliases, and disabled standalone alias exclusions`() {
        val registry = CommandRegistry()
        registry.registerSubcommand(
            name = "skills",
            description = "Open Opus RPG skills GUI or view stats",
            permission = "opus.command",
            aliases = listOf("mcstats", "stats"),
            disabledStandaloneAliases = listOf("stats"),
        ) { _, _ -> true }

        registry.registerSubcommand(
            name = "inspect",
            description = "Inspect stats",
            permission = "opus.command",
            aliases = listOf("mcinspect", "mmoinspect"),
            disabledStandaloneAliases = listOf("inspect"),
        ) { _, _ -> true }

        registry.registerSubcommand(
            name = "adminchat",
            description = "Toggle admin chat",
            permission = "opus.adminchat",
            aliases = listOf("opusac", "ac", "a"),
            disabledStandaloneAliases = listOf("ac", "a"),
        ) { _, _ -> true }

        registry.registerSubcommand(
            name = "menu",
            description = "Open hub",
            permission = "opus.command",
            standaloneByDefault = false,
        ) { _, _ -> true }

        assertEquals(4, registry.allSubcommands().size)
        val skills = registry.getSubcommand("mcstats")
        assertNotNull(skills)
        assertEquals("skills", skills!!.name)
        assertTrue("stats" in skills.disabledStandaloneAliases)
        assertFalse("mcstats" in skills.disabledStandaloneAliases)

        val inspect = registry.getSubcommand("mmoinspect")
        assertNotNull(inspect)
        assertTrue("inspect" in inspect!!.disabledStandaloneAliases)
        assertFalse("mcinspect" in inspect.disabledStandaloneAliases)

        val adminChat = registry.getSubcommand("opusac")
        assertNotNull(adminChat)
        assertEquals(setOf("ac", "a"), adminChat!!.disabledStandaloneAliases)

        val menu = registry.getSubcommand("menu")
        assertNotNull(menu)
        assertFalse(menu!!.standaloneByDefault)
    }

    @Test
    fun `SpecializedCommandExecutor parses delays and evaluates conditions cleanly`() {
        assertEquals(40L, SpecializedCommandExecutor.parseDelayTicks("delay!2"))
        assertEquals(30L, SpecializedCommandExecutor.parseDelayTicks("delay:1.5s"))
        assertEquals(1L, SpecializedCommandExecutor.parseDelayTicks("delay!0.01"))
        assertNull(SpecializedCommandExecutor.parseDelayTicks("delay!0"))
        assertNull(SpecializedCommandExecutor.parseDelayTicks("delay!abc"))
        assertNull(SpecializedCommandExecutor.parseDelayTicks("actionbar: hi"))

        assertTrue(SpecializedCommandExecutor.evaluateCondition(null, "online>=5", null) { 10 })
        assertFalse(SpecializedCommandExecutor.evaluateCondition(null, "!online>=5", null) { 10 })
        assertTrue(SpecializedCommandExecutor.evaluateCondition(null, "online<5", null) { 2 })
    }

    @Test
    fun `ActionBarService arbitrates priority and expires transient entries`() {
        val service = ActionBarService()
        val uuid = UUID.randomUUID()

        service.setState(uuid, "xp", ActionBarPriority.XP, Component.text("XP +10"))
        service.setState(uuid, "warmup", ActionBarPriority.WARMUP, Component.text("Warmup 3s"))
        service.setState(
            uuid,
            ActionBarService.TRANSIENT_KEY,
            ActionBarPriority.TRANSIENT,
            Component.text("Saved!"),
            ttlMillis = 1000L
        )

        val now = System.currentTimeMillis()
        assertEquals("transient", service.resolveActiveEntry(uuid, now)?.key?.let {
            if (it == ActionBarService.TRANSIENT_KEY) "transient" else it
        })

        // After TTL expires, WARMUP outranks XP
        val afterExpiry = service.resolveActiveEntry(uuid, now + 5000L)
        assertEquals("warmup", afterExpiry?.key)

        // When low-priority HUD is disabled, XP is filtered out while WARMUP stays visible
        service.clearState(uuid, "warmup")
        assertNull(service.resolveActiveEntry(uuid, now + 5000L, includeLowPriorityHud = false))
        assertEquals("xp", service.resolveActiveEntry(uuid, now + 5000L, includeLowPriorityHud = true)?.key)
    }
}
