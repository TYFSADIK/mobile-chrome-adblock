package com.tyfsadik.adblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Answers `am broadcast -n com.tyfsadik.adblock/.StatusReceiver` with the
 * current state in the result data, so a terminal can read it.
 */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = if (AdBlockVpnService.isRunning) "on" else "off"
        resultData = "$state queries=${AdBlockVpnService.queries.get()} blocked=${AdBlockVpnService.blocked.get()}"
    }
}
