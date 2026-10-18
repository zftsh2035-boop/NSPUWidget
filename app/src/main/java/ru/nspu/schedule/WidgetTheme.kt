package ru.nspu.schedule

import android.content.Context
import android.content.res.Configuration
import android.os.Build

enum class WidgetMode { LIGHT, DARK, AMOLED, NEON }

/** Home-screen RemoteViews cannot inherit MaterialTheme; resolve the saved app theme. */
object WidgetTheme {
    fun mode(setting: String, systemDark: Boolean): WidgetMode = when (setting) {
        AppSettings.LIGHT -> WidgetMode.LIGHT
        AppSettings.DARK -> WidgetMode.DARK
        AppSettings.AMOLED -> WidgetMode.AMOLED
        AppSettings.NEON -> WidgetMode.NEON
        else -> if (systemDark) WidgetMode.DARK else WidgetMode.LIGHT
    }
    fun mode(context: Context): WidgetMode {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return mode(AppSettings.widgetTheme(context), systemDark)
    }
    data class Palette(
        val panel: Int,
        val card: Int,
        val focusCard: Int,
        val badge: Int,
        val notice: Int,
        val ink: Int,
        val body: Int,
        val muted: Int,
        val accent: Int,
        val noticeTitle: Int,
        val noticeText: Int,
        val room: Int,
        val change: Int
    )
    private fun color(value: Long): Int = value.toInt()
    fun palette(context: Context): Palette = palette(mode(context), context)
    fun palette(mode: WidgetMode, context: Context? = null): Palette = when (mode) {
        WidgetMode.LIGHT -> Palette(
            R.drawable.widget_theme_light_panel,
            R.drawable.widget_theme_light_card,
            R.drawable.widget_theme_light_focus,
            R.drawable.widget_theme_light_badge,
            R.drawable.widget_theme_light_notice,
            color(0xFF16243AL), color(0xFF27354CL), color(0xFF53647CL),
            color(0xFF1963BDL), color(0xFF804600L), color(0xFF493319L),
            color(0xFF195FADL), color(0xFFAD5516L)
        )
        WidgetMode.DARK -> Palette(
            R.drawable.panel, R.drawable.lesson_panel, R.drawable.lesson_focus_panel,
            R.drawable.room_panel, R.drawable.notice_panel,
            color(0xFFF9FCFFL), color(0xFFD1E0F3L), color(0xFFAEBFD5L),
            color(0xFF8FC8FFL), color(0xFFFFDC9CL), color(0xFFFFF4E0L),
            color(0xFFA4D7FFL), color(0xFFFFC27CL)
        )
        WidgetMode.AMOLED -> {
            val systemAccent = if (Build.VERSION.SDK_INT >= 31 && context != null) {
                context.getColor(android.R.color.system_accent1_200)
            } else color(0xFF98CBFFL)
            Palette(
                R.drawable.widget_theme_black_panel,
                R.drawable.widget_theme_black_card,
                R.drawable.widget_theme_black_focus,
                R.drawable.widget_theme_black_badge,
                R.drawable.widget_theme_black_notice,
                color(0xFFFFFFFFL), color(0xFFE4E4E4L), color(0xFFA9A9A9L),
                systemAccent, color(0xFFFFD587L), color(0xFFFFE9C7L),
                systemAccent, color(0xFFFFC378L)
            )
        }
        WidgetMode.NEON -> Palette(
            R.drawable.widget_theme_neon_panel,
            R.drawable.widget_theme_neon_card,
            R.drawable.widget_theme_neon_focus,
            R.drawable.widget_theme_neon_badge,
            R.drawable.widget_theme_neon_notice,
            color(0xFFFFFFFFL), color(0xFFF1F1F1L), color(0xFFA5FFCEL),
            color(0xFF39FF14L), color(0xFFFF00EBL), color(0xFFFFFFFFL),
            color(0xFF00F5FFL), color(0xFFFFFF00L)
        )
    }
    fun lessonAccent(kind: String, mode: WidgetMode): Int {
        return if (mode == WidgetMode.NEON) when (kind) {
            "ЛЕКЦИЯ" -> color(0xFF39FF14L)
            "ПРАКТИКА" -> color(0xFFFF00F5L)
            "ЛАБОРАТОРНАЯ" -> color(0xFF00F5FFL)
            "ЗАЧЁТ" -> color(0xFFFFFF00L)
            "ЭКЗАМЕН" -> color(0xFFFF405AL)
            else -> color(0xFFBB75FFL)
        } else if (mode == WidgetMode.LIGHT) when (kind) {
            "ЛЕКЦИЯ" -> color(0xFF176CC5L)
            "ПРАКТИКА" -> color(0xFF047D6CL)
            "ЛАБОРАТОРНАЯ" -> color(0xFF7544C7L)
            "ЗАЧЁТ" -> color(0xFFAC6400L)
            "ЭКЗАМЕН" -> color(0xFFC43A4DL)
            else -> color(0xFF596B84L)
        } else SiteParser.lessonColor(kind)
    }
}
