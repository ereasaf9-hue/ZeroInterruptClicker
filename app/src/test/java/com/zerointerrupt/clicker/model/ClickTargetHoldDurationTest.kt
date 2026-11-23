package com.zerointerrupt.clicker.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickTargetHoldDurationTest {
    @Test
    fun legacyTapDefaultOnHoldBecomesARealLongPress() {
        assertEquals(
            ClickTarget.DEFAULT_HOLD_DURATION_MS,
            ClickTarget.normalizePressDuration(ActionType.HOLD, ClickTarget.DEFAULT_PRESS_DURATION_MS)
        )
    }

    @Test
    fun tapDefaultRemainsUnchangedForTapActions() {
        assertEquals(
            ClickTarget.DEFAULT_PRESS_DURATION_MS,
            ClickTarget.normalizePressDuration(ActionType.TAP, ClickTarget.DEFAULT_PRESS_DURATION_MS)
        )
    }

    @Test
    fun explicitlyConfiguredHoldDurationsRemainUnchanged() {
        listOf(300L, 800L, ClickTarget.MAX_PRESS_MS).forEach { duration ->
            assertEquals(duration, ClickTarget.normalizePressDuration(ActionType.HOLD, duration))
        }
    }
}
