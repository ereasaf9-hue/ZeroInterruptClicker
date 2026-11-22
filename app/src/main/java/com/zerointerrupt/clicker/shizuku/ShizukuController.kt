package com.zerointerrupt.clicker.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.zerointerrupt.clicker.BuildConfig
import com.zerointerrupt.clicker.ITouchMuxCallback
import com.zerointerrupt.clicker.ITouchMuxService
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import rikka.shizuku.Shizuku

enum class ShizukuConnectionState {
    /** Shizuku server is not running / not reachable. */
    SHIZUKU_NOT_RUNNING,

    /** Shizuku is running, but has not granted this app API access. */
    NEEDS_SHIZUKU_AUTH,

    /** The privileged UserService is being bound. */
    BINDING,

    CONNECTED,
    ERROR
}

data class EngineSnapshot(
    val engineMode: Int = TouchMuxUserService.MODE_IDLE,
    val preferredBackend: Int = ClickGlobalSettings.BACKEND_AUTO,
    val uinputWritable: Boolean = false,
    val uinputErrno: Int = -1,
    val uhidWritable: Boolean = false,
    val uhidErrno: Int = -1,
    val touchDeviceReadable: Boolean = false,
    val touchErrno: Int = -1,
    val fallbackReady: Boolean = false,
    val touchDeviceInfo: String = "",
    val absRangeText: String = "",
    val statusDetail: String = "未连接 Shizuku 服务",
    val uid: Int = -1,
    val pid: Int = -1,
    val nativeProbeError: String = "",
    val bindRetryCount: Int = 0
)

data class DisplayScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val rotation: Int
)

/**
 * Process-wide Shizuku UserService connection, permission, auto-rebind, and IPC controller.
 */
object ShizukuController {
    private const val TAG = "ShizukuController"
    const val REQUEST_CODE_SHIZUKU = 10042

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var listenersRegistered = false
    private var boundArgs: Shizuku.UserServiceArgs? = null
    @Volatile
    private var bindInFlight = false
    @Volatile
    private var bindAttemptId = 0
    @Volatile
    private var service: ITouchMuxService? = null

    /**
     * Version code handed to the Shizuku server for the UserService.
     *
     * The server reuses an existing user service record as long as the version code
     * stays the same. When that record points at a dead process the server happily
     * hands the very same dead binder back and the bind can never complete.
     * Bumping this value makes the server tear the old process down and start a
     * brand new one, which is the only reliable way out of a stale binding.
     */
    @Volatile
    private var userServiceVersionCode: Int = BuildConfig.VERSION_CODE

    @Volatile
    private var autoBindRetries = 0

    @Volatile
    private var permissionRequestPending = false

