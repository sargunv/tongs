package dev.sargunv.tongs.feature.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.ui.TongsIcons
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Picks when a snoozed thread comes back: presets, each showing its time, or a date and time of
 * your own. On a keyboard, the number beside each preset picks it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeDialog(now: Instant, onPick: (Instant) -> Unit, onDismiss: () -> Unit) {
  var custom by remember { mutableStateOf(false) }
  if (custom) {
    CustomSnooze(now, onPick, onDismiss)
    return
  }
  val options = remember(now) { snoozeOptions(now) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  BasicAlertDialog(onDismissRequest = onDismiss) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
      modifier =
        Modifier.widthIn(min = 280.dp, max = 360.dp)
          .focusRequester(focus)
          .focusable()
          .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val index = NUMBER_KEYS.indexOf(event.key)
            when {
              index in options.indices -> onPick(options[index].until)
              index == options.size -> custom = true
              event.key == Key.Escape -> onDismiss()
              else -> return@onPreviewKeyEvent false
            }
            true
          },
    ) {
      Column(Modifier.padding(vertical = 16.dp)) {
        Text(
          "Snooze until",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        for ((i, option) in options.withIndex()) {
          SnoozeRow(option.label, option.time, i + 1) { onPick(option.until) }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        SnoozeRow("Pick date & time", null, options.size + 1, TongsIcons.Event) { custom = true }
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onDismiss) { Text("Cancel") }
        }
      }
    }
  }
}

private val NUMBER_KEYS =
  listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

@Composable
private fun SnoozeRow(
  label: String,
  time: String?,
  number: Int,
  icon: androidx.compose.ui.graphics.vector.ImageVector = TongsIcons.Snooze,
  onClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    if (time != null) {
      Text(
        time,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (dev.sargunv.tongs.core.ui.isDesktop) {
      Text(
        "$number",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}

/** A date, then a time, in the local zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomSnooze(now: Instant, onPick: (Instant) -> Unit, onDismiss: () -> Unit) {
  val zone = TimeZone.currentSystemDefault()
  val today = now.toLocalDateTime(zone).date
  var date by remember { mutableStateOf<LocalDate?>(null) }
  val picked = date
  if (picked == null) {
    // The date picker works in UTC midnights.
    val dates =
      rememberDatePickerState(
        initialSelectedDateMillis =
          today.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
        selectableDates =
          object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
              Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date >=
                today
          },
      )
    DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
          onClick = {
            date =
              dates.selectedDateMillis?.let {
                Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
              }
          },
          enabled = dates.selectedDateMillis != null,
        ) {
          Text("Next")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
      DatePicker(
        dates,
        title = { Text("Snooze until", Modifier.padding(start = 24.dp, top = 16.dp)) },
      )
    }
  } else {
    val time = rememberTimePickerState(initialHour = 8, initialMinute = 0)
    val until = picked.atTime(LocalTime(time.hour, time.minute)).toInstant(zone)
    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("At what time?") },
      text = { TimePicker(time) },
      confirmButton = {
        TextButton(onClick = { onPick(until) }, enabled = until > now) {
          Text("Snooze until ${snoozeTime(until, now)}")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
  }
}
