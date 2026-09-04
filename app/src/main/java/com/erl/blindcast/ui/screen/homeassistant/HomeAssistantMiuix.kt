package com.erl.blindcast.ui.screen.homeassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erl.blindcast.R
import com.erl.blindcast.ui.component.miuix.SuperEditArrow
import com.erl.blindcast.ui.theme.LocalEnableBlur
import com.erl.blindcast.ui.util.BlurredBar
import com.erl.blindcast.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Slice 6.2 HA 页 Miuix：Hero 状态 + Broker 配置 + 实体清单 + REST 备选。
 */
@Composable
fun HomeAssistantPagerMiuix(
    state: HomeAssistantUiState,
    actions: HomeAssistantActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            TopBar(scrollBehavior = scrollBehavior, backdrop = backdrop, barColor = barColor)
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
                        StatusHeroCard(state)
                        ConfigCard(state, actions)
                        EntitiesCard()
                        RestCard(state, actions)
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
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
            title = stringResource(R.string.home_assistant),
            scrollBehavior = scrollBehavior
        )
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
private fun StatusHeroCard(state: HomeAssistantUiState) {
    val containerColor = when (state.connectionState) {
        HaConnectionState.CONNECTED -> Color(0xFFDFFAE4)
        HaConnectionState.CONNECTING -> Color(0xFFFFF3D6)
        HaConnectionState.FAILED -> Color(0xFFF8E2E2)
        else -> Color(0xFFE8E8E8)
    }
    val textColor = Color(0xFF111111)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.ha_status_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
            Text(
                text = "${statusLabel(state.connectionState)} · ${state.entityId}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.78f),
            )
            Text(
                text = stringResource(R.string.ha_status_subtitle),
                fontSize = 13.sp,
                color = textColor.copy(alpha = 0.62f),
            )
            if (state.statusDetail.isNotBlank()) {
                Text(
                    text = state.statusDetail,
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(state: HomeAssistantUiState, actions: HomeAssistantActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = stringResource(R.string.ha_enable_title),
            summary = stringResource(R.string.ha_enable_summary),
            checked = state.enabled,
            onCheckedChange = actions.onToggleEnabled,
        )
        TextEditArrow(
            title = stringResource(R.string.ha_host_title),
            value = state.brokerHost,
            placeholder = stringResource(R.string.ha_host_hint),
            onConfirm = actions.onHostChange,
        )
        SuperEditArrow(
            title = stringResource(R.string.ha_port_title),
            defaultValue = state.brokerPort,
            onValueChange = actions.onPortChange,
        )
        TextEditArrow(
            title = stringResource(R.string.ha_user_title),
            value = state.username,
            placeholder = stringResource(R.string.ha_user_anonymous),
            onConfirm = actions.onUsernameChange,
        )
        TextEditArrow(
            title = stringResource(R.string.ha_pass_title),
            value = state.password,
            placeholder = stringResource(R.string.ha_pass_unset),
            displayValue = if (state.password.isEmpty()) {
                stringResource(R.string.ha_pass_unset)
            } else {
                stringResource(R.string.ha_pass_set)
            },
            isPassword = true,
            onConfirm = actions.onPasswordChange,
        )
        BasicComponent(
            title = if (state.isTesting) {
                stringResource(R.string.ha_action_testing)
            } else {
                stringResource(R.string.ha_action_test)
            },
            summary = if (state.statusDetail.isNotBlank()) {
                state.statusDetail
            } else {
                stringResource(R.string.ha_config_subtitle)
            },
            onClick = { if (!state.isTesting) actions.onTestConnection() },
        )
    }
}

@Composable
private fun EntitiesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.ha_entity_switch),
            summary = stringResource(R.string.ha_entity_switch_summary),
            onClick = {},
        )
        BasicComponent(
            title = stringResource(R.string.ha_entity_url),
            summary = stringResource(R.string.ha_entity_url_summary),
            onClick = {},
        )
        BasicComponent(
            title = stringResource(R.string.ha_entity_battery),
            summary = stringResource(R.string.ha_entity_battery_summary),
            onClick = {},
        )
        BasicComponent(
            title = stringResource(R.string.ha_entity_temp),
            summary = stringResource(R.string.ha_entity_temp_summary),
            onClick = {},
        )
    }
}

@Composable
private fun RestCard(state: HomeAssistantUiState, actions: HomeAssistantActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.ha_rest_copy),
            summary = stringResource(R.string.ha_rest_subtitle),
            onClick = { actions.onCopyRest(state.restSnippet) },
        )
        Text(
            text = state.restSnippet.ifBlank { stringResource(R.string.ha_rest_subtitle) },
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** 字符串版 SuperEditArrow（Miuix ArrowPreference + OverlayDialog 文本输入）。 */
@Composable
private fun TextEditArrow(
    title: String,
    value: String,
    placeholder: String,
    displayValue: String = value.ifBlank { placeholder },
    isPassword: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    ArrowPreference(
        title = title,
        summary = displayValue,
        onClick = { showDialog = true },
        holdDownState = showDialog,
    )
    if (showDialog) {
        var draft by remember(value, showDialog) { mutableStateOf(value) }
        OverlayDialog(
            show = true,
            title = title,
            onDismissRequest = { showDialog = false },
            content = {
                TextField(
                    modifier = Modifier.padding(bottom = 16.dp),
                    value = draft,
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                    ),
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    onValueChange = { draft = it },
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
