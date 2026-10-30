package com.tyfsadik.adblock

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** Loads, caches and updates the blocklists for the selected blocking level. */
object Lists {

    enum class Level { STRONG, ULTIMATE }

    private class Source(val file: String, val url: String)

    private val stevenBlack = Source("stevenblack.txt", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")
    private val adGuard = Source("adguard.txt", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt")
    private val hagezi = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"
    private val proPlus = Source("hagezi-pro-plus.txt", hagezi + "pro.plus-onlydomains.txt")
    private val ultimate = Source("hagezi-ultimate.txt", hagezi + "ultimate-onlydomains.txt")
    // Encrypted DNS servers that apps could use to get around the blocker.
    private val doh = Source("hagezi-doh.txt", hagezi + "doh-onlydomains.txt")

    private fun sources(level: Level) =
        listOf(stevenBlack, adGuard, if (level == Level.ULTIMATE) ultimate else proPlus, doh)

    @Volatile
    private var cached: Blocklist? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("adblock", Context.MODE_PRIVATE)

    fun level(ctx: Context): Level =
        runCatching { Level.valueOf(prefs(ctx).getString("level", null)!!) }.getOrDefault(Level.STRONG)

    fun lastUpdated(ctx: Context): Long = prefs(ctx).getLong("updated", 0)

    fun userAllowed(ctx: Context): Set<String> = prefs(ctx).getStringSet("allow", emptySet())!!

    /** True when every list for [level] is available, either bundled or downloaded. */
    fun isAvailable(ctx: Context, level: Level) = sources(level).all { file(ctx, it).exists() || isBundled(ctx, it) }

    fun setLevel(ctx: Context, level: Level) {
        prefs(ctx).edit().putString("level", level.name).apply()
        cached = null
    }

    fun get(ctx: Context): Blocklist = cached ?: synchronized(this) { cached ?: load(ctx).also { cached = it } }

    fun allow(ctx: Context, domain: String) {
        val set = userAllowed(ctx) + domain
        prefs(ctx).edit().putStringSet("allow", set).apply()
        cached = cached?.withUserAllow(set)
    }

    fun disallow(ctx: Context, domain: String) {
        val set = userAllowed(ctx) - domain
        prefs(ctx).edit().putStringSet("allow", set).apply()
        cached = cached?.withUserAllow(set)
    }

    /** Downloads fresh copies of the current level's lists. Throws if any download fails. */
    fun update(ctx: Context) {
        for (s in sources(level(ctx))) {
            val conn = URL(s.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                if (conn.responseCode != 200) throw java.io.IOException("${s.url}: HTTP ${conn.responseCode}")
                val tmp = File(file(ctx, s).path + ".tmp")
                tmp.parentFile!!.mkdirs()
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file(ctx, s))
            } finally {
                conn.disconnect()
            }
        }
        prefs(ctx).edit().putLong("updated", System.currentTimeMillis()).apply()
        cached = load(ctx)
    }

    private fun load(ctx: Context): Blocklist {
        val b = Blocklist.Builder()
        for (s in sources(level(ctx))) {
            val f = file(ctx, s)
            val reader = when {
                f.exists() -> f.bufferedReader()
                else -> try {
                    ctx.assets.open(s.file).bufferedReader()
                } catch (_: FileNotFoundException) {
                    continue
                }
            }
            b.addList(reader)
        }
        return b.build(userAllowed(ctx))
    }

    private fun file(ctx: Context, s: Source) = File(ctx.filesDir, "lists/${s.file}")

    private fun isBundled(ctx: Context, s: Source) = ctx.assets.list("")?.contains(s.file) == true
}
