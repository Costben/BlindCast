package com.erl.blindcast.ui.navigation3

import android.os.Parcelable
import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation keys for Navigation3.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 *
 * Clean-HA-License-1: BlindCast converges to exactly 2 first-level pages backed by
 * HorizontalPager + bottom bar / side rail:
 * - [Home] (page 0), [Settings] (page 1).
 *
 * [HomeAssistant] is temporarily hidden (not under development): its route object
 * and pager file are retained for future restore but must not appear in the
 * bottom bar / side rail.
 *
 * [About], [ColorPalette] and [Permissions] are secondary push destinations only
 * and must never appear in the bottom bar.
 */
sealed interface Route : NavKey, Parcelable {
    @Parcelize
    @Serializable
    data object Main : Route

    @Parcelize
    @Serializable
    data object Home : Route

    @Parcelize
    @Serializable
    data object HomeAssistant : Route

    @Parcelize
    @Serializable
    data object Settings : Route

    @Parcelize
    @Serializable
    data object About : Route

    @Parcelize
    @Serializable
    data object ColorPalette : Route

    @Parcelize
    @Serializable
    data object Permissions : Route
}
