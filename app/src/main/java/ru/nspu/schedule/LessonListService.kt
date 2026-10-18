package ru.nspu.schedule

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Scrollable, unbounded widget list instead of four hard-coded rows. */
class LessonListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var rows: List<DayRow> = emptyList()
        private var date = Store.date(context)
        private var focus: FocusedLesson? = null

        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() {
            date = Store.date(context)
            rows = DayTimeline.build(Store.forDate(context, date))
            focus = Store.focus(context)
        }
        override fun onDestroy() { rows = emptyList() }
        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews? {
            val row = rows.getOrNull(position) ?: return null
            if (row is DayRow.WindowRow) {
                return RemoteViews(context.packageName, R.layout.lesson_window).apply {
                    setTextViewText(R.id.windowHeading, row.heading + " · " + row.time)
                    setTextViewText(R.id.windowMessage, row.message)
                    setOnClickFillInIntent(R.id.windowCard, Intent())
                }
            }
            val lesson = (row as DayRow.LessonRow).lesson
            val detail = SiteParser.details(lesson, date, Store.termStart(context))
            val selected = LessonFocus.matches(focus, date, lesson)
            val color = if (selected) 0xFFFFE083.toInt() else SiteParser.lessonColor(detail.kind)
            return RemoteViews(context.packageName,
                if (selected) R.layout.lesson_item_focus else R.layout.lesson_item).apply {
                setTextViewText(R.id.lessonState,
                    if (focus?.ongoing == true) "● до " + lesson.time.substringAfter('-')
                    else "● в " + lesson.time.substringBefore('-'))
                setViewVisibility(R.id.lessonState, if (selected) View.VISIBLE else View.GONE)
                setTextViewText(R.id.lessonTimeStart, lesson.time.substringBefore('-'))
                setTextViewText(R.id.lessonTimeEnd, lesson.time.substringAfter('-', ""))
                setTextViewText(R.id.lessonSubject, detail.subject)
                setTextViewText(R.id.lessonTeacher, detail.teacher)
                setTextViewText(R.id.lessonKind, SiteParser.shortKind(detail.kind))
                setTextColor(R.id.lessonKind, color)
                setInt(R.id.lessonStripe, "setBackgroundColor", color)
                setTextViewText(R.id.lessonRoom, if (detail.room == "—") "—" else "ауд.\n" + detail.room)
                setViewVisibility(R.id.lessonChanged, if (lesson.changed) View.VISIBLE else View.GONE)
                setOnClickFillInIntent(R.id.lessonCard, Intent())
            }
        }

        override fun getLoadingView(): RemoteViews? = null
        // Two lesson layouts (normal / focused) and the free-period card.
        override fun getViewTypeCount(): Int = 3
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
    }
}
