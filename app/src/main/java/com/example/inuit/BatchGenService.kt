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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that shields batch generation (personalized + web
 * harvest) from the app being closed and the screen being turned off:
 *
 *  - FOREGROUND + dataSync type → the process is not cached/killed while a
 *    batch is in flight, and Doze network restrictions are lifted.
 *  - PARTIAL wake lock → the CPU keeps running with the screen off.
 *  - START_STICKY → if the system still kills us, the service restarts,
 *    the process (and its AppGraph) is recreated, and generation resumes
 *    from where the persisted store left off. Batches and answers are
 *    persisted immediately, so no progress is ever lost.
 *
 * Started/stopped automatically by [InuitApp] observing the generator state.
 */
class BatchGenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.i(TAG, "service created — shielding batch generation")
        startInForeground()
        acquireWakeLock()

        val graph = (application as InuitApp).graph

        // Covers the START_STICKY restart path: the process died mid-batch,
        // the system restarted us — kick generation again (no-op if healthy).
        scope.launch { graph.generator.maybeGenerate() }

        scope.launch {
            try {
                while (true) {
                    // Wait out the current run (or a startup grace period).
                    val quiet = withTimeoutOrNull(START_GRACE_MS) {
                        graph.generator.state.first { it !is GenState.Running }
                    }
                    // Still Running after the grace window → a batch is in
                    // flight; keep waiting indefinitely.
                    if (quiet == null) {
                        graph.generator.state.first { it !is GenState.Running }
                    }
                    // Quiet now — but generation may chain straight into the
                    // next phase (batch → stockpile harvest). Grace period.
                    delay(QUIET_GRACE_MS)
                    if (graph.generator.state.value !is GenState.Running) break
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
                if (st is GenState.Running) updateNotification(st.note)
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

    private fun startInForeground() {
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
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Generating questions…"), type)
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
                acquire(WAKE_TIMEOUT_MS) // safety expiry; refreshed on restart
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
        private const val START_GRACE_MS = 30_000L
        private const val QUIET_GRACE_MS = 5_000L
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
