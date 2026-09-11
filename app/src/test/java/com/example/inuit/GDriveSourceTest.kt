package com.example.inuit

import com.example.inuit.data.SourceMix
import com.example.inuit.data.Net
import com.example.inuit.data.gdrive.GDriveFact
import com.example.inuit.data.gdrive.GDriveFactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive-document source rides the standard source-mix machinery: a fifth
 * accent with its own weight, JSON round-trip and accent-line rendering.
 */
class GDriveSourceTest {

    // ── SourceMix ───────────────────────────────────────────────────────

    @Test
    fun `gdrive is a built-in accent key`() {
        assertTrue(SourceMix.GDRIVE in SourceMix.ACCENTS)
    }

    @Test
    fun `normalize always emits a gdrive key`() {
        val mix = SourceMix.normalize(mapOf(SourceMix.LOCATION to 20))
        assertEquals(0, mix[SourceMix.GDRIVE])
        assertEquals(80, mix[SourceMix.CORE])
        assertEquals(100, mix.values.sum())
    }

    @Test
    fun `legacy toggle gives gdrive its sprinkle share`() {
        val mix = SourceMix.legacy(
            location = false, date = false, crossNet = false,
            tailText = false, gdrive = true
        )
        assertEquals(SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.GDRIVE])
        assertEquals(100 - SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.CORE])
    }

    @Test
    fun `net json round-trips the gdrive toggle and weight`() {
        val net = Net(
            id = "g1", name = "Physics",
            gdriveEnabled = true,
            sourceWeights = mapOf(SourceMix.GDRIVE to 15, SourceMix.LOCATION to 10)
        )
        val copy = Net.fromJson(net.toJson())
        assertTrue(copy.gdriveEnabled)
        assertEquals(15, copy.mix()[SourceMix.GDRIVE])
        assertEquals(10, copy.mix()[SourceMix.LOCATION])
        assertEquals(75, copy.mix()[SourceMix.CORE])
    }

    @Test
    fun `legacy net json without gdrive key parses with the accent off`() {
        val net = Net(id = "g2", name = "Plain")
        val json = net.toJson()
        json.remove("gdrive")
        val copy = Net.fromJson(json)
        assertFalse(copy.gdriveEnabled)
        assertEquals(0, copy.mix()[SourceMix.GDRIVE])
    }

    // ── fact-pool rendering ─────────────────────────────────────────────

    @Test
    fun `accent lines render one compact doc line newest first`() {
        val facts = listOf(
            GDriveFact("d1", "T1", "fact one", 1000L),
            GDriveFact("d2", "T2", "fact two\nwith a newline", 2000L)
        )
        val lines = GDriveFactStore.lines(facts)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("- DOC \"T2\"")) // newest first
        assertFalse(lines[0].contains('\n'))
        assertTrue(lines[1].contains("fact one"))
    }

    @Test
    fun `accent lines cap at the configured dosage`() {
        val facts = (1..10).map { GDriveFact("d$it", "T$it", "f$it", it.toLong() * 1000) }
        val lines = GDriveFactStore.lines(facts, max = 5)
        assertEquals(5, lines.size)
        // the five NEWEST documents survive the cap (T10 is newest)
        assertTrue(lines.first().contains("\"T10\""))
        assertTrue(lines.last().contains("\"T6\""))
    }

    @Test
    fun `long facts are clipped with an ellipsis`() {
        val long = "x".repeat(500)
        val lines = GDriveFactStore.lines(listOf(GDriveFact("d", "T", long, 1L)))
        val line = lines.single()
        assertTrue(line.length < 500)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `fact json round-trips`() {
        val f = GDriveFact("doc9", "Title with \"quotes\"", "facts with\nnewline", 42L)
        val copy = GDriveFact.fromJson(f.toJson())
        assertEquals(f, copy)
    }

    @Test
    fun `store serialize-parse round-trips through the companion`() {
        val facts = listOf(
            GDriveFact("a", "A", "alpha", 1L),
            GDriveFact("b", "B", "beta", 2L)
        )
        val parsed = GDriveFactStore.parse(GDriveFactStore.serialize(facts))
        assertEquals(facts, parsed)
    }
}
