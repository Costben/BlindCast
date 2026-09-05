package com.erl.blindcast.ui.component.bottombar

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.erl.blindcast.R

/**
 * Slice 1.2: strict 3-item bottom destinations for BlindCast.
 *
 * Order == HorizontalPager page index:
 * 0 -> Home, 1 -> HomeAssistant, 2 -> Settings.
 *
 * Secondary destinations (About / ColorPalette / Permissions) stay as
 * Navigation3 push routes and must NOT appear here.
 */
enum class BottomBarDestination(
    @get:StringRes val label: Int,
    val miuixIcon: ImageVector,
    val materialSelectedIcon: ImageVector,
    val materialUnselectedIcon: ImageVector,
    val pageIndex: Int,
) {
    Home(
        label = R.string.home,
        miuixIcon = Icons.Rounded.Cottage,
        materialSelectedIcon = Icons.Filled.Home,
        materialUnselectedIcon = Icons.Outlined.Home,
        pageIndex = 0,
    ),
    HomeAssistant(
        label = R.string.bottom_bar_ha,
        miuixIcon = Icons.Rounded.DeviceHub,
        materialSelectedIcon = Icons.Filled.DeviceHub,
        materialUnselectedIcon = Icons.Outlined.DeviceHub,
        pageIndex = 1,
    ),
    Settings(
        label = R.string.settings,
        miuixIcon = Icons.Rounded.Settings,
        materialSelectedIcon = Icons.Filled.Settings,
        materialUnselectedIcon = Icons.Outlined.Settings,
        pageIndex = 2,
    ),
}
