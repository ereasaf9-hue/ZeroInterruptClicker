package com.zerointerrupt.clicker.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuSessionGenerationTest {
    @Test
    fun issuingANewSessionMakesLateCallbacksFromTheOldSessionStale() {
        val generations = ShizukuSessionGeneration()

        val oldSession = generations.issue()
        assertTrue(generations.isCurrent(oldSession))

        val newSession = generations.issue()
        assertFalse(generations.isCurrent(oldSession))
        assertTrue(generations.isCurrent(newSession))
    }

    @Test
    fun invalidatingASessionRejectsItsOutstandingCallbacks() {
        val generations = ShizukuSessionGeneration()
        val session = generations.issue()

        generations.invalidate()

        assertFalse(generations.isCurrent(session))
    }
}
