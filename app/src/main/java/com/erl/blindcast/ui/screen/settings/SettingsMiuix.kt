package com.erl.blindcast.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erl.blindcast.R
import com.erl.blindcast.ui.UiMode
import com.erl.blindcast.ui.component.dialog.rememberLoadingDialog
import com.erl.blindcast.ui.component.miuix.SendLogDialog
import com.erl.blindcast.ui.component.miuix.SuperEditArrow
import com.erl.blindcast.ui.theme.LocalEnableBlur
import com.erl.blindcast.ui.util.BlurredBar
import com.erl.blindcast.ui.util.rememberBlurBackdrop
import com.erl.blindcast.ui.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Slice 6.2 设置页 Miuix：画质 / 安全 / scrcpy / 保活 / 外观五卡全量绑定。
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val loadingDialog = rememberLoadingDialog()
    val showSendLogDialog = rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.settings),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
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
                    QualityCard(uiState, actions)
                    SecurityCard(uiState, actions)
                    ScrcpyCard(uiState, actions)
                    BlackoutCard(uiState, actions)
                    AppearanceCard(uiState, actions)

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_check_update),
                            summary = stringResource(id = R.string.settings_check_update_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Update,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_check_update),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate
                        )
                    }

                    Card(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.send_log),
                            startAction = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.send_log),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showSendLogDialog.value = true },
                        )
                        SendLogDialog(
                            show = showSendLogDialog.value,
                            onDismissRequest = { showSendLogDialog.value = false },
                            loadingDialog = loadingDialog
                        )
                        val about = stringResource(id = R.string.about)
                        ArrowPreference(
                            title = about,
                            startAction = {
                                Icon(
                                    Icons.Rounded.ContactPage,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = about,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenAbout,
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

@Composable
private fun QualityCard(uiState: SettingsUiState, actions: SettingsScreenActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_resolution),
            summary = stringResource(R.string.settings_resolution_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.DesktopWindows,
                    stringResource(R.string.settings_resolution)
                )
            },
            items = SettingsViewModel.RESOLUTIONS,
            selectedIndex = SettingsViewModel.RESOLUTIONS.indexOf(uiState.videoResolution).coerceAtLeast(0),
            onSelectedIndexChange = actions.onSetResolutionIndex,
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_fps),
            summary = stringResource(R.string.settings_fps_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.Speed,
                    stringResource(R.string.settings_fps)
                )
            },
            items = SettingsViewModel.FPS_OPTIONS.map { "$it FPS" },
            selectedIndex = SettingsViewModel.FPS_OPTIONS.indexOf(uiState.videoFps).coerceAtLeast(0),
            onSelectedIndexChange = actions.onSetFpsIndex,
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_bitrate),
            summary = stringResource(R.string.settings_bitrate_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.DataUsage,
                    stringResource(R.string.settings_bitrate)
                )
            },
            items = SettingsViewModel.BITRATE_MBPS_OPTIONS.map { "$it Mbps" },
            selectedIndex = SettingsViewModel.BITRATE_MBPS_OPTIONS.indexOf(uiState.videoBitrateMbps).coerceAtLeast(0),
            onSelectedIndexChange = actions.onSetBitrateIndex,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_audio),
            summary = stringResource(R.string.settings_audio_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.AutoMirrored.Rounded.VolumeUp,
                    stringResource(R.string.settings_audio)
                )
            },
            checked = uiState.audioEnabled,
            onCheckedChange = actions.onSetAudioEnabled,
        )
    }
}

@Composable
private fun SecurityCard(uiState: SettingsUiState, actions: SettingsScreenActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        TokenEditArrow(
            value = uiState.streamToken,
            icon = Icons.Rounded.VpnKey,
            onConfirm = actions.onSetToken,
        )
        SuperEditArrow(
            title = stringResource(R.string.settings_port),
            defaultValue = uiState.serverPort,
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.SettingsEthernet,
                    stringResource(R.string.settings_port)
                )
            },
            onValueChange = actions.onSetServerPort,
        )
        BasicComponent(
            title = stringResource(R.string.settings_restart_hint),
            summary = stringResource(R.string.settings_token_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.Info,
                    stringResource(R.string.settings_restart_hint)
                )
            },
            onClick = {},
        )
    }
}

