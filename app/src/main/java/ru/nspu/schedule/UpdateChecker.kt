package ru.nspu.schedule

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val version: String, val releaseUrl: String, val apkUrl: String)

/**
 * Lightweight, opt-in release discovery. Only official, non-prerelease GitHub releases count.
 * A rejected version never prompts again; a passive update tile is still available.
 */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/zftsh2035-boop/NSPUWidget/releases/latest"
    private const val RELEASES = "https://github.com/zftsh2035-boop/NSPUWidget/releases"
    private const val PREFS = "app-updates"
    private const val INTERVAL_MS = 6L * 60 * 60 * 1000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedVersion(c: Context): String {
        @Suppress("DEPRECATION")
        val pkg = c.packageManager.getPackageInfo(c.packageName, 0)
        return pkg.versionName ?: "0.0.0"
    }

    /** Numeric ordering avoids the lexical 1.10 < 1.9 bug. */
    fun newer(remote: String, current: String): Boolean {
        fun parse(v: String): List<Int>? {
            val clean = v.removePrefix("v").substringBefore('-')
            if (!Regex("""\d+(?:\.\d+){1,3}""").matches(clean)) return null
            return clean.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
        }
        val a = parse(remote) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    fun previouslyPrompted(c: Context, version: String): Boolean =
        prefs(c).getStringSet("prompted_versions", emptySet())?.contains(version) == true

    fun markPrompted(c: Context, version: String) {
        val old = prefs(c).getStringSet("prompted_versions", emptySet()) ?: emptySet()
        prefs(c).edit().putStringSet("prompted_versions", (old + version).toList().takeLast(50).toSet()).apply()
    }

    private fun stored(c: Context): AppUpdate? {
        val prefs = prefs(c)
        val version = prefs.getString("available_version", null) ?: return null
        val release = prefs.getString("release_url", null) ?: return null
        val apk = prefs.getString("apk_url", null) ?: return null
        return AppUpdate(version, release, apk).takeIf { newer(it.version, installedVersion(c)) }
    }

    /** Callback is always on the Android UI thread. No network I/O on Compose. */
    fun check(c: Context, callback: (AppUpdate?) -> Unit) {
        val app = c.applicationContext
        val initial = stored(app)
        Handler(Looper.getMainLooper()).post { callback(initial) }
        val now = System.currentTimeMillis()
        if (now - prefs(app).getLong("last_check_ms", 0) < INTERVAL_MS) return
        Thread {
            val result = runCatching {
                val con = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "NSPUWidget-Android")
                }
                try {
                    if (con.responseCode != 200) error("HTTP " + con.responseCode)
                    val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
                    require(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
                    val tag = json.getString("tag_name")
                    val url = json.getString("html_url")
                    require(url.startsWith("$RELEASES/tag/v"))
                    val assets = json.getJSONArray("assets")
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                        ?.optString("browser_download_url")
                        ?.takeIf { it.startsWith("$RELEASES/download/v") } ?: ""
                    AppUpdate(tag.removePrefix("v"), url, apk)
                } finally { con.disconnect() }
            }
            result.onSuccess { update ->
                val pref = prefs(app).edit().putLong("last_check_ms", System.currentTimeMillis())
                    .putString("available_version", update.version)
                    .putString("release_url", update.releaseUrl)
                    .putString("apk_url", update.apkUrl)
                pref.apply()
                Handler(Looper.getMainLooper()).post {
                    callback(update.takeIf { newer(it.version, installedVersion(app)) })
                }
            }
        }.start()
    }
}
