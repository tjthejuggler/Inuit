package com.example.inuit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.inuit.data.DebugLog
import com.example.inuit.data.gen.QuestionGenerator.GenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that shields batch generation (personalized + web
 * harvest) — and the scheduled auto-retry after a failed batch — from the
 * app being closed and the screen being turned off:
 *
 *  - FOREGROUND + dataSync type → the process is not cached/killed while a
 *    batch is in flight or a retry is pending, and Doze network
 *    restrictions are lifted.
 *  - PARTIAL wake lock → the CPU keeps running with the screen off; it is
 *    refreshed every [WAKE_REFRESH_MS] so multi-batch refills and long
 *    retry back-offs outlive the 10-minute safety expiry.
 *  - START_STICKY → if the system still kills us, the service restarts,
 *    the process (and its AppGraph) is recreated, and generation resumes
 *    from where the persisted store left off. Batches and answers are
 *    persisted immediately, so no progress is ever lost.
 *
 * Started/stopped automatically by [InuitApp] observing the generator's
 * [com.example.inuit.data.gen.QuestionGenerator.serviceNeeded] flow, which
 * stays true for the WHOLE refill run (batch → harvest → other nets) and
 * through retry back-off waits — not just while the state says Running.
 */
class BatchGenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.i(TAG, "service created — shielding batch generation")
        val graph = (application as InuitApp).graph
        try {
            startInForeground(initialText(graph.generator.state.value))
        } catch (e: Exception) {
            // If we cannot enter the foreground state (OEM / FGS-type quirks),
            // the service is useless AND the unsatisfied startForeground
            // requirement would get the app killed — stop immediately instead.
            DebugLog.e(TAG, "failed to enter foreground — stopping service", e)
            stopSelf()
            return
        }
        acquireWakeLock()

        // Covers the START_STICKY restart path: the process died mid-batch,
        // the system restarted us — kick generation again (no-op if healthy).
        scope.launch { graph.generator.maybeGenerate() }

        // Stand down only when no work AND no pending retry remains. The
        // loop refreshes the wake lock each cycle so waits longer than the
        // lock's safety expiry still keep the CPU awake.
        scope.launch {
            try {
                while (true) {
                    acquireWakeLock()
                    val done = withTimeoutOrNull(WAKE_REFRESH_MS) {
                        graph.generator.serviceNeeded.first { !it }
                    }
                    if (done != null) break // nothing in flight, nothing scheduled
                    // still needed — loop and refresh the wake lock
                }
            } catch (_: Exception) {
                // scope cancelled in onDestroy — fall through
            }
            DebugLog.i(TAG, "generation finished — stopping")
            stopSelf()
        }

        // Mirror progress notes into the notification.
        scope.launch {
            graph.generator.state.collect { st ->
                when (st) {
                    is GenState.Running -> updateNotification(st.note)
                    is GenState.Error -> updateNotification("Batch failed — retrying soon…")
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock() // re-acquire on each (re)start; refreshes the timeout
        return START_STICKY
    }

    override fun onDestroy() {
        DebugLog.i(TAG, "service destroyed")
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ── foreground plumbing ──────────────────────────────────────────────

    private fun initialText(state: GenState): String = when (state) {
        is GenState.Running -> state.note
        is GenState.Error -> "Batch failed — retrying soon…"
        else -> "Generating questions…"
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Batch generation",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while question batches are generated"
                    setShowBadge(false)
                }
            )
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_inuit)
            .setContentTitle("Inuit — building your question batch")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    // ── wake lock ────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "inuit:batchgen").apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS) // safety expiry; refreshed by the wait loop
            }
            DebugLog.i(TAG, "wake lock acquired (screen-off generation protected)")
        } catch (e: Exception) {
            DebugLog.e(TAG, "wake lock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "BatchGenService"
        private const val CHANNEL_ID = "batch_gen"
        private const val NOTIF_ID = 42
        private const val WAKE_REFRESH_MS = 60_000L
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L

        /** Start the service; safe to call repeatedly and from the background
         *  (falls back silently — generation still runs in the app scope). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BatchGenService::class.java))
            } catch (e: Exception) {
                // Android 12+ forbids FGS starts from the background; the
                // generation coroutine keeps running in the app scope anyway.
                DebugLog.w(TAG, "could not start foreground service (background start?) — continuing in-app")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatchGenService::class.java))
        }
    }
}
