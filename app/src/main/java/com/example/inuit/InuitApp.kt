package com.example.inuit

import android.app.Application
import com.example.inuit.data.DebugLog
import kotlinx.coroutines.launch

class InuitApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(filesDir)
        DebugLog.i("App", "Inuit started")
        graph = AppGraph(this)
        // Kick a top-up in case the queue ran low while the app was closed.
        graph.appScope.launch { graph.generator.maybeGenerate() }
    }
}
