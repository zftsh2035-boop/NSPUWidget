package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test

class FavoriteGroupTest {
    @Test fun defaultNameUsesGroupCodeAndRenameKeepsOfficialIdentity() {
        val original = FavoriteGroup(3328, "3.008.2.26 ИСиТвО")
        assertEquals("3.008.2.26", original.displayName)
        val renamed = original.renamed("  Мой друг  ")
        assertEquals("Мой друг", renamed.displayName)
        assertEquals(original.id, renamed.id)
        assertEquals(original.title, renamed.title)
        assertEquals("3.008.2.26", renamed.renamed(" ").displayName)
    }
    @Test fun selectingExistingGroupDoesNotDuplicateOrLoseItsNickname() {
        val original = FavoriteGroup(3328, "3.008.2.26 ИСиТвО").renamed("Моё")
        val friend = FavoriteGroup(9001, "3.001.2.26 Другое направление")
        val result = FavoriteList.add(listOf(original, friend), FavoriteGroup(3328, "3.008.2.26 Уточнённое название"))
        assertEquals(listOf(3328, 9001), result.map { it.id })
        assertEquals("Моё", result.first().displayName)
        assertEquals("3.008.2.26 Уточнённое название", result.first().title)
    }
    @Test fun fourGroupsHaveIndependentNamesEvenWhenNicknamesMatch() {
        val groups = (1..4).fold(emptyList<FavoriteGroup>()) { items, id ->
            FavoriteList.add(items, FavoriteGroup(id, "3.00$id.2.26 Направление").renamed("Друг"))
        }
        assertEquals(4, groups.size)
        assertEquals(4, groups.map { it.id }.distinct().size)
        assertEquals("3.003.2.26", groups[2].defaultName)
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidUniversityGroupCannotEnterFavorites() {
        FavoriteList.add(emptyList(), FavoriteGroup(-1, "Неизвестная группа"))
    }
}
