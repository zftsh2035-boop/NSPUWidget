package ru.nspu.schedule

import android.content.Context

internal object WidgetSettings {
    private fun prefs(c: Context, id: Int) = c.getSharedPreferences("schedule-widget-$id", Context.MODE_PRIVATE)
    fun groupId(c: Context, id: Int): Int {
        Store.initialize(c)
        val p = prefs(c, id)
        val saved = p.getInt("group_id", -1)
        if (Favorites.find(c, saved) != null) return saved
        val fallback = Favorites.primaryId(c)
        p.edit().putInt("group_id", fallback).apply()
        return fallback
    }
    fun view(c: Context, id: Int) = ScheduleView(Store.data(c, groupId(c, id)), prefs(c, id))
    fun pickerOpen(c: Context, id: Int) = prefs(c, id).getBoolean("picker", false)
    fun setPicker(c: Context, id: Int, open: Boolean) { prefs(c, id).edit().putBoolean("picker", open).apply() }
    fun select(c: Context, id: Int, groupId: Int) {
        if (Favorites.find(c, groupId) == null) return
        prefs(c, id).edit().putInt("group_id", groupId).putBoolean("picker", false)
            .remove("selected_date").remove("offset").apply()
    }
    fun delete(c: Context, id: Int) { prefs(c, id).edit().clear().apply() }
}
