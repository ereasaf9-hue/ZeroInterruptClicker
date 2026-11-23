package com.zerointerrupt.clicker;

import com.zerointerrupt.clicker.ClickTargetParceled;
import com.zerointerrupt.clicker.ITouchMuxCallback;

interface ITouchMuxService {
    // Keep the existing declaration-order transaction IDs stable. AIDL IDs are zero-based offsets.
    String probeCapabilities(String nativeLibDir) = 0;
    int initNativeEngine(String nativeLibDir, int displayWidth, int displayHeight, int rotation, int preferredBackend) = 1;
    int startSelfCheckGrab(String nativeLibDir, int displayWidth, int displayHeight, int rotation, int timeoutMs, int preferredBackend) = 2;
    boolean confirmSelfCheck() = 3;
    void cancelSelfCheckAndUngrab() = 4;
    int startClicking(
        in List<ClickTargetParceled> targets,
        int displayWidth,
        int displayHeight,
        int rotation,
        boolean allowFallback,
        int executionMode,
        int presetLoopCount,
        float jitterRadiusPx,
        int preferredBackend
    ) = 5;
    void updateTargets(
        in List<ClickTargetParceled> targets,
        int displayWidth,
        int displayHeight,
        int rotation,
        int executionMode,
        int presetLoopCount,
        float jitterRadiusPx
    ) = 6;
    void updateDisplayMetrics(int displayWidth, int displayHeight, int rotation) = 7;
    void stopClicking() = 8;
    void shutdownEngine() = 9;
    int getEngineMode() = 10;
    String getStatusDetail() = 11;
    int cleanupOrphanProcesses() = 12;
    int[] getSlotStates() = 13;
    void setPreferredBackend(int preferredBackend) = 14;
    void setVolumeKeyControlEnabled(boolean enabled) = 15;
    void registerCallback(ITouchMuxCallback callback) = 16;
    void injectRpcTap(float x, float y, long durationMs) = 17;
    void injectRpcSwipe(float x1, float y1, float x2, float y2, long durationMs) = 18;
    void setRpcHold(int holdId, boolean down, float x, float y) = 19;
    boolean startTouchRecording(int displayWidth, int displayHeight, int rotation, int excludeLeft, int excludeTop, int excludeRight, int excludeBottom) = 20;
    void updateRecordingExcludeRect(int excludeLeft, int excludeTop, int excludeRight, int excludeBottom) = 21;
    long[] getTouchRecordingStats() = 22;
    String stopTouchRecording() = 23;
    void cancelTouchRecording() = 24;

    // Shizuku reserves transaction 16777115 for UserService.destroy(); AIDL uses offset 16777114.
    void destroy() = 16777114;
}
