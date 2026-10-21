package ru.nspu.schedule

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Scrollable, unbounded widget list instead of four hard-coded rows. */
class LessonListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Factory(private val context: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var rows: List<DayRow> = emptyList()
        private var dayNotes: List<PersonalNote> = emptyList()
        private var date = java.time.LocalDate.now(ZONE)
        private var cards: Map<Lesson, LessonPresentation> = emptyMap()
        private var focus: FocusedLesson? = null
        private var themeMode = WidgetTheme.mode(context)
        private var colors = WidgetTheme.palette(context)

        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() {
            val view = WidgetSettings.view(context, widgetId)
            val data = view.data
            date = view.date()
            val lessons = data.forDate(date)
            rows = DayTimeline.build(lessons)
            val links = ScheduleLinks.Index(data.currentLinks())
            val start = data.termStart()
            val notes = runCatching { Notes.forDay(context, data.id, date) }
                .onFailure { android.util.Log.e("NSPUWidget", "Cannot read widget notes", it) }
                .getOrDefault(emptyList())
            dayNotes = notes.filter { it.target.isDay }
            cards = prepareLessonCards(data.id, date, lessons, start, links, notes)
            focus = data.focus()
            themeMode = WidgetTheme.mode(context)
            colors = WidgetTheme.palette(context)
        }
        override fun onDestroy() { rows = emptyList(); dayNotes = emptyList() }
        override fun getCount(): Int = rows.size + dayNotes.size

        override fun getViewAt(position: Int): RemoteViews? {
            dayNotes.getOrNull(position)?.let { note ->
                return RemoteViews(context.packageName, R.layout.widget_day_note).apply {
                    setInt(R.id.dayNoteCard, "setBackgroundResource", colors.card)
                    setTextColor(R.id.dayNoteTitle, colors.accent)
                    setTextColor(R.id.dayNoteText, colors.body)
                    setTextViewText(R.id.dayNoteTitle, "На день · " + note.target.subject)
                    setTextViewText(R.id.dayNoteText, note.preview)
                    setOnClickFillInIntent(R.id.dayNoteCard, NotesNavigation.fill(Intent(), note.target, note))
                }
            }
            val row = rows.getOrNull(position - dayNotes.size) ?: return null
            if (row is DayRow.WindowRow) {
                return RemoteViews(context.packageName, R.layout.lesson_window).apply {
                    setInt(R.id.windowCard, "setBackgroundResource", colors.card)
                    setTextColor(R.id.windowHeading, colors.accent)
                    setTextColor(R.id.windowMessage, colors.body)
                    setTextViewText(R.id.windowHeading, row.heading + " · " + row.time)
                    setTextViewText(R.id.windowMessage, row.message)
                    setOnClickFillInIntent(R.id.windowCard, Intent())
                }
            }
            val lesson = (row as DayRow.LessonRow).lesson
            val detail = cards[lesson] ?: return null
            val selected = LessonFocus.matches(focus, date, lesson)
            val color = if (selected) {
                if (themeMode == WidgetMode.DARK) 0xFFFFE083.toInt() else colors.accent
            } else WidgetTheme.lessonAccent(detail.kind, themeMode)
            return RemoteViews(context.packageName,
                if (selected) R.layout.lesson_item_focus else R.layout.lesson_item).apply {
                setInt(R.id.lessonCard, "setBackgroundResource",
                    if (selected) colors.focusCard else colors.card)
                setInt(R.id.lessonRoom, "setBackgroundResource", colors.badge)
                setTextColor(R.id.lessonTimeStart, colors.ink)
                setTextColor(R.id.lessonTimeEnd, colors.muted)
                setTextColor(R.id.lessonSubject, colors.ink)
                setTextColor(R.id.lessonTeacher, colors.body)
                setTextColor(R.id.lessonRoom, colors.room)
                setTextColor(R.id.lessonState, colors.accent)
                setTextColor(R.id.lessonChanged, colors.change)
                setTextColor(R.id.lessonNote, colors.accent)
                setTextViewText(R.id.lessonState,
                    if (focus?.ongoing == true) "● до " + lesson.time.substringAfter('-')
                    else "● в " + lesson.time.substringBefore('-'))
                setViewVisibility(R.id.lessonState, if (selected) View.VISIBLE else View.GONE)
                setTextViewText(R.id.lessonTimeStart, lesson.time.substringBefore('-'))
                setTextViewText(R.id.lessonTimeEnd, lesson.time.substringAfter('-', ""))
                setTextViewText(R.id.lessonSubject, detail.subject)
                setTextViewText(R.id.lessonTeacher, detail.teacher)
                setTextViewText(R.id.lessonKind, SiteParser.displayKind(detail.kind))
                setTextColor(R.id.lessonKind, color)
                setInt(R.id.lessonStripe, "setBackgroundColor", color)
                setTextViewText(R.id.lessonRoom, if (detail.room == "—") "—" else "ауд.\n" + detail.room)
                setViewVisibility(R.id.lessonChanged, if (lesson.changed) View.VISIBLE else View.GONE)
                setOnClickFillInIntent(R.id.lessonCard, Intent())
                val visibleNote = detail.note?.takeIf { it.options.visibleOn(date) }
                setViewVisibility(R.id.lessonNote, if (visibleNote != null) View.VISIBLE else View.GONE)
                setTextViewText(R.id.lessonNote, visibleNote?.let { "Заметка: " + it.preview }.orEmpty())
                detail.noteTarget?.let { target ->
                    setOnClickFillInIntent(R.id.lessonNote,
                        NotesNavigation.fill(Intent(), target, detail.note))
                }
            }
        }

        override fun getLoadingView(): RemoteViews? = null
        // Two lesson layouts (normal / focused) and the free-period card.
        override fun getViewTypeCount(): Int = 4
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
    }
}
