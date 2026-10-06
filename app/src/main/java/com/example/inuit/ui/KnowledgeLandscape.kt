package com.example.inuit.ui

import com.example.inuit.data.StatsCalculator
import com.example.inuit.data.gen.RealmTaxonomy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure model behind the interactive knowledge map: the full realm taxonomy
 * merged with the user's session-frozen stats (blind-training invariant:
 * only aggregates that predate the current session are ever shown).
 *
 * Two layers:
 *  1. [buildLandscape] merges taxonomy + stats into sections → realms →
 *     territories (charted and uncharted alike).
 *  2. [buildMapModel] lays those out as a deterministic "knowledge galaxy":
 *     sections on a phyllotaxis spiral, realms clustered around their
 *     section, territories orbiting their realm.
 */

/** One territory (taxonomy subrealm, e.g. "Optics") with its frozen stats. */
internal data class LandscapeSubgroup(
    val name: String,
    val path: String,
    val attempts: Int,
    val correct: Int
) {
    val charted: Boolean get() = attempts > 0
    val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts
}

/** One realm (taxonomy key, e.g. "Science > Physics") with its territories. */
internal data class LandscapeRealm(
    val name: String,
    val path: String,
    val section: String,
    val attempts: Int,
    val correct: Int,
    val subgroups: List<LandscapeSubgroup>
) {
    val charted: Boolean get() = attempts > 0
    val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts
    val chartedSubgroups: Int get() = subgroups.count { it.charted }
}

/** A group of realms sharing a top-level segment (e.g. "Science"). */
internal data class LandscapeSection(
    val name: String,
    val realms: List<LandscapeRealm>
) {
    val attempts: Int get() = realms.sumOf { it.attempts }
    val correct: Int get() = realms.sumOf { it.correct }
    val chartedRealms: Int get() = realms.count { it.charted }
}

internal enum class LandscapeFilter(val label: String) {
    ALL("All"),
    CHARTED("Charted"),
    UNCHARTED("Uncharted")
}

/**
 * Merges the full [RealmTaxonomy] with the session-frozen stats tree:
 * every realm and territory appears — with proficiency where the user has
 * answers, "uncharted" where they do not.
 *
 * Stats paths outside the static taxonomy (e.g. LLM-explored paths) are
 * seamlessly integrated into their matching top-level section if one
 * exists (e.g. "Science > Astrobiology" merges into "Science"), or given
 * their own top-level section if completely novel, rather than being
 * lumped into an artificial "Frontiers" cluster.
 *
 * Custom nets: the all-knowledge taxonomy is out of scope — the net's own
 * subtopics (prefix already stripped by the snapshot) ARE the landscape,
 * charted as the realms of a single section named after the net. A subtopic
 * that happens to share a taxonomy realm's name ("History") must stay in
 * the net, never leak into the generic taxonomy.
 */