@Composable
private fun ScrcpyCard(uiState: SettingsUiState, actions: SettingsScreenActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        SwitchPreference(
            title = stringResource(R.string.settings_touch),
            summary = stringResource(R.string.settings_touch_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.TouchApp,
                    stringResource(R.string.settings_touch)
                )
            },
            checked = uiState.scrcpyTouchEnabled,
            onCheckedChange = actions.onSetTouchEnabled,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_right_back),
            summary = stringResource(R.string.settings_right_back_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.AutoMirrored.Rounded.Undo,
                    stringResource(R.string.settings_right_back)
                )
            },
            checked = uiState.scrcpyRightBackEnabled,
            enabled = uiState.scrcpyTouchEnabled,
            onCheckedChange = actions.onSetRightBackEnabled,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_keyboard),
            summary = stringResource(R.string.settings_keyboard_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.Keyboard,
                    stringResource(R.string.settings_keyboard)
                )
            },
            checked = uiState.scrcpyKeyboardEnabled,
            enabled = uiState.scrcpyTouchEnabled,
            onCheckedChange = actions.onSetKeyboardEnabled,
        )
    }
}

@Composable
private fun BlackoutCard(uiState: SettingsUiState, actions: SettingsScreenActions) {
    val items = listOf(
        stringResource(R.string.settings_blackout_hw),
        stringResource(R.string.settings_blackout_overlay),
    )
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_blackout_mode),
            summary = stringResource(R.string.settings_blackout_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.DarkMode,
                    stringResource(R.string.settings_blackout_mode)
                )
            },
            items = items,
            selectedIndex = if (uiState.blackoutMode == "overlay") 1 else 0,
            onSelectedIndexChange = actions.onSetBlackoutIndex,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_keepalive),
            summary = stringResource(R.string.settings_keepalive_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.AlarmOn,
                    stringResource(R.string.settings_keepalive)
                )
            },
            checked = uiState.keepAliveEnabled,
            onCheckedChange = actions.onSetKeepAlive,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_boot_start),
            summary = stringResource(R.string.settings_boot_start_summary),
            startAction = {
                SettingsLeadingIcon(
                    Icons.Rounded.PowerSettingsNew,
                    stringResource(R.string.settings_boot_start)
                )
            },
            checked = uiState.bootStartEnabled,
            onCheckedChange = actions.onSetBootStart,
        )
    }
}

@Composable
private fun AppearanceCard(uiState: SettingsUiState, actions: SettingsScreenActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        OverlayDropdownPreference(
            title = stringResource(id = R.string.settings_ui_mode),
            summary = stringResource(id = R.string.settings_ui_mode_summary),
            items = UiMode.entries.map { it.name },
            startAction = {
                Icon(
                    Icons.Rounded.Dashboard,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = stringResource(id = R.string.settings_ui_mode),
                    tint = colorScheme.onBackground
                )
            },
            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
            onSelectedIndexChange = actions.onSetUiModeIndex
        )
        ArrowPreference(
            title = stringResource(id = R.string.settings_theme),
            summary = stringResource(id = R.string.settings_theme_summary),
            startAction = {
                Icon(
                    Icons.Rounded.Palette,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = stringResource(id = R.string.settings_theme),
                    tint = colorScheme.onBackground
                )
            },
            onClick = actions.onOpenTheme
        )
    }
}

@Composable
private fun TokenEditArrow(value: String, icon: ImageVector, onConfirm: (String) -> Unit) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val summary = if (value.isBlank()) {
        stringResource(R.string.settings_token_unset)
    } else {
        stringResource(R.string.ha_pass_set)
    }
    ArrowPreference(
        title = stringResource(R.string.settings_token),
        summary = "$summary · ${stringResource(R.string.settings_token_summary)}",
        startAction = {
            SettingsLeadingIcon(icon, stringResource(R.string.settings_token))
        },
        onClick = { showDialog = true },
        holdDownState = showDialog,
    )
    if (showDialog) {
        var draft by rememberSaveable(value, showDialog) { mutableStateOf(value) }
        OverlayDialog(
            show = true,
            title = stringResource(R.string.settings_token),
            onDismissRequest = { showDialog = false },
            content = {
                TextField(
                    modifier = Modifier.padding(bottom = 16.dp),
                    value = draft,
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    onValueChange = { draft = it },
                )
                Text(
                    text = stringResource(R.string.settings_restart_hint),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { showDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.confirm),
                        onClick = {
                            showDialog = false
                            onConfirm(draft.trim())
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            },
        )
    }
}

/** 设置页一级行左侧标准图标（Miuix Icon 容器，不改行高/字号/配色规范）。 */
@Composable
private fun SettingsLeadingIcon(icon: ImageVector, contentDescription: String) {
    Icon(
        icon,
        modifier = Modifier.padding(end = 6.dp),
        contentDescription = contentDescription,
        tint = colorScheme.onBackground,
    )
}
