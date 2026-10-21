package ru.nspu.schedule

import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A small local journal. Never records lessons, note text, favourite names or network addresses. */
internal object WidgetDiagnostics {
    private fun prefs(c: Context) = c.getSharedPreferences("widget-diagnostics", Context.MODE_PRIVATE)

    @Synchronized
    fun record(c: Context, action: String, id: Int = -1, error: Throwable? = null) {
        runCatching {
            val p = prefs(c)
            val now = System.currentTimeMillis()
            val types = generateSequence(error) { it.cause }.take(4)
                .joinToString(" → ") { it.javaClass.simpleName }
            val entry = "${Instant.ofEpochMilli(now)} $action" +
                (if (id >= 0) " widget=$id" else "") + (if (types.isNotEmpty()) " error=$types" else "")
            val journal = (p.getString("events", "").orEmpty().lines().filter(String::isNotBlank) + entry)
                .takeLast(30).joinToString("\n")
            p.edit().putString("events", journal).apply {
                if (action == "render_ok") putLong("render_$id", now)
            }.apply()
        }
    }

    /** Run on IO: the bounded WorkManager query must not block the UI. */
    @Suppress("DEPRECATION")
    fun report(c: Context): String = buildString {
        appendLine("NSPUWidget · диагностика виджетов")
        appendLine("Время (UTC): ${Instant.now()}")
        appendLine("Версия: ${UpdateChecker.installedVersion(c)} · ${BuildConfig.GIT_SHA.take(8)}")
        appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        appendLine("Плотность: ${c.resources.displayMetrics.density}")
        fun line(label: String, value: () -> Any?) {
            appendLine("$label: " + runCatching(value).getOrElse { it.javaClass.simpleName })
        }
        line("Лаунчер") {
            c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName ?: "не определён"
        }
        val power = c.getSystemService(PowerManager::class.java)
        line("Энергосбережение") { power?.isPowerSaveMode }
        line("Без оптимизации батареи") { power?.isIgnoringBatteryOptimizations(c.packageName) }
        if (Build.VERSION.SDK_INT >= 28) line("Фон ограничен Android") {
            c.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
        }
        val network = c.getSystemService(ConnectivityManager::class.java)
        line("Сеть подключена") { network?.activeNetworkInfo?.isConnected == true }
        line("Экономия трафика (1=нет, 2=исключение, 3=ограничен)") { network?.restrictBackgroundStatus }
        val manager = AppWidgetManager.getInstance(c)
        val ids = runCatching { manager.getAppWidgetIds(ComponentName(c, ScheduleWidget::class.java)) }
            .getOrElse { appendLine("Ошибка списка виджетов: ${it.javaClass.simpleName}"); intArrayOf() }
        appendLine("Виджетов: ${ids.size}")
        for (id in ids) {
            appendLine("\nВиджет $id")
            line("Размеры dp (minWidth/minHeight/maxWidth/maxHeight)") {
                val o = manager.getAppWidgetOptions(id)
                listOf(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    .joinToString("/") { o.getInt(it).toString() }
            }
            line("Последняя успешная отправка в лаунчер") {
                prefs(c).getLong("render_$id", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: "не записана"
            }
            line("Данные группы (id/строк/дата)") {
                val view = WidgetSettings.view(c, id)
                "${view.data.id}/${view.data.cached().size}/${view.date()}"
            }
            line("Ошибка загрузки сохранена") { WidgetSettings.view(c, id).data.error().isNotBlank() }
        }
        line("Кэш в приложении (id/строк)") {
            val data = Store.data(c)
            "${data.id}/${data.cached().size}"
        }
        val workNames = listOf("nspu-sync") + runCatching { Favorites.list(c).map { "nspu-group-${it.id}" } }
            .getOrDefault(emptyList())
        for (name in workNames.distinct().take(12)) line("Задача $name") {
            WorkManager.getInstance(c).getWorkInfosForUniqueWork(name).get(500, TimeUnit.MILLISECONDS)
                .joinToString { "${it.state} (попыток ${it.runAttemptCount})" }.ifBlank { "нет" }
        }
        appendLine("\nСобытия (отправка в лаунчер не подтверждает отрисовку на экране):")
        appendLine(prefs(c).getString("events", "").orEmpty().ifBlank { "Ещё не записаны" })
    }
}
