package com.erl.blindcast.ui.screen.homeassistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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

    val actions = HomeAssistantActions(
        onTestConnection = { /* TODO(Slice 5.x/6.2): test MQTT + re-publish Discovery */ },
        onToggleEnabled = viewModel::setEnabled,
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
