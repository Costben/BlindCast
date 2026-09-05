package com.erl.blindcast.ui.screen.homeassistant

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.erl.blindcast.R
import com.erl.blindcast.ui.component.material.SegmentedColumn
import com.erl.blindcast.ui.component.material.SegmentedListItem
import com.erl.blindcast.ui.component.material.SegmentedSwitchItem
import com.erl.blindcast.ui.component.material.SegmentedTextField
import com.erl.blindcast.ui.component.material.TonalCard

/**
 * Slice 6.2 HA 页 Material：与 Miuix 同构（Hero + 配置 + 实体 + REST）。
 */
@Composable
fun HomeAssistantPagerMaterial(
    state: HomeAssistantUiState,
    actions: HomeAssistantActions,
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
            TonalCard {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.ha_status_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${statusLabel(state.connectionState)} · ${state.entityId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ha_status_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (state.statusDetail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.statusDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            SegmentedColumn(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.ha_config_title),
                content = listOf(
                    {
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.PowerSettingsNew,
                            title = stringResource(R.string.ha_enable_title),
                            summary = stringResource(R.string.ha_enable_summary),
                            checked = state.enabled,
                            onCheckedChange = actions.onToggleEnabled,
                        )
                    },
                    {
                        SegmentedTextField(
                            label = stringResource(R.string.ha_host_title),
                            value = state.brokerHost,
                            onValueChange = actions.onHostChange,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.ha_host_hint)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Dns,
                                    stringResource(R.string.ha_host_title)
                                )
                            },
                        )
                    },
                    {
                        SegmentedTextField(
                            label = stringResource(R.string.ha_port_title),
                            value = state.brokerPort.toString(),
                            onValueChange = { v ->
                                v.trim().toIntOrNull()?.let { actions.onPortChange(it) }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.SettingsEthernet,
                                    stringResource(R.string.ha_port_title)
                                )
                            },
                        )
                    },
                    {
                        SegmentedTextField(
                            label = stringResource(R.string.ha_user_title),
                            value = state.username,
                            onValueChange = actions.onUsernameChange,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.ha_user_anonymous)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Person,
                                    stringResource(R.string.ha_user_title)
                                )
                            },
                        )
                    },
                    {
                        SegmentedTextField(
                            label = stringResource(R.string.ha_pass_title),
                            value = state.password,
                            onValueChange = actions.onPasswordChange,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text(stringResource(R.string.ha_pass_unset)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Lock,
                                    stringResource(R.string.ha_pass_title)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = { if (!state.isTesting) actions.onTestConnection() },
                            headlineContent = {
                                Text(
                                    if (state.isTesting) {
                                        stringResource(R.string.ha_action_testing)
                                    } else {
                                        stringResource(R.string.ha_action_test)
                                    }
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Wifi,
                                    stringResource(R.string.ha_action_test)
                                )
                            },
                            supportingContent = {
                                Text(
                                    if (state.statusDetail.isNotBlank()) {
                                        state.statusDetail
                                    } else {
                                        stringResource(R.string.ha_config_subtitle)
                                    }
                                )
                            },
                        )
                    },
                )
            )
            SegmentedColumn(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.ha_entities_title),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = {},
                            headlineContent = { Text(stringResource(R.string.ha_entity_switch)) },
                            supportingContent = { Text(stringResource(R.string.ha_entity_switch_summary)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.ToggleOn,
                                    stringResource(R.string.ha_entity_switch)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = {},
                            headlineContent = { Text(stringResource(R.string.ha_entity_url)) },
                            supportingContent = { Text(stringResource(R.string.ha_entity_url_summary)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Link,
                                    stringResource(R.string.ha_entity_url)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = {},
                            headlineContent = { Text(stringResource(R.string.ha_entity_battery)) },
                            supportingContent = { Text(stringResource(R.string.ha_entity_battery_summary)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.BatteryStd,
                                    stringResource(R.string.ha_entity_battery)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = {},
                            headlineContent = { Text(stringResource(R.string.ha_entity_temp)) },
                            supportingContent = { Text(stringResource(R.string.ha_entity_temp_summary)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Thermostat,
                                    stringResource(R.string.ha_entity_temp)
                                )
                            },
                        )
                    },
                )
            )
            SegmentedColumn(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.ha_rest_title),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = { actions.onCopyRest(state.restSnippet) },
                            headlineContent = { Text(stringResource(R.string.ha_rest_copy)) },
                            supportingContent = { Text(stringResource(R.string.ha_rest_subtitle)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    stringResource(R.string.ha_rest_copy)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = { actions.onCopyRest(state.restSnippet) },
                            headlineContent = {
                                Text(
                                    text = state.restSnippet.ifBlank {
                                        stringResource(R.string.ha_rest_subtitle)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    },
                )
            )
            Spacer(Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun statusLabel(state: String): String = when (state) {
    HaConnectionState.CONNECTED -> stringResource(R.string.ha_status_connected)
    HaConnectionState.CONNECTING -> stringResource(R.string.ha_status_connecting)
    HaConnectionState.FAILED -> stringResource(R.string.ha_status_failed)
    else -> stringResource(R.string.ha_status_unconfigured)
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.home_assistant)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
