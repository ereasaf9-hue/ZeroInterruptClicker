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
    SHIZUKU_NOT_RUNNING,
    NO_PERMISSION,
    NEEDS_SHIZUKU_AUTH,
    REQUESTING_PERMISSION,
    VERIFYING_PERMISSION,
    BINDING,
    INITIALIZING,
    CONNECTED,
    ERROR;

    val displayName: String
        get() = when (this) {
            SHIZUKU_NOT_RUNNING -> "Shizuku 未运行"
            NO_PERMISSION -> "需要 Shizuku 授权"
            NEEDS_SHIZUKU_AUTH -> "授权未完成"
            REQUESTING_PERMISSION -> "等待授权"
            VERIFYING_PERMISSION -> "正在确认授权"
            BINDING -> "正在连接"
            INITIALIZING -> "正在初始化服务"
            CONNECTED -> "已连接"
            ERROR -> "连接失败"
        }

    val isBusy: Boolean
        get() = when (this) {
            REQUESTING_PERMISSION, VERIFYING_PERMISSION, BINDING, INITIALIZING -> true
            else -> false
        }

    val actionEnabled: Boolean
        get() = when (this) {
            SHIZUKU_NOT_RUNNING, NO_PERMISSION, NEEDS_SHIZUKU_AUTH, ERROR -> true
            REQUESTING_PERMISSION, VERIFYING_PERMISSION, BINDING, INITIALIZING, CONNECTED -> false
        }
}

data class ShizukuConnectionStatus(
    val state: ShizukuConnectionState,
    val detail: String
)

/** Monotonic token source used to reject callbacks from retired Binder sessions. */
internal class ShizukuSessionGeneration {
    private var current = 0L

    @Synchronized
    fun issue(): Long = ++current

    @Synchronized
    fun invalidate(): Long = ++current

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == current
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

    private val lifecycleLock = Any()
    /** Serializes Shizuku's bind/unbind calls against one another. */
    private val shizukuApiLock = Any()

    @Volatile
    private var appContext: Context? = null
    private var listenersRegistered = false
    private var currentSession: BindSession? = null
    private val generations = ShizukuSessionGeneration()
    private var pendingBindGeneration: Long? = null
    private var pendingBindJob: kotlinx.coroutines.Job? = null
    private var permissionWatchJob: kotlinx.coroutines.Job? = null
    private var activePermissionRequestCode: Int? = null
    private var nextPermissionRequestCode = REQUEST_CODE_SHIZUKU
    @Volatile
    private var service: ITouchMuxService? = null

    private data class BindSession(
        val generation: Long,
        val args: Shizuku.UserServiceArgs,
        val connection: ServiceConnection,
        var connected: Boolean = false,
        var initializing: Boolean = false,
        var timeoutJob: kotlinx.coroutines.Job? = null
    )

    /**
     * The UserService version is the server-side identity of this process. A fresh
     * version is requested only after an explicit/automatic recovery attempt.
     */
    @Volatile
    private var userServiceVersionCode: Int = BuildConfig.VERSION_CODE

    @Volatile
    private var autoBindRetries = 0

