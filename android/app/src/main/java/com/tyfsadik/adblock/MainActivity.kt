package com.tyfsadik.adblock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val EXTRA_START = "start"
        private const val REQUEST_VPN = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var warning: TextView
    private lateinit var listInfo: TextView
    private lateinit var update: Button
    private lateinit var level: RadioGroup
    private lateinit var recentAdapter: ArrayAdapter<String>
    private var shownRecent = emptyList<String>()

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(v.paddingLeft, top, v.paddingRight, bottom)
            insets
        }

        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        warning = findViewById(R.id.warning)
        listInfo = findViewById(R.id.list_info)
        update = findViewById(R.id.update)
        level = findViewById(R.id.level)

        toggle.setOnClickListener {
            if (AdBlockVpnService.isRunning) AdBlockVpnService.stop(this) else requestStart()
        }
        update.setOnClickListener { updateLists() }

        level.check(if (Lists.level(this) == Lists.Level.ULTIMATE) R.id.level_ultimate else R.id.level_strong)
        level.setOnCheckedChangeListener { _, id ->
            val chosen = if (id == R.id.level_ultimate) Lists.Level.ULTIMATE else Lists.Level.STRONG
            if (chosen == Lists.level(this)) return@setOnCheckedChangeListener
            Lists.setLevel(this, chosen)
            if (Lists.isAvailable(this, chosen)) reloadInBackground() else updateLists()
        }

        recentAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        val recentList = findViewById<ListView>(R.id.recent)
        recentList.adapter = recentAdapter
        recentList.emptyView = findViewById(R.id.recent_empty)
        recentList.setOnItemClickListener { _, _, pos, _ -> confirmAllow(recentAdapter.getItem(pos)!!) }
        findViewById<Button>(R.id.allowed).setOnClickListener { showAllowed() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        if (intent.getBooleanExtra(EXTRA_START, false)) requestStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false)) requestStart()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun requestStart() {
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQUEST_VPN) else AdBlockVpnService.start(this)
    }

    @Deprecated("Activity result API needs AndroidX; the platform callback is fine here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) AdBlockVpnService.start(this)
    }

    private fun render() {
        val on = AdBlockVpnService.isRunning
        toggle.setText(if (on) R.string.turn_off else R.string.turn_on)
        toggle.isActivated = on
        status.setText(if (on) R.string.status_on else R.string.status_off)

        val q = AdBlockVpnService.queries.get()
        val b = AdBlockVpnService.blocked.get()
        val pct = if (q > 0) b * 100 / q else 0
        stats.text = getString(R.string.stats, q, b, pct)

        val strictDns = privateDnsHost()
        warning.visibility = if (strictDns != null) View.VISIBLE else View.GONE
        if (strictDns != null) warning.text = getString(R.string.private_dns_warning, strictDns)

        if (update.isEnabled) {
            val updated = Lists.lastUpdated(this)
            val whenText = if (updated == 0L) getString(R.string.bundled) else DateUtils.getRelativeTimeSpanString(updated).toString()
            listInfo.text = getString(R.string.list_info, "%,d".format(Lists.get(this).size), whenText)
        }

        val recent = AdBlockVpnService.recentBlocked()
        if (recent != shownRecent) {
            shownRecent = recent
            recentAdapter.clear()
            recentAdapter.addAll(recent)
        }
    }

    /** Strict Private DNS sends lookups straight to that server and bypasses us. */
    private fun privateDnsHost(): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val cm = getSystemService(ConnectivityManager::class.java)
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            if (lp.isPrivateDnsActive && lp.privateDnsServerName != null) return lp.privateDnsServerName
        }
        return null
    }

    private fun updateLists() {
        update.isEnabled = false
        listInfo.setText(R.string.updating)
        thread {
            val error = runCatching { Lists.update(this) }.exceptionOrNull()
            runOnUiThread {
                update.isEnabled = true
                Toast.makeText(
                    this,
                    if (error == null) getString(R.string.updated) else getString(R.string.update_failed, error.message),
                    Toast.LENGTH_LONG,
                ).show()
                render()
            }
        }
    }

    private fun reloadInBackground() {
        update.isEnabled = false
        listInfo.setText(R.string.loading)
        thread {
            Lists.get(this)
            runOnUiThread {
                update.isEnabled = true
                render()
            }
        }
    }

    private fun confirmAllow(entry: String) {
        val domain = entry.substringBefore(" (via ")
        AlertDialog.Builder(this)
            .setTitle(domain)
            .setMessage(R.string.allow_question)
            .setPositiveButton(R.string.allow) { _, _ -> Lists.allow(this, domain) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAllowed() {
        val allowed = Lists.userAllowed(this).sorted()
        if (allowed.isEmpty()) {
            Toast.makeText(this, R.string.nothing_allowed, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.allowed_title)
            .setItems(allowed.toTypedArray()) { _, i ->
                AlertDialog.Builder(this)
                    .setTitle(allowed[i])
                    .setMessage(R.string.block_again_question)
                    .setPositiveButton(R.string.block_again) { _, _ -> Lists.disallow(this, allowed[i]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }
}
