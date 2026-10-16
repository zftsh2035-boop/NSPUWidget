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
        private var lessons: List<Lesson> = emptyList()
        private var date = Store.date(context)

        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() {
            date = Store.date(context)
            lessons = Store.forDate(context, date)
        }
        override fun onDestroy() { lessons = emptyList() }
        override fun getCount(): Int = lessons.size

        override fun getViewAt(position: Int): RemoteViews? {
            val lesson = lessons.getOrNull(position) ?: return null
            val detail = SiteParser.details(lesson, date)
            val color = SiteParser.lessonColor(detail.kind)
            return RemoteViews(context.packageName, R.layout.lesson_item).apply {
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
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = true
    }
}
