package com.example.inuit

/**
 * Pure decision logic for the [BatchGenService] start/stop lifecycle — the
 * guard against the launch-crash race (wired up in [InuitApp]):
 *
 * When a refill starts, `serviceNeeded` flips true and the service is started
 * via `startForegroundService()`. On a healthy launch (every net's queue
 * above the threshold, or the LLM not configured) the run can finish in
 * ~100 ms, flipping `serviceNeeded` back to false almost immediately. If
 * that stop reaches `stopService()` BEFORE the system has created the
 * service, the pending "must call startForeground()" requirement is left
 * unsatisfied and Android kills the app ~10 s later with
 * `ForegroundServiceDidNotStartInTimeException` — a repeatable
 * crash-on-launch loop (confirmed on Android 16).
 *
 * The gate therefore NEVER stops the service immediately: a stop is only
 * scheduled after [graceMs], by which time the service has long been created
 * and entered the foreground (and usually already stopped itself — the
 * external stop is just a safety net). A later re-need cancels the pending
 * stop and starts the service again.
 *
 * Pure JVM — unit tested in `ServiceStopGateTest`.
 */
class ServiceStopGate(private val graceMs: Long = DEFAULT_GRACE_MS) {

    sealed interface Action {
        /** Start the foreground service now (idempotent while it runs). */
        data object StartService : Action

        /** Cancel a previously scheduled stop — a refill needs the service again. */
        data object CancelStop : Action

        /** Stop the service, but only after [delayMs] — never immediately. */
        data class ScheduleStop(val delayMs: Long) : Action
    }

    private var stopScheduled = false

    /** Feed one `serviceNeeded` value; returns the actions to perform, in order. */
    fun onNeeded(needed: Boolean): List<Action> {
        if (needed) {
            val out = ArrayList<Action>(2)
            if (stopScheduled) {
                stopScheduled = false
                out.add(Action.CancelStop)
            }
            // Always (re)start: startForegroundService on an already-running
            // service is a cheap onStartCommand delivery, and starting again
            // is safe even if the collector somehow missed a transition.
            out.add(Action.StartService)
            return out
        }
        if (stopScheduled) return emptyList() // stop already pending — keep its deadline
        stopScheduled = true
        return listOf(Action.ScheduleStop(graceMs))
    }

    companion object {
        /**
         * Comfortably longer than service creation takes (well under a second
         * even on a cold start), so a scheduled stop always lands on an
         * existing, foreground service — and far below the ~10 s window
         * Android gives the service to call startForeground().
         */
        const val DEFAULT_GRACE_MS = 5_000L
    }
}
