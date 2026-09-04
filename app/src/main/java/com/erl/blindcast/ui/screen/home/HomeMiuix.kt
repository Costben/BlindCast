package com.erl.blindcast.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erl.blindcast.R
import com.erl.blindcast.permission.PermissionState
import com.erl.blindcast.ui.component.miuix.WarningCard
import com.erl.blindcast.ui.theme.LocalEnableBlur
import com.erl.blindcast.ui.util.BlurredBar
import com.erl.blindcast.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    permissionState: PermissionState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Slice 6.1: Hero 运行双态 + 快捷操作 + 局域网二维码 + 硬件监控（全部绑定真实状态）。
                        BlindCastHeroCard(service = state.service)
                        BlindCastActionsCard(
                            service = state.service,
                            onBlackout = actions.onBlackout,
                            onRestore = actions.onRestore,
                            onToggleService = actions.onToggleService,
                        )
                        BlindCastLanCard(
                            lan = state.lan,
                            onCopyLanUrl = actions.onCopyLanUrl,
                            onOpenUrl = actions.onOpenUrl,
                        )
                        BlindCastHwCard(hw = state.hw)
                        // Keep the theme settings preview in sync whenever this home layout changes.
                        WarningCard(stringResource(R.string.home_sample_notification))
                        PermissionCardMiuix(permissionState, actions.onPermissionsClick)
                        InfoCard(systemInfo = state.systemInfo)
                        ExampleLinkCard(onOpenUrl = actions.onOpenUrl)
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

@Composable
private fun BlindCastHeroCard(service: ServiceCardState) {
    val running = service.isRunning
    val containerColor = if (running) Color(0xFFDFFAE4) else Color(0xFFE8E8E8)
    val textColor = Color(0xFF111111)
    val iconColor = if (running) Color(0xFF36D167) else Color(0xFF9E9E9E)
    val title = if (running) {
        stringResource(R.string.blindcast_home_running_title)
    } else {
        stringResource(R.string.blindcast_home_stopped_title)
    }
    val subtitle = if (running && service.lanIp.isNotBlank()) {
        "http://${service.lanIp}:${service.port}"
    } else {
        stringResource(R.string.blindcast_home_stopped_subtitle)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = containerColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(164.dp)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 70.dp, y = 44.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(182.dp),
                    imageVector =
                        if (running) Icons.Rounded.CheckCircleOutline else Icons.Rounded.Cancel,
                    tint = iconColor,
                    contentDescription = null,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, top = 28.dp, end = 148.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                    )
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor.copy(alpha = 0.72f),
                    )
                }
                Text(
                    text = stats,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun BlindCastActionsCard(
    service: ServiceCardState,
    onBlackout: () -> Unit,
    onRestore: () -> Unit,
    onToggleService: (Boolean) -> Unit,
) {
    val screenSummary = if (service.blackedOut) {
        stringResource(R.string.blindcast_home_screen_off)
    } else {
        stringResource(R.string.blindcast_home_screen_on)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.blindcast_home_action_start),
            summary = stringResource(R.string.blindcast_home_blackout_summary),
            onClick = onBlackout,
        )
        BasicComponent(
            title = stringResource(R.string.blindcast_home_action_stop),
            summary = "${stringResource(R.string.blindcast_home_restore_summary)} · $screenSummary",
            onClick = onRestore,
        )
        SwitchPreference(
            title = stringResource(R.string.blindcast_home_service_switch),
            summary = stringResource(R.string.blindcast_home_service_switch_summary),
            checked = service.isRunning,
            onCheckedChange = onToggleService,
        )
    }
}

