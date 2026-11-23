package com.zerointerrupt.clicker.shizuku

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPermissionGateTest {
    @Test
    fun waitsUntilPermissionBecomesVisible() = runBlocking {
        var permissionChecks = 0
        val ready = awaitShizukuPermission(
            maxAttempts = 5,
            intervalMs = 0L,
            binderAlive = { true },
            hasPermission = { ++permissionChecks >= 3 }
        )

        assertTrue(ready)
        assertEquals(3, permissionChecks)
    }

    @Test
    fun stopsWhenShizukuBinderIsDead() = runBlocking {
        var permissionChecks = 0
        val ready = awaitShizukuPermission(
            maxAttempts = 5,
            intervalMs = 0L,
            binderAlive = { false },
            hasPermission = { permissionChecks++; true }
        )

        assertFalse(ready)
        assertEquals(0, permissionChecks)
    }

    @Test
    fun givesUpAfterBoundedPermissionChecks() = runBlocking {
        var permissionChecks = 0
        val ready = awaitShizukuPermission(
            maxAttempts = 4,
            intervalMs = 0L,
            binderAlive = { true },
            hasPermission = { permissionChecks++; false }
        )

        assertFalse(ready)
        assertEquals(4, permissionChecks)
    }
}
