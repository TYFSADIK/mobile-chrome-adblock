package com.tyfsadik.adblock

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile for switching blocking on and off from the notification shade. */
class QuickTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (AdBlockVpnService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        if (AdBlockVpnService.isRunning) {
            AdBlockVpnService.stop(this)
        } else if (VpnService.prepare(this) == null) {
            AdBlockVpnService.start(this)
        } else {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            return
        }
        qsTile?.let {
            it.state = if (it.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }
}