    private const val USER_SERVICE_BIND_TIMEOUT_MS = 15_000L
    private const val USER_SERVICE_RECONNECT_DELAY_MS = 750L
    private const val PERMISSION_REQUEST_TIMEOUT_MS = 20_000L
    private const val MAX_AUTO_BIND_RETRIES = 4
    private val BIND_RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L)

    private val _connectionState = MutableStateFlow(ShizukuConnectionState.SHIZUKU_NOT_RUNNING)
    val connectionState: StateFlow<ShizukuConnectionState> = _connectionState.asStateFlow()

    private val _engineSnapshot = MutableStateFlow(EngineSnapshot())
    val engineSnapshot: StateFlow<EngineSnapshot> = _engineSnapshot.asStateFlow()

    private val _slotStates = MutableStateFlow(List(16) { 0 })
    val slotStates: StateFlow<List<Int>> = _slotStates.asStateFlow()

    private val _volumeKeyToggleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val volumeKeyToggleEvents: SharedFlow<Unit> = _volumeKeyToggleEvents.asSharedFlow()

    private val _emergencyUngrabEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val emergencyUngrabEvents: SharedFlow<Unit> = _emergencyUngrabEvents.asSharedFlow()

    private val _clickingCompletedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val clickingCompletedEvents: SharedFlow<Unit> = _clickingCompletedEvents.asSharedFlow()

    private val serviceCallback = object : ITouchMuxCallback.Stub() {
        override fun onVolumeKeyToggleRequested() {
            _volumeKeyToggleEvents.tryEmit(Unit)
        }

        override fun onEmergencyUngrabTriggered() {
            _slotStates.value = List(16) { 0 }
            _emergencyUngrabEvents.tryEmit(Unit)
            refreshCapabilitiesAsync()
        }

        override fun onSlotStatesChanged(slots: IntArray?) {
            if (slots != null && slots.size >= 16) {
                _slotStates.value = slots.take(16)
            }
        }

        override fun onClickingCompleted() {
            _clickingCompletedEvents.tryEmit(Unit)
            refreshCapabilitiesAsync()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received.")
        autoBindRetries = 0
        evaluateShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died; resetting service reference.")
        invalidateBindAttempt()
        autoBindRetries = 0
        permissionRequestPending = false
        service = null
        _slotStates.value = List(16) { 0 }
        _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
        _engineSnapshot.value = _engineSnapshot.value.copy(
            engineMode = TouchMuxUserService.MODE_IDLE,
            statusDetail = "Shizuku 服务已断开，等待重新连接"
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            permissionRequestPending = false
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                autoBindRetries = 0
                bindUserService()
            } else {
                invalidateBindAttempt()
                service = null
                _connectionState.value = ShizukuConnectionState.NEEDS_SHIZUKU_AUTH
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "未获得 Shizuku 授权：请在 Shizuku 管理器的「使用 Shizuku 的应用」中开启本应用"
                )
            }
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            finishBindAttempt()
            if (binder == null || !binder.pingBinder()) {
                handleBindFailure("Shizuku UserService 返回了无效 Binder，正在重建服务…")
                return
            }
            val svc = ITouchMuxService.Stub.asInterface(binder)
            service = svc
            reconnectAttempts = 0
            autoBindRetries = 0
            _connectionState.value = ShizukuConnectionState.CONNECTED
            try {
                svc.registerCallback(serviceCallback)
                appContext?.let { ctx ->
                    val settings = ClickConfigStore.loadSettings(ctx)
                    svc.setVolumeKeyControlEnabled(settings.volumeKeyToggleEnabled)
                    svc.setPreferredBackend(settings.preferredBackend)
                }
            } catch (_: Throwable) {
            }
            try {
                binder.linkToDeath({
                    Log.w(TAG, "TouchMuxUserService remote process died.")
                    service = null
                    _slotStates.value = List(16) { 0 }
                    if (Shizuku.pingBinder() && hasPermission()) {
                        _connectionState.value = ShizukuConnectionState.BINDING
                        bindUserService(forceFreshService = true)
                    } else {
                        _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                    }
                }, 0)
            } catch (_: Throwable) {
            }
            refreshCapabilitiesAsync()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _slotStates.value = List(16) { 0 }
            if (Shizuku.pingBinder() && hasPermission()) {
                _connectionState.value = ShizukuConnectionState.BINDING
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "Shizuku UserService 已断开，正在重新连接…"
                )
                scheduleUserServiceReconnect()
            } else {
                invalidateBindAttempt()
                _connectionState.value = if (Shizuku.pingBinder()) {
                    ShizukuConnectionState.NEEDS_SHIZUKU_AUTH
                } else {
                    ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                }
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            handleBindFailure("Android 已结束 Shizuku UserService 绑定，正在重建服务…")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleBindFailure("Shizuku UserService 返回空 Binder，正在重建服务…")
        }
    }

    @Volatile
    private var reconnectAttempts = 0

    @Synchronized
    private fun finishBindAttempt() {
        bindInFlight = false
        bindAttemptId++
    }

    @Synchronized
    private fun invalidateBindAttempt() {
        bindInFlight = false
        bindAttemptId++
    }

    @Synchronized
    private fun setBindingError(message: String) {
        service = null
        bindInFlight = false
        bindAttemptId++
        _connectionState.value = ShizukuConnectionState.ERROR
        _engineSnapshot.value = _engineSnapshot.value.copy(
            statusDetail = message,
            bindRetryCount = autoBindRetries
        )
    }

    @Synchronized
    private fun failBindAttemptIfCurrent(attemptId: Int, message: String): Boolean {
        if (bindAttemptId != attemptId || !bindInFlight) return false
        setBindingError(message)
        return true
    }

    /**
     * A bind attempt failed. Instead of leaving the UI stuck on "connecting" we
     * schedule a bounded automatic retry that always asks the Shizuku server for a
     * *fresh* UserService process.
     */
    @Synchronized
    private fun handleBindFailure(message: String) {
        service = null
        _slotStates.value = List(16) { 0 }
        _connectionState.value = ShizukuConnectionState.BINDING
        _engineSnapshot.value = _engineSnapshot.value.copy(
            statusDetail = message,
            bindRetryCount = autoBindRetries
        )
        scheduleBindRetry()
    }

    @Synchronized
    private fun scheduleBindRetry() {
        if (autoBindRetries >= MAX_AUTO_BIND_RETRIES) {
            setBindingError(
                "Shizuku UserService 多次连接失败。请确认 Shizuku 管理器已打开且未被强制停止，" +
                    "然后点击「重试」重新连接。"
            )
            return
        }
        val delayMs = BIND_RETRY_DELAYS_MS[autoBindRetries.coerceAtMost(BIND_RETRY_DELAYS_MS.size - 1)]
        autoBindRetries++
        val retryId = ++bindAttemptId
        bindInFlight = true
        _engineSnapshot.value = _engineSnapshot.value.copy(
            statusDetail = "Shizuku UserService 连接失败，正在重试（$autoBindRetries/$MAX_AUTO_BIND_RETRIES）…",
            bindRetryCount = autoBindRetries
        )
        scope.launch {
            delay(delayMs)
            if (bindAttemptId != retryId) return@launch
            if (!Shizuku.pingBinder() || !hasPermission()) {
                bindInFlight = false
                evaluateShizukuState()
                return@launch
            }
            bindInFlight = false
            bindUserService(forceFreshService = true)
        }
    }

    @Synchronized
    private fun scheduleUserServiceReconnect() {
        if (reconnectAttempts >= 2) {
            setBindingError("Shizuku UserService 多次断开，请点击重试。")
            return
        }
        reconnectAttempts++
        bindInFlight = true
        val reconnectId = ++bindAttemptId
        scope.launch {
            delay(USER_SERVICE_RECONNECT_DELAY_MS)
            if (bindAttemptId != reconnectId || service?.asBinder()?.pingBinder() == true) return@launch
            if (!Shizuku.pingBinder() || !hasPermission()) {
                bindInFlight = false
                evaluateShizukuState()
                return@launch
            }
            val args = boundArgs
            if (args != null) {
                try {
                    Shizuku.unbindUserService(args, userServiceConnection, true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not clear stale UserService binding before reconnect.", t)
                }
            }
            if (!prepareReconnect(reconnectId)) return@launch
            bindInFlight = false
            bindUserService(forceFreshService = true)
        }
    }

    @Synchronized
    private fun prepareReconnect(reconnectId: Int): Boolean =
        bindAttemptId == reconnectId && service?.asBinder()?.pingBinder() != true

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (!listenersRegistered) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
        }
        evaluateShizukuState()
    }

    /**
     * Drops the current binding and asks the Shizuku server for a brand new
     * UserService process. Used by the manual "retry" action.
     */
    fun forceRebind() {
        val args = boundArgs
        invalidateBindAttempt()
        service = null
        _slotStates.value = List(16) { 0 }
        if (args != null) {
            try {
                Shizuku.unbindUserService(args, userServiceConnection, true)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not clear stale UserService binding before retry.", t)
            }
        }
        autoBindRetries = 0
        reconnectAttempts = 0
        bindUserService(forceFreshService = true)
    }

    @Synchronized
    fun evaluateShizukuState() {
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!binderAlive) {
            invalidateBindAttempt()
            service = null
            _slotStates.value = List(16) { 0 }
            _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
            _engineSnapshot.value = EngineSnapshot(statusDetail = "请先启动 Shizuku 服务")
            return
        }
        if (!hasPermission()) {
            invalidateBindAttempt()
            service = null
            _connectionState.value = ShizukuConnectionState.NEEDS_SHIZUKU_AUTH
            _engineSnapshot.value = EngineSnapshot(
                statusDetail = "Shizuku 已运行，等待授权（点击「授权连接」）"
            )
            return
        }
        if (service?.asBinder()?.pingBinder() == true) {
            _connectionState.value = ShizukuConnectionState.CONNECTED
            refreshCapabilitiesAsync()
        } else {
            bindUserService()
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                _engineSnapshot.value = _engineSnapshot.value.copy(statusDetail = "请先启动 Shizuku 服务")
                return
            }
            if (hasPermission()) {
                autoBindRetries = 0
                bindUserService()
                return
            }
            // API access is granted by the Shizuku manager, so use its permission
            // request flow rather than Android's runtime-permission dialog.
            if (!permissionRequestPending) {
                permissionRequestPending = true
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                scope.launch {
                    delay(PERMISSION_REQUEST_TIMEOUT_MS)
                    if (!permissionRequestPending) return@launch
                    permissionRequestPending = false
                    _connectionState.value = ShizukuConnectionState.NEEDS_SHIZUKU_AUTH
                    _engineSnapshot.value = _engineSnapshot.value.copy(
                        statusDetail = "等待 Shizuku 授权超时：请确认 Shizuku 管理器已打开且未被强制停止"
                    )
                }
            }
            _connectionState.value = ShizukuConnectionState.NEEDS_SHIZUKU_AUTH
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "正在等待 Shizuku 授权…"
            )
        } catch (t: Throwable) {
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "请求 Shizuku 权限失败: ${t.message}"
            )
        }
    }

    private fun buildUserServiceArgs(ctx: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, TouchMuxUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("touchmux")
            .debuggable(BuildConfig.DEBUG)
            .version(userServiceVersionCode)

    /**
     * Binds the privileged UserService.
     *
     * @param forceFreshService when true the Shizuku server is asked to recreate the
     * UserService process (the version code is bumped). This is required whenever the
     * previous binding may still point at a dead process, because the server reuses a
     * user service record as long as its version code does not change.
     */
    @Synchronized
    fun bindUserService(forceFreshService: Boolean = false) {
        val ctx = appContext ?: return
        if (!Shizuku.pingBinder() || !hasPermission()) {
            evaluateShizukuState()
            return
        }
        if (!forceFreshService && service?.asBinder()?.pingBinder() == true) {
            _connectionState.value = ShizukuConnectionState.CONNECTED
            return
        }
        if (bindInFlight) return
        bindInFlight = true
        val attemptId = ++bindAttemptId
        _connectionState.value = ShizukuConnectionState.BINDING
        _engineSnapshot.value = _engineSnapshot.value.copy(
            statusDetail = "正在启动 Shizuku UserService…",
            bindRetryCount = autoBindRetries
        )
        if (forceFreshService) {
            userServiceVersionCode += 1
        }
        val args = buildUserServiceArgs(ctx)
        boundArgs = args

        try {
            Shizuku.bindUserService(args, userServiceConnection)
            scope.launch {
                delay(USER_SERVICE_BIND_TIMEOUT_MS)
                if (failBindAttemptIfCurrent(
                        attemptId,
                        "连接 Shizuku UserService 超时，正在重建服务…"
                    )
                ) {
                    Log.e(TAG, "Timed out waiting for Shizuku UserService connection callback.")
                    try {
                        Shizuku.unbindUserService(args, userServiceConnection, true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not clear timed-out UserService binding.", t)
                    }
                    scheduleBindRetry()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService error", t)
            setBindingError("绑定 Shizuku UserService 失败: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun refreshCapabilitiesAsync() {
        val ctx = appContext ?: return
        val svc = service ?: return
        scope.launch {
            try {
                val jsonStr = svc.probeCapabilities(ctx.applicationInfo.nativeLibraryDir)
                val obj = JSONObject(jsonStr)
                val touchPath = obj.optString("touchDevicePath", "")
                val touchName = obj.optString("touchDeviceName", "")
                val touchInfo = if (touchPath.isNotEmpty()) "$touchPath ($touchName)" else "未检测到可读触控节点"
                val detail = obj.optString("statusDetail", "Ready")
                val uinputOk = obj.optBoolean("uinputWritable", false)
                val uinputErr = obj.optInt("uinputErrno", -1)
                val uhidOk = obj.optBoolean("uhidWritable", false)
                val uhidErr = obj.optInt("uhidErrno", -1)
                val touchOk = obj.optBoolean("touchDeviceReadable", false)
                val touchErr = obj.optInt("touchDeviceErrno", -1)
                val minX = obj.optInt("absMinX", 0)
                val maxX = obj.optInt("absMaxX", 0)
                val minY = obj.optInt("absMinY", 0)
                val maxY = obj.optInt("absMaxY", 0)
                val absText = if (touchOk) "X[$minX..$maxX] Y[$minY..$maxY]" else "-"

                val probeErr = buildString {
                    val loadErr = obj.optString("nativeLoadError", "")
                    if (loadErr.isNotEmpty()) append(loadErr)
                    if (!uinputOk) {
                        if (isNotEmpty()) append("; ")
                        append("/dev/uinput errno=$uinputErr")
                    }
                    if (!uhidOk) {
                        if (isNotEmpty()) append("; ")
                        append("/dev/uhid errno=$uhidErr")
                    }
                    if (!touchOk) {
                        if (isNotEmpty()) append("; ")
                        append("/dev/input errno=$touchErr")
                    }
                }

                _engineSnapshot.value = EngineSnapshot(
                    engineMode = obj.optInt("currentMode", TouchMuxUserService.MODE_IDLE),
                    preferredBackend = obj.optInt("preferredBackend", ClickGlobalSettings.BACKEND_AUTO),
                    uinputWritable = uinputOk,
                    uinputErrno = uinputErr,
                    uhidWritable = uhidOk,
                    uhidErrno = uhidErr,
                    touchDeviceReadable = touchOk,
                    touchErrno = touchErr,
                    fallbackReady = obj.optBoolean("fallbackInjectorReady", false),
                    touchDeviceInfo = touchInfo,
                    absRangeText = absText,
                    statusDetail = detail,
                    uid = obj.optInt("uid", -1),
                    pid = obj.optInt("pid", -1),
                    nativeProbeError = probeErr,
                    bindRetryCount = autoBindRetries
                )
            } catch (t: Throwable) {
                Log.w(TAG, "probeCapabilities failed", t)
            }
        }
    }

    fun startSelfCheckGrab(timeoutMs: Int = 5000): Int {
        val ctx = appContext ?: return -1
        val svc = service ?: return -2
        val settings = ClickConfigStore.loadSettings(ctx)
        val metrics = getDisplayScreenMetrics(ctx)
        return try {
            val rc = svc.startSelfCheckGrab(
                ctx.applicationInfo.nativeLibraryDir,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                timeoutMs,
                settings.preferredBackend
            )
            refreshCapabilitiesAsync()
            rc
        } catch (t: Throwable) {
            Log.e(TAG, "startSelfCheckGrab failed", t)
            -3
        }
    }

    fun confirmSelfCheck(): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        return try {
            val ok = svc.confirmSelfCheck()
            ClickConfigStore.markSelfCheckPassed(ctx, ok)
            refreshCapabilitiesAsync()
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "confirmSelfCheck failed", t)
            false
        }
    }

    fun cancelSelfCheckAndUngrab() {
        val svc = service ?: return
        try {
            svc.cancelSelfCheckAndUngrab()
            _slotStates.value = List(16) { 0 }
            refreshCapabilitiesAsync()
        } catch (_: Throwable) {
        }
    }

    fun cleanupOrphanProcesses(): Int {
        val svc = service ?: return 0
        return try {
            val count = svc.cleanupOrphanProcesses()
            refreshCapabilitiesAsync()
            count
        } catch (_: Throwable) {
            0
        }
    }

    fun refreshSlotStates() {
        val svc = service ?: return
        scope.launch {
            try {
                val arr = svc.slotStates
                if (arr != null && arr.size >= 16) {
                    _slotStates.value = arr.take(16)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun setVolumeKeyControlEnabled(enabled: Boolean) {
        val svc = service ?: return
        try {
            svc.setVolumeKeyControlEnabled(enabled)
        } catch (_: Throwable) {
        }
    }

    fun setPreferredBackend(backend: Int, reinitIfActive: Boolean = false) {
        val ctx = appContext
        val svc = service ?: return
        try {
            svc.setPreferredBackend(backend)
            if (reinitIfActive && ctx != null) {
                val settings = ClickConfigStore.loadSettings(ctx)
                val metrics = getDisplayScreenMetrics(ctx)
                if (settings.selfCheckPassed) {
                    svc.initNativeEngine(
                        ctx.applicationInfo.nativeLibraryDir,
                        metrics.widthPx,
                        metrics.heightPx,
                        metrics.rotation,
                        backend
                    )
                }
            }
            refreshCapabilitiesAsync()
        } catch (_: Throwable) {
        }
    }

    fun ensureNativeIfVerified(): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        val settings = ClickConfigStore.loadSettings(ctx)
        if (!settings.selfCheckPassed) return false
        if (settings.preferredBackend == ClickGlobalSettings.BACKEND_FALLBACK) return false
        return try {
            val curMode = svc.engineMode
            val matchesPref = settings.preferredBackend == ClickGlobalSettings.BACKEND_AUTO ||
                curMode == settings.preferredBackend
            if (TouchMuxUserService.isNativeMode(curMode) && matchesPref) return true
            val metrics = getDisplayScreenMetrics(ctx)
            val rc = svc.initNativeEngine(
                ctx.applicationInfo.nativeLibraryDir,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                settings.preferredBackend
            )
            refreshCapabilitiesAsync()
            rc == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun startClicking(
        targets: List<ClickTarget>? = null,
        executionMode: ExecutionMode? = null,
        presetLoopCount: Int? = null
    ): Int {
        val ctx = appContext ?: return -1
        val svc = service ?: return -2
        return try {
            val snapshot = _engineSnapshot.value
            val settings = ClickConfigStore.loadSettings(ctx)
            if (settings.selfCheckPassed &&
                settings.preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK &&
                snapshot.touchDeviceReadable &&
                (snapshot.uinputWritable || snapshot.uhidWritable || snapshot.fallbackReady)
            ) {
                ensureNativeIfVerified()
            }
            val metrics = getDisplayScreenMetrics(ctx)
            val presets = ClickConfigStore.loadPresets(ctx)
            val activePreset = ClickConfigStore.getActivePreset(ctx)
            val effectiveMode = executionMode ?: activePreset.executionMode
            val effectiveLoopCount = (presetLoopCount ?: activePreset.presetLoopCount).coerceIn(0, 9999)
            val parceled = ClickConfigStore.compileTargetsForExecution(
                presets = presets,
                activePreset = activePreset,
                liveOverrideTargets = targets,
                executionMode = effectiveMode,
                settings = settings,
                screenW = metrics.widthPx,
                screenH = metrics.heightPx
            )
            if (parceled.isEmpty()) return -4
            val mode = svc.startClicking(
                parceled,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                true,
                effectiveMode.code,
                effectiveLoopCount,
                settings.jitterRadiusPx,
                settings.preferredBackend
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = if (mode > 0) mode else TouchMuxUserService.MODE_IDLE,
                preferredBackend = settings.preferredBackend,
                statusDetail = svc.statusDetail
            )
            mode
        } catch (t: Throwable) {
            Log.e(TAG, "startClicking failed", t)
            -3
        }
    }

    fun updateTargets(
        targets: List<ClickTarget>? = null,
        executionMode: ExecutionMode? = null,
        presetLoopCount: Int? = null
    ) {
        val ctx = appContext ?: return
        val svc = service ?: return
        try {
            val settings = ClickConfigStore.loadSettings(ctx)
            val metrics = getDisplayScreenMetrics(ctx)
            val presets = ClickConfigStore.loadPresets(ctx)
            val activePreset = ClickConfigStore.getActivePreset(ctx)
            val effectiveMode = executionMode ?: activePreset.executionMode
            val effectiveLoopCount = (presetLoopCount ?: activePreset.presetLoopCount).coerceIn(0, 9999)
            val parceled = ClickConfigStore.compileTargetsForExecution(
                presets = presets,
                activePreset = activePreset,
                liveOverrideTargets = targets,
                executionMode = effectiveMode,
                settings = settings,
                screenW = metrics.widthPx,
                screenH = metrics.heightPx
            )
            svc.updateTargets(
                parceled,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                effectiveMode.code,
                effectiveLoopCount,
                settings.jitterRadiusPx
            )
        } catch (t: Throwable) {
            Log.w(TAG, "updateTargets failed", t)
        }
    }

    fun updateDisplayMetrics() {
        val ctx = appContext ?: return
        val svc = service ?: return
        try {
            val metrics = getDisplayScreenMetrics(ctx)
            svc.updateDisplayMetrics(metrics.widthPx, metrics.heightPx, metrics.rotation)
        } catch (_: Throwable) {
        }
    }

    fun stopClicking() {
        val svc = service ?: return
        try {
            svc.stopClicking()
            _engineSnapshot.value = _engineSnapshot.value.copy(statusDetail = svc.statusDetail)
        } catch (t: Throwable) {
            Log.w(TAG, "stopClicking failed", t)
        }
    }

    fun shutdownEngine() {
        val svc = service ?: return
        try {
            svc.shutdownEngine()
            _slotStates.value = List(16) { 0 }
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = TouchMuxUserService.MODE_IDLE,
                statusDetail = svc.statusDetail
            )
        } catch (_: Throwable) {
        }
    }

    fun rpcTap(x: Float, y: Float, durationMs: Long = 25L): Boolean {
        val svc = service ?: return false
        return try {
            svc.injectRpcTap(x, y, durationMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun rpcSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val svc = service ?: return false
        return try {
            svc.injectRpcSwipe(x1, y1, x2, y2, durationMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun rpcHold(holdId: Int, down: Boolean, x: Float, y: Float): Boolean {
        val svc = service ?: return false
        return try {
            svc.setRpcHold(holdId, down, x, y)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun startTouchRecording(excludeLeft: Int, excludeTop: Int, excludeRight: Int, excludeBottom: Int): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        return try {
            // Ensure libtouch_mux.so is loaded inside UserService before recording
            if (_engineSnapshot.value.touchDeviceInfo.isEmpty()) {
                svc.probeCapabilities(ctx.applicationInfo.nativeLibraryDir)
            }
            val metrics = getDisplayScreenMetrics(ctx)
            svc.startTouchRecording(
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                excludeLeft,
                excludeTop,
                excludeRight,
                excludeBottom
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startTouchRecording failed", t)
            false
        }
    }

    fun updateRecordingExcludeRect(excludeLeft: Int, excludeTop: Int, excludeRight: Int, excludeBottom: Int) {
        val svc = service ?: return
        try {
            svc.updateRecordingExcludeRect(excludeLeft, excludeTop, excludeRight, excludeBottom)
        } catch (_: Throwable) {
        }
    }

    fun getTouchRecordingStats(): LongArray {
        val svc = service ?: return longArrayOf(0L, 0L, 0L, 0L)
        return try {
            svc.touchRecordingStats ?: longArrayOf(0L, 0L, 0L, 0L)
        } catch (_: Throwable) {
            longArrayOf(0L, 0L, 0L, 0L)
        }
    }

    fun stopTouchRecording(): String {
        val svc = service ?: return ""
        return try {
            svc.stopTouchRecording().orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "stopTouchRecording failed", t)
            ""
        }
    }

    fun cancelTouchRecording() {
        val svc = service ?: return
        try {
            svc.cancelTouchRecording()
        } catch (_: Throwable) {
        }
    }

    fun unbindUserService() {
        invalidateBindAttempt()
        val args = boundArgs ?: return
        try {
            service?.shutdownEngine()
        } catch (_: Throwable) {
        }
        try {
            Shizuku.unbindUserService(args, userServiceConnection, true)
        } catch (_: Throwable) {
        }
        service = null
        _slotStates.value = List(16) { 0 }
    }

    fun isServiceConnected(): Boolean = service?.asBinder()?.pingBinder() == true

    @Suppress("DEPRECATION")
    fun getDisplayScreenMetrics(context: Context): DisplayScreenMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            DisplayScreenMetrics(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), rotation)
        } else {
            val metrics = DisplayMetrics()
            display?.getRealMetrics(metrics)
            DisplayScreenMetrics(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), rotation)
        }
    }
}
