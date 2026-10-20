package ru.nspu.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun FavoritesDialog(items: List<FavoriteGroup>, selectedId: Int, primaryId: Int,
    onDismiss: () -> Unit, onSelect: (FavoriteGroup) -> Unit, onAdd: () -> Unit,
    onRename: (FavoriteGroup, String) -> Unit, onRemove: (FavoriteGroup) -> Unit,
    onMakePrimary: (FavoriteGroup) -> Unit) {
    var editing by remember { mutableStateOf<FavoriteGroup?>(null) }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Избранные группы") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(items, key = { it.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onSelect(item) }.padding(vertical = 12.dp)) {
                            Text((if (item.id == selectedId) "● " else "") + item.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (item.id == selectedId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                            Text(item.title, style = MaterialTheme.typography.bodySmall)
                            if (item.id == primaryId) Text("Основная · уведомления",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.semantics {
                                contentDescription = "Настройки группы " + item.defaultName
                            }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                                    menu = false; name = item.displayName; editing = item
                                })
                                if (item.id != primaryId) DropdownMenuItem(text = { Text("Сделать основной") },
                                    onClick = { menu = false; onMakePrimary(item) })
                                if (items.size > 1) DropdownMenuItem(text = { Text("Убрать из избранного") },
                                    onClick = { menu = false; onRemove(item) })
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Добавить группу") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
    editing?.let { item ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Название в избранном") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(80) },
                        label = { Text("Название") }, singleLine = true)
                    Text("Пустое поле вернёт название группы: " + item.defaultName,
                        style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = {
                onRename(item, name); editing = null
            }) { Text("Сохранить") } }, dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Отмена") }
            })
    }
}
