package com.zerointerrupt.clicker.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.automation.AutomationReceiver
import com.zerointerrupt.clicker.automation.LocalRpcServer
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.shizuku.TouchMuxUserService
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.CheckSwitch
import com.zerointerrupt.clicker.ui.components.ExpressiveCard
import com.zerointerrupt.clicker.ui.components.ExpressiveConnectedGroup
import com.zerointerrupt.clicker.ui.components.ExpressiveOption
import com.zerointerrupt.clicker.ui.components.PageTopHeader
import com.zerointerrupt.clicker.ui.components.SlotMatrixStrip

@Composable
fun LabTab(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var versionTapCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTopHeader(title = "实验室")

        ExpressiveCard {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "外观与控制",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "应用外观",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ExpressiveConnectedGroup(
                        options = listOf(
                            ExpressiveOption(ClickGlobalSettings.THEME_SYSTEM, "跟随系统"),
                            ExpressiveOption(ClickGlobalSettings.THEME_DARK, "深色"),
                            ExpressiveOption(ClickGlobalSettings.THEME_LIGHT, "浅色")
                        ),
                        selectedValue = state.themeMode,
                        onSelect = callbacks.onThemeModeChange,
                        height = 42.dp
                    )
                }

                LabSwitchRow(
                    title = "动态取色",
                    checked = state.dynamicColor,
                    onCheckedChange = callbacks.onDynamicColorChange
                )

                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "桌面图标",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ExpressiveConnectedGroup(
                        options = listOf(
                            ExpressiveOption(ClickGlobalSettings.LAUNCHER_SYSTEM, "跟随系统"),
                            ExpressiveOption(ClickGlobalSettings.LAUNCHER_DARK, "深色"),
                            ExpressiveOption(ClickGlobalSettings.LAUNCHER_LIGHT, "浅色")
                        ),
                        selectedValue = state.launcherIconMode,
                        onSelect = callbacks.onLauncherIconModeChange,
                        height = 42.dp
                    )
                    LabSwitchRow(
                        title = "桌面图标动态取色",
                        checked = state.launcherDynamicIcon,
                        onCheckedChange = callbacks.onLauncherDynamicIconChange
                    )
                    Text(
                        text = "向系统桌面提供 Monochrome 单色图层（需 Android 13+ 且桌面支持/开启主题图标配色）",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

                LabSwitchRow(
                    title = "音量减键控制",
                    checked = state.volumeKeyToggleEnabled,
                    onCheckedChange = callbacks.onVolumeKeyToggleChange
                )

                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "状态栏磁贴",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    )
                    FilledTonalButton(
                        onClick = callbacks.onRequestAddQuickTile,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tile),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("添加", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        ExpressiveCard(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = !state.proModeEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val next = versionTapCount + 1
                    if (next >= 7) {
                        versionTapCount = 0
                        callbacks.onProModeChange(true)
                    } else {
                        versionTapCount = next
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lab),
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = if (state.proModeEnabled) {
                                "版本 v${state.appVersionName} · 专业模式"
                            } else {
                                "版本 v${state.appVersionName}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!state.proModeEnabled && versionTapCount in 1..6) {
                            Text(
                                text = "还差 ${7 - versionTapCount} 次",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.primary
                            )
                        }
                    }
                }

                if (state.proModeEnabled) {
                    CheckSwitch(
                        checked = true,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                versionTapCount = 0
                                callbacks.onProModeChange(false)
                            }
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.proModeEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProHardwareConsoleCard(state = state, callbacks = callbacks)
                ProRpcBridgeCard(state = state, callbacks = callbacks)
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ProHardwareConsoleCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val snap = state.engineSnapshot

    ExpressiveCard(
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "硬件监控",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = callbacks.onRefreshCapabilities,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新硬件探测",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DiagLine("Shizuku 状态", "${state.connectionState} · ${modeName(snap.engineMode)}")
                    DiagLine("Shizuku 连接详情", snap.statusDetail)
                    DiagLine("UID / PID", "${snap.uid} / ${snap.pid}")
                    DiagLine("物理触控节点", snap.touchDeviceInfo.ifBlank { "未发现可读节点" })
                    DiagLine("Abs 量程", snap.absRangeText.ifBlank { "未初始化" })
                    DiagLine(
                        "/dev/uinput",
                        "${if (snap.uinputWritable) "RW 可用" else "受限"} (errno=${snap.uinputErrno})"
                    )
                    DiagLine(
                        "/dev/uhid",
                        "${if (snap.uhidWritable) "RW 可用" else "受限"} (errno=${snap.uhidErrno})"
                    )
                    DiagLine(
                        "/dev/input/*",
                        "${if (snap.touchDeviceReadable) "可读" else "不可读"} (errno=${snap.touchErrno})"
                    )
                    DiagLine("引擎详情", snap.statusDetail)
                    if (snap.nativeProbeError.isNotBlank()) {
                        Text(
                            text = "Native 错误: ${snap.nativeProbeError}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.error,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callbacks.onCleanupOrphans()
                    },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("强制清理孤儿进程", maxLines = 1, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callbacks.onReleaseNative()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("释放独占", maxLines = 1, fontSize = 12.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "注入后端",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                ExpressiveConnectedGroup(
                    options = listOf(
                        ExpressiveOption(ClickGlobalSettings.BACKEND_AUTO, "Auto"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_UINPUT, "UInput"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_UHID, "UHID"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_HYBRID, "Hybrid"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_FALLBACK, "降级")
                    ),
                    selectedValue = state.preferredBackend,
                    onSelect = callbacks.onPreferredBackendChange,
                    height = 40.dp
                )
            }

            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

            SlotMatrixStrip(slots = state.slotStates)
        }
    }
}

@Composable
private fun ProRpcBridgeCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks
) {
    val cs = MaterialTheme.colorScheme
    val tokenDisplay = state.rpcToken.ifBlank { "<未生成>" }

    val adbCommand = remember(tokenDisplay) {
        "adb shell am broadcast -p com.zerointerrupt.clicker -a ${AutomationReceiver.ACTION_RPC} " +
            "--es token \"$tokenDisplay\" --es cmd \"TAP\" --ef x 540 --ef y 1200 --el duration_ms 30"
    }

    val pythonExample = remember(tokenDisplay) {
        """
        # 1. 先执行: adb forward tcp:${LocalRpcServer.RPC_PORT} tcp:${LocalRpcServer.RPC_PORT}
        import json, socket

        TOKEN = "$tokenDisplay"
        with socket.create_connection(("127.0.0.1", ${LocalRpcServer.RPC_PORT}), timeout=3) as s:
            def rpc(payload):
                payload["token"] = TOKEN
                s.sendall((json.dumps(payload) + "\n").encode())
                return json.loads(s.makefile().readline())

            print(rpc({"cmd": "ping"}))
            print(rpc({"cmd": "status"}))
            print(rpc({"cmd": "tap", "x": 540, "y": 1200, "durationMs": 30}))
            print(rpc({"cmd": "swipe", "x1": 300, "y1": 1400, "x2": 300, "y2": 600, "durationMs": 280}))
        """.trimIndent()
    }

    ExpressiveCard(
        border = BorderStroke(1.dp, cs.tertiary.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RPC 接口桥",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.rpcServerRunning) {
                            "127.0.0.1:${LocalRpcServer.RPC_PORT}"
                        } else {
                            "未开启"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.rpcServerRunning) cs.primary else cs.onSurfaceVariant
                    )
                }
                CheckSwitch(
                    checked = state.rpcBridgeEnabled,
                    onCheckedChange = callbacks.onRpcBridgeEnabledChange
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Access Token",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                    Text(
                        text = tokenDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { callbacks.onCopyText("Access Token", tokenDisplay) },
                            enabled = state.rpcToken.isNotBlank(),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("复制 Token", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = callbacks.onResetRpcToken,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("重置 Token", fontSize = 12.sp)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "接口 A · ADB Broadcast (START/STOP/SWITCH_PRESET/TAP/SWIPE)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cs.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                OutlinedButton(
                    onClick = { callbacks.onCopyText("ADB 广播命令", adbCommand) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("复制 ADB 广播调试命令")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "接口 B · 127.0.0.1:${LocalRpcServer.RPC_PORT} 按行 JSON-RPC",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "协议指令: ping / status / tap / swipe / hold_down / hold_up / start / stop / switch_preset",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = { callbacks.onCopyText("Python RPC 示例", pythonExample) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("一键复制 Python 调用示例")
                }
            }
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LabSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        CheckSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun modeName(mode: Int): String = when (mode) {
    TouchMuxUserService.MODE_NATIVE_UINPUT -> "Tier1 UInput"
    TouchMuxUserService.MODE_NATIVE_UHID -> "Tier2 UHID"
    TouchMuxUserService.MODE_HYBRID_EVDEV_INJECT -> "Tier3 Hybrid"
    TouchMuxUserService.MODE_SHIZUKU_FALLBACK -> "Tier4 Fallback"
    else -> "Idle"
}
