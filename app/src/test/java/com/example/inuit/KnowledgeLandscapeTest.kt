package com.example.inuit

import com.example.inuit.data.StatsCalculator
import com.example.inuit.ui.LandscapeFilter
import com.example.inuit.ui.MapNodeKind
import com.example.inuit.ui.buildLandscape
import com.example.inuit.ui.buildMapModel
import com.example.inuit.ui.filterLandscape
import com.example.inuit.ui.sectionNodePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The knowledge-landscape merge: taxonomy + frozen stats + filters. */
class KnowledgeLandscapeTest {

    private fun snapshot(tree: List<StatsCalculator.DomainNode>): StatsCalculator.Snapshot =
        StatsCalculator.Snapshot(
            totalAnswers = 7,
            totalCorrect = 5,
            accuracy = 5f / 7f,
            dayStreak = 1,
            domainsExplored = 2,
            distinctDomains = 3,
            queueSize = 0,
            byDay = emptyList(),
            accuracyTrend = emptyList(),
            domainTree = tree,
            topDomains = emptyList(),
            weakest = emptyList(),
            strongest = emptyList(),
            byDifficulty = emptyList(),
            byType = emptyList(),
            growth = emptyList()
        )

    private fun node(
        name: String,
        path: String,
        attempts: Int,
        correct: Int,
        children: List<StatsCalculator.DomainNode> = emptyList()
    ) = StatsCalculator.DomainNode(name, path, attempts, correct, children)

    @Test
    fun `taxonomy realms merge frozen stats and keep uncharted land`() {
        val tree = listOf(
            node("Science", "Science", 0, 0, listOf(
                node("Physics", "Science > Physics", 2, 1, listOf(
                    node("Optics", "Science > Physics > Optics", 3, 3)
                )),
                node("Chemistry", "Science > Chemistry", 1, 0)
            )),
            node("Frontierland", "Frontierland", 1, 1)
        )
        val sections = buildLandscape(snapshot(tree))

        val science = sections.first { it.name == "Science" }
        val physics = science.realms.first { it.name == "Physics" }
        // realm aggregate includes its own answers plus its territories
        assertEquals(5, physics.attempts)
        assertEquals(4, physics.correct)
        val optics = physics.subgroups.first { it.name == "Optics" }
        assertEquals(3, optics.attempts)
        assertTrue(optics.charted)
        // untouched taxonomy territories stay visible as uncharted
        assertTrue(physics.subgroups.any { !it.charted })

        val chemistry = science.realms.first { it.name == "Chemistry" }
        assertEquals(1, chemistry.attempts)
        // charted realms sort first inside a section
        assertEquals("Physics", science.realms.first().name)

        // taxonomy land with no answers at all remains on the map
        val math = sections.first { it.name == "Mathematics" }
        assertTrue(math.realms.all { !it.charted })

        // stats outside the taxonomy land in the Frontiers section
        val frontiers = sections.first { it.name == "Frontiers" }
        assertEquals(1, frontiers.attempts)
    }

    @Test
    fun `charted sections sort before untouched taxonomy order`() {
        val tree = listOf(
            node("Mathematics", "Mathematics", 0, 0, listOf(
                node("Geometry", "Mathematics > Geometry", 4, 2)
            ))
        )
        val sections = buildLandscape(snapshot(tree))
        assertEquals("Mathematics", sections.first().name)
        assertTrue(sections.first().attempts > 0)
        assertTrue(sections.last().attempts == 0)
    }

    @Test
    fun `filters and search narrow the landscape`() {
        val tree = listOf(
            node("Science", "Science", 0, 0, listOf(
                node("Physics", "Science > Physics", 2, 1, listOf(
                    node("Optics", "Science > Physics > Optics", 3, 3)
                ))
            )),
            node("Mathematics", "Mathematics", 0, 0, listOf(
                node("Geometry", "Mathematics > Geometry", 4, 2)
            ))
        )
        val sections = buildLandscape(snapshot(tree))

        val charted = filterLandscape(sections, "", LandscapeFilter.CHARTED)
        assertTrue(charted.all { it.attempts > 0 })
        assertTrue(charted.none { it.name == "History" })

        val uncharted = filterLandscape(sections, "", LandscapeFilter.UNCHARTED)
        assertTrue(uncharted.all { it.attempts == 0 })
        assertTrue(uncharted.any { it.name == "History" })

        val hit = filterLandscape(sections, "optic", LandscapeFilter.ALL)
        // matches the territories "Optics" (Science) and "Glass & Optics"
        // (Matter & Light) — charted sections sort first
        assertEquals(setOf("Science", "Matter & Light"), hit.map { it.name }.toSet())
        assertEquals("Science", hit.first().name)
        assertEquals(listOf("Physics"), hit.first().realms.map { it.name })

        assertTrue(filterLandscape(sections, "zzzzz", LandscapeFilter.ALL).isEmpty())
    }

    @Test
    fun `map model places every realm and territory deterministically`() {
        val tree = listOf(
            node("Science", "Science", 0, 0, listOf(
                node("Physics", "Science > Physics", 2, 1, listOf(
                    node("Optics", "Science > Physics > Optics", 3, 3)
                ))
            ))
        )
        val sections = buildLandscape(snapshot(tree))

        // deterministic layout — same input, identical node list
        val m1 = buildMapModel(sections)
        val m2 = buildMapModel(sections)
        assertEquals(m1.nodes, m2.nodes)

        // every realm, territory and section is on the map
        assertTrue(m1.realms.containsKey("Science > Physics"))
        assertTrue(m1.byPath.containsKey("Science > Physics > Optics"))
        assertTrue(m1.byPath.containsKey(sectionNodePath("Science")))
        assertTrue(m1.byPath.values.any { it.kind == MapNodeKind.SECTION && it.name == "Science" })

        // charted realm node carries its frozen aggregate
        val physics = m1.byPath.getValue("Science > Physics")
        assertEquals(MapNodeKind.REALM, physics.kind)
        assertEquals(5, physics.attempts)

        // territory nodes attach to their realm
        val optics = m1.byPath.getValue("Science > Physics > Optics")
        assertEquals("Science > Physics", optics.parent)
        assertEquals(MapNodeKind.TERRITORY, optics.kind)

        // bounds contain every node; positions are finite
        m1.nodes.forEach { n ->
            assertTrue(n.x.isFinite() && n.y.isFinite())
            assertTrue(n.x >= m1.minX && n.x <= m1.maxX)
            assertTrue(n.y >= m1.minY && n.y <= m1.maxY)
        }
    }
}
