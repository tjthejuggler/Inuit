package com.example.inuit

import com.example.inuit.data.Net
import com.example.inuit.data.NetStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure round-trip tests for the net registry (no Android dependencies). */
class NetStoreTest {

    @Test
    fun `serialize then parse round-trips nets and active id`() {
        val nets = listOf(
            Net.ALL,
            Net(id = "j1", name = "Juggling", description = "All aspects of juggling", podcastEnabled = false, createdAt = 42L)
        )
        val text = NetStore.serialize("j1", nets)
        val (active, parsed) = NetStore.parse(text)

        assertEquals("j1", active)
        assertEquals(2, parsed.size)
        assertEquals(Net.ALL_ID, parsed[0].id)
        assertEquals("Juggling", parsed[1].name)
        assertEquals("All aspects of juggling", parsed[1].description)
        assertFalse(parsed[1].podcastEnabled)
        assertEquals(42L, parsed[1].createdAt)
    }

    @Test
    fun `parse always guarantees the All net exists and comes first`() {
        val json = """{"version":1,"active":"ghost","nets":[{"id":"x","name":"Chess"}]}"""
        val (active, parsed) = NetStore.parse(json)

        // active pointed at a missing net → falls back to All
        assertEquals(Net.ALL_ID, active)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isAll)
        assertEquals("Chess", parsed[1].name)
    }

    @Test
    fun `parse of an empty registry yields just the All net`() {
        val (active, parsed) = NetStore.parse("""{"version":1}""")
        assertEquals(Net.ALL_ID, active)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].isAll)
    }

    @Test
    fun `net json round-trips through fromJson`() {
        val net = Net(id = "n9", name = "  Bread  ", description = "Baking science", podcastEnabled = false, createdAt = 7L)
        val copy = Net.fromJson(net.toJson())
        assertEquals("n9", copy.id)
        assertEquals("Bread", copy.name) // trimmed
        assertEquals("Baking science", copy.description)
        assertFalse(copy.podcastEnabled)
        assertEquals(7L, copy.createdAt)
    }

    @Test
    fun `blank net name falls back to Net`() {
        val copy = Net.fromJson(JSONObject().apply {
            put("id", "z")
            put("name", "   ")
        })
        assertEquals("Net", copy.name)
        assertTrue(copy.podcastEnabled) // default
    }

    @Test
    fun `question store file names namespace per net`() {
        assertEquals("inuit_store.json", com.example.inuit.data.QuestionStore.fileNameFor(Net.ALL_ID))
        assertEquals("inuit_store_j1.json", com.example.inuit.data.QuestionStore.fileNameFor("j1"))
    }
}
