package com.example.inuit

import com.example.inuit.data.DomainStat
import com.example.inuit.data.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Net-aware stats: inside a custom net every domain path starts with the net
 * name, which is redundant on screen (everything shown IS that net). Net mode
 * therefore aggregates AND displays at the SUBTOPIC level with the net prefix
 * stripped — "Notation", not "Juggling > Notation" — for topDomains, growth
 * and the domain tree (the "30 answers, 1 point on the knowledge map" and
 * "why does every category say Juggling >" regressions).
 */
class StatsNetAggregationTest {

    @Test
    fun `topKey strips the net prefix for net-rooted paths only`() {
        assertEquals("Notation", StatsCalculator.topKey("Juggling > Notation > Siteswap", "Juggling"))
        // casing of the stored path is preserved; grouping is case-insensitive
        assertEquals("notation", StatsCalculator.topKey("juggling > notation", "Juggling"))
        // flat or foreign-rooted paths fall back to the first segment
        assertEquals("Juggling", StatsCalculator.topKey("Juggling", "Juggling"))
        assertEquals("History", StatsCalculator.topKey("History > Middle Ages", "Juggling"))
        // no net → legacy first-segment behavior
        assertEquals("Science", StatsCalculator.topKey("Science > Physics > Optics", null))
    }

    @Test
    fun `net mode splits topDomains into prefix-free subtopics`() {
        val stats = listOf(
            DomainStat("Juggling > Notation > Siteswap", 4, 3, 0),
            DomainStat("Juggling > Notation > History", 1, 1, 0),
            DomainStat("Juggling > Patterns", 1, 0, 0),
            DomainStat("Juggling > History > Ancient Art", 3, 2, 0)
        )
        val snap = StatsCalculator.compute(emptyList(), emptyList(), stats, 0, netName = "Juggling")

        assertEquals(3, snap.domainsExplored)
        assertEquals("Juggling", snap.netName)
        val byPath = snap.topDomains.associateBy { it.path }
        assertEquals(setOf("Notation", "Patterns", "History"), byPath.keys)
        assertEquals(5, byPath.getValue("Notation").attempts)
        assertEquals(4, byPath.getValue("Notation").correct)
    }

    @Test
    fun `net mode drops the net root from the domain tree`() {
        val stats = listOf(
            DomainStat("Juggling > Notation > Siteswap", 4, 3, 0),
            DomainStat("Juggling > History", 3, 2, 0)
        )
        val snap = StatsCalculator.compute(emptyList(), emptyList(), stats, 0, netName = "Juggling")

        // the tree starts at the subtopics — no redundant "Juggling" root
        assertEquals(setOf("Notation", "History"), snap.domainTree.map { it.name }.toSet())
        val notation = snap.domainTree.first { it.name == "Notation" }
        assertEquals("Notation", notation.path)
        assertEquals(listOf("Siteswap"), notation.children.map { it.name })
        assertEquals(4, notation.totalAttempts)
    }

    @Test
    fun `legacy aggregation is unchanged without a net`() {
        val stats = listOf(
            DomainStat("Science > Physics > Optics", 3, 3, 0),
            DomainStat("Science > Chemistry", 2, 1, 0),
            DomainStat("History > Middle Ages", 1, 0, 0)
        )
        val snap = StatsCalculator.compute(emptyList(), emptyList(), stats, 0)

        assertNull(snap.netName)
        assertEquals(2, snap.domainsExplored)
        assertEquals(
            setOf("Science", "History"),
            snap.topDomains.map { it.path }.toSet()
        )
        assertEquals(5, snap.topDomains.first { it.path == "Science" }.attempts)
        // tree keeps full paths in All mode
        assertEquals("Science", snap.domainTree.first { it.name == "Science" }.path)
    }
}
