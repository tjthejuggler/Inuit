package com.example.inuit

import com.example.inuit.data.gen.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Podcast recommendation parsing: fences, missing fields, garbage. */
class PodcastParseTest {

    @Test
    fun `parses a full recommendation`() {
        val rec = Prompts.parsePodcastRec(
            """{"show":"In Our Time","title":"The Speed of Light","reason":"Physics is your weakest realm",""" +
                """"search_query":"In Our Time The Speed of Light","url":"https://podcasts.apple.com/x"}"""
        )
        assertNotNull(rec)
        rec!!
        assertEquals("In Our Time", rec.show)
        assertEquals("The Speed of Light", rec.title)
        assertEquals("In Our Time The Speed of Light", rec.searchQuery)
        assertEquals("https://podcasts.apple.com/x", rec.url)
    }

    @Test
    fun `strips markdown fences and defaults missing fields`() {
        val rec = Prompts.parsePodcastRec(
            "```json\n{\"show\":\"Hardcore History\",\"title\":\"Blueprint for Armageddon I\",\"reason\":\"\"}\n```"
        )
        assertNotNull(rec)
        rec!!
        assertEquals("Hardcore History", rec.show)
        assertEquals("Blueprint for Armageddon I", rec.title)
        assertEquals("Hardcore History Blueprint for Armageddon I", rec.searchQuery)
        assertNull(rec.url)
        assertEquals("Targets one of your weakest areas.", rec.reason)
    }

    @Test
    fun `blank show or title is rejected`() {
        assertNull(Prompts.parsePodcastRec("""{"show":"","title":"X"}"""))
        assertNull(Prompts.parsePodcastRec("""{"show":"X","title":""}"""))
        assertNull(Prompts.parsePodcastRec("not json at all"))
    }

    @Test
    fun `invalid urls are dropped but the rec is kept`() {
        val garbage = Prompts.parsePodcastRec("""{"show":"S","title":"T","url":"not a url"}""")
        assertNotNull(garbage)
        assertNull(garbage!!.url)
        val ftp = Prompts.parsePodcastRec("""{"show":"S","title":"T","url":"ftp://example.com/x"}""")
        assertNotNull(ftp)
        assertNull(ftp!!.url)
    }
}
