package com.zerointerrupt.clicker.shizuku

import kotlinx.coroutines.delay

/**
 * Polls Shizuku's permission check for a bounded time after an approval callback.
 * The callback and the server-side permission state can become observable at slightly
 * different times, so callers should not start UserService until the check succeeds.
 */
internal suspend fun awaitShizukuPermission(
    maxAttempts: Int,
    intervalMs: Long,
    binderAlive: () -> Boolean,
    hasPermission: () -> Boolean
): Boolean {
    val attempts = maxAttempts.coerceAtLeast(0)
    val delayMs = intervalMs.coerceAtLeast(0L)
    repeat(attempts) { attempt ->
        if (!binderAlive()) return false
        if (hasPermission()) return true
        if (attempt < attempts - 1 && delayMs > 0L) delay(delayMs)
    }
    return false
}