    // Shizuku may spend about 30s creating the helper process on slower/OEM devices.
    // Leave room for that startup window before retiring the bind attempt.
    private const val USER_SERVICE_BIND_TIMEOUT_MS = 35_000L
    private const val USER_SERVICE_RECONNECT_DELAY_MS = 750L
    private const val PERMISSION_REQUEST_TIMEOUT_MS = 20_000L
    private const val PERMISSION_POLL_INTERVAL_MS = 250L
    private const val USER_SERVICE_STARTUP_DELAY_MS = 2_000L
    private const val MAX_AUTO_BIND_RETRIES = 4
    private val BIND_RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L)

    private val _connectionStatus = MutableStateFlow(
        ShizukuConnectionStatus(
            ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
            "请先启动 Shizuku 服务。"
        )
    )
    val connectionStatus: StateFlow<ShizukuConnectionStatus> = _connectionStatus.asStateFlow()

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

    private fun setConnectionStateLocked(state: ShizukuConnectionState, detail: String) {
        _connectionStatus.value = ShizukuConnectionStatus(state, detail)
    }

    private fun isCurrentSession(generation: Long): Boolean = synchronized(lifecycleLock) {
        generations.isCurrent(generation) && currentSession?.generation == generation
    }

    private fun callbackFor(generation: Long) = object : ITouchMuxCallback.Stub() {
        override fun onVolumeKeyToggleRequested() {
            if (isCurrentSession(generation)) _volumeKeyToggleEvents.tryEmit(Unit)
        }

        override fun onEmergencyUngrabTriggered() {
            if (!isCurrentSession(generation)) return
            _slotStates.value = List(16) { 0 }
            _emergencyUngrabEvents.tryEmit(Unit)
            refreshCapabilitiesAsync()
        }

        override fun onSlotStatesChanged(slots: IntArray?) {
            if (isCurrentSession(generation) && slots != null && slots.size >= 16) {
                _slotStates.value = slots.take(16)
            }
        }

        override fun onClickingCompleted() {
            if (!isCurrentSession(generation)) return
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
        Log.w(TAG, "Shizuku binder died; invalidating all UserService sessions.")
        val retired = synchronized(lifecycleLock) {
            val old = invalidatePendingWorkLocked()
            service = null
            _slotStates.value = List(16) { 0 }
            setConnectionStateLocked(
                ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
                "Shizuku 服务已断开，请在管理器中重新启动。"
            )
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Shizuku 服务已断开，等待重新连接")
            old
        }
        if (retired != null) removeUserServiceSafely(retired)
        autoBindRetries = 0
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        var retired: BindSession? = null
        val result = synchronized(lifecycleLock) {
            if (activePermissionRequestCode != requestCode) {
                0
            } else if (grantResult == PackageManager.PERMISSION_GRANTED) {
                // Keep the request alive until checkSelfPermission() actually sees the grant.
                // The Shizuku callback may arrive before the permission state is propagated.
                setConnectionStateLocked(
                    ShizukuConnectionState.VERIFYING_PERMISSION,
                    "已授予 Shizuku 权限，正在等待系统确认…"
                )
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "Shizuku izni verildi; izin durumu doğrulanıyor…"
                )
                1
            } else {
                retired = invalidatePendingWorkLocked()
                service = null
                _slotStates.value = List(16) { 0 }
                setConnectionStateLocked(
                    ShizukuConnectionState.NEEDS_SHIZUKU_AUTH,
                    "未授予权限。请在 Shizuku 管理器中启用权限后重试。"
                )
                _engineSnapshot.value = EngineSnapshot(
                    statusDetail = "Shizuku izni reddedildi. Shizuku yöneticisinden izin verip yeniden deneyin."
                )
                2
            }
        }
        when (result) {
            0 -> Log.d(TAG, "Ignoring stale Shizuku permission result for request=$requestCode")
            1 -> Log.i(TAG, "Shizuku permission callback granted request=$requestCode; permission watcher remains active.")
            2 -> {
                Log.i(TAG, "Shizuku permission denied for request=$requestCode")
                if (retired != null) removeUserServiceSafely(retired!!)
            }
        }
    }

    private fun startPermissionWatch(requestCode: Int) {
        val watcher = scope.launch {
            val permissionReady = awaitShizukuPermission(
                maxAttempts = (PERMISSION_REQUEST_TIMEOUT_MS / PERMISSION_POLL_INTERVAL_MS).toInt(),
                intervalMs = PERMISSION_POLL_INTERVAL_MS,
                binderAlive = { runCatching { Shizuku.pingBinder() }.getOrDefault(false) },
                hasPermission = { hasPermission() },
                requestPending = {
                    synchronized(lifecycleLock) { activePermissionRequestCode == requestCode }
                }
            )
            val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val granted = binderAlive && (permissionReady || hasPermission())
            val result = synchronized(lifecycleLock) {
                if (activePermissionRequestCode != requestCode) {
                    0
                } else {
                    activePermissionRequestCode = null
                    permissionWatchJob = null
                    when {
                        granted -> {
                            setConnectionStateLocked(
                                ShizukuConnectionState.BINDING,
                                "正在启动 Shizuku UserService…"
                            )
                            _engineSnapshot.value = _engineSnapshot.value.copy(
                                statusDetail = "Shizuku izni doğrulandı; UserService başlatılıyor…"
                            )
                            1
                        }
                        !binderAlive -> {
                            setConnectionStateLocked(
                                ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
                                "等待授权时 Shizuku 服务已停止，请重新启动 Shizuku。"
                            )
                            _engineSnapshot.value = EngineSnapshot(
                                statusDetail = "İzin beklenirken Shizuku bağlantısı kesildi. Shizuku’yu başlatıp yeniden deneyin."
                            )
                            3
                        }
                        else -> {
                            setConnectionStateLocked(
                                ShizukuConnectionState.NEEDS_SHIZUKU_AUTH,
                                "无法确认权限，请在 Shizuku 管理器中检查此应用的授权状态。"
                            )
                            _engineSnapshot.value = _engineSnapshot.value.copy(
                                statusDetail = "Shizuku izni 20 saniye içinde doğrulanamadı. Shizuku yöneticisinde bu uygulamanın iznini kontrol edin."
                            )
                            2
                        }
                    }
                }
            }
            when (result) {
                1 -> {
                    autoBindRetries = 0
                    Log.i(TAG, "Permission watcher observed the Shizuku grant for request=$requestCode; starting UserService bind.")
                    scheduleUserServiceBind(delayMs = 0L)
                }
                2 -> Log.w(TAG, "Shizuku permission request timed out without a visible grant: request=$requestCode")
                3 -> {
                    Log.w(TAG, "Shizuku binder disappeared while waiting for permission: request=$requestCode")
                    evaluateShizukuState()
                }
            }
        }
        synchronized(lifecycleLock) {
            if (activePermissionRequestCode == requestCode) {
                permissionWatchJob?.cancel()
                permissionWatchJob = watcher
            } else {
                watcher.cancel()
            }
        }
    }

    private fun invalidatePendingWorkLocked(): BindSession? {
        generations.invalidate()
        pendingBindJob?.cancel()
        pendingBindJob = null
        pendingBindGeneration = null
        permissionWatchJob?.cancel()
        permissionWatchJob = null
        activePermissionRequestCode = null
        val retired = currentSession
        retired?.timeoutJob?.cancel()
        currentSession = null
        return retired
    }

    private fun buildUserServiceArgs(ctx: Context, version: Int): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, TouchMuxUserService::class.java.name)
        )
            .tag("${ctx.packageName}.touchmux")
            .daemon(false)
            .processNameSuffix("touchmux")
            .debuggable(BuildConfig.DEBUG)
            .version(version)

    private fun newServiceConnection(generation: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            handleServiceConnected(generation, name, binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleSessionFailure(generation, "Shizuku UserService 连接已断开。")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleSessionFailure(generation, "Android 已终止 Shizuku UserService 绑定。")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleSessionFailure(generation, "Shizuku UserService 返回了空 Binder。")
        }
    }

    private fun handleServiceConnected(generation: Long, name: ComponentName?, binder: IBinder?) {
        if (binder == null || !runCatching { binder.pingBinder() }.getOrDefault(false)) {
            handleSessionFailure(generation, "Shizuku UserService 返回了无效 Binder。")
            return
        }
        val svc = ITouchMuxService.Stub.asInterface(binder)
        val session = synchronized(lifecycleLock) {
            val active = currentSession
            if (active == null || active.generation != generation || active.initializing || active.connected) return
            active.initializing = true
            setConnectionStateLocked(
                ShizukuConnectionState.INITIALIZING,
                "UserService 已响应，正在完成初始化…"
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "正在启动 Shizuku UserService…"
            )
            active
        }
        Log.i(TAG, "UserService connected for $name, generation=${session.generation}")
        try {
            binder.linkToDeath({
                Log.w(TAG, "TouchMuxUserService binder died, generation=$generation")
                handleSessionFailure(generation, "Shizuku UserService 意外退出。")
            }, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach UserService death recipient.", t)
            handleSessionFailure(generation, "无法监视 Shizuku UserService Binder 生命周期。")
            return
        }
        scope.launch {
            try {
                svc.registerCallback(callbackFor(generation))
                val ctx = appContext
                if (ctx == null || !isCurrentSession(generation)) return@launch
                val settings = ClickConfigStore.loadSettings(ctx)
                svc.setVolumeKeyControlEnabled(settings.volumeKeyToggleEnabled)
                svc.setPreferredBackend(settings.preferredBackend)
                val initialized = synchronized(lifecycleLock) {
                    val active = currentSession
                    if (active !== session || active.generation != generation || !binder.isBinderAlive) {
                        false
                    } else {
                        active.initializing = false
                        active.connected = true
                        active.timeoutJob?.cancel()
                        active.timeoutJob = null
                        service = svc
                        autoBindRetries = 0
                        setConnectionStateLocked(
                            ShizukuConnectionState.CONNECTED,
                            "Shizuku UserService 已连接并完成初始化。"
                        )
                        _engineSnapshot.value = _engineSnapshot.value.copy(
                            statusDetail = "Shizuku UserService bağlı.",
                            bindRetryCount = 0
                        )
                        true
                    }
                }
                if (initialized) refreshCapabilitiesAsync()
            } catch (t: Throwable) {
                Log.w(TAG, "Initial UserService configuration failed for generation=$generation", t)
                handleSessionFailure(generation, "无法向 Shizuku UserService 发送初始化设置。")
            }
        }
    }

    private fun handleSessionFailure(generation: Long, message: String) {
        val retired = synchronized(lifecycleLock) {
            val session = currentSession
            if (session == null || session.generation != generation) return
            session.timeoutJob?.cancel()
            currentSession = null
            generations.invalidate()
            service = null
            _slotStates.value = List(16) { 0 }
            setConnectionStateLocked(
                ShizukuConnectionState.BINDING,
                "$message 正在准备自动重连…"
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = TouchMuxUserService.MODE_IDLE,
                statusDetail = "$message Yeniden bağlanılıyor…",
                bindRetryCount = autoBindRetries
            )
            session
        }
        removeUserServiceSafely(retired)
        scheduleBindRetry(message)
    }

    private fun removeUserServiceSafely(session: BindSession) {
        try {
            synchronized(shizukuApiLock) {
                Shizuku.unbindUserService(session.args, session.connection, true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not remove retired UserService generation=${session.generation}", t)
        }
    }

    private fun scheduleBindRetry(cause: String) {
        var prerequisitesUnavailable = false
        val plan = synchronized(lifecycleLock) {
            if (!runCatching { Shizuku.pingBinder() && hasPermission() }.getOrDefault(false)) {
                prerequisitesUnavailable = true
                return@synchronized null
            }
            if (autoBindRetries >= MAX_AUTO_BIND_RETRIES) {
                setConnectionStateLocked(
                    ShizukuConnectionState.ERROR,
                    "$cause Shizuku UserService 重试 $MAX_AUTO_BIND_RETRIES 次后仍未连接，请检查 Shizuku 后重试。"
                )
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "$cause Shizuku UserService $MAX_AUTO_BIND_RETRIES denemeden sonra bağlanamadı. Shizuku’yu kontrol edip yeniden deneyin.",
                    bindRetryCount = autoBindRetries
                )
                return@synchronized null
            }
            val delayMs = BIND_RETRY_DELAYS_MS[autoBindRetries.coerceAtMost(BIND_RETRY_DELAYS_MS.lastIndex)]
            autoBindRetries++
            val generation = generations.issue()
            pendingBindGeneration = generation
            setConnectionStateLocked(
                ShizukuConnectionState.BINDING,
                "$cause 自动重连 ${autoBindRetries}/$MAX_AUTO_BIND_RETRIES。"
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "$cause 自动重连 ${autoBindRetries}/$MAX_AUTO_BIND_RETRIES。",
                bindRetryCount = autoBindRetries
            )
            generation to delayMs
        } ?: run {
            // If permission or the Shizuku binder disappeared, reconcile to an actionable state.
            if (prerequisitesUnavailable) evaluateShizukuState()
            return
        }

        val (generation, delayMs) = plan
        val job = scope.launch {
            delay(delayMs)
            val isCurrent = synchronized(lifecycleLock) {
                if (pendingBindGeneration != generation) false else {
                    pendingBindGeneration = null
                    pendingBindJob = null
                    true
                }
            }
            if (isCurrent) startUserServiceBind(forceFreshService = true)
        }
        synchronized(lifecycleLock) {
            if (pendingBindGeneration == generation) pendingBindJob = job else job.cancel()
        }
    }

    private fun scheduleUserServiceBind(forceFreshService: Boolean = false, delayMs: Long = USER_SERVICE_STARTUP_DELAY_MS) {
        val generation = synchronized(lifecycleLock) {
            if (service?.asBinder()?.let { runCatching { it.pingBinder() }.getOrDefault(false) } == true) {
                setConnectionStateLocked(ShizukuConnectionState.CONNECTED, "Shizuku UserService 已连接并就绪。")
                return
            }
            if (pendingBindGeneration != null || currentSession != null) return
            val next = generations.issue()
            pendingBindGeneration = next
            setConnectionStateLocked(
                ShizukuConnectionState.BINDING,
                "正在准备 Shizuku 连接…"
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "正在准备 Shizuku 连接…",
                bindRetryCount = autoBindRetries
            )
            next
        }
        val job = scope.launch {
            delay(delayMs)
            val isCurrent = synchronized(lifecycleLock) {
                if (pendingBindGeneration != generation) false else {
                    pendingBindGeneration = null
                    pendingBindJob = null
                    true
                }
            }
            if (!isCurrent) return@launch
            if (!runCatching { Shizuku.pingBinder() && hasPermission() }.getOrDefault(false)) {
                evaluateShizukuState()
                return@launch
            }
            startUserServiceBind(forceFreshService)
        }
        synchronized(lifecycleLock) {
            if (pendingBindGeneration == generation) pendingBindJob = job else job.cancel()
        }
    }

    private fun onBindTimeout(generation: Long) {
        Log.w(TAG, "Timed out waiting for Shizuku UserService connection callback, generation=$generation")
        val timedOut = synchronized(lifecycleLock) {
            val session = currentSession
            if (session == null || session.generation != generation || session.connected) return
            currentSession = null
            generations.invalidate()
            service = null
            setConnectionStateLocked(
                ShizukuConnectionState.BINDING,
                "Shizuku 未响应，正在准备自动重试…"
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "Shizuku UserService 在 ${USER_SERVICE_BIND_TIMEOUT_MS / 1000} 秒内未响应。",
                bindRetryCount = autoBindRetries
            )
            session
        }
        removeUserServiceSafely(timedOut)
        scheduleBindRetry("UserService bağlantı zaman aşımı.")
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val shouldRegister = synchronized(lifecycleLock) {
            if (listenersRegistered) false else {
                listenersRegistered = true
                true
            }
        }
        if (shouldRegister) {
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            } catch (t: Throwable) {
                synchronized(lifecycleLock) {
                    listenersRegistered = false
                    setConnectionStateLocked(
                        ShizukuConnectionState.ERROR,
                        "无法注册 Shizuku 事件监听器：${t.message ?: t.javaClass.simpleName}"
                    )
                    _engineSnapshot.value = _engineSnapshot.value.copy(
                        statusDetail = "无法注册 Shizuku 事件监听器：${t.message ?: t.javaClass.simpleName}"
                    )
                }
                Log.e(TAG, "Could not register Shizuku listeners.", t)
                return
            }
        }
        evaluateShizukuState()
    }

    /**
     * Invalidates the old callback generation before asking Shizuku for a fresh
     * process. Late callbacks from the old process can no longer change app state.
     */
    fun forceRebind() {
        val retired = synchronized(lifecycleLock) {
            if (_connectionStatus.value.state.isBusy) return
            pendingBindJob?.cancel()
            pendingBindJob = null
            pendingBindGeneration = null
            permissionWatchJob?.cancel()
            permissionWatchJob = null
            activePermissionRequestCode = null
            generations.invalidate()
            val old = currentSession
            currentSession = null
            old?.timeoutJob?.cancel()
            service = null
            _slotStates.value = List(16) { 0 }
            setConnectionStateLocked(
                ShizukuConnectionState.BINDING,
                "正在重新建立 Shizuku 连接…"
            )
            _engineSnapshot.value = EngineSnapshot(statusDetail = "正在关闭旧的 Shizuku 会话…")
            old
        }
        if (retired != null) removeUserServiceSafely(retired)
        autoBindRetries = 0
        if (!runCatching { Shizuku.pingBinder() && hasPermission() }.getOrDefault(false)) {
            evaluateShizukuState()
            return
        }
        scheduleUserServiceBind(forceFreshService = true, delayMs = USER_SERVICE_RECONNECT_DELAY_MS)
    }

    fun evaluateShizukuState() {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            val retired = synchronized(lifecycleLock) {
                val old = invalidatePendingWorkLocked()
                service = null
                _slotStates.value = List(16) { 0 }
                setConnectionStateLocked(
                    ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
                    "Shizuku 服务未运行，请在管理器中启动。"
                )
                _engineSnapshot.value = EngineSnapshot(statusDetail = "请先启动 Shizuku 服务。")
                old
            }
            if (retired != null) removeUserServiceSafely(retired)
            return
        }
        if (Shizuku.isPreV11()) {
            val retired = synchronized(lifecycleLock) {
                val old = invalidatePendingWorkLocked()
                service = null
                setConnectionStateLocked(
                    ShizukuConnectionState.ERROR,
                    "请安装 Shizuku v11 或更高版本；不支持旧版本。"
                )
                _engineSnapshot.value = EngineSnapshot(statusDetail = "此应用需要 Shizuku v11 或更高版本。")
                old
            }
            if (retired != null) removeUserServiceSafely(retired)
            return
        }
        if (!hasPermission()) {
            val retired = synchronized(lifecycleLock) {
                if (activePermissionRequestCode != null) {
                    // onResume may run before Shizuku.checkSelfPermission() exposes the grant.
                    // Keep the callback and permission watcher alive during that propagation gap.
                    val verifying = _connectionStatus.value.state == ShizukuConnectionState.VERIFYING_PERMISSION
                    setConnectionStateLocked(
                        if (verifying) ShizukuConnectionState.VERIFYING_PERMISSION
                        else ShizukuConnectionState.REQUESTING_PERMISSION,
                        if (verifying) "已授予 Shizuku 权限，正在等待系统确认…"
                        else "Shizuku izin penceresinde seçiminiz bekleniyor…"
                    )
                    _engineSnapshot.value = _engineSnapshot.value.copy(
                        statusDetail = "正在等待 Shizuku 权限，并重新检查授权状态…"
                    )
                    null
                } else {
                    val old = invalidatePendingWorkLocked()
                    service = null
                    _slotStates.value = List(16) { 0 }
                    setConnectionStateLocked(
                        ShizukuConnectionState.NO_PERMISSION,
                        "Shizuku çalışıyor. Devam etmek için bu uygulamaya izin verin."
                    )
                    _engineSnapshot.value = EngineSnapshot(
                        statusDetail = "Shizuku 正在运行。请授予此应用 Shizuku 权限后重试。"
                    )
                    old
                }
            }
            if (retired != null) removeUserServiceSafely(retired)
            return
        }
        clearPendingPermissionRequestAfterGrant()
        val connected = synchronized(lifecycleLock) {
            val live = service?.asBinder()?.let { runCatching { it.pingBinder() }.getOrDefault(false) } == true
            if (live) setConnectionStateLocked(ShizukuConnectionState.CONNECTED, "Shizuku 服务已连接并就绪。")
            live
        }
        if (connected) {
            refreshCapabilitiesAsync()
        } else {
            scheduleUserServiceBind()
        }
    }

    /** A visible grant wins over the callback/poll and prevents stale timeout state. */
    private fun clearPendingPermissionRequestAfterGrant() {
        synchronized(lifecycleLock) {
            if (activePermissionRequestCode == null) return
            activePermissionRequestCode = null
            permissionWatchJob?.cancel()
            permissionWatchJob = null
        }
    }

    fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                evaluateShizukuState()
                return
            }
            if (Shizuku.isPreV11()) {
                synchronized(lifecycleLock) {
                    setConnectionStateLocked(
                        ShizukuConnectionState.ERROR,
                        "请安装 Shizuku v11 或更高版本；不支持旧版本。"
                    )
                    _engineSnapshot.value = _engineSnapshot.value.copy(
                        statusDetail = "请安装 Shizuku v11 或更高版本；不支持旧版本。"
                    )
                }
                return
            }
            if (hasPermission()) {
                clearPendingPermissionRequestAfterGrant()
                autoBindRetries = 0
                scheduleUserServiceBind()
                return
            }
            val requestCode = synchronized(lifecycleLock) {
                if (activePermissionRequestCode != null) return
                val nextCode = nextPermissionRequestCode
                nextPermissionRequestCode = if (nextCode == Int.MAX_VALUE) REQUEST_CODE_SHIZUKU else nextCode + 1
                activePermissionRequestCode = nextCode
                setConnectionStateLocked(
                    ShizukuConnectionState.REQUESTING_PERMISSION,
                    "Shizuku izin penceresinde seçiminiz bekleniyor…"
                )
                _engineSnapshot.value = _engineSnapshot.value.copy(statusDetail = "正在等待 Shizuku 授权…")
                nextCode
            }
            Shizuku.requestPermission(requestCode)
            startPermissionWatch(requestCode)
        } catch (t: Throwable) {
            synchronized(lifecycleLock) {
                activePermissionRequestCode = null
                permissionWatchJob?.cancel()
                permissionWatchJob = null
                setConnectionStateLocked(
                    ShizukuConnectionState.ERROR,
                    "无法请求 Shizuku 权限：${t.message ?: t.javaClass.simpleName}"
                )
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "无法请求 Shizuku 权限：${t.message ?: t.javaClass.simpleName}"
                )
            }
            Log.e(TAG, "Shizuku permission request failed.", t)
        }
    }

    /** Idempotent action for the connection card; it never restarts work already in flight. */
    fun connectOrRecover() {
        when (_connectionStatus.value.state) {
            ShizukuConnectionState.NO_PERMISSION,
            ShizukuConnectionState.NEEDS_SHIZUKU_AUTH -> scope.launch { requestPermission() }
            ShizukuConnectionState.ERROR -> scope.launch { forceRebind() }
            ShizukuConnectionState.SHIZUKU_NOT_RUNNING -> evaluateShizukuState()
            ShizukuConnectionState.REQUESTING_PERMISSION,
            ShizukuConnectionState.VERIFYING_PERMISSION,
            ShizukuConnectionState.BINDING,
            ShizukuConnectionState.INITIALIZING,
            ShizukuConnectionState.CONNECTED -> Unit
        }
    }

    /** Starts one uniquely identified Shizuku UserService binding attempt. */
    fun bindUserService(forceFreshService: Boolean = false) = startUserServiceBind(forceFreshService)

    private fun startUserServiceBind(forceFreshService: Boolean) {
        val ctx = appContext ?: return
        if (!runCatching { Shizuku.pingBinder() && hasPermission() }.getOrDefault(false)) {
            evaluateShizukuState()
            return
        }
        val session = synchronized(lifecycleLock) {
            if (currentSession != null || pendingBindGeneration != null) return
            if (!forceFreshService && service?.asBinder()?.let { runCatching { it.pingBinder() }.getOrDefault(false) } == true) {
                setConnectionStateLocked(ShizukuConnectionState.CONNECTED, "Shizuku 服务已连接并就绪。")
                return
            }
            if (forceFreshService) {
                userServiceVersionCode = if (userServiceVersionCode == Int.MAX_VALUE) BuildConfig.VERSION_CODE else userServiceVersionCode + 1
            }
            val generation = generations.issue()
            val args = buildUserServiceArgs(ctx, userServiceVersionCode)
            val attempt = BindSession(generation, args, newServiceConnection(generation))
            currentSession = attempt
            setConnectionStateLocked(
                ShizukuConnectionState.BINDING,
                "正在启动 Shizuku UserService…"
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "正在启动 Shizuku UserService…",
                bindRetryCount = autoBindRetries
            )
            attempt
        }
        // Start the timeout before entering Shizuku's synchronous Binder call. Some
        // OEM/server failures can stall bindUserService itself, before it returns.
        val timeout = scope.launch {
            delay(USER_SERVICE_BIND_TIMEOUT_MS)
            onBindTimeout(session.generation)
        }
        synchronized(lifecycleLock) {
            if (currentSession?.generation == session.generation && !session.connected) {
                session.timeoutJob = timeout
            } else {
                timeout.cancel()
            }
        }
        try {
            Log.i(TAG, "Binding UserService generation=${session.generation}, version=$userServiceVersionCode, forceFresh=$forceFreshService")
            synchronized(shizukuApiLock) {
                val stillCurrent = synchronized(lifecycleLock) {
                    currentSession?.generation == session.generation
                }
                if (stillCurrent) Shizuku.bindUserService(session.args, session.connection)
            }
        } catch (t: Throwable) {
            timeout.cancel()
            Log.e(TAG, "bindUserService failed for generation=${session.generation}", t)
            handleSessionFailure(session.generation, "Shizuku UserService 绑定失败：${t.message ?: t.javaClass.simpleName}")
            return
        }
    }

    fun unbindUserService() {
        val retired = synchronized(lifecycleLock) {
            pendingBindJob?.cancel()
            pendingBindJob = null
            pendingBindGeneration = null
            generations.invalidate()
            val old = currentSession
            currentSession = null
            old?.timeoutJob?.cancel()
            service = null
            _slotStates.value = List(16) { 0 }
            val nextState = if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                ShizukuConnectionState.SHIZUKU_NOT_RUNNING to "Shizuku hizmeti çalışmıyor."
            } else if (!hasPermission()) {
                ShizukuConnectionState.NO_PERMISSION to "Shizuku izni gerekiyor."
            } else {
                ShizukuConnectionState.ERROR to "Shizuku bağlantısı kapatıldı. Yeniden bağlanmak için dokunun."
            }
            setConnectionStateLocked(nextState.first, nextState.second)
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Shizuku UserService 连接已关闭。")
            old
        }
        if (retired != null) removeUserServiceSafely(retired)
    }

    fun isServiceConnected(): Boolean = service?.asBinder()?.let { runCatching { it.pingBinder() }.getOrDefault(false) } == true

    fun refreshCapabilitiesAsync() {
        val ctx = appContext ?: return
        val svc = service ?: return
        val generation = synchronized(lifecycleLock) { currentSession?.generation ?: return }
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

                val snapshot = EngineSnapshot(
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
                if (isCurrentSession(generation) && service === svc) {
                    _engineSnapshot.value = snapshot
                }
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
        val generation = synchronized(lifecycleLock) {
            currentSession?.takeIf { service === svc }?.generation
        } ?: return
        scope.launch {
            try {
                val arr = svc.slotStates
                if (arr != null && arr.size >= 16 && isCurrentSession(generation)) {
                    synchronized(lifecycleLock) {
                        if (currentSession?.generation == generation && service === svc) {
                            _slotStates.value = arr.take(16)
                        }
                    }
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
