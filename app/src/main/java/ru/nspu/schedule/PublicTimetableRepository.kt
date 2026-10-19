package ru.nspu.schedule

import org.jsoup.Jsoup
import java.time.LocalDate

/** A tiny bounded in-memory cache keeps a few weeks available without extra HTTP calls.
 * No DB, no WebView, no account, no background polling for teacher/room pages.
 */
object PublicTimetableRepository {
    private const val LIFETIME_MS = 12L * 60_000L
    private const val MAX_ENTRIES = 16
    private data class Item(val cachedAt: Long, val data: PublicTimetable)
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Item>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>?): Boolean =
            size > MAX_ENTRIES
    }

    fun load(page: OfficialSchedulePage, weekOffset: Int, includePast: Boolean = false): PublicTimetable {
        require(weekOffset in -80..80) { "Неделя вне доступного диапазона" }
        val suffix = if (includePast) "&old=1" else ""
        val url = page.url + "&nextweek=$weekOffset" + suffix
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cache[url]?.takeIf { now - it.cachedAt < LIFETIME_MS }?.let { return it.data }
        }
        val html = Jsoup.connect(url)
            .userAgent("NSPUWidget-Android/2.5")
            .timeout(14_000)
            .maxBodySize(3_000_000)
            .followRedirects(false)
            .get()
        val parsed = PublicTimetableParser.parse(html, page, weekOffset, LocalDate.now(ZONE))
        synchronized(lock) { cache[url] = Item(now, parsed) }
        return parsed
    }
}
