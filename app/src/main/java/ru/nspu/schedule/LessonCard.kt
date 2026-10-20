package ru.nspu.schedule

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime

@Composable
internal fun LessonCard(card: LessonPresentation, highlighted: Boolean, ongoing: Boolean,
    animateHighlight: Boolean, active: Boolean, neon: Boolean, scrolling: State<Boolean>,
    onOpenSchedule: (OfficialSchedulePage) -> Unit,
    onOpenNote: (LessonPresentation) -> Unit) {
    val accent = remember(card.kind, neon) {
        Color(if (neon) WidgetTheme.lessonAccent(card.kind, WidgetMode.NEON)
            else SiteParser.lessonColor(card.kind))
    }
    val pulse = rememberLessonPulse(highlighted && active && animateHighlight, scrolling)
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer

    Surface(shape = RoundedCornerShape(21.dp),
        tonalElevation = if (highlighted) 0.dp else 2.dp,
        color = MaterialTheme.colorScheme.surface,
        border = if (highlighted) BorderStroke(2.dp, primary)
            else BorderStroke(1.dp, accent.copy(alpha = .34f)),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().drawBehind {
            // Read animated State in drawing only. Text, links and layout are untouched
            // by animation frames; the focused row does not recompose 60 times a second.
            if (highlighted) drawRect(lerp(container, primary, .05f + .19f * pulse.value))
            drawRoundRect(accent, topLeft = Offset.Zero,
                size = Size(5.dp.toPx(), size.height),
                cornerRadius = CornerRadius(2.dp.toPx()))
        }, verticalAlignment = Alignment.Top) {
            Spacer(Modifier.width(5.dp))
            Column(Modifier.width(72.dp).padding(start = 12.dp, top = 14.dp)) {
                Text(card.start, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(card.end, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onOpenNote(card) }, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_note),
                        contentDescription = (if (card.note == null) "Добавить заметку: " else "Открыть заметку: ") +
                            card.subject + ", " + card.start,
                        tint = if (card.note != null) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = accent.copy(alpha = .18f), shape = RoundedCornerShape(8.dp)) {
                        Text(card.kindLabel, fontSize = 12.sp, color = accent,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                    if (card.lesson.changed) {
                        Spacer(Modifier.width(7.dp))
                        Text("● ЗАМЕНА", color = MaterialTheme.colorScheme.error,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (highlighted) {
                    LessonTiming(FocusedLesson(card.date, card.lesson, ongoing), active, pulse)
                }
                Spacer(Modifier.height(6.dp))
                Text(card.subject, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(card.teacher, style = MaterialTheme.typography.bodySmall,
                    color = if (card.teacherPage != null) primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (card.teacherPage != null) Modifier.clickable(
                        role = Role.Button, onClickLabel = "Расписание преподавателя"
                    ) { onOpenSchedule(card.teacherPage) } else Modifier)
                card.note?.takeIf { it.options.visibleOn(card.date) }?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text("Заметка: " + note.preview,
                        style = MaterialTheme.typography.bodySmall, color = primary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable(
                            role = Role.Button, onClickLabel = "Прочитать заметку целиком"
                        ) { onOpenNote(card) }.padding(vertical = 4.dp))
                }
            }
            RoomBadge(card, onOpenSchedule,
                Modifier.padding(start = 4.dp, end = 12.dp, top = 14.dp))
        }
    }
}

@Composable
private fun RoomBadge(card: LessonPresentation, onOpenSchedule: (OfficialSchedulePage) -> Unit,
    modifier: Modifier) {
    Surface(onClick = { card.roomPage?.let(onOpenSchedule) }, enabled = card.roomPage != null,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(56.dp).semantics {
            contentDescription = "Аудитория " + card.room
        }) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("АУД.", fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .7f))
            Text(card.room, fontSize = if (card.room.length <= 4) 17.sp else 11.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** This tiny scope alone observes scroll start/stop. The screen never reads that state. */
@Composable
private fun rememberLessonPulse(enabled: Boolean, scrolling: State<Boolean>): State<Float> {
    return if (enabled && !scrolling.value && ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label = "focused_lesson_pulse").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "focus_brightness")
    } else remember { mutableFloatStateOf(.7f) }
}

/** Only the countdown observes the minute clock. */
@Composable
private fun LessonTiming(focus: FocusedLesson, active: Boolean, pulse: State<Float>) {
    var now by remember(focus) { mutableStateOf(LocalDateTime.now(ZONE)) }
    LaunchedEffect(focus, active) {
        if (active) {
            now = LocalDateTime.now(ZONE)
            while (true) {
                delay(60_000L - System.currentTimeMillis() % 60_000L + 150L)
                now = LocalDateTime.now(ZONE)
            }
        }
    }
    val timing = remember(now, focus) { FocusDisplay.of(now, focus) }
    val primary = MaterialTheme.colorScheme.primary
    Spacer(Modifier.height(7.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(7.dp)) {
            drawCircle(primary, alpha = .22f + .78f * pulse.value)
        }
        Spacer(Modifier.width(5.dp))
        Text(timing?.label ?: "Ближайшая пара", color = primary,
            fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
    }
    if (timing != null) {
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(progress = { timing.progress },
            modifier = Modifier.fillMaxWidth().height(4.dp), color = primary,
            trackColor = primary.copy(alpha = .15f))
    }
}
