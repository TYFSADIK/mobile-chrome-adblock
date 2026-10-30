package com.tyfsadik.adblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A local VPN that only carries DNS. The phone is told to use a fake DNS
 * server inside the tunnel; everything else bypasses the VPN entirely, so
 * normal traffic is untouched. Ad domains are answered with 0.0.0.0 right
 * here on the phone, and other lookups are forwarded to a public resolver.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlock"
        private const val ACTION_START = "com.tyfsadik.adblock.START"
        private const val ACTION_STOP = "com.tyfsadik.adblock.STOP"
        private const val CHANNEL = "status"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private val upstreams = listOf("1.1.1.1", "9.9.9.9", "8.8.8.8")

        @Volatile
        var isRunning = false
            private set

        val queries = AtomicLong()
        val blocked = AtomicLong()
        private val recent = ArrayDeque<String>()

        fun recentBlocked(): List<String> = synchronized(recent) { recent.toList() }

        private fun remember(domain: String) = synchronized(recent) {
            recent.remove(domain)
            recent.addFirst(domain)
            while (recent.size > 100) recent.removeLast()
        }

        /** Callers must have VPN permission already (VpnService.prepare returned null). */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AdBlockVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            if (isRunning) ctx.startService(Intent(ctx, AdBlockVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var wakePipe: FileDescriptor? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        // A null or android.net.VpnService action means the system started us (always-on VPN).
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification())
        }
        if (!isRunning) {
            isRunning = true
            refreshTile()
            thread(name = "adblock-vpn") { run() }
        }
        return START_STICKY
    }

    override fun onRevoke() = shutdown()

    override fun onDestroy() {
        shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!isRunning) return
        isRunning = false
        wakePipe?.let { runCatching { Os.write(it, byteArrayOf(1), 0, 1) } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        refreshTile()
    }

    private fun run() {
        var pipeRead: FileDescriptor? = null
        try {
            Lists.get(this) // load before routing DNS to us so the first lookups aren't slow
            val vpn = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32)
                .addDisallowedApplication(packageName)
                .setConfigureIntent(
                    PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
                .establish() ?: return // permission was revoked
            tun = vpn
            val pipe = Os.pipe()
            pipeRead = pipe[0]
            wakePipe = pipe[1]
            loop(vpn.fileDescriptor, pipe[0])
        } catch (e: Exception) {
            Log.e(TAG, "VPN stopped", e)
        } finally {
            runCatching { tun?.close() }
            pipeRead?.let { runCatching { Os.close(it) } }
            wakePipe?.let { runCatching { Os.close(it) } }
            tun = null
            wakePipe = null
            if (isRunning) shutdown()
        }
    }

    private fun loop(fd: FileDescriptor, wake: FileDescriptor) {
        val buf = ByteArray(32767)
        val tunPoll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        val wakePoll = StructPollfd().apply { this.fd = wake; events = OsConstants.POLLIN.toShort() }
        while (isRunning) {
            tunPoll.revents = 0
            wakePoll.revents = 0
            try {
                Os.poll(arrayOf(tunPoll, wakePoll), -1)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue else throw e
            }
            if (wakePoll.revents.toInt() != 0) return
            if (tunPoll.revents.toInt() and OsConstants.POLLIN == 0) continue
            val n = try {
                Os.read(fd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) continue else throw e
            }
            if (n > 0) handle(fd, buf, n)
        }
    }

    private fun handle(fd: FileDescriptor, buf: ByteArray, n: Int) {
        val query = DnsPacket.parse(buf, n) ?: return
        queries.incrementAndGet()
        val q = DnsPacket.question(query.dns)
        if (q != null && Lists.get(this).isBlocked(q.name)) {
            block(q.name)
            write(fd, query.reply(DnsPacket.blockedAnswer(query.dns, q)))
            return
        }
        pool.execute { forward(fd, query, q) }
    }

    private fun forward(fd: FileDescriptor, query: DnsPacket.Query, q: DnsPacket.Question?) {
        for (server in upstreams) {
            try {
                DatagramSocket().use { s ->
                    protect(s) // keep our own lookup out of the tunnel
                    s.soTimeout = 4000
                    s.send(DatagramPacket(query.dns, query.dns.size, InetAddress.getByName(server), 53))
                    val rb = ByteArray(4096)
                    val p = DatagramPacket(rb, rb.size)
                    s.receive(p)
                    var resp = rb.copyOf(p.length)
                    if (q != null) {
                        val list = Lists.get(this)
                        DnsPacket.cnameTargets(resp).firstOrNull(list::isBlocked)?.let {
                            block("${q.name} (via $it)")
                            resp = DnsPacket.blockedAnswer(query.dns, q)
                        }
                    }
                    write(fd, query.reply(resp))
                }
                return
            } catch (_: IOException) {
                // try the next resolver
            }
        }
    }

    private fun block(name: String) {
        blocked.incrementAndGet()
        remember(name.trimEnd('.'))
    }

    private fun write(fd: FileDescriptor, packet: ByteArray) {
        synchronized(writeLock) {
            try {
                Os.write(fd, packet, 0, packet.size)
            } catch (_: ErrnoException) {
                // interface closed or momentarily full; the app will retry its lookup
            } catch (_: java.io.InterruptedIOException) {
            }
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.turn_off), stop).build())
            .build()
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, android.content.ComponentName(this, QuickTileService::class.java))
    }
}
