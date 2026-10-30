package com.tyfsadik.adblock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity for turning blocking on and off from Termux, Tasker or
 * adb. An activity is used instead of a broadcast because Android only lets
 * apps start a foreground service from the foreground.
 */
class ToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val turnOn = when (intent.action) {
            "com.tyfsadik.adblock.ON" -> true
            "com.tyfsadik.adblock.OFF" -> false
            else -> !AdBlockVpnService.isRunning
        }
        if (!turnOn) {
            AdBlockVpnService.stop(this)
            Toast.makeText(this, R.string.status_off, Toast.LENGTH_SHORT).show()
        } else if (VpnService.prepare(this) == null) {
            AdBlockVpnService.start(this)
            Toast.makeText(this, R.string.status_on, Toast.LENGTH_SHORT).show()
        } else {
            // First run: the user has to approve the VPN once in the app.
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true))
        }
        finish()
    }
}
