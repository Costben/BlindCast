package com.erl.blindcast.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.erl.blindcast.R
import com.erl.blindcast.ui.UiMode
import com.erl.blindcast.ui.component.material.SegmentedColumn
import com.erl.blindcast.ui.component.material.SegmentedDropdownItem
import com.erl.blindcast.ui.component.material.SegmentedListItem
import com.erl.blindcast.ui.component.material.SegmentedSwitchItem
import com.erl.blindcast.ui.component.material.SegmentedTextField
import com.erl.blindcast.ui.component.material.SendLogBottomSheet
import com.erl.blindcast.ui.component.material.SnackBarHost
import com.erl.blindcast.ui.viewmodel.SettingsViewModel

/**
 * Slice 6.2 设置页 Material：与 Miuix 同构五卡。
 */
@Composable
fun SettingPagerMaterial(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackBarHost = remember { SnackbarHostState() }
    var showBottomSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(scrollBehavior = scrollBehavior)
        },
        snackbarHost = { SnackBarHost(hostState = snackBarHost, modifier = Modifier.padding(bottom = bottomInnerPadding)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                title = stringResource(R.string.settings_quality_title),
                content = buildList {
                    add {
                        SegmentedDropdownItem(
                            title = stringResource(R.string.settings_resolution),
                            summary = stringResource(R.string.settings_resolution_summary),
                            items = SettingsViewModel.RESOLUTIONS,
                            selectedIndex = SettingsViewModel.RESOLUTIONS.indexOf(uiState.videoResolution).coerceAtLeast(0),
                            onItemSelected = actions.onSetResolutionIndex,
                        )
                    }
                    add {
                        SegmentedDropdownItem(
                            title = stringResource(R.string.settings_fps),
                            summary = stringResource(R.string.settings_fps_summary),
                            items = SettingsViewModel.FPS_OPTIONS.map { "$it FPS" },
                            selectedIndex = SettingsViewModel.FPS_OPTIONS.indexOf(uiState.videoFps).coerceAtLeast(0),
                            onItemSelected = actions.onSetFpsIndex,
                        )
                    }
                    add {
                        SegmentedDropdownItem(
                            title = stringResource(R.string.settings_bitrate),
                            summary = stringResource(R.string.settings_bitrate_summary),
                            items = SettingsViewModel.BITRATE_MBPS_OPTIONS.map { "$it Mbps" },
                            selectedIndex = SettingsViewModel.BITRATE_MBPS_OPTIONS.indexOf(uiState.videoBitrateMbps).coerceAtLeast(0),
                            onItemSelected = actions.onSetBitrateIndex,
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.settings_audio),
                            summary = stringResource(R.string.settings_audio_summary),
                            checked = uiState.audioEnabled,
                            onCheckedChange = actions.onSetAudioEnabled,
                        )
                    }
                }
            )
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                title = stringResource(R.string.settings_security_title),
                content = listOf(
                    {
                        SegmentedTextField(
                            label = stringResource(R.string.settings_token),
                            value = uiState.streamToken,
                            onValueChange = actions.onSetToken,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text(stringResource(R.string.settings_token_unset)) },
                            supportingContent = { Text(stringResource(R.string.settings_restart_hint)) },
                        )
                    },
                    {
                        SegmentedTextField(
                            label = stringResource(R.string.settings_port),
                            value = uiState.serverPort.toString(),
                            onValueChange = { v ->
                                v.trim().toIntOrNull()?.let { actions.onSetServerPort(it) }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingContent = { Text(stringResource(R.string.settings_restart_hint)) },
                        )
                    },
                )
            )
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                title = stringResource(R.string.settings_scrcpy_title),
                content = listOf(
                    {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.settings_touch),
                            summary = stringResource(R.string.settings_touch_summary),
                            checked = uiState.scrcpyTouchEnabled,
                            onCheckedChange = actions.onSetTouchEnabled,
                        )
                    },
                    {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.settings_right_back),
                            summary = stringResource(R.string.settings_right_back_summary),
                            checked = uiState.scrcpyRightBackEnabled,
                            enabled = uiState.scrcpyTouchEnabled,
                            onCheckedChange = actions.onSetRightBackEnabled,
                        )
                    },
                    {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.settings_keyboard),
                            summary = stringResource(R.string.settings_keyboard_summary),
                            checked = uiState.scrcpyKeyboardEnabled,
                            enabled = uiState.scrcpyTouchEnabled,
                            onCheckedChange = actions.onSetKeyboardEnabled,
                        )
                    },
                )
            )
            val blackoutItems = listOf(
                stringResource(R.string.settings_blackout_hw),
                stringResource(R.string.settings_blackout_overlay),
            )
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                title = stringResource(R.string.settings_blackout_title),
                content = listOf(
                    {
                        SegmentedDropdownItem(
                            title = stringResource(R.string.settings_blackout_mode),
                            summary = stringResource(R.string.settings_blackout_summary),
                            items = blackoutItems,
                            selectedIndex = if (uiState.blackoutMode == "overlay") 1 else 0,
                            onItemSelected = actions.onSetBlackoutIndex,
                        )
                    },
                    {
                        SegmentedSwitchItem(
                            title = stringResource(R.string.settings_keepalive),
                            summary = stringResource(R.string.settings_keepalive_summary),
                            checked = uiState.keepAliveEnabled,
                            onCheckedChange = actions.onSetKeepAlive,
                        )
                    },
                )
            )
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                title = stringResource(R.string.settings_appearance_title),
                content = buildList {
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.Update,
                            title = stringResource(id = R.string.settings_check_update),
                            summary = stringResource(id = R.string.settings_check_update_summary),
                            checked = uiState.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate
                        )
                    }
                    add {
                        SegmentedDropdownItem(
                            icon = Icons.Rounded.Dashboard,
                            title = stringResource(id = R.string.settings_ui_mode),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onItemSelected = actions.onSetUiModeIndex
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = actions.onOpenTheme,
                            headlineContent = { Text(stringResource(id = R.string.settings_theme)) },
                            supportingContent = { Text(stringResource(id = R.string.settings_theme_summary)) },
                            leadingContent = { Icon(Icons.Filled.Palette, stringResource(id = R.string.settings_theme)) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                }
            )

            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = { showBottomSheet = true },
                            headlineContent = { Text(stringResource(id = R.string.send_log)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.BugReport,
                                    stringResource(id = R.string.send_log)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = actions.onOpenAbout,
                            headlineContent = { Text(stringResource(id = R.string.about)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.ContactPage,
                                    stringResource(id = R.string.about)
                                )
                            },
                        )
                    }
                )
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (showBottomSheet) {
                SendLogBottomSheet(
                    onDismiss = { showBottomSheet = false },
                    snackbarHostState = snackBarHost,
                )
            }
            Spacer(modifier = Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
