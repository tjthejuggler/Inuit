package com.example.inuit

import android.content.Context
import com.example.inuit.data.QuestionStore
import com.example.inuit.data.SettingsStore
import com.example.inuit.data.gen.Harvester
import com.example.inuit.data.gen.QuestionGenerator
import com.example.inuit.data.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled DI graph — small app, no framework needed. */
class AppGraph(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore = SettingsStore(context)
    val store = QuestionStore(context, appScope)
    val llm = LlmClient()
    val harvester = Harvester(store, llm)
    val generator = QuestionGenerator(store, settingsStore, llm, harvester, appScope)
}
