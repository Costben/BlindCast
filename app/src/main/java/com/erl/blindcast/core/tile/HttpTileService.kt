package com.erl.blindcast.core.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.erl.blindcast.R
import com.erl.blindcast.core.quickaction.QuickActionExecutor
import com.erl.blindcast.core.service.BlindCastForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 状态栏快捷磁贴：快速开关 HTTP 服务端口。
 */
class HttpTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(BlindCastForegroundService.status.value.isRunning)
        scope.launch {
            BlindCastForegroundService.status.collectLatest { status ->
                updateTileState(status.isRunning)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val newState = QuickActionExecutor.toggleHttp(this)
        updateTileState(newState)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun updateTileState(isRunning: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_http)
        tile.label = getString(R.string.tile_http)

        if (isRunning) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val port = BlindCastForegroundService.status.value.port
                tile.subtitle = "已开启 (:$port)"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "已停止"
            }
        }
        tile.updateTile()
    }
}
