package ru.nspu.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Public timetable: bounded memory/disk cache, stale data shown instantly,
 * background revalidation and single-flight network loads.
 * Call disk and HTTP functions from Dispatchers.IO only.
 */
object PublicTimetableRepository {
    const val PARSER_VERSION = 4
    const val FRESH_MS = 20L * 60_000L
    private const val MAX_AGE_MS = 24L * 60 * 60_000L
    private const val MAX_DISK_BYTES = 1_200_000
    private data class Entry(val loadedAt: Long, val data: PublicTimetable)
    private val lock = Any()
    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val cache = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > 16
    }

    private fun requestUrl(page: OfficialSchedulePage, weekOffset: Int, includePast: Boolean): String {
        require(weekOffset in -80..80)
        return page.url + "&nextweek=$weekOffset" + if (includePast) "&old=1" else ""
    }

    private fun diskFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "nspu-public-$digest.json")
    }

    fun memory(page: OfficialSchedulePage, weekOffset: Int, includePast: Boolean): PublicTimetable? {
        val url = requestUrl(page, weekOffset, includePast)
        return synchronized(lock) { cache[url]?.data }
    }

    /** Disk-backed snapshots survive app restart. Never call on main thread. */
    fun cached(context: Context, page: OfficialSchedulePage, weekOffset: Int,
               includePast: Boolean = false): PublicTimetable? {
        val url = requestUrl(page, weekOffset, includePast)
        synchronized(lock) {
            cache[url]?.let {
                if (System.currentTimeMillis() - it.loadedAt < MAX_AGE_MS) return it.data
            }
        }
        val file = diskFile(context, url)
        if (!file.isFile || file.length() > MAX_DISK_BYTES ||
            System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt("parserVersion") != PARSER_VERSION) {
                file.delete()
                return null
            }
            val data = decode(json)
            synchronized(lock) { cache[url] = Entry(file.lastModified(), data) }
            data
        }.getOrNull()
    }

    fun isFresh(context: Context, page: OfficialSchedulePage,
                weekOffset: Int, includePast: Boolean): Boolean {
        val url = requestUrl(page, weekOffset, includePast)
        val stamp = synchronized(lock) { cache[url]?.loadedAt }
            ?: diskFile(context, url).lastModified()
        return stamp > 0L && System.currentTimeMillis() - stamp < FRESH_MS
    }

    fun load(context: Context, page: OfficialSchedulePage, weekOffset: Int,
             includePast: Boolean = false, force: Boolean = false): PublicTimetable {
        val url = requestUrl(page, weekOffset, includePast)
        if (!force) cached(context, page, weekOffset, includePast)
            ?.takeIf { isFresh(context, page, weekOffset, includePast) }
            ?.let { return it }
        // A prefetch and foreground open for the same week share one network request.
        val key = keyLocks.getOrPut(url) { Any() }
        synchronized(key) {
            if (!force) cached(context, page, weekOffset, includePast)
                ?.takeIf { isFresh(context, page, weekOffset, includePast) }
                ?.let { return it }
            val start = System.nanoTime()
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android) NSPUWidget/2.6")
                .timeout(11_000)
                .maxBodySize(3_000_000)
                .followRedirects(false)
                .get()
            val parsed = PublicTimetableParser.parse(doc, page, weekOffset, LocalDate.now(ZONE))
            val duration = (System.nanoTime() - start) / 1_000_000L
            android.util.Log.i("NSPUWidget", "public schedule load " + page.kind + ": " + duration + "ms")
            val entry = Entry(System.currentTimeMillis(), parsed)
            synchronized(lock) { cache[url] = entry }
            runCatching {
                val file = diskFile(context, url)
                val tmp = File(file.parentFile, file.name + ".tmp")
                val data = encode(parsed).toString()
                if (data.length < MAX_DISK_BYTES) {
                    tmp.writeText(data, Charsets.UTF_8)
                    if (!tmp.renameTo(file)) {
                        file.writeText(data, Charsets.UTF_8)
                        tmp.delete()
                    }
                    val all = context.cacheDir.listFiles { f ->
                        f.name.startsWith("nspu-public-") && f.name.endsWith(".json")
                    }?.sortedByDescending { it.lastModified() } ?: emptyList()
                    all.drop(28).forEach { it.delete() }
                }
            }
            return entry.data
        }
    }

    private fun encode(v: PublicTimetable): JSONObject = JSONObject()
        .put("parserVersion", PARSER_VERSION)
        .put("title", v.title).put("week", v.week)
        .put("weekStart", v.weekStart.toString())
        .put("roomInfo", v.roomInfo).put("hasPast", v.hasPastButton)
        .put("sessions", JSONArray().also { array ->
            v.sessions.forEach { lesson ->
                array.put(JSONObject().put("time", lesson.time).put("subject", lesson.subject)
                    .put("description", lesson.description).put("kind", lesson.kind)
                    .put("day", lesson.day).put("date", lesson.date?.toString() ?: "")
                    .put("section", lesson.section.name)
                    .put("links", JSONArray().also { refs ->
                        lesson.links.forEach { r ->
                            refs.put(JSONObject().put("label", r.label)
                                .put("url", r.url).put("type", r.type))
                        }
                    }))
            }
        })

    private fun decode(o: JSONObject): PublicTimetable {
        val events = o.getJSONArray("sessions")
        val lessons = (0 until events.length()).map { i ->
            val e = events.getJSONObject(i)
            val refs = e.getJSONArray("links")
            val links = (0 until refs.length()).map { n ->
                val r = refs.getJSONObject(n)
                PublicReference(r.getString("label"), r.getString("url"), r.getString("type"))
            }
            PublicClass(e.getString("time"), e.getString("subject"),
                e.getString("description"), e.getString("kind"), links,
                e.getInt("day"), e.optString("date").takeIf { it.isNotBlank() }
                    ?.let(LocalDate::parse), PublicSection.valueOf(e.getString("section")))
        }
        return PublicTimetable(o.getString("title"), o.getInt("week"),
            LocalDate.parse(o.getString("weekStart")), lessons,
            o.optString("roomInfo"), o.optBoolean("hasPast"))
    }
}
