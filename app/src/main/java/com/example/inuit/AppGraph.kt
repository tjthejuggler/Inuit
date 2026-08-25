package com.example.inuit

import android.content.Context
import com.example.inuit.data.NetStore
import com.example.inuit.data.QuestionStore
import com.example.inuit.data.SettingsStore
import com.example.inuit.data.TailIntegration
import com.example.inuit.data.gen.AccentsBuilder
import com.example.inuit.data.gen.Harvester
import com.example.inuit.data.gen.LocationProvider
import com.example.inuit.data.gen.PodcastRecommender
import com.example.inuit.data.gen.QuestionGenerator
import com.example.inuit.data.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled DI graph — small app, no framework needed. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore = SettingsStore(context)
    val netStore = NetStore(context, appScope)
    val store = QuestionStore(context, appScope, netStore)
    val llm = LlmClient()
    val harvester = Harvester(store, llm, netStore)
    val tail = TailIntegration(context)
    val accents = AccentsBuilder(LocationProvider(appContext), netStore, store, tail)
    val generator = QuestionGenerator(store, settingsStore, netStore, llm, harvester, accents, appScope)
    val podcasts = PodcastRecommender(store, settingsStore, netStore, llm, appScope)

    init {
        // Net switch → swap QuestionStore's active state SYNCHRONOUSLY, before
        // NetStore publishes the new activeNet. Every consumer reacting to the
        // switch (ViewModel, generator, podcasts) then sees the right net's
        // data; the store's own async collector would race them.
        // (Callback instead of constructor arg to avoid a circular dependency.)
        netStore.onNetChanged = { store.switchNet(it) }
        // Net deletion → erase that net's question/answer/podcast file.
        netStore.onNetDeleted = { store.deleteNetData(it) }
    }
}
