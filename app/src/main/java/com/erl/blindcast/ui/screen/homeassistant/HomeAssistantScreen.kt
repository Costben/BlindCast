package com.erl.blindcast.ui.screen.homeassistant

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.erl.blindcast.R
import com.erl.blindcast.ui.LocalUiMode
import com.erl.blindcast.ui.UiMode
import com.erl.blindcast.ui.navigation3.Navigator
import com.erl.blindcast.ui.viewmodel.HomeAssistantViewModel

@Composable
fun HomeAssistantPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<HomeAssistantViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val actions = HomeAssistantActions(
        onToggleEnabled = viewModel::setEnabled,
        onHostChange = viewModel::setHost,
        onPortChange = viewModel::setPort,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onTestConnection = viewModel::testConnection,
        onCopyRest = { snippet ->
            if (snippet.isNotBlank()) {
                clipboardManager.setText(AnnotatedString(snippet))
                Toast.makeText(
                    context,
                    context.getString(R.string.blindcast_home_copied),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onRefresh = viewModel::refresh,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> HomeAssistantPagerMiuix(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
        UiMode.Material -> HomeAssistantPagerMaterial(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}
