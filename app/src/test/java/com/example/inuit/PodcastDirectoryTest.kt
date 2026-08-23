package com.example.inuit

import com.example.inuit.data.PodcastDirectory
import com.example.inuit.data.PodcastRec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iTunes-directory parsing, feed-path shaping, and rec persistence roundtrip. */
class PodcastDirectoryTest {

    private fun resultsJson(vararg shows: Triple<String, String, String>): String {
        val arr = JSONArray()
        for ((name, feed, page) in shows) {
            arr.put(
                JSONObject()
                    .put("collectionName", name)
                    .put("feedUrl", feed)
                    .put("trackViewUrl", page)
            )
        }
        return JSONObject().put("results", arr).toString()
    }

    @Test
    fun parseResultsPicksTheShowMatchingTheQuery() {
        val body = resultsJson(
            Triple("Stuff You Should Know", "https://feeds.example.com/sysk", "https://podcasts.apple.com/sysk"),
            Triple("In Our Time", "https://www.bbc.co.uk/programmes/b006qykl/episodes.rss", "https://podcasts.apple.com/iot"),
            Triple("The Daily", "https://www.nytimes.com/the-daily/rss", "https://podcasts.apple.com/daily")
        )
        val m = PodcastDirectory.parseResults(body, "In Our Time")
        assertEquals("In Our Time", m?.show)
        assertEquals("https://www.bbc.co.uk/programmes/b006qykl/episodes.rss", m?.feedUrl)
        assertEquals("https://podcasts.apple.com/iot", m?.pageUrl)
    }

    @Test
    fun parseResultsIgnoresUnrelatedShows() {
        val body = resultsJson(
            Triple("The Daily", "https://www.nytimes.com/rss", "https://podcasts.apple.com/daily")
        )
        // No word overlap with the query — subscribing would be wrong.
        assertNull(PodcastDirectory.parseResults(body, "In Our Time"))
    }

    @Test
    fun parseResultsSurvivesEmptyAndBrokenBodies() {
        assertNull(PodcastDirectory.parseResults("{}", "anything"))
        assertNull(PodcastDirectory.parseResults("not json at all", "anything"))
    }

    @Test
    fun feedPathStripsTheSchemeAndValidatesTheHost() {
        assertEquals(
            "feeds.example.com/sysk.rss",
            PodcastDirectory.feedPath("https://feeds.example.com/sysk.rss")
        )
        assertEquals("example.com/feed", PodcastDirectory.feedPath("http://example.com/feed"))
        assertNull(PodcastDirectory.feedPath("not a url"))
        assertNull(PodcastDirectory.feedPath("example.com/feed with spaces"))
    }

    @Test
    fun recJsonRoundtripKeepsTheFeedAndLoadsLegacyRecs() {
        val rec = PodcastRec(
            show = "Show", title = "Ep", reason = "why", searchQuery = "show ep",
            url = "https://podcasts.apple.com/x", feedUrl = "https://f.example/rss"
        )
        val back = PodcastRec.fromJson(rec.toJson())
        assertEquals("https://f.example/rss", back.feedUrl)
        assertEquals("https://podcasts.apple.com/x", back.url)

        // Recs persisted before feeds existed load with a null feed.
        val legacy = JSONObject()
            .put("show", "S").put("title", "T").put("reason", "r").put("q", "q")
            .put("url", "https://podcasts.apple.com/old")
        val legacyRec = PodcastRec.fromJson(legacy)
        assertNull(legacyRec.feedUrl)
        assertEquals("https://podcasts.apple.com/old", legacyRec.url)
    }

    private fun episodeResultsJson(vararg eps: Triple<String, String, String>): String {
        val arr = JSONArray()
        for ((show, title, page) in eps) {
            arr.put(
                JSONObject()
                    .put("collectionName", show)
                    .put("trackName", title)
                    .put("trackViewUrl", page)
            )
        }
        return JSONObject().put("results", arr).toString()
    }

    @Test
    fun parseEpisodeResultsPicksTheEpisodeFromTheRightShow() {
        val body = episodeResultsJson(
            Triple("The Daily", "The Roman Empire Explained", "https://podcasts.apple.com/daily?i=1"),
            Triple("In Our Time", "The Roman Empire", "https://podcasts.apple.com/iot?i=42"),
            Triple("In Our Time", "Something Unrelated", "https://podcasts.apple.com/iot?i=43")
        )
        val m = PodcastDirectory.parseEpisodeResults(
            body, show = "In Our Time", episode = "The Roman Empire"
        )
        assertEquals("In Our Time", m?.show)
        assertEquals("The Roman Empire", m?.title)
        assertEquals("https://podcasts.apple.com/iot?i=42", m?.pageUrl)
    }

    @Test
    fun parseEpisodeResultsIgnoresOtherShowsAndBrokenBodies() {
        val body = episodeResultsJson(
            Triple("The Daily", "The Roman Empire", "https://podcasts.apple.com/daily?i=9")
        )
        assertNull(
            PodcastDirectory.parseEpisodeResults(body, show = "In Our Time", episode = "The Roman Empire")
        )
        assertNull(PodcastDirectory.parseEpisodeResults("{}", show = "x", episode = "y"))
    }
}
