package com.erl.blindcast.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.erl.blindcast.R
import com.erl.blindcast.ui.component.material.TonalCard

@Composable
fun HomePagerMaterial(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = { TopBar(scrollBehavior = scrollBehavior) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Fix-Home-1：Home 只保留 4 卡（Hero 三态 / 快捷操作 / 局域网二维码 / 硬件监控）。
            HeroCard(
                service = state.service,
                permissionGranted = state.permissionGranted,
                missingPermissions = state.missingPermissions,
                onPermissionsClick = actions.onPermissionsClick,
            )
            ActionsCard(
                service = state.service,
                privResult = state.privResult,
                onBlackout = actions.onBlackout,
                onRestore = actions.onRestore,
                onToggleService = actions.onToggleService,
            )
            LanCard(
                lan = state.lan,
                onCopyLanUrl = actions.onCopyLanUrl,
                onOpenUrl = actions.onOpenUrl,
            )
            HwCard(hw = state.hw)
            Spacer(Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun HeroCard(
    service: ServiceCardState,
    permissionGranted: Boolean,
    missingPermissions: List<String> = emptyList(),
    onPermissionsClick: () -> Unit,
) {
    // Fix-Home-1 三态：未授权=红（可点跳授权）/ 已授权未运行=灰 / 运行中=绿。
    val running = service.isRunning
    val needPermission = !permissionGranted
    val container = when {
        needPermission -> MaterialTheme.colorScheme.errorContainer
        running -> Color(0xFFDFFAE4)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when {
        needPermission -> MaterialTheme.colorScheme.onErrorContainer
        running -> Color(0xFF111111)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when {
        needPermission -> stringResource(R.string.blindcast_home_need_permission_title)
        running -> stringResource(R.string.blindcast_home_running_title)
        else -> stringResource(R.string.blindcast_home_stopped_title)
    }
    val baseNeedPermissionSubtitle = stringResource(R.string.blindcast_home_need_permission_subtitle)
    val missingText = if (missingPermissions.isNotEmpty()) {
        stringResource(R.string.blindcast_home_need_permission_missing, missingPermissions.joinToString("、"))
    } else {
        ""
    }
    val subtitle = when {
        needPermission ->
            if (missingText.isNotBlank()) "$baseNeedPermissionSubtitle · $missingText" else baseNeedPermissionSubtitle
        running && service.lanIp.isNotBlank() -> "http://${service.lanIp}:${service.port}"
        else -> stringResource(R.string.blindcast_home_stopped_subtitle)
    }
    val tokenStr = stringResource(
        if (service.tokenProtected) R.string.blindcast_home_token_on
        else R.string.blindcast_home_token_off,
    )
    val stats = if (running && service.fps >= 0 && service.bitrateMbps >= 0) {
        stringResource(
            R.string.blindcast_home_stats,
            service.fps,
            service.bitrateMbps,
            service.clients,
            tokenStr,
        )
    } else {
        stringResource(R.string.blindcast_home_stats_idle, service.clients, tokenStr)
    }
    val heroContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = onContainer)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = onContainer)
            Spacer(Modifier.height(4.dp))
            Text(text = stats, style = MaterialTheme.typography.bodySmall, color = onContainer)
        }
    }
    if (needPermission) {
        TonalCard(containerColor = container, onClick = onPermissionsClick, content = heroContent)
    } else {
        TonalCard(containerColor = container, content = heroContent)
    }
}

@Composable
private fun ActionsCard(
    service: ServiceCardState,
    privResult: String? = null,
    onBlackout: () -> Unit,
    onRestore: () -> Unit,
    onToggleService: (Boolean) -> Unit,
) {
    val screenSummary = if (service.blackedOut) {
        stringResource(R.string.blindcast_home_screen_off)
    } else {
        stringResource(R.string.blindcast_home_screen_on)
    }
    // 特权操作结果行已隐藏（排障时看 logcat；状态仍在 HomeUiState.privResult 保留）。
    TonalCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.blindcast_home_service_switch),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.blindcast_home_service_switch_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(checked = service.isRunning, onCheckedChange = onToggleService)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onBlackout, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.blindcast_home_action_start))
                }
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.blindcast_home_action_stop))
                }
            }
            Text(
                text = "${stringResource(R.string.blindcast_home_blackout_summary)} · $screenSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun LanCard(
    lan: LanState,
    onCopyLanUrl: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    TonalCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.blindcast_home_lan_url_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = lan.urlWithToken.ifBlank {
                    stringResource(R.string.blindcast_home_no_ip)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onCopyLanUrl(lan.urlWithToken) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.blindcast_home_copy_link))
                }
                OutlinedButton(
                    onClick = { if (lan.url.isNotBlank()) onOpenUrl(lan.urlWithToken.ifBlank { lan.url }) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.blindcast_home_open_browser))
                }
            }
            if (lan.hasIp && lan.urlWithToken.isNotBlank()) {
                QrCodeImage(
                    content = lan.urlWithToken,
                    fallback = {
                        Text(
                            text = stringResource(R.string.blindcast_home_qr_title),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.blindcast_home_qr_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun HwCard(hw: HwState) {
    val unknown = stringResource(R.string.blindcast_home_hw_unknown)
    val batteryText = if (hw.batteryPercent < 0) {
        unknown
    } else {
        val chargeStr = stringResource(
            if (hw.charging) R.string.blindcast_home_charging
            else R.string.blindcast_home_not_charging,
        )
        "${hw.batteryPercent}% · $chargeStr"
    }
    val tempText = if (hw.temperatureC.isNaN()) unknown else "%.1f°C".format(hw.temperatureC)
    val ramText = if (hw.availRamMb < 0 || hw.totalRamMb < 0) {
        unknown
    } else {
        stringResource(R.string.blindcast_home_hw_ram_value, hw.availRamMb, hw.totalRamMb)
    }
    val wifiText = if (hw.wifiLinkMbps < 0) unknown else "${hw.wifiLinkMbps} Mbps"
    TonalCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HwRow(
                label = stringResource(R.string.blindcast_home_hw_battery),
                value = batteryText,
            )
            HwRow(label = stringResource(R.string.blindcast_home_hw_temp), value = tempText)
            HwRow(label = stringResource(R.string.blindcast_home_hw_ram), value = ramText)
            HwRow(label = stringResource(R.string.blindcast_home_hw_wifi), value = wifiText)
        }
    }
}

@Composable
private fun HwRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}


