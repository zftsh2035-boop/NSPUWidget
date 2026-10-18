package ru.nspu.schedule

import android.content.Context

/** Simple local preferences; never uploaded to GitHub or the university. */
object AppSettings {
    const val STABLE = "stable"
    const val DEV = "dev"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    const val AMOLED = "amoled"
    const val NEON = "neon"

    private fun prefs(c: Context) = c.getSharedPreferences("app-settings", Context.MODE_PRIVATE)

    private val supportedThemes = setOf(SYSTEM, LIGHT, DARK, AMOLED, NEON)
    fun isSupportedTheme(value: String): Boolean = value in supportedThemes

    fun channel(c: Context): String =
        prefs(c).getString("channel", STABLE)?.takeIf { it == STABLE || it == DEV } ?: STABLE

    fun setChannel(c: Context, channel: String) {
        require(channel == STABLE || channel == DEV)
        prefs(c).edit().putString("channel", channel).apply()
    }

    fun theme(c: Context): String =
        prefs(c).getString("theme", SYSTEM)?.takeIf(::isSupportedTheme) ?: SYSTEM

    fun setTheme(c: Context, theme: String) {
        require(isSupportedTheme(theme))
        prefs(c).edit().putString("theme", theme).apply()
        // App appearance must NOT change the separate widget appearance.
    }

    fun widgetTheme(c: Context): String =
        prefs(c).getString("widget_theme", SYSTEM)?.takeIf(::isSupportedTheme) ?: SYSTEM

    fun setWidgetTheme(c: Context, theme: String) {
        require(isSupportedTheme(theme))
        prefs(c).edit().putString("widget_theme", theme).apply()
        val app = c.applicationContext
        // RemoteViews inflation is IPC; never let a widget/launcher failure close Activity.
        Thread {
            runCatching { ScheduleWidget.renderAll(app) }
                .onFailure { android.util.Log.e("NSPUWidget", "Widget theme update failed", it) }
        }.start()
    }

    fun animateHighlight(c: Context) = prefs(c).getBoolean("animate_highlight", true)
    fun setAnimateHighlight(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean("animate_highlight", enabled).apply()

    fun autoScroll(c: Context) = prefs(c).getBoolean("auto_scroll", true)
    fun setAutoScroll(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean("auto_scroll", enabled).apply()

    fun updatePrompts(c: Context) = prefs(c).getBoolean("update_prompts", true)
    fun setUpdatePrompts(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean("update_prompts", enabled).apply()
}
