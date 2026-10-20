package ru.nspu.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Virtual dates: scrolling does not change the selected date or allocate a large list. */
@Composable
internal fun DateStrip(date: LocalDate, onSelect: (LocalDate) -> Unit) {
    val center = Int.MAX_VALUE / 2
    val anchor by rememberSaveable { mutableLongStateOf(date.toEpochDay()) }
    val index = (center.toLong() + date.toEpochDay() - anchor).coerceIn(2L, Int.MAX_VALUE.toLong() - 3).toInt()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index - 1)
    val caption = remember { DateTimeFormatter.ofPattern("EE d MMM", Locale.forLanguageTag("ru-RU")) }
    LaunchedEffect(date) {
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) state.scrollToItem(index - 1)
    }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(count = Int.MAX_VALUE, key = { it }) { position ->
            val day = LocalDate.ofEpochDay(anchor + position.toLong() - center)
            FilterChip(selected = day == date, onClick = { onSelect(day) }, label = { Text(day.format(caption)) })
        }
    }
}
