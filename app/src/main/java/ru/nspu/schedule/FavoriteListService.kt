package ru.nspu.schedule

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class FavoriteListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Factory(val context: Context, val widgetId: Int) : RemoteViewsFactory {
        private var items = emptyList<FavoriteGroup>()
        private var selectedId = -1
        private var colors = WidgetTheme.palette(context)
        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() {
            items = Favorites.list(context)
            selectedId = WidgetSettings.groupId(context, widgetId)
            colors = WidgetTheme.palette(context)
        }
        override fun getViewAt(position: Int): RemoteViews? {
            val item = items.getOrNull(position) ?: return null
            return RemoteViews(context.packageName, R.layout.widget_favorite_item).apply {
                setTextViewText(R.id.favoriteName, (if (item.id == selectedId) "● " else "") + item.displayName)
                setTextColor(R.id.favoriteName, if (item.id == selectedId) colors.accent else colors.ink)
                setTextViewText(R.id.favoriteCode, item.title)
                setTextColor(R.id.favoriteCode, colors.muted)
                setOnClickFillInIntent(R.id.favoriteRow, Intent().putExtra("favorite_id", item.id))
            }
        }
        override fun getCount() = items.size
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items[position].id.toLong()
        override fun hasStableIds() = true
        override fun getLoadingView(): RemoteViews? = null
        override fun onDestroy() { items = emptyList() }
    }
}
