package com.example.inuit

import com.example.inuit.data.Net
import com.example.inuit.data.gen.QuestionGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the refill scheduler: which net gets generated for next
 * (active-first priority, then neediest background net) and the escalating
 * auto-retry cadence that keeps failed batches coming back.
 */
class NetSchedulingTest {

    private val all = Net.ALL
    private val juggling = Net(id = "j", name = "Juggling")
    private val chess = Net(id = "c", name = "Chess")
    private val nets = listOf(all, juggling, chess)

    private fun picker(queues: Map<String, Int>, activeId: String, threshold: Int = 150) =
        QuestionGenerator.pickNeedyNet(activeId, nets, threshold) { queues[it] ?: 0 }

    @Test
    fun `active net wins even when other nets are emptier`() {
        // Chess is empty, Juggling has 5 — but the user is looking at Juggling.
        val target = picker(mapOf("all" to 150, "j" to 5, "c" to 0), activeId = "j")
        assertEquals("j", target?.id)
    }

    @Test
    fun `neediest background net is picked when the active net is healthy`() {
        val target = picker(mapOf("all" to 150, "j" to 40, "c" to 10), activeId = "all")
        assertEquals("c", target?.id) // 10 < 40 — emptiest first
    }

    @Test
    fun `null when every net is at the threshold`() {
        assertNull(picker(mapOf("all" to 150, "j" to 150, "c" to 150), activeId = "all"))
    }

    @Test
    fun `nets at or above threshold are never picked`() {
        val target = picker(mapOf("all" to 200, "j" to 150, "c" to 3), activeId = "all")
        assertEquals("c", target?.id)
    }

    @Test
    fun `a brand-new empty net is picked immediately when active`() {
        val target = picker(mapOf("all" to 150, "j" to 0, "c" to 150), activeId = "j")
        assertEquals("j", target?.id)
    }

    @Test
    fun `unknown active id falls back to the neediest other net`() {
        val target = picker(mapOf("all" to 150, "j" to 20, "c" to 90), activeId = "ghost")
        assertEquals("j", target?.id)
    }

    @Test
    fun `auto retry delay escalates by five up to the cap`() {
        val base = 60_000L
        assertEquals(300_000L, QuestionGenerator.nextAutoRetryDelay(base))
        assertEquals(900_000L, QuestionGenerator.nextAutoRetryDelay(300_000L))
        assertEquals(900_000L, QuestionGenerator.nextAutoRetryDelay(900_000L)) // capped at 15 min
    }
}
