package com.erl.blindcast.core.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.erl.blindcast.R
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.quickaction.QuickActionExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 状态栏快捷磁贴：快速熄屏 / 物理黑屏挂机。
 */
class BlackoutTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            QuickActionExecutor.toggleBlackout(this@BlackoutTileService)
            updateTileState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isBlacked = PowerController.isBlackedOut
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_blackout)
        tile.label = getString(R.string.tile_blackout)

        if (isBlacked) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "已熄屏"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "已点亮"
            }
        }
        tile.updateTile()
    }
}
