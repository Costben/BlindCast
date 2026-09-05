package com.erl.blindcast.ui.screen.home

import androidx.compose.runtime.Immutable
import com.erl.blindcast.ui.util.LatestVersionInfo

@Immutable
data class HomeUiState(
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentAppVersionCode: Long,
    val systemInfo: SystemInfo,
    // Slice 6.1 全要素绑定（默认值为停止态快照，ViewModel 定时刷新覆盖）。
    val service: ServiceCardState = ServiceCardState(),
    val lan: LanState = LanState(),
    val hw: HwState = HwState(),
    // Fix-Home-1：Hero 三态判定收敛（permissionGranted + service.isRunning 双字段）。
    // permissionGranted 对应 PermissionState.requiredGranted，由 HomeScreen 同步进 ViewModel。
    // Fix-Home-2：ViewModel 3s 轮询直读自愈为准；missingPermissions 为缺项中文名清单，供红卡自报。
    val permissionGranted: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    // Priv-Bridge-1：最近一次快捷操作（熄屏/点亮）失败文案（成功不写）；seq 自增保证相同文案重复触发 Toast。
    val actionError: String? = null,
    val actionErrorSeq: Int = 0,
)

/** Hero 大卡片：串流总服务实时快照。 */
@Immutable
data class ServiceCardState(
    val isRunning: Boolean = false,
    val port: Int = 8888,
    val fps: Int = -1,
    val bitrateMbps: Double = -1.0,
    val clients: Int = 0,
    val tokenProtected: Boolean = false,
    val blackedOut: Boolean = false,
    val lanIp: String = "",
)

/** 局域网访问卡片：完整 Web 地址（含 Token）与二维码内容。 */
@Immutable
data class LanState(
    val ip: String = "",
    val port: Int = 8888,
    val url: String = "",
    val urlWithToken: String = "",
    val hasIp: Boolean = false,
)

/** 硬件监控卡片：电池 / 温度 / 内存 / WiFi（-1 / NaN = 暂无读数）。 */
@Immutable
data class HwState(
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val temperatureC: Float = Float.NaN,
    val availRamMb: Long = -1L,
    val totalRamMb: Long = -1L,
    val wifiLinkMbps: Int = -1,
)

@Immutable
data class HomeActions(
    val onPermissionsClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    // Slice 6.1 快捷操作（由 HomeScreen 接入 HomeViewModel / 剪贴板）。
    val onToggleService: (Boolean) -> Unit = {},
    val onBlackout: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onCopyLanUrl: (String) -> Unit = {},
)
