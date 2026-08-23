package com.example.inuit.data

import com.example.inuit.data.llm.Http
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Podcast directory lookups against Apple's public iTunes Search API
 * (free, keyless). The LLM reliably knows *which* show/episode to recommend
 * but hallucinates URLs — this grounds the recommendation in a real RSS
 * [feedUrl][ShowMatch.feedUrl], the one identifier every podcast app can
 * subscribe to, plus the show's Apple Podcasts page when the model produced
 * no link itself.
 */
object PodcastDirectory {

    private const val TAG = "PodcastDir"

    data class ShowMatch(val show: String, val feedUrl: String?, val pageUrl: String?)

    /** A specific episode resolved to its stable public page. */
    data class EpisodeMatch(val show: String, val title: String, val pageUrl: String)

    /** Looks up the show by name; null when nothing plausible comes back. */
    suspend fun lookupShow(showName: String): ShowMatch? = try {
        val term = URLEncoder.encode(showName, "UTF-8")
        val resp = Http.get(
            "https://itunes.apple.com/search?term=$term&media=podcast&limit=5",
            headers = emptyMap(),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000
        )
        if (resp.code in 200..299) parseResults(resp.body, showName) else null
    } catch (e: Exception) {
        DebugLog.w(TAG, "itunes lookup failed: ${e.message}")
        null
    }

    /**
     * Looks up the specific episode (Pocket Casts has no episode deep link,
     * but the episode's stable page still opens the exact episode through
     * the system/browser path); null when nothing plausible comes back.
     */
    suspend fun lookupEpisode(showName: String, episodeTitle: String): EpisodeMatch? = try {
        val term = URLEncoder.encode(episodeTitle, "UTF-8")
        val resp = Http.get(
            "https://itunes.apple.com/search?term=$term&entity=podcastEpisode&limit=25",
            headers = emptyMap(),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000
        )
        if (resp.code in 200..299) parseEpisodeResults(resp.body, showName, episodeTitle) else null
    } catch (e: Exception) {
        DebugLog.w(TAG, "itunes episode lookup failed: ${e.message}")
        null
    }

    /**
     * Returns [rec] with [PodcastRec.feedUrl] (and a page [PodcastRec.url]
     * when missing) filled from the directory; unchanged when already
     * grounded or unresolvable.
     */
    suspend fun resolve(rec: PodcastRec): PodcastRec {
        if (rec.feedUrl != null) return rec
        val query = rec.show.ifBlank { rec.searchQuery }
        if (query.isBlank()) return rec
        // Episode page first (the exact episode via system/browser), then the
        // show lookup to ground the RSS feed for in-app subscribe links.
        val episode = lookupEpisode(query, rec.title)
        val match = lookupShow(episode?.show ?: query)
        DebugLog.i(
            TAG,
            "resolved show='${match?.show}' feed=${match?.feedUrl != null} " +
                "episodePage=${episode?.pageUrl != null}"
        )
        return rec.copy(
            feedUrl = match?.feedUrl,
            url = episode?.pageUrl ?: rec.url ?: match?.pageUrl
        )
    }

    /** Picks the result whose name best overlaps the query; null when none overlaps. */
    internal fun parseResults(body: String, query: String): ShowMatch? {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return null
        var best: ShowMatch? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val name = o.optString("collectionName")
            if (name.isBlank()) continue
            val score = nameOverlap(name, query)
            if (score <= 0 || score <= bestScore) continue
            bestScore = score
            best = ShowMatch(
                show = name,
                feedUrl = o.optString("feedUrl").ifBlank { null },
                pageUrl = o.optString("trackViewUrl").ifBlank { null }
            )
        }
        return best
    }

    /** Count of meaningful words shared by the show name and the query. */
    internal fun nameOverlap(a: String, b: String): Int {
        val words: (String) -> Set<String> = { s ->
            s.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()
        }
        return words(a).intersect(words(b)).size
    }

    /** Picks the episode belonging to the queried show whose title best
     *  overlaps; episodes from other shows are ignored. */
    internal fun parseEpisodeResults(body: String, show: String, episode: String): EpisodeMatch? {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return null
        var best: EpisodeMatch? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val collection = o.optString("collectionName")
            val track = o.optString("trackName")
            val page = o.optString("trackViewUrl").ifBlank { null } ?: continue
            if (collection.isBlank() || track.isBlank()) continue
            if (nameOverlap(collection, show) <= 0) continue // wrong show
            val score = nameOverlap(track, episode)
            if (score <= 0 || score <= bestScore) continue
            bestScore = score
            best = EpisodeMatch(show = collection, title = track, pageUrl = page)
        }
        return best
    }

    /**
     * Strips the scheme from a feed URL for subscribe deep links such as
     * `pktc://subscribe/<host/path>` — Pocket Casts documents that the feed
     * URL must follow WITHOUT its own `http(s)://` prefix. Null when what's
     * left doesn't look like a host.
     */
    internal fun feedPath(feed: String): String? {
        val f = feed.trim()
        val p = when {
            f.startsWith("https://", ignoreCase = true) -> f.substring(8)
            f.startsWith("http://", ignoreCase = true) -> f.substring(7)
            else -> f
        }
        if (p.any { it.isWhitespace() }) return null
        if (!p.substringBefore('/').contains('.')) return null
        return p
    }
}
