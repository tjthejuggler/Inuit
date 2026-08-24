package com.example.inuit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the crash-on-launch race: a fast serviceNeeded
 * true→false flicker (healthy launch) must never stop BatchGenService
 * immediately — only after a grace delay, so the service is always created
 * and has called startForeground() before any stop can land. An immediate
 * stop left the pending foreground requirement unsatisfied and Android
 * killed the app with ForegroundServiceDidNotStartInTimeException.
 */
class ServiceStopGateTest {

    @Test
    fun `need starts the service immediately`() {
        val gate = ServiceStopGate(graceMs = 5_000L)
        assertEquals(listOf(ServiceStopGate.Action.StartService), gate.onNeeded(true))
    }

    @Test
    fun `stop is always delayed, never immediate`() {
        val gate = ServiceStopGate(graceMs = 5_000L)
        gate.onNeeded(true)
        val actions = gate.onNeeded(false)
        assertEquals(1, actions.size)
        val stop = actions.filterIsInstance<ServiceStopGate.Action.ScheduleStop>().single()
        assertEquals(5_000L, stop.delayMs)
    }

    @Test
    fun `re-need cancels the pending stop and starts again`() {
        val gate = ServiceStopGate()
        gate.onNeeded(true)
        gate.onNeeded(false)
        assertEquals(
            listOf(ServiceStopGate.Action.CancelStop, ServiceStopGate.Action.StartService),
            gate.onNeeded(true)
        )
    }

    @Test
    fun `repeated idle does not stack stops`() {
        val gate = ServiceStopGate()
        gate.onNeeded(true)
        assertEquals(1, gate.onNeeded(false).size)
        assertEquals(emptyList<ServiceStopGate.Action>(), gate.onNeeded(false))
        assertEquals(emptyList<ServiceStopGate.Action>(), gate.onNeeded(false))
    }

    @Test
    fun `flicker sequence never emits an immediate stop`() {
        val gate = ServiceStopGate()
        val all = mutableListOf<ServiceStopGate.Action>()
        for (needed in listOf(true, false, true, false, false, true, false)) {
            all += gate.onNeeded(needed)
        }
        // Every stop is scheduled with a delay; none is ever immediate.
        val stops = all.filterIsInstance<ServiceStopGate.Action.ScheduleStop>()
        assertEquals(3, stops.size)
        assertTrue(stops.all { it.delayMs > 0 })
        assertEquals(3, all.count { it == ServiceStopGate.Action.StartService })
        assertEquals(2, all.count { it == ServiceStopGate.Action.CancelStop })
    }
}
