package ru.nspu.schedule

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

internal fun ChangeRecord.annotated(): AnnotatedString = buildAnnotatedString {
    append(message)
    highlights.filter { it.start >= 0 && it.end > it.start && it.end <= message.length }.forEach {
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.start, it.end)
    }
}
