package com.example.inuit.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** A podcast app installed on the device (or the system-default pseudo entry). */
data class PodcastAppInfo(val label: String, val packageName: String) {
    val isSystemDefault: Boolean get() = packageName.isEmpty()
}

/**
 * Discovers installed podcast apps and opens recommended episodes.
 *
 * There is no universal podcast deep link, but there IS a universal podcast
 * identifier: the show's RSS feed URL. Every podcast app can subscribe from
 * a feed, and some document feed-based schemes (Pocket Casts:
 * `pktc://subscribe/<feed>`, AntennaPod: `antennapod-subscribe://<feed>`).
 * [PodcastDirectory] grounds each recommendation in a real feed URL, so with
 * a chosen app the chain is:
 *
 *  1. the app's DOCUMENTED feed-subscribe link — lands on the exact show,
 *  2. the episode's stable page URL restricted to the app,
 *  3. the app's documented in-app search link,
 *  4. the app itself cold-launched with the search query on the clipboard.
 *
 * Undocumented scheme guesses are deliberately avoided: apps like Pocket
 * Casts register catch-all handlers for their scheme and surface an error
 * for URIs they don't understand. With no app chosen ("System default") the
 * episode URL resolves through Android's system resolution (default handler
 * or chooser), falling back to a web search.
 */
object PodcastApps {

    /** Pseudo entry meaning "let Android resolve the handler". */
    val SYSTEM_DEFAULT = PodcastAppInfo("System default", "")

    /** Podcast-specific URL schemes used to discover installed apps.
     *  https probes are deliberately excluded — they would list every browser. */
    private val PROBES = listOf(
        "itms-podcasts://probe",          // iOS-style scheme many apps register
        "antennapod-subscribe://probe",   // AntennaPod
        "pktc://probe",                   // Pocket Casts
        "podcastaddict://probe"           // Podcast Addict
    )

    /** Documented feed-subscribe prefixes; the feed URL follows WITHOUT its
     *  own scheme (per Pocket Casts' URL-scheme documentation). */
    private val SUBSCRIBE_LINKS: Map<String, String> = mapOf(
        "au.com.shiftyjelly.pocketcasts" to "pktc://subscribe/",
        "de.danoeh.antennapod" to "antennapod-subscribe://"
    )

    /** Documented in-app search deep links per known package. */
    private val SEARCH_LINKS: Map<String, (String) -> Uri> = mapOf(
        "com.spotify.music" to { q -> Uri.parse("spotify:search:${Uri.encode(q)}") },
        "com.google.android.apps.youtube.music" to { q ->
            Uri.parse("https://music.youtube.com/search")
                .buildUpon().appendQueryParameter("q", q).build()
        }
    )

    /** Installed podcast apps, by package; empty list when none declare podcast schemes. */
    fun find(context: Context): List<PodcastAppInfo> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>() // package -> label
        for (probe in PROBES) {
            val resolved = try {
                pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(probe)), 0)
            } catch (e: Exception) {
                continue
            }
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg in found) continue
                found[pkg] = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull() ?: pkg
            }
        }
        return found.map { (pkg, label) -> PodcastAppInfo(label, pkg) }
    }

    /** Opens the recommended episode — see the class doc for the fallback chain. */
    fun open(context: Context, rec: PodcastRec, pkg: String?) {
        val url = rec.url?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val feedPath = rec.feedUrl?.let { PodcastDirectory.feedPath(it) }

        if (!pkg.isNullOrBlank()) {
            // The chosen app must open — never the browser.
            // 1. Documented feed-subscribe link: the exact show, in the app.
            //    It lands on the SHOW (not the episode), so stage the episode
            //    title on the clipboard — Android cannot paste into another
            //    app's UI, but one paste in the app's search jumps straight
            //    to the episode.
            val subscribe = SUBSCRIBE_LINKS[pkg]
            if (subscribe != null && feedPath != null) {
                copyToClipboard(
                    context,
                    rec.title,
                    "Episode title copied — paste it in search to jump to it"
                )
                if (tryLaunch(context, Uri.parse(subscribe + feedPath), pkg)) return
            }
            // 2. The episode page, if the app registers as its handler.
            if (url != null && tryLaunch(context, url, pkg)) return
            // 3. Documented in-app search deep link.
            val searchLink = SEARCH_LINKS[pkg]
            if (searchLink != null && tryLaunch(context, searchLink(rec.searchQuery), pkg)) return
            // 4. Cold-launch the app with the query on the clipboard.
            copyToClipboard(
                context,
                rec.searchQuery,
                "Search query copied — paste it into your podcast app's search"
            )
            if (launchApp(context, pkg)) return
            // App uninstalled since selection — system resolution as last resort.
            if (url != null && tryLaunch(context, url, null)) return
            runCatching { launch(context, webSearch(rec), null) }
            return
        }

        if (url != null && tryLaunch(context, url, null)) return
        runCatching { launch(context, webSearch(rec), null) }
    }

    private fun webSearch(rec: PodcastRec): Uri =
        Uri.parse("https://www.google.com/search")
            .buildUpon()
            .appendQueryParameter("q", "podcast ${rec.searchQuery}")
            .build()

    private fun launch(context: Context, uri: Uri, pkg: String?) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        context.startActivity(intent)
    }

    private fun tryLaunch(context: Context, uri: Uri, pkg: String?): Boolean =
        try {
            launch(context, uri, pkg)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    /** Cold-launches the podcast app itself; false when it is gone/unlaunchable. */
    private fun launchApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyToClipboard(context: Context, text: String, hint: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Podcast", text))
            Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }
}
