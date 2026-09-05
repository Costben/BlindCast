package com.erl.blindcast.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionManager(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val _state = MutableStateFlow(Companion.readState(appContext))

    val state: StateFlow<PermissionState> = _state.asStateFlow()

    fun refresh() {
        _state.value = Companion.readState(appContext)
    }

    fun notificationRuntimePermission(): String? =
        Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }

    fun microphonePermission(): String = Manifest.permission.RECORD_AUDIO

    fun legacyStoragePermission(): String? =
        Manifest.permission.READ_EXTERNAL_STORAGE.takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.R }

    fun storageSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        } else {
            appDetailsIntent()
        }

    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        }

    fun batteryWhitelistIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }

    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }

    private fun appDetailsIntent() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }

    private fun readState(): PermissionState = Companion.readState(appContext)

    private fun hasPermission(permission: String): Boolean =
        Companion.hasPermission(appContext, permission)

    companion object {
        /**
         * Fix-Home-2：直读四门禁，供 HomeViewModel 轮询自愈复用。
         * 门禁定义不变：storage && notification && microphone && batteryWhitelist。
         */
        fun readState(context: Context): PermissionState {
            val appCtx = context.applicationContext ?: context
            return PermissionState(
                storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    hasPermission(appCtx, Manifest.permission.READ_EXTERNAL_STORAGE)
                },
                notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
                },
                microphone = hasPermission(appCtx, Manifest.permission.RECORD_AUDIO),
                batteryWhitelist =
                    (appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(appCtx.packageName),
                overlay = Settings.canDrawOverlays(appCtx),
            )
        }

        private fun hasPermission(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED
    }
}
