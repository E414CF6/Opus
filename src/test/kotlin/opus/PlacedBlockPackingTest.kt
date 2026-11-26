package opus

import opus.core.text.DurationParser
import opus.core.text.TextFormatter
import opus.skills.PlacedBlockTracker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlacedBlockPackingTest {

    @Test
    fun `64-bit coordinate packing round-trips across negative and positive Y coordinates`() {
        val coords = listOf(
            Triple(0, -64, 0),
            Triple(15, 320, 15),
            Triple(-16, 0, -1),
            Triple(127, 64, -255),
            Triple(30000000, 255, -30000000),
        )
        for ((x, y, z) in coords) {
            val packed = PlacedBlockTracker.pack(x, y, z)
            val (lx, uy, lz) = PlacedBlockTracker.unpackLocal(packed)
            assertEquals(x and 0xF, lx)
            assertEquals(y, uy)
            assertEquals(z and 0xF, lz)
        }
    }

    @Test
    fun `TextFormatter normalizes legacy codes and builds progress bars`() {
        val normalized = TextFormatter.normalizeLegacyCodes("&aHello &#123456World")
        assertEquals("<green>Hello <#123456>World", normalized)
        assertEquals("1m 5s", TextFormatter.formatDuration(65_000L))
        val bar = TextFormatter.progressBar(50.0, 100.0, 10)
        assertTrue(bar.contains("█████"))
        assertTrue(bar.contains("░░░░░"))
    }

    @Test
    fun `DurationParser parses multi-unit strings`() {
        assertEquals(30_000L, DurationParser.parseMillis("30s"))
        assertEquals(90_000L, DurationParser.parseMillis("1m30s"))
        assertEquals(86_400_000L, DurationParser.parseMillis("1d"))
        assertNull(DurationParser.parseMillis("invalid"))
    }
}
