package dev.sargunv.tongs.core.ui

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val MONTHS =
  listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** A compact age for list rows: "now", "5m", "3h", "6d", then a date like "Oct 3". */
fun shortAge(epochMillis: Long, nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()) =
  (nowMillis - epochMillis).milliseconds.let { age ->
    when {
      age < 1.minutes -> "now"
      age < 1.hours -> "${age.inWholeMinutes}m"
      age < 1.days -> "${age.inWholeHours}h"
      age < 7.days -> "${age.inWholeDays}d"
      else -> {
        val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date
        val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
        val day = "${MONTHS[date.month.ordinal]} ${date.day}"
        if (date.year == now.year) day else "$day, ${date.year}"
      }
    }
  }
