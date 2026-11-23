package com.zerointerrupt.clicker.shizuku

import kotlinx.coroutines.delay

/**
 * Polls Shizuku's permission check independently of the result callback. Some devices
 * expose the grant after the callback, or can miss the callback; callers keep the request
 * pending until the grant is visible or the bounded wait expires.
 */
internal suspend fun awaitShizukuPermission(
    maxAttempts: Int,
    intervalMs: Long,
    binderAlive: () -> Boolean,
    hasPermission: () -> Boolean,
    requestPending: () -> Boolean = { true }
): Boolean {
    val attempts = maxAttempts.coerceAtLeast(0)
    val delayMs = intervalMs.coerceAtLeast(0L)
    repeat(attempts) { attempt ->
        if (!requestPending()) return false
        if (!binderAlive()) return false
        if (hasPermission()) return true
        if (attempt < attempts - 1 && delayMs > 0L) delay(delayMs)
    }
    return false
}