@Composable
private fun BlindCastLanCard(
    lan: LanState,
    onCopyLanUrl: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicComponent(
                title = stringResource(R.string.blindcast_home_lan_url_title),
                summary = lan.urlWithToken.ifBlank {
                    stringResource(R.string.blindcast_home_no_ip)
                },
                onClick = {},
            )
            BasicComponent(
                title = stringResource(R.string.blindcast_home_copy_link),
                summary = lan.urlWithToken.ifBlank {
                    stringResource(R.string.blindcast_home_no_ip)
                },
                onClick = { onCopyLanUrl(lan.urlWithToken) },
            )
            BasicComponent(
                title = stringResource(R.string.blindcast_home_open_browser),
                summary = lan.url.ifBlank {
                    stringResource(R.string.blindcast_home_no_ip)
                },
                onClick = { if (lan.url.isNotBlank()) onOpenUrl(lan.urlWithToken.ifBlank { lan.url }) },
            )
            if (lan.hasIp && lan.urlWithToken.isNotBlank()) {
                QrCodeImage(
                    content = lan.urlWithToken,
                    modifier = Modifier.padding(top = 12.dp),
                    fallback = {
                        Text(
                            text = stringResource(R.string.blindcast_home_qr_title),
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.blindcast_home_qr_title),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.blindcast_home_no_ip),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BlindCastHwCard(hw: HwState) {
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
    val tempText = if (hw.temperatureC.isNaN()) {
        unknown
    } else {
        "%.1f°C".format(hw.temperatureC)
    }
    val ramText = if (hw.availRamMb < 0 || hw.totalRamMb < 0) {
        unknown
    } else {
        stringResource(R.string.blindcast_home_hw_ram_value, hw.availRamMb, hw.totalRamMb)
    }
    val wifiText = if (hw.wifiLinkMbps < 0) unknown else "${hw.wifiLinkMbps} Mbps"
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.blindcast_home_hw_battery),
            summary = batteryText,
            onClick = {},
        )
        BasicComponent(
            title = stringResource(R.string.blindcast_home_hw_temp),
            summary = tempText,
            onClick = {},
        )
        BasicComponent(
            title = stringResource(R.string.blindcast_home_hw_ram),
            summary = ramText,
            onClick = {},
        )
        BasicComponent(
            title = stringResource(R.string.blindcast_home_hw_wifi),
            summary = wifiText,
            onClick = {},
        )
    }
}

@Composable
private fun PermissionCardMiuix(
    state: PermissionState,
    onClick: () -> Unit,
) {
    val requiredGranted = state.requiredGranted
    val iconColor = if (requiredGranted) Color(0xFF36D167) else Color(0xFFF72727)
    val containerColor = if (requiredGranted) Color(0xFFDFFAE4) else Color(0xFFF8E2E2)
    val textColor = Color(0xFF111111)
    val summary =
        if (requiredGranted) {
            stringResource(R.string.permission_ready)
        } else {
            stringResource(R.string.permission_missing)
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = containerColor),
        onClick = onClick,
        showIndication = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(164.dp)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 70.dp, y = 44.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(182.dp),
                    imageVector =
                        if (requiredGranted) Icons.Rounded.CheckCircleOutline else Icons.Rounded.Cancel,
                    tint = iconColor,
                    contentDescription = null,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, top = 28.dp, end = 148.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text =
                            if (requiredGranted) {
                                stringResource(R.string.permission_status_ready_title)
                            } else {
                                stringResource(R.string.permission_status_missing_title)
                            },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                    )
                    Text(
                        text = summary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor.copy(alpha = 0.72f),
                    )
                }
                Text(
                    text =
                        if (requiredGranted) {
                            stringResource(R.string.permission_granted)
                        } else {
                            stringResource(R.string.permission_action_required)
                        },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.app_name),
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
private fun ExampleLinkCard(
    onOpenUrl: (String) -> Unit,
) {
    val url = stringResource(R.string.home_example_link_url)
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.home_example_link_title),
            summary = stringResource(R.string.home_example_link_subtitle),
            endActions = {
                Icon(
                    imageVector = MiuixIcons.Link,
                    tint = colorScheme.onSurface,
                    contentDescription = null
                )
            },
            onClick = { onOpenUrl(url) }
        )
    }
}

@Composable
private fun InfoCard(systemInfo: SystemInfo) {
    @Composable
    fun InfoText(
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface
        )
        Text(
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding)
        )
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            InfoText(
                title = stringResource(R.string.home_app_version),
                content = systemInfo.appVersion,
                bottomPadding = 0.dp
            )
        }
    }
}
