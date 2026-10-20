package ru.nspu.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Favorites {
    private fun prefs(c: Context) = c.getSharedPreferences("schedule", Context.MODE_PRIVATE)
    @Synchronized
    fun list(c: Context): List<FavoriteGroup> {
        val p = prefs(c)
        val saved = runCatching {
            val array = JSONArray(p.getString("favorites", "[]"))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                FavoriteGroup(item.getInt("id"), item.getString("title"), item.optString("name"))
            }.filter { it.id > 0 && it.title.isNotBlank() }.distinctBy { it.id }
        }.getOrDefault(emptyList())
        return saved.ifEmpty {
            listOf(FavoriteGroup(p.getInt("group_id", 3328),
                p.getString("group_name", "3.008.2.26 ИСиТвО")!!))
        }
    }
    fun find(c: Context, id: Int) = list(c).firstOrNull { it.id == id }
    fun primaryId(c: Context): Int {
        val items = list(c)
        return prefs(c).getInt("primary_group_id", items.first().id)
            .takeIf { id -> items.any { it.id == id } } ?: items.first().id
    }
    private fun save(c: Context, items: List<FavoriteGroup>) {
        val json = JSONArray().also { a -> items.forEach { item ->
            a.put(JSONObject().put("id", item.id).put("title", item.title).put("name", item.customName))
        } }
        prefs(c).edit().putString("favorites", json.toString()).apply()
    }
    @Synchronized
    fun add(c: Context, id: Int, title: String) {
        save(c, FavoriteList.add(list(c), FavoriteGroup(id, title)))
    }
    @Synchronized
    fun rename(c: Context, id: Int, name: String) {
        save(c, list(c).map { if (it.id == id) it.renamed(name) else it })
    }
    @Synchronized
    fun makePrimary(c: Context, id: Int) {
        if (find(c, id) != null) prefs(c).edit().putInt("primary_group_id", id).apply()
    }
    @Synchronized
    fun remove(c: Context, id: Int) {
        val remaining = list(c).filterNot { it.id == id }
        if (remaining.isEmpty()) return
        val wasPrimary = primaryId(c) == id
        save(c, remaining)
        if (wasPrimary) makePrimary(c, remaining.first().id)
        if (Store.scheduleGroupId(c) == id) {
            Store.selectScheduleGroup(c, remaining.first().id, remaining.first().title)
        }
    }
}
