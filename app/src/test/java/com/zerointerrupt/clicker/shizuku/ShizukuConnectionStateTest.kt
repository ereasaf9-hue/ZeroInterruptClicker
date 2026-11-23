package com.zerointerrupt.clicker.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuConnectionStateTest {
    @Test
    fun inFlightPermissionAndBindStatesCannotBeRestartedByPrimaryButton() {
        val busyStates = listOf(
            ShizukuConnectionState.REQUESTING_PERMISSION,
            ShizukuConnectionState.VERIFYING_PERMISSION,
            ShizukuConnectionState.BINDING,
            ShizukuConnectionState.INITIALIZING
        )

        busyStates.forEach { state ->
            assertTrue("$state must be displayed as busy", state.isBusy)
            assertFalse("$state must not expose a restart action", state.actionEnabled)
        }
    }

    @Test
    fun onlyRecoveryStatesExposeAnAction() {
        val actionStates = listOf(
            ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
            ShizukuConnectionState.NO_PERMISSION,
            ShizukuConnectionState.NEEDS_SHIZUKU_AUTH,
            ShizukuConnectionState.ERROR
        )

        actionStates.forEach { state ->
            assertTrue("$state should offer the appropriate recovery action", state.actionEnabled)
            assertFalse("$state is not a busy operation", state.isBusy)
        }

        assertFalse(ShizukuConnectionState.CONNECTED.actionEnabled)
        assertFalse(ShizukuConnectionState.CONNECTED.isBusy)
    }

    @Test
    fun eachConnectionStateHasAUserFacingStatusLabel() {
        ShizukuConnectionState.entries.forEach { state ->
            assertTrue("$state should have a display label", state.displayName.isNotBlank())
            assertFalse("$state should not expose its enum identifier", state.displayName == state.name)
        }
    }
}
