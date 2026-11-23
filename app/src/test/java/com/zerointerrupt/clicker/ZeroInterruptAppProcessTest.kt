package com.zerointerrupt.clicker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroInterruptAppProcessTest {
    @Test
    fun appUidRunsRegularApplicationBootstrap() {
        assertTrue(shouldRunAppProcessBootstrap(processUid = 10123, appUid = 10123))
    }

    @Test
    fun privilegedShizukuUserServiceUidSkipsRegularApplicationBootstrap() {
        assertFalse(shouldRunAppProcessBootstrap(processUid = 2000, appUid = 10123))
        assertFalse(shouldRunAppProcessBootstrap(processUid = 0, appUid = 10123))
    }
}
