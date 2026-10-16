package ru.nspu.schedule

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity: Activity() {
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val navy = Color.rgb(18, 35, 55)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 30, 26, 34)
            setBackgroundColor(Color.rgb(245, 248, 252))
        }
        scroll.addView(content)
        setContentView(scroll)
        Sync.schedule(this)
        if (Store.cached(this).isEmpty()) Sync.now(this)
        show()
        if (Store.cached(this).isEmpty()) refreshAfterWork()
    }
    override fun onResume() { super.onResume(); if (::content.isInitialized) show() }

    private fun refreshAfterWork() {
        handler.postDelayed({ if (!isFinishing && !isDestroyed) show() }, 3000)
        handler.postDelayed({ if (!isFinishing && !isDestroyed) show() }, 9000)
    }
    private fun action(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setOnClickListener { click() }
    }
    private fun line(text: String, size: Float = 15f, color: Int = navy, pad: Int = 8) {
        content.addView(TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            setPadding(2, pad, 2, pad)
        })
    }
    private fun show() {
        content.removeAllViews()
        line("Расписание НГПУ", 25f, navy, 4)
        line("Группа 3.008.2.26 · автоматическая проверка каждые 3 часа", 12f, Color.DKGRAY, 4)

        val notices = Store.notices(this)
        val unread = notices.count { it.isNew }
        line("Объявления ${if (unread > 0) "· $unread новых" else ""}", 20f, navy, 12)
        if (notices.isEmpty()) {
            line("Объявлений нет или данные ещё не загружены.", 13f)
        } else {
            notices.forEach { n ->
                val old = SiteParser.noticeIsOutdated(n, LocalDate.now(ZONE))
                val label = when {
                    n.isNew -> "● НОВОЕ · "
                    old -> "АРХИВ · "
                    else -> "С сайта · "
                }
                val box = TextView(this).apply {
                    text = "$label${SiteParser.formattedNoticeDate(n)}\n${n.text}"
                    textSize = 14f
                    setTextColor(if (n.isNew) Color.rgb(98, 54, 0) else navy)
                    setBackgroundColor(if (n.isNew) Color.rgb(255, 235, 191) else Color.rgb(230, 235, 242))
                    setPadding(17, 13, 17, 13)
                }
                content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
            }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("Прочитано") {
            Store.markAllRead(this); ScheduleWidget.renderAll(this); show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(action("Сайт НГПУ ↗") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE)))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(actions)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            line("Разреши уведомления, чтобы узнавать о новых объявлениях без открытия приложения.", 12f)
            content.addView(action("Разрешить уведомления") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            })
        } else line("Уведомления о новых объявлениях: доступны", 12f, Color.rgb(27, 108, 68), 4)

        line("Расписание занятий", 20f, navy, 12)
        val groups = listOf("А1", "А2", "А3", "А4", "А5", "А6", "А7", "А8", "А9", "А10", "Немецкий")
        line("Подгруппа по иностранному языку", 12f, Color.DKGRAY, 2)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groups)
        spinner.setSelection(groups.indexOf(Store.group(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (Store.group(this@MainActivity) != groups[position]) {
                    Store.setGroup(this@MainActivity, groups[position]); ScheduleWidget.renderAll(this@MainActivity); show()
                }
            }
        }
        content.addView(spinner)
        val toolbar = LinearLayout(this)
        listOf("‹ День", "Сегодня", "День ›", "⟳ Обновить").forEachIndexed { i, label ->
            toolbar.addView(action(label) {
                when (i) {
                    0 -> Store.offset(this, -1)
                    1 -> Store.resetDate(this)
                    2 -> Store.offset(this, 1)
                    3 -> { Sync.now(this); refreshAfterWork() }
                }
                ScheduleWidget.renderAll(this); show()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(toolbar)
        val date = Store.date(this)
        line("${date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale("ru")))} · ${Store.week(date)}-я неделя", 17f)
        val lessons = Store.forDate(this, date)
        if (lessons.isEmpty()) line("Пар нет или данные ещё не загружены", 15f)
        else lessons.forEach { l ->
            line("${if (l.changed) "● ИЗМЕНЕНИЕ\n" else ""}${l.time}\n${l.raw}", 14f,
                if (l.changed) Color.rgb(143, 76, 0) else navy, 12)
        }
        line("Последняя успешная проверка: ${Store.status(this)} (Новосибирск)", 12f, Color.DKGRAY)
        if (Store.error(this).isNotBlank()) line("Ошибка синхронизации: ${Store.error(this)}. Показан кэш.", 12f, Color.RED)
        line("Новые объявления и изменения сохраняются до нажатия «Прочитано». Старые объявления не исчезают из приложения, пока находятся на сайте. При первой загрузке уведомления не отправляются.", 12f, Color.DKGRAY)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) show()
    }
}
