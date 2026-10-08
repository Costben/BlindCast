package com.erl.blindcast.ui.screen.home

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.PackageInfoCompat
import com.erl.blindcast.R
import com.erl.blindcast.core.server.BlindCastServer
import com.erl.blindcast.core.server.auth.PairingManager
import com.erl.blindcast.core.service.BlindCastForegroundService
import java.net.NetworkInterface

@Immutable
data class AppVersion(
    val versionName: String,
    val versionCode: Long
)

@Immutable
data class SystemInfo(
    val appVersion: String,
)

fun getAppVersion(context: Context): AppVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)!!
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return AppVersion(
        versionName = packageInfo.versionName!!,
        versionCode = versionCode
    )
}

/**
 * 读取局域网 IPv4：优先 WifiManager（当前连接），回退枚举网卡首个
 * site-local 非回环 IPv4（兼容有线/USB 网络共享），均无则 null。
 *
 * 首页与配对页共用同一口径：配对二维码/链接必须与「Web 访问地址」指向同一地址，
 * 否则手机里算出来的地址会和用户实际打开的不一致。
 */
fun readLanIp(context: Context): String? {
    runCatching {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val raw = wm?.connectionInfo?.ipAddress ?: 0
        if (raw != 0) {
            return "${raw and 0xFF}.${raw shr 8 and 0xFF}.${raw shr 16 and 0xFF}.${raw shr 24 and 0xFF}"
        }
    }
    runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                val host = addr.hostAddress ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                if (host.contains(':')) continue // 跳过 IPv6。
                if (addr.isSiteLocalAddress) return host
            }
        }
    }
    return null
}

/**
 * 当前生效的 HTTP 端口（首页口径）：服务运行中取实时端口，否则回退偏好值。
 * 停服时实时值为 -1/0，不能直接展示，必须回退偏好里的监听端口。
 */
fun resolveLanPort(context: Context): Int {
    val svc = BlindCastForegroundService.status.value
    if (svc.isRunning) {
        return svc.port.takeIf { it in 1..65535 } ?: BlindCastServer.DEFAULT_PORT
    }
    val prefPort = runCatching {
        context.applicationContext
            .getSharedPreferences(BlindCastForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(BlindCastForegroundService.KEY_PORT, BlindCastServer.DEFAULT_PORT)
    }.getOrDefault(BlindCastServer.DEFAULT_PORT)
    return prefPort.takeIf { it in 1..65535 } ?: BlindCastServer.DEFAULT_PORT
}

/** 剩余时长 `m:ss`（首页入口摘要与配对页倒计时共用；向上取整，避免有效码显示 0:00）。 */
fun formatPairingRemaining(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

/**
 * 首页「配对与设备」入口的副标题：有有效码 → 码 + 剩余时长，无码 → 引导生成。
 * 读的是 [PairingManager] 内存态（非 Compose 状态），随首页既有轮询重组自然刷新。
 */
@Composable
fun pairingEntrySummary(): String {
    val ticket = PairingManager.current()
    return if (ticket != null) {
        stringResource(
            R.string.blindcast_pairing_home_summary,
            ticket.display,
            formatPairingRemaining(ticket.remainingMs()),
        )
    } else {
        stringResource(R.string.blindcast_pairing_home_subtitle)
    }
}
