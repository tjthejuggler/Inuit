package com.example.inuit

import android.app.Application
import com.example.inuit.data.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        //
        // Stops go through ServiceStopGate: a fast serviceNeeded true→false
        // flicker (healthy launch — every queue already above threshold) must
        // never reach stopService() before the system has created the service,
        // or the pending startForeground() requirement goes unmet and Android
        // kills the app ~10 s later with ForegroundServiceDidNotStartInTime-
        // Exception (a repeatable crash-on-launch loop; seen on Android 16).
        graph.appScope.launch {
            val gate = ServiceStopGate()
            var stopJob: Job? = null
            graph.generator.serviceNeeded.collect { needed ->
                for (action in gate.onNeeded(needed)) when (action) {
                    ServiceStopGate.Action.StartService ->
                        BatchGenService.start(this@InuitApp)
                    ServiceStopGate.Action.CancelStop ->
                        stopJob?.cancel()
                    is ServiceStopGate.Action.ScheduleStop ->
                        stopJob = launch {
                            delay(action.delayMs)
                            BatchGenService.stop(this@InuitApp)
                        }
                }
            }
        }
    }
}
