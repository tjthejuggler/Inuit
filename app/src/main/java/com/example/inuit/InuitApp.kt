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
        // Shield in-flight batches AND pending auto-retries with a foreground
        // service: closing the app or turning the screen off must never kill
        // a running batch, and a failed batch's scheduled comeback must
        // survive the user leaving during the back-off wait.
        graph.appScope.launch {
            graph.generator.serviceNeeded.collect { needed ->
                if (needed) BatchGenService.start(this@InuitApp)
                else BatchGenService.stop(this@InuitApp)
            }
        }
    }
}
