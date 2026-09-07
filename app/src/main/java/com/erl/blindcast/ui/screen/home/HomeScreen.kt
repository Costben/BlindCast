package com.erl.blindcast.ui.screen.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.erl.blindcast.R
import com.erl.blindcast.permission.PermissionManager
import com.erl.blindcast.ui.LocalUiMode
import com.erl.blindcast.ui.UiMode
import com.erl.blindcast.ui.navigation3.Navigator
import com.erl.blindcast.ui.navigation3.Route
import com.erl.blindcast.ui.viewmodel.HomeViewModel

@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true
) {
    val viewModel = viewModel<HomeViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val permissionManager = remember(context) { PermissionManager(context) }
    val permissionState by permissionManager.state.collectAsStateWithLifecycle()

    var hasActivated by remember { mutableStateOf(false) }
    if (isCurrentPage) hasActivated = true

    if (hasActivated) {
        LaunchedEffect(Unit) {
            viewModel.refresh()
        }
    }
    LifecycleResumeEffect(permissionManager) {
        permissionManager.refresh()
        onPauseOrDispose { }
    }
    // Fix-Home-1：权限判定收敛进 HomeViewModel，UI 只读 HomeUiState.permissionGranted。
    LaunchedEffect(permissionState.requiredGranted) {
        viewModel.setPermissionGranted(permissionState.requiredGranted)
    }
    // Priv-Bridge-1：特权操作（熄屏/点亮）失败文案 Toast；seq 保证相同文案可重复触发，展示后消费防重弹。
    LaunchedEffect(uiState.actionError, uiState.actionErrorSeq) {
        val err = uiState.actionError
        if (err != null) {
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            viewModel.consumeActionError()
        }
    }

    val actions = HomeActions(
        onPermissionsClick = { navigator.push(Route.Permissions) },
        onOpenUrl = uriHandler::openUri,
        onToggleService = viewModel::toggleService,
        onToggleHttp = viewModel::toggleHttp,
        onToggleStreaming = viewModel::toggleStreaming,
        onBlackout = viewModel::blackoutNow,
        onRestore = viewModel::restoreScreen,
        onCopyLanUrl = { url ->
            if (url.isNotBlank()) {
                clipboardManager.setText(AnnotatedString(url))
                Toast.makeText(
                    context,
                    context.getString(R.string.blindcast_home_copied),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> HomePagerMiuix(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> HomePagerMaterial(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}
