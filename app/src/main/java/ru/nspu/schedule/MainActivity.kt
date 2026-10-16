package ru.nspu.schedule

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
        line("Автообновление каждые 3 часа", 12f, Color.DKGRAY, 4)
        content.addView(action("Группа: " + Store.scheduleGroupName(this) + "   ▾") { chooseFaculty() })

        val notices = Store.notices(this)
        val unread = notices.count { it.isNew }
        line("Объявления ${if (unread > 0) "· $unread новых" else ""}", 20f, navy, 12)
        if (notices.isEmpty()) {
            line("Объявлений нет или данные ещё не загружены.", 13f)
        } else {
            notices.forEach { n ->
                val label = SiteParser.noticeLabel(n) + " · "
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
        val history = Store.archivedNotices(this)
        if (history.isNotEmpty()) {
            line("Больше не на сайте · ${history.size}", 13f, Color.DKGRAY)
            content.addView(action("Показать историю объявлений") {
                val historyText = Store.archivedNotices(this).joinToString("\n\n") { it.text }
                AlertDialog.Builder(this).setTitle("Больше не на сайте НГПУ")
                    .setMessage(historyText.take(18000))
                    .setPositiveButton("Закрыть", null).show()
            })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("Прочитано") {
            Store.markAllRead(this); ScheduleWidget.renderAll(this); show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(action("Сайт НГПУ ↗") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Store.source(this))))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(actions)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            line("Разреши уведомления, чтобы узнавать о новых объявлениях без открытия приложения.", 12f)
            content.addView(action("Разрешить уведомления") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            })
        } else line("Уведомления о новых объявлениях: доступны", 12f, Color.rgb(27, 108, 68), 4)

        line("Расписание занятий", 20f, navy, 12)
        val groups = listOf("Все", "А1", "А2", "А3", "А4", "А5", "А6", "А7", "А8", "А9", "А10", "Немецкий")
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
        else lessons.forEach { lessonCard(it) }
        line("Последняя успешная проверка: ${Store.status(this)} (Новосибирск)", 12f, Color.DKGRAY)
        if (Store.error(this).isNotBlank()) line("Ошибка синхронизации: ${Store.error(this)}. Показан кэш.", 12f, Color.RED)
        line("Статусы: «новое», «на сайте», «больше не на сайте». Даты внутри текста не считаются датами публикации или окончания действия.", 12f, Color.DKGRAY)
    }


    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun chooseFaculty() {
        Toast.makeText(this, "Загружаю институты…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { GroupCatalog.faculties() }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { options ->
                    if (options.isEmpty()) Toast.makeText(this, "Нет доступных институтов", Toast.LENGTH_LONG).show()
                    else AlertDialog.Builder(this).setTitle("Выбери институт")
                        .setItems(options.map { it.title }.toTypedArray()) { _, index -> chooseGroup(options[index]) }
                        .setNegativeButton("Отмена", null).show()
                }.onFailure { Toast.makeText(this, "Каталог недоступен: " + it.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun chooseGroup(faculty: Faculty) {
        Toast.makeText(this, "Загружаю группы…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { GroupCatalog.groups(faculty.id) }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { groups ->
                    if (groups.isEmpty()) Toast.makeText(this, "Группы пока не опубликованы", Toast.LENGTH_LONG).show()
                    else AlertDialog.Builder(this).setTitle(faculty.title + " · группа")
                        .setItems(groups.map { it.title }.toTypedArray()) { _, index ->
                            val g = groups[index]
                            Store.selectScheduleGroup(this, g.id, g.title)
                            ScheduleWidget.renderAll(this)
                            Sync.now(this)
                            show()
                            refreshAfterWork()
                        }.setNegativeButton("Назад", null).show()
                }.onFailure { Toast.makeText(this, "Ошибка загрузки: " + it.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun lessonCard(l: Lesson) {
        val d = SiteParser.details(l)
        val accent = if (l.changed) Color.rgb(245, 162, 72) else SiteParser.lessonColor(d.kind)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(223, 233, 243))
            }
            setPadding(0, dp(10), dp(12), dp(10))
        }
        card.addView(View(this).apply { setBackgroundColor(accent) },
            LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(TextView(this).apply {
            text = l.time.replace("-", "\n")
            textSize = 13f; setTextColor(navy); setPadding(dp(12), dp(4), 0, 0)
        }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = d.kind + if (l.changed) " · ИЗМЕНЕНО" else ""
            textSize = 10f; setTextColor(accent)
        })
        info.addView(TextView(this).apply {
            text = d.subject; textSize = 15f; setTextColor(navy)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(6))
        })
        info.addView(TextView(this).apply {
            text = "Преподаватель: " + d.teacher; textSize = 12f
            setTextColor(Color.rgb(81, 95, 114))
        })
        info.addView(TextView(this).apply {
            text = "Аудитория: " + d.room; textSize = 12f
            setTextColor(Color.rgb(29, 113, 168)); setPadding(0, dp(4), 0, 0)
        })
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) show()
    }
}
