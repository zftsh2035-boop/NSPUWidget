package ru.nspu.schedule

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

enum class ScheduleLinkKind { TEACHER, ROOM }

data class ScheduleLink(val kind: ScheduleLinkKind, val label: String, val url: String)

/** Use actual hyperlinks in the official NSPU group page, never guess teacher/room ids. */
object ScheduleLinks {
    private const val HOST = "https://schedule.nspu.ru"

    fun normalized(text: String): String = text.lowercase(java.util.Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("""[^\p{L}\p{N}#]+"""), "")

    private fun roomLabel(text: String): String = normalized(
        text.trim().replace(Regex("""(?iu)^(?:а\.|ауд\.?|аудитория)\s*"""), "")
    )

    fun fromPage(doc: Document): List<ScheduleLink> = doc.select("a[href]").mapNotNull { anchor ->
        val href = anchor.absUrl("href")
        val kind = when {
            href.startsWith("$HOST/teacher_shedule.php?") -> ScheduleLinkKind.TEACHER
            href.startsWith("$HOST/room_shedule.php?") -> ScheduleLinkKind.ROOM
            else -> return@mapNotNull null
        }
        val match = Regex("""(?:\?|&)id=(\d+)(?:&|$)""").find(href)
            ?: return@mapNotNull null
        val label = anchor.text().trim().take(120)
        if (label.isBlank()) return@mapNotNull null
        val id = match.groupValues[1]
        val path = if (kind == ScheduleLinkKind.TEACHER) "teacher_shedule.php"
            else "room_shedule.php"
        ScheduleLink(kind, label, "$HOST/$path?id=$id")
    }.distinctBy { it.kind to it.label }

    fun teacherLink(links: List<ScheduleLink>, raw: String): ScheduleLink? {
        val candidate = normalized(raw)
        return links.firstOrNull {
            it.kind == ScheduleLinkKind.TEACHER &&
                normalized(it.label).let { key -> key.length >= 5 && candidate.contains(key) }
        }
    }

    fun teacherUrl(links: List<ScheduleLink>, display: String): String? =
        teacherLink(links, display)?.url

    fun roomUrl(links: List<ScheduleLink>, room: String): String? {
        val wanted = roomLabel(room)
        if (wanted.isBlank() || wanted == "—") return null
        return links.firstOrNull {
            it.kind == ScheduleLinkKind.ROOM && roomLabel(it.label) == wanted
        }?.url
    }

    fun encode(links: List<ScheduleLink>): String = JSONArray().also { array ->
        links.forEach {
            array.put(JSONObject().put("kind", it.kind.name)
                .put("label", it.label).put("url", it.url))
        }
    }.toString()

    fun decode(json: String): List<ScheduleLink> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val kind = runCatching { ScheduleLinkKind.valueOf(item.getString("kind")) }.getOrNull()
                ?: return@mapNotNull null
            val label = item.optString("label")
            val url = item.optString("url")
            // Reject mutated or stale untrusted destinations.
            val expected = if (kind == ScheduleLinkKind.TEACHER) "teacher_shedule.php"
                else "room_shedule.php"
            val prefix = "$HOST/$expected?id="
            val identifier = url.removePrefix(prefix)
            if (!url.startsWith(prefix) || identifier.isBlank() || !identifier.all { it.isDigit() })
                return@mapNotNull null
            ScheduleLink(kind, label, url)
        }
    }.getOrDefault(emptyList())
}
