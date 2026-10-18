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

    fun channel(c: Context): String =
        prefs(c).getString("channel", STABLE)?.takeIf { it == STABLE || it == DEV } ?: STABLE

    fun setChannel(c: Context, channel: String) {
        require(channel == STABLE || channel == DEV)
        prefs(c).edit().putString("channel", channel).apply()
    }

    fun theme(c: Context): String =
        prefs(c).getString("theme", SYSTEM)?.takeIf {
            it in listOf(SYSTEM, LIGHT, DARK, AMOLED, NEON)
        } ?: SYSTEM

    fun setTheme(c: Context, theme: String) {
        require(theme in listOf(SYSTEM, LIGHT, DARK, AMOLED))
        prefs(c).edit().putString("theme", theme).apply()
        // Push the saved Material theme to every home-screen widget immediately.
        ScheduleWidget.renderAll(c.applicationContext)
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
