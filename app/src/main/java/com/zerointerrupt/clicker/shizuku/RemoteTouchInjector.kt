package com.zerointerrupt.clicker.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Privileged touch injection that does **not** require a Shizuku UserService process.
 *
 * A Shizuku UserService is started through `app_process` and needs the helper process to
 * be created, its `Application` instantiated and the Binder handshake to complete. On a
 * number of Android 15/16 ROMs (and with Shizuku 13.6.x on HyperOS) that helper process
 * never comes up, which leaves the whole app unusable even though Shizuku itself is
 * running and the permission was granted.
 *
 * This class bypasses that entirely: it grabs the real `input` system service Binder and
 * wraps it in [ShizukuBinderWrapper], so every transaction is forwarded through the
 * Shizuku server (running as shell/root, which holds `INJECT_EVENTS`). The result is a
 * fully working tap/swipe/hold channel from the app process itself.
 *
 * The call is made with a hand written Binder transaction instead of reflecting on the
 * hidden `IInputManager` classes, so it keeps working under Android's hidden API
 * restrictions.
 *
 * All calls are best-effort and never throw; failures are reported through [error].
 */
internal object RemoteTouchInjector {
    private const val TAG = "RemoteTouchInjector"

    private const val INPUT_SERVICE = "input"
    private const val INPUT_MANAGER_DESCRIPTOR = "android.hardware.input.IInputManager"

    /** `IInputManager` AIDL transaction id of `injectInputEvent(InputEvent, int)`. */
    private const val TRANSACTION_INJECT_INPUT_EVENT = IBinder.FIRST_CALL_TRANSACTION + 0

    /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC` */
    private const val INJECT_MODE_ASYNC = 0

    private const val SWIPE_STEP_MS = 16L
    private const val MAX_SWIPE_STEPS = 240

    @Volatile
    private var inputBinder: IBinder? = null

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var holdDownTime: Long = 0L

    @Volatile
    private var holdX: Float = 0f

    @Volatile
    private var holdY: Float = 0f

    /** Human readable reason for the last failure, empty when healthy. */
    val error: String
        get() = lastError

    /** True when Shizuku is alive and this app currently holds the API permission. */
    fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isReady(): Boolean = inputBinder != null

    /**
     * Resolves the `input` system service through Shizuku once and caches the wrapped
     * Binder. Cheap to call repeatedly.
     */
    fun ensureReady(): Boolean {
        if (inputBinder != null) return true
        if (!isShizukuReady()) {
            lastError = "Shizuku 未运行或未授权"
            return false
        }
        return try {
            val raw: IBinder = SystemServiceHelper.getSystemService(INPUT_SERVICE)
            if (!raw.pingBinder()) {
                lastError = "input 系统服务不可用"
                return false
            }
            inputBinder = ShizukuBinderWrapper(raw)
            lastError = ""
            Log.i(
                TAG,
                "Direct privileged injection channel ready (Shizuku v${runCatching { Shizuku.getVersion() }.getOrDefault(-1)})."
            )
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            inputBinder = null
            Log.w(TAG, "Could not open direct injection channel: $lastError", t)
            false
        }
    }

    /** Drops the cached channel (e.g. after the Shizuku binder died). */
    fun reset() {
        inputBinder = null
        holdDownTime = 0L
        lastError = ""
    }

    fun tap(x: Float, y: Float, durationMs: Long = 25L): Boolean {
        if (!ensureReady()) return false
        val downTime = SystemClock.uptimeMillis()
        val holdMs = durationMs.coerceIn(1L, 5_000L)
        val down = obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y) ?: return false
        if (!inject(down)) {
            down.recycle()
            return false
        }
        down.recycle()
        SystemClock.sleep(holdMs)
        val up = obtain(downTime, downTime + holdMs, MotionEvent.ACTION_UP, x, y) ?: return false
        val ok = inject(up)
        up.recycle()
        return ok
    }

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L
    ): Boolean {
        if (!ensureReady()) return false
        val total = durationMs.coerceIn(16L, 10_000L)
        val steps = (total / SWIPE_STEP_MS).toInt().coerceIn(1, MAX_SWIPE_STEPS)
        val stepMs = (total / steps).coerceAtLeast(1L)
        val downTime = SystemClock.uptimeMillis()

        val down = obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1) ?: return false
        if (!inject(down)) {
            down.recycle()
            return false
        }
        down.recycle()

        try {
            for (i in 1..steps) {
                val fraction = i.toFloat() / steps
                val cx = x1 + (x2 - x1) * fraction
                val cy = y1 + (y2 - y1) * fraction
                val move = obtain(
                    downTime,
                    downTime + stepMs * i,
                    MotionEvent.ACTION_MOVE,
                    cx,
                    cy
                ) ?: continue
                inject(move)
                move.recycle()
                SystemClock.sleep(stepMs)
            }
            val up = obtain(
                downTime,
                downTime + stepMs * steps + 1,
                MotionEvent.ACTION_UP,
                x2,
                y2
            ) ?: return false
            val ok = inject(up)
            up.recycle()
            return ok
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            return false
        }
    }

    /**
     * Starts ([down] = true) or ends a held pointer. A held pointer is a single
     * ACTION_DOWN that stays open until the matching release call.
     */
    fun hold(down: Boolean, x: Float, y: Float): Boolean {
        if (!ensureReady()) return false
        return try {
            if (down) {
                if (holdDownTime != 0L) releaseHold()
                val now = SystemClock.uptimeMillis()
                val ev = obtain(now, now, MotionEvent.ACTION_DOWN, x, y) ?: return false
                val ok = inject(ev)
                ev.recycle()
                if (ok) {
                    holdDownTime = now
                    holdX = x
                    holdY = y
                }
                ok
            } else {
                releaseHold()
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    fun releaseHold(): Boolean {
        if (holdDownTime == 0L) return true
        if (!ensureReady()) return false
        val downTime = holdDownTime
        holdDownTime = 0L
        return try {
            val up = obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                holdX,
                holdY
            ) ?: return false
            val ok = inject(up)
            up.recycle()
            ok
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    private fun obtain(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent? = try {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        ev
    } catch (t: Throwable) {
        lastError = t.message ?: t.javaClass.simpleName
        null
    }

    private fun inject(event: MotionEvent): Boolean {
        val binder = inputBinder
        if (binder == null) {
            lastError = "注入通道未就绪"
            return false
        }
        var data: Parcel? = null
        var reply: Parcel? = null
        return try {
            data = Parcel.obtain()
            reply = Parcel.obtain()
            data.writeInterfaceToken(INPUT_MANAGER_DESCRIPTOR)
            data.writeInt(1) // event is not null
            event.writeToParcel(data, 0)
            data.writeInt(INJECT_MODE_ASYNC)
            binder.transact(TRANSACTION_INJECT_INPUT_EVENT, data, reply, 0)
            reply.readException()
            val ok = reply.dataAvail() <= 0 || reply.readInt() != 0
            lastError = if (ok) "" else "injectInputEvent 返回 false"
            ok
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "injectInputEvent failed: $lastError")
            if (t is android.os.DeadObjectException || t is android.os.RemoteException) {
                reset()
            }
            false
        } finally {
            runCatching { data?.recycle() }
            runCatching { reply?.recycle() }
        }
    }
}
