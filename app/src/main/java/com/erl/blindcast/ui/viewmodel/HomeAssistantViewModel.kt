package com.erl.blindcast.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.erl.blindcast.ui.screen.homeassistant.HomeAssistantUiState

/**
 * Slice 1.2 skeleton ViewModel.
 * Holds static placeholder state only; real MQTT wiring arrives in Slice 5.x/6.2.
 */
class HomeAssistantViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeAssistantUiState())
    val uiState: StateFlow<HomeAssistantUiState> = _uiState.asStateFlow()

    fun refresh() {
        // Skeleton: nothing to load yet. Kept for pager-lifecycle symmetry with Home/Settings.
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
    }
}