internal fun buildLandscape(stats: StatsCalculator.Snapshot): List<LandscapeSection> {
    // 1. flatten the frozen tree to exact-path counters [attempts, correct]
    val flat = HashMap<String, IntArray>()
    fun walk(nodes: List<StatsCalculator.DomainNode>) {
        for (n in nodes) {
            if (n.attempts > 0 || n.correct > 0) {
                flat.getOrPut(n.path) { IntArray(2) }.let { it[0] += n.attempts; it[1] += n.correct }
            }
            walk(n.children)
        }
    }
    walk(stats.domainTree)

    // subtree aggregate: the node itself plus everything deeper
    fun agg(prefix: String): IntArray {
        val out = IntArray(2)
        for ((p, v) in flat) {
            if (p == prefix || p.startsWith("$prefix > ")) {
                out[0] += v[0]; out[1] += v[1]
            }
        }
        return out
    }

    // Net mode: the net's subtopics are the whole landscape — one section
    // named after the net, one realm per subtopic, territories beneath.
    val netSectionName = stats.netName?.trim()?.takeIf { it.isNotEmpty() }
    if (netSectionName != null) {
        val realms = flat.keys.map { it.substringBefore(" > ") }.distinct().sorted()
            .mapNotNull { top ->
                val paths = flat.keys.filter { it.substringBefore(" > ") == top }.sorted()
                if (paths.isEmpty()) return@mapNotNull null
                val topAgg = agg(top)
                val subgroups = paths.map { p ->
                    val name = p.split(" > ").drop(1).joinToString(" > ").ifBlank { p }
                    val v = flat[p] ?: IntArray(2)
                    LandscapeSubgroup(name, p, v[0], v[1])
                }
                LandscapeRealm(top, top, netSectionName, topAgg[0], topAgg[1], subgroups)
            }
        return listOf(
            LandscapeSection(
                name = netSectionName,
                realms = realms.sortedWith(
                    compareByDescending<LandscapeRealm> { it.attempts }.thenBy { it.name }
                )
            )
        )
    }

    // 2. taxonomy sections: top-level segment → realms (taxonomy keys)
    val sections = LinkedHashMap<String, MutableList<LandscapeRealm>>()
    val knownRealmPaths = HashSet<String>()
    for ((key, subs) in RealmTaxonomy.REALMS) {
        val top = key.substringBefore(" > ")
        knownRealmPaths.add(key)
        val realmAgg = agg(key)
        val subgroups = subs.map { s ->
            val p = "$key > $s"
            val a = agg(p)
            LandscapeSubgroup(s, p, a[0], a[1])
        }
        sections.getOrPut(top) { mutableListOf() }.add(
            LandscapeRealm(
                name = key.substringAfterLast(" > "),
                path = key,
                section = top,
                attempts = realmAgg[0],
                correct = realmAgg[1],
                subgroups = subgroups
            )
        )
    }

    // 3. stats paths not present in the static taxonomy:
    // Break up frontiers: if a path's top-level section matches an existing section,
    // merge the realm into that section; otherwise create a new top-level section.
    // Determine the realm path for each non-taxonomy path:
    // e.g. "Science > Astrobiology > Extremo" -> realm path "Science > Astrobiology"
    // e.g. "Frontierland > Sub" -> realm path "Frontierland" or "Frontierland > Sub"
    // For consistency with taxonomy keys, if path has 2+ segments and top is multi-segment in taxonomy,
    // or if path has >= 2 segments, the realm is the first 2 segments if top has sub-realms in taxonomy,
    // or the top segment if it's a standalone realm.
    // In Inuit's taxonomy:
    // Sections like "Science" have keys like "Science > Physics", "Science > Chemistry".
    // Other sections like "Mathematics" have key "Mathematics" and subrealms ("Geometry", etc.).
    // Let's identify the realm path for any path:
    // If top is an existing section where all taxonomy keys are multi-segment (e.g. "Science"):
    //   realmPath is segs.take(2).joinToString(" > ")
    // Else (single-segment taxonomy section like "Mathematics" or unknown section):
    //   If segs.size >= 2 and segs[0] in sections: realmPath is segs[0] (or if segs[0] has keys with > 1 seg)
    // Let's be precise:
    // Does the section have multi-segment keys in RealmTaxonomy?
    // Check RealmTaxonomy.REALMS keys starting with "$top > "
    val multiSegmentTops = RealmTaxonomy.REALMS.keys
        .filter { it.contains(" > ") }
        .map { it.substringBefore(" > ") }
        .toSet()

    // Find all paths in `flat` whose corresponding realm is not already in knownRealmPaths
    // Group remaining paths by realm path:
    val extraRealmPaths = LinkedHashMap<String, MutableList<String>>() // realmPath -> all paths under it
    for (path in flat.keys) {
        val segs = path.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) continue
        val top = segs[0]
        val realmPath = if (top in multiSegmentTops) {
            if (segs.size >= 2) "${segs[0]} > ${segs[1]}" else segs[0]
        } else {
            segs[0]
        }
        if (realmPath !in knownRealmPaths) {
            extraRealmPaths.getOrPut(realmPath) { mutableListOf() }.add(path)
        }
    }

    for ((realmPath, paths) in extraRealmPaths) {
        val top = realmPath.substringBefore(" > ")
        val realmName = realmPath.substringAfterLast(" > ")
        val realmAgg = agg(realmPath)
        val subgroups = paths.filter { it != realmPath }.sorted().map { p ->
            val name = p.removePrefix(realmPath).removePrefix(" > ").ifBlank { p }
            val v = flat[p] ?: IntArray(2)
            LandscapeSubgroup(name, p, v[0], v[1])
        }
        sections.getOrPut(top) { mutableListOf() }.add(
            LandscapeRealm(
                name = realmName,
                path = realmPath,
                section = top,
                attempts = realmAgg[0],
                correct = realmAgg[1],
                subgroups = subgroups
            )
        )
    }

    // charted sections first (stable — taxonomy order survives among ties),
    // charted realms first within each section.
    return sections.entries
        .map { (name, realms) ->
            LandscapeSection(
                name = name,
                realms = realms.sortedWith(
                    compareByDescending<LandscapeRealm> { it.attempts }.thenBy { it.name }
                )
            )
        }
        .sortedByDescending { it.attempts }
}

