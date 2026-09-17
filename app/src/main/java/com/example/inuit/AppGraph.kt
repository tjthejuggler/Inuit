package com.example.inuit

import android.content.Context
import com.example.inuit.data.NetStore
import com.example.inuit.data.QuestionStore
import com.example.inuit.data.ReviewQueue
import com.example.inuit.data.SettingsStore
import com.example.inuit.data.DebugLog
import com.example.inuit.data.TailIntegration
import com.example.inuit.data.gdrive.GDriveClient
import com.example.inuit.data.gdrive.GDriveCredentials
import com.example.inuit.data.gdrive.GDriveFactStore
import com.example.inuit.data.gdrive.GDriveSync
import com.example.inuit.data.gen.AccentsBuilder
import com.example.inuit.data.gen.Harvester
import com.example.inuit.data.gen.LocationProvider
import com.example.inuit.data.gen.PodcastRecommender
import com.example.inuit.data.gen.QuestionGenerator
import com.example.inuit.data.llm.LlmClient
import com.example.inuit.data.llm.LlmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Hand-rolled DI graph — small app, no framework needed. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore = SettingsStore(context)

    /** Persisted "scored in error" disputes awaiting an LLM pass. */
    val reviewQueue = ReviewQueue(context.filesDir)

    val netStore = NetStore(context, appScope)
    val store = QuestionStore(context, appScope, netStore)
    val llm = LlmClient()
    val harvester = Harvester(store, llm, netStore)
    val tail = TailIntegration(context)
    val gdriveFacts = GDriveFactStore(context)
    val accents = AccentsBuilder(LocationProvider(appContext), netStore, store, tail, gdriveFacts)
    val generator = QuestionGenerator(store, settingsStore, netStore, llm, harvester, accents, appScope)
    val podcasts = PodcastRecommender(store, settingsStore, netStore, llm, appScope)

    /** Lazy Drive sync machinery — built on demand from current credentials. */
    private fun buildGDriveSync(creds: GDriveCredentials): GDriveSync =
        GDriveSync(GDriveClient(creds), gdriveFacts, llm)

    /**
     * Runs one Drive sync pass (if credentials are configured): ingest
     * Gemini-chat exports, archive everything else. Returns a human-readable
     * result; null when the source is not configured.
     */
    suspend fun runGDriveSync(): String? {
        val s = settingsStore.current()
        if (!s.gdriveConfigured) return null
        val creds = GDriveCredentials(s.gdriveClientId, s.gdriveClientSecret, s.gdriveRefreshToken)
        val sync = buildGDriveSync(creds)
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        val result = sync.runOnce(creds, cfg, s.disableThinking)
        DebugLog.i("AppGraph", "gdrive sync: $result")
        return result.toString()
    }

    init {
        // Net switch → swap QuestionStore's active state SYNCHRONOUSLY, before
        // NetStore publishes the new activeNet. Every consumer reacting to the
        // switch (ViewModel, generator, podcasts) then sees the right net's
        // data; the store's own async collector would race them.
        // (Callback instead of constructor arg to avoid a circular dependency.)
        netStore.onNetChanged = { store.switchNet(it) }
        // Net deletion → erase that net's question/answer/podcast file.
        netStore.onNetDeleted = { store.deleteNetData(it) }
        // Drive source: one sync pass shortly after process start, with a
        // small retry loop — VPN-on-device DNS (e.g. Tailscale MagicDNS)
        // can be flaky right after boot/unlock and the first attempt fails
        // with "Unable to resolve host". The sync itself dedups, so
        // repeated passes are always safe.
        appScope.launch {
            kotlinx.coroutines.delay(INITIAL_SYNC_DELAY_MS)
            repeat(STARTUP_SYNC_ATTEMPTS) { attempt ->
                try {
                    val result = runGDriveSync()
                    if (result != null) {
                        // A null only means "not configured"; any completed
                        // (even failed-per-doc) pass ends the retry loop.
                        val aborted = "sync aborted" in result
                        if (!aborted) return@launch
                    }
                } catch (_: Exception) { }
                kotlinx.coroutines.delay(STARTUP_SYNC_RETRY_DELAY_MS)
            }
        }
    }

    companion object {
        private const val INITIAL_SYNC_DELAY_MS = 20_000L
        private const val STARTUP_SYNC_ATTEMPTS = 3
        private const val STARTUP_SYNC_RETRY_DELAY_MS = 45_000L
    }
}
