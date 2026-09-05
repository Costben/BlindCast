package com.erl.blindcast.ui.screen.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import android.widget.Toast
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.erl.blindcast.R
import com.erl.blindcast.core.priv.PrivilegedBridge
import com.erl.blindcast.permission.PermissionManager
import com.erl.blindcast.permission.PermissionState
import com.erl.blindcast.ui.LocalUiMode
import com.erl.blindcast.ui.UiMode
import com.erl.blindcast.ui.navigation3.LocalNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun PermissionScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val manager = remember(context) { PermissionManager(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Priv-Bridge-2：Shizuku 四态（未运行 / 运行中·未授权 / 运行中·已授权），点行应用内一键授权。
    var shizukuState by remember { mutableStateOf(PrivilegedBridge.ShizukuState.NOT_RUNNING) }
    fun refreshShizuku() {
        scope.launch(Dispatchers.IO) {
            val fresh = PrivilegedBridge.shizukuState()
            withContext(Dispatchers.Main) { shizukuState = fresh }
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { manager.refresh() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { manager.refresh() }

    LifecycleResumeEffect(manager) {
        manager.refresh()
        refreshShizuku()
        onPauseOrDispose { }
    }

    val actions = PermissionActions(
        onStorage = {
            manager.legacyStoragePermission()?.let(permissionLauncher::launch)
                ?: settingsLauncher.launch(manager.storageSettingsIntent())
        },
        onNotification = {
            manager.notificationRuntimePermission()?.let(permissionLauncher::launch)
                ?: settingsLauncher.launch(manager.notificationSettingsIntent())
        },
        onMicrophone = { permissionLauncher.launch(manager.microphonePermission()) },
        onBattery = { settingsLauncher.launch(manager.batteryWhitelistIntent()) },
        onOverlay = { settingsLauncher.launch(manager.overlaySettingsIntent()) },
        onShizuku = {
            scope.launch {
                val cur = withContext(Dispatchers.IO) { PrivilegedBridge.shizukuState() }
                withContext(Dispatchers.Main) { shizukuState = cur }
                when (cur) {
                    PrivilegedBridge.ShizukuState.NOT_RUNNING -> {
                        Toast.makeText(
                            context,
                            PrivilegedBridge.SHIZUKU_NOT_RUNNING_MESSAGE,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    PrivilegedBridge.ShizukuState.GRANTED -> {
                        // 已授权：仅刷新，无操作。
                    }
                    PrivilegedBridge.ShizukuState.RUNNING_UNAUTHORIZED -> {
                        // 应用内一键授权：Shizuku 授权弹窗自动弹出，允许即回。
                        val granted = PrivilegedBridge.awaitPermission()
                        val fresh = withContext(Dispatchers.IO) { PrivilegedBridge.shizukuState() }
                        withContext(Dispatchers.Main) { shizukuState = fresh }
                        if (!granted && fresh != PrivilegedBridge.ShizukuState.GRANTED) {
                            Toast.makeText(
                                context,
                                PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        },
    )
    val onBack = dropUnlessResumed { navigator.pop() }

    when (LocalUiMode.current) {
        UiMode.Miuix -> PermissionScreenMiuix(state, actions, shizukuState, onBack)
        UiMode.Material -> PermissionScreenMaterial(state, actions, shizukuState, onBack)
    }
}

private data class PermissionActions(
    val onStorage: () -> Unit,
    val onNotification: () -> Unit,
    val onMicrophone: () -> Unit,
    val onBattery: () -> Unit,
    val onOverlay: () -> Unit,
    val onShizuku: () -> Unit,
)

@Composable
private fun PermissionScreenMiuix(
    state: PermissionState,
    actions: PermissionActions,
    shizukuState: PrivilegedBridge.ShizukuState,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    MiuixScaffold(
        topBar = {
            MiuixTopAppBar(
                title = stringResource(R.string.permission_section),
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        MiuixIcon(MiuixIcons.Back, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                MiuixCard(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = if (state.requiredGranted) {
                            stringResource(R.string.permission_status_ready_title)
                        } else {
                            stringResource(R.string.permission_status_missing_title)
                        },
                        summary = if (state.requiredGranted) {
                            stringResource(R.string.permission_ready)
                        } else {
                            stringResource(R.string.permission_missing)
                        },
                        endActions = {
                            MiuixIcon(
                                imageVector =
                                    if (state.requiredGranted) Icons.Default.CheckCircle
                                    else Icons.Default.ErrorOutline,
                                tint =
                                    if (state.requiredGranted) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.error,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
            item {
                MiuixCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    PermissionRowMiuix(
                        stringResource(R.string.permission_storage),
                        state.storage,
                        true,
                        Icons.Default.Folder,
                        actions.onStorage,
                    )
                    PermissionRowMiuix(
                        stringResource(R.string.permission_notification),
                        state.notification,
                        true,
                        Icons.Default.Notifications,
                        actions.onNotification,
                    )
                    PermissionRowMiuix(
                        stringResource(R.string.permission_microphone),
                        state.microphone,
                        true,
                        Icons.Default.Mic,
                        actions.onMicrophone,
                    )
                    PermissionRowMiuix(
                        stringResource(R.string.permission_battery),
                        state.batteryWhitelist,
                        true,
                        Icons.Default.BatterySaver,
                        actions.onBattery,
                    )
                    PermissionRowMiuix(
                        stringResource(R.string.permission_overlay),
                        state.overlay,
                        false,
                        Icons.Default.WebAsset,
                        actions.onOverlay,
                    )
                    ShizukuRowMiuix(
                        shizukuState = shizukuState,
                        onClick = actions.onShizuku,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PermissionRowMiuix(
    title: String,
    granted: Boolean,
    required: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = when {
            granted -> stringResource(R.string.permission_granted)
            required -> stringResource(R.string.permission_required)
            else -> stringResource(R.string.permission_optional)
        },
        startAction = {
            MiuixIcon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
        endActions = {
            MiuixText(
                text =
                    if (granted) {
                        stringResource(R.string.permission_granted)
                    } else {
                        stringResource(R.string.permission_grant_action)
                    },
                color =
                    if (granted) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = FontWeight.Medium,
            )
        },
        onClick = onClick,
        holdDownState = false,
        enabled = true,
    )
}

/**
 * Priv-Bridge-2：权限页“Shizuku 授权”行（四态：运行中 / 未运行 / 已授权 / 未授权）。
 * 点行 → 未运行弹 Toast 引导启动；运行中未授权调 awaitPermission 弹系统授权框；已授权仅刷新。
 */
@Composable
private fun ShizukuRowMiuix(
    shizukuState: PrivilegedBridge.ShizukuState,
    onClick: () -> Unit,
) {
    val granted = shizukuState == PrivilegedBridge.ShizukuState.GRANTED
    val summary = when (shizukuState) {
        PrivilegedBridge.ShizukuState.GRANTED ->
            stringResource(R.string.permission_shizuku_state_granted)
        PrivilegedBridge.ShizukuState.RUNNING_UNAUTHORIZED ->
            stringResource(R.string.permission_shizuku_state_unauthorized)
        PrivilegedBridge.ShizukuState.NOT_RUNNING ->
            stringResource(R.string.permission_shizuku_state_not_running)
    }
    val endText = when (shizukuState) {
        PrivilegedBridge.ShizukuState.GRANTED ->
            stringResource(R.string.permission_shizuku_granted)
        PrivilegedBridge.ShizukuState.RUNNING_UNAUTHORIZED ->
            stringResource(R.string.permission_shizuku_unauthorized)
        PrivilegedBridge.ShizukuState.NOT_RUNNING ->
            stringResource(R.string.permission_shizuku_not_running)
    }
    ArrowPreference(
        title = stringResource(R.string.permission_shizuku),
        summary = summary,
        startAction = {
            MiuixIcon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
        endActions = {
            MiuixText(
                text = if (granted) endText else stringResource(R.string.permission_grant_action),
                color =
                    if (granted) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = FontWeight.Medium,
            )
        },
        onClick = onClick,
        holdDownState = false,
        enabled = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionScreenMaterial(
    state: PermissionState,
    actions: PermissionActions,
    shizukuState: PrivilegedBridge.ShizukuState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_section)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor =
                            if (state.requiredGranted) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text =
                                if (state.requiredGranted) {
                                    stringResource(R.string.permission_status_ready_title)
                                } else {
                                    stringResource(R.string.permission_status_missing_title)
                                },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text =
                                if (state.requiredGranted) {
                                    stringResource(R.string.permission_ready)
                                } else {
                                    stringResource(R.string.permission_missing)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PermissionRowMaterial(
                            stringResource(R.string.permission_storage),
                            state.storage,
                            true,
                            Icons.Default.Folder,
                            actions.onStorage,
                        )
                        PermissionRowMaterial(
                            stringResource(R.string.permission_notification),
                            state.notification,
                            true,
                            Icons.Default.Notifications,
                            actions.onNotification,
                        )
                        PermissionRowMaterial(
                            stringResource(R.string.permission_microphone),
                            state.microphone,
                            true,
                            Icons.Default.Mic,
                            actions.onMicrophone,
                        )
                        PermissionRowMaterial(
                            stringResource(R.string.permission_battery),
                            state.batteryWhitelist,
                            true,
                            Icons.Default.BatterySaver,
                            actions.onBattery,
                        )
                        PermissionRowMaterial(
                            stringResource(R.string.permission_overlay),
                            state.overlay,
                            false,
                            Icons.Default.WebAsset,
                            actions.onOverlay,
                        )
                        ShizukuRowMaterial(
                            shizukuState = shizukuState,
                            onClick = actions.onShizuku,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRowMaterial(
    title: String,
    granted: Boolean,
    required: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = when {
                    granted -> stringResource(R.string.permission_granted)
                    required -> stringResource(R.string.permission_required)
                    else -> stringResource(R.string.permission_optional)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            AssistChip(
                onClick = { },
                label = { Text(stringResource(R.string.permission_granted)) },
                leadingIcon = {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                },
            )
        } else {
            OutlinedButton(onClick = onClick) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.permission_grant_action))
            }
        }
    }
}

/**
 * Priv-Bridge-2：权限页“Shizuku 授权”行（Material 版，四态）。
 */
@Composable
private fun ShizukuRowMaterial(
    shizukuState: PrivilegedBridge.ShizukuState,
    onClick: () -> Unit,
) {
    val granted = shizukuState == PrivilegedBridge.ShizukuState.GRANTED
    val summary = when (shizukuState) {
        PrivilegedBridge.ShizukuState.GRANTED ->
            stringResource(R.string.permission_shizuku_state_granted)
        PrivilegedBridge.ShizukuState.RUNNING_UNAUTHORIZED ->
            stringResource(R.string.permission_shizuku_state_unauthorized)
        PrivilegedBridge.ShizukuState.NOT_RUNNING ->
            stringResource(R.string.permission_shizuku_state_not_running)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Security, contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.permission_shizuku),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            AssistChip(
                onClick = { },
                label = { Text(stringResource(R.string.permission_shizuku_granted)) },
                leadingIcon = {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                },
            )
        } else {
            OutlinedButton(onClick = onClick) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.permission_grant_action))
            }
        }
    }
}