/** Applies the filter (search-free variant used by the map). */
internal fun filterLandscape(
    sections: List<LandscapeSection>,
    query: String,
    filter: LandscapeFilter
): List<LandscapeSection> {
    val q = query.trim().lowercase()
    return sections.mapNotNull { s ->
        if (filter == LandscapeFilter.CHARTED && s.attempts == 0) return@mapNotNull null
        if (filter == LandscapeFilter.UNCHARTED && s.attempts > 0) return@mapNotNull null
        val realms = s.realms.filter { r ->
            val passFilter = when (filter) {
                LandscapeFilter.CHARTED -> r.attempts > 0
                LandscapeFilter.UNCHARTED -> r.attempts == 0
                LandscapeFilter.ALL -> true
            }
            val passQuery = q.isEmpty() ||
                r.name.lowercase().contains(q) ||
                s.name.lowercase().contains(q) ||
                r.subgroups.any { it.name.lowercase().contains(q) }
            passFilter && passQuery
        }
        if (realms.isEmpty()) null else s.copy(realms = realms)
    }
}

// ── spatial layout: the knowledge galaxy ───────────────────────────────────

internal enum class MapNodeKind { SECTION, REALM, TERRITORY }

/**
 * One drawable node. World coordinates are arbitrary units; the screen
 * maps them via `screen = world * scale + offset`.
 */
internal data class MapNode(
    val kind: MapNodeKind,
    val name: String,
    val path: String,
    val x: Float,
    val y: Float,
    val radius: Float,
    val attempts: Int,
    val correct: Int,
    /** Parent node path (section for realms, realm for territories). */
    val parent: String?,
    /** Index into the map's tint palette — one color per section. */
    val tintIndex: Int
) {
    val charted: Boolean get() = attempts > 0
    val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts
}

internal data class MapModel(
    val nodes: List<MapNode>,
    val byPath: Map<String, MapNode>,
    val sections: Map<String, LandscapeSection>,
    val realms: Map<String, LandscapeRealm>,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
)

internal fun sectionNodePath(name: String): String = "section:$name"

/**
 * Deterministic phyllotaxis layout: sections spiral outward on a golden-
 * angle sunflower (even spacing, no overlap, no randomness), realms form a
 * small sunflower cluster around their section, territories orbit their
 * realm in a ring. Charters grow slightly with answer volume.
 */
internal fun buildMapModel(sections: List<LandscapeSection>): MapModel {
    val nodes = mutableListOf<MapNode>()
    val goldenAngle = 2.399963f
    val sectionSpacing = 340f

    sections.forEachIndexed { si, section ->
        val secPath = sectionNodePath(section.name)
        val theta = si * goldenAngle
        val rho = sectionSpacing * sqrt(si + 0.8f)
        val scx = rho * cos(theta)
        val scy = rho * sin(theta)
        nodes += MapNode(
            MapNodeKind.SECTION, section.name, secPath, scx, scy, 30f,
            section.attempts, section.correct, null, si
        )
        section.realms.forEachIndexed { ri, realm ->
            val phi = ri * goldenAngle + 0.7f
            val r = 46f * sqrt(ri + 0.75f) + 58f
            val rx = scx + r * cos(phi)
            val ry = scy + r * sin(phi)
            val radius = if (realm.charted) 15f + min(10f, realm.attempts * 0.35f) else 11f
            nodes += MapNode(
                MapNodeKind.REALM, realm.name, realm.path, rx, ry, radius,
                realm.attempts, realm.correct, secPath, si
            )
            val tCount = realm.subgroups.size
            realm.subgroups.forEachIndexed { ti, sub ->
                val a = (2f * PI.toFloat() * ti / tCount) + phi
                val tr = radius + 15f
                nodes += MapNode(
                    MapNodeKind.TERRITORY, sub.name, sub.path,
                    rx + tr * cos(a), ry + tr * sin(a),
                    if (sub.charted) 6f else 4.2f,
                    sub.attempts, sub.correct, realm.path, si
                )
            }
        }
    }

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    nodes.forEach { n ->
        minX = min(minX, n.x - n.radius); maxX = max(maxX, n.x + n.radius)
        minY = min(minY, n.y - n.radius); maxY = max(maxY, n.y + n.radius)
    }

    return MapModel(
        nodes = nodes,
        byPath = nodes.associateBy { it.path },
        sections = sections.associateBy { sectionNodePath(it.name) },
        realms = buildMap { for (s in sections) for (r in s.realms) put(r.path, r) },
        minX = minX, minY = minY, maxX = maxX, maxY = maxY
    )
}
