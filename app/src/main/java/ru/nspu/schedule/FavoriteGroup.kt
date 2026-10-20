package ru.nspu.schedule

import androidx.compose.runtime.Immutable

@Immutable
data class FavoriteGroup(val id: Int, val title: String, val customName: String = "") {
    val defaultName: String get() = title.substringBefore(' ').trim()
    val displayName: String get() = customName.ifBlank { defaultName }
    fun renamed(value: String) = copy(customName = value.trim().take(80)
        .takeUnless { it == defaultName }.orEmpty())
}

/** Pure catalogue operations also used by migration and tests. */
object FavoriteList {
    fun add(items: List<FavoriteGroup>, group: FavoriteGroup): List<FavoriteGroup> {
        require(group.id > 0 && group.title.isNotBlank())
        val previous = items.firstOrNull { it.id == group.id }
        return if (previous == null) items + group
            else items.map { if (it.id == group.id) it.copy(title = group.title) else it }
    }
}
