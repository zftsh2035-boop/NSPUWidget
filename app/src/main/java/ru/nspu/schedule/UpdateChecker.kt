package ru.nspu.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String,
    val channel: String = AppSettings.STABLE,
    val key: String = version,
    val notes: String = ""
)

/** Stable = verified GitHub Releases, dev = successful Actions artifacts from dev branch. */
object UpdateChecker {
    private const val ROOT = "https://api.github.com/repos/zftsh2035-boop/NSPUWidget/"
    const val RELEASES = "https://github.com/zftsh2035-boop/NSPUWidget/releases"
    const val DEV_BUILDS = "https://github.com/zftsh2035-boop/NSPUWidget/actions/workflows/build-apk.yml?query=branch%3Adev"
    private const val PREFS = "app-updates"
    private const val INTERVAL_MS = 6L * 60 * 60 * 1000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun installedVersion(c: Context): String {
        @Suppress("DEPRECATION")
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        return info.versionName ?: "0.0.0"
    }

    /** Numeric ordering avoids 1.10 being incorrectly considered older than 1.9. */
    fun newer(remote: String, current: String): Boolean {
        fun parts(s: String): List<Int>? {
            val v = s.removePrefix("v").substringBefore('-')
            if (!Regex("""\d+(?:\.\d+){1,3}""").matches(v)) return null
            return v.split('.').mapNotNull { it.toIntOrNull() }
        }
        val a = parts(remote) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    fun previouslyPrompted(c: Context, key: String): Boolean {
        val saved = prefs(c).getStringSet("prompted_versions", emptySet()) ?: emptySet()
        // Older releases stored just the version; preserve those dismissals.
        return key in saved || (key.startsWith("stable:") && key.substringAfter(':') in saved)
    }

    fun markPrompted(c: Context, key: String) {
        val saved = prefs(c).getStringSet("prompted_versions", emptySet()) ?: emptySet()
        prefs(c).edit().putStringSet("prompted_versions", (saved + key).toSet()).apply()
    }

    private fun request(path: String): JSONObject {
        val conn = (URL(ROOT + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", "NSPUWidget-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) error("GitHub HTTP " + conn.responseCode)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun stable(c: Context): AppUpdate? {
        val json = request("releases/latest")
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.getString("tag_name")
        val url = json.getString("html_url")
        if (!url.startsWith("$RELEASES/tag/")) return null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url")
            ?.takeIf { it.startsWith("$RELEASES/download/") } ?: ""
        if (!newer(tag, installedVersion(c))) return null
        return AppUpdate(tag.removePrefix("v"), url, apk, AppSettings.STABLE,
            "stable:" + tag.removePrefix("v"), ReleaseNotes.display(json.optString("body")))
    }

    private fun dev(c: Context): AppUpdate? {
        val json = request("actions/workflows/build-apk.yml/runs?branch=dev&status=success&per_page=5")
        val runs = json.optJSONArray("workflow_runs") ?: return null
        val run = (0 until runs.length()).map { runs.getJSONObject(it) }
            .firstOrNull { it.optString("conclusion") == "success" &&
                it.optString("head_branch") == "dev" } ?: return null
        val sha = run.optString("head_sha")
        if (!Regex("""[0-9a-f]{40}""").matches(sha)) return null
        if (sha == BuildConfig.GIT_SHA) return null
        // Don't treat the same code as a new dev release when it is already stable.
        val stableSha = runCatching { request("releases/latest").optString("target_commitish") }.getOrNull()
        if (sha == stableSha) return null
        val page = run.optString("html_url")
        if (!page.startsWith("https://github.com/zftsh2035-boop/NSPUWidget/actions/runs/")) return null
        val version = "dev-" + sha.take(8)
        // A public Actions artifact may require signing in to GitHub; never label it a direct APK.
        return AppUpdate(version, page, "", AppSettings.DEV, "dev:" + sha,
            ReleaseNotes.display(run.optJSONObject("head_commit")?.optString("message").orEmpty()))
    }

    private fun stored(c: Context, channel: String): AppUpdate? {
        val p = prefs(c)
        val key = p.getString("key_$channel", null) ?: return null
        val version = p.getString("version_$channel", null) ?: return null
        val release = p.getString("url_$channel", null) ?: return null
        val apk = p.getString("apk_$channel", "") ?: ""
        val result = AppUpdate(version, release, apk, channel, key, p.getString("notes_$channel", "").orEmpty())
        return if (channel == AppSettings.STABLE) result.takeIf { newer(it.version, installedVersion(c)) }
            else result.takeIf { it.key.removePrefix("dev:") != BuildConfig.GIT_SHA }
    }

    /** Returns cached result first, then performs a non-blocking check (max once per 6h). */
    fun check(c: Context, force: Boolean = false, callback: (AppUpdate?) -> Unit) {
        val app = c.applicationContext
        val channel = AppSettings.channel(app)
        val handler = Handler(Looper.getMainLooper())
        if (!force) handler.post { callback(stored(app, channel)) }
        val p = prefs(app)
        if (!force && System.currentTimeMillis() - p.getLong("last_$channel", 0L) < INTERVAL_MS) return
        Thread {
            val result = runCatching {
                if (channel == AppSettings.DEV) dev(app) else stable(app)
            }
            result.onSuccess { update ->
                p.edit().putLong("last_$channel", System.currentTimeMillis())
                    .putString("key_$channel", update?.key)
                    .putString("version_$channel", update?.version)
                    .putString("url_$channel", update?.releaseUrl)
                    .putString("apk_$channel", update?.apkUrl)
                    .putString("notes_$channel", update?.notes)
                    .apply()
                handler.post {
                    // Don't show a stale result if the user switched channels during the request.
                    if (AppSettings.channel(app) == channel) callback(update)
                }
            }
            // On a network failure preserve cache and try again on the next start.
            if (result.isFailure) handler.post {
                if (AppSettings.channel(app) == channel) callback(stored(app, channel))
            }
        }.start()
    }

    /** Explicitly opened history for the installed version; cached for offline reading. Run on IO. */
    fun installedNotes(c: Context): String {
        val version = installedVersion(c)
        if (!Regex("\\d+(?:\\.\\d+){1,3}").matches(version)) return ""
        val p = prefs(c)
        val key = "installed_notes_$version"
        val cached = p.getString(key, "").orEmpty()
        if (cached.isNotBlank() && System.currentTimeMillis() - p.getLong("${key}_at", 0L) < INTERVAL_MS)
            return cached
        return runCatching { ReleaseNotes.display(request("releases/tags/v$version").optString("body")) }
            .onSuccess { notes -> if (notes.isNotBlank()) p.edit().putString(key, notes)
                .putLong("${key}_at", System.currentTimeMillis()).apply() }
            .getOrDefault(cached)
    }
}
