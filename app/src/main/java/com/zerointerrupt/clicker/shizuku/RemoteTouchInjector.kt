package com.zerointerrupt.clicker.shizuku

import android.content.pm.PackageManager
import android.os.Build
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
    private const val INPUT_MANAGER_STUB = "android.hardware.input.IInputManager\$Stub"

    /**
     * `IInputManager` AIDL transaction id of `injectInputEvent(InputEvent, int)`.
     *
     * The id is **not** stable across releases because methods get inserted into the
     * interface (it is 9 on Android 13, 10 on Android 14 and 11 on Android 15/16), so it
     * is resolved at runtime: first through Shizuku's `getTransactionCode` helper, then
     * from the stub class constant, then from the known per-API layout.
     */
    @Volatile
    private var injectTransactionCode: Int = -1

    /**
     * `InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH`.
     *
     * The async mode returns `true` even when the event is dropped, which is
     * indistinguishable from "nothing happens". Waiting for the dispatch result surfaces
     * permission/dispatcher failures instead of hiding them.
     */
    private const val INJECT_MODE_WAIT_FOR_FINISH = 1

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
            if (!resolveInjectTransactionCode()) {
                inputBinder = null
                return false
            }
            lastError = ""
            Log.i(
                TAG,
                "Direct privileged injection channel ready (Shizuku v${runCatching { Shizuku.getVersion() }.getOrDefault(-1)}, " +
                    "injectInputEvent transaction=$injectTransactionCode)."
            )
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            inputBinder = null
            Log.w(TAG, "Could not open direct injection channel: $lastError", t)
            false
        }
    }

    /**
     * Finds the AIDL transaction id of `injectInputEvent`. Guessing this wrong silently
     * calls a completely different method, which looks exactly like "nothing happens".
     */
    private fun resolveInjectTransactionCode(): Boolean {
        if (injectTransactionCode > 0) return true

        // 1) Shizuku's own helper reads TRANSACTION_injectInputEvent from the stub class.
        val fromHelper = runCatching {
            SystemServiceHelper.getTransactionCode(INPUT_MANAGER_STUB, "injectInputEvent")
        }.getOrNull()
        if (fromHelper != null && fromHelper > 0) {
            injectTransactionCode = fromHelper
            return true
        }

        // 2) Read the public constant from the stub class directly.
        val fromField = runCatching {
            val stub = Class.forName(INPUT_MANAGER_STUB)
            stub.getField("TRANSACTION_injectInputEvent").getInt(null)
        }.getOrNull()
        if (fromField != null && fromField > 0) {
            injectTransactionCode = fromField
            return true
        }

        // 3) Known interface layout per platform release.
        val fallback = when {
            Build.VERSION.SDK_INT >= 35 -> 11 // Android 15/16
            Build.VERSION.SDK_INT == 34 -> 10 // Android 14
            Build.VERSION.SDK_INT == 33 -> 9  // Android 13
            else -> -1
        }
        if (fallback <= 0) {
            lastError = "无法确定 injectInputEvent 事务号（API ${Build.VERSION.SDK_INT}）"
            return false
        }
        injectTransactionCode = fallback
        return true
    }

    /** Drops the cached channel (e.g. after the Shizuku binder died). */
    fun reset() {
        inputBinder = null
        injectTransactionCode = -1
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
            data.writeInt(INJECT_MODE_WAIT_FOR_FINISH)
            binder.transact(injectTransactionCode, data, reply, 0)
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
