package com.opencray.app

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun parseScheduledTaskAbsoluteEpochMs(
  value: String,
  fieldName: String,
): Long = parseScheduledTaskDateTimeEpochMs(
  value = value,
  timezoneId = null,
  fieldName = fieldName,
  allowLocalDateTime = false,
)

internal fun parseScheduledTaskDelayMs(
  value: String,
  fieldName: String,
): Long = runCatching {
  Duration.parse(value.trim())
}.getOrElse {
  throw IllegalArgumentException(
    "$fieldName must be an ISO-8601 duration such as PT2H or P1D.",
  )
}.toMillis().also { delayMs ->
  require(delayMs >= 1L) { "$fieldName must resolve to at least 1 millisecond." }
}

internal fun parseScheduledTaskRecurrenceTrigger(
  startAt: String,
  timezone: String?,
  rrule: String,
  exdates: List<String>,
  rdates: List<String>,
): ScheduledTrigger.Recurrence {
  val zoneId = resolveScheduledTaskZoneId(
    timezoneId = timezone,
    startAt = startAt,
    fieldName = "trigger.timezone",
  )
  val startAtEpochMs = parseScheduledTaskDateTimeEpochMs(
    value = startAt,
    timezoneId = zoneId.id,
    fieldName = "trigger.start_at",
    allowLocalDateTime = true,
  )
  parseScheduledTaskRRule(
    rrule = rrule,
    timezoneId = zoneId.id,
    startAtEpochMs = startAtEpochMs,
  )
  val exdatesEpochMs = exdates.mapIndexed { index, value ->
    parseScheduledTaskDateTimeEpochMs(
      value = value,
      timezoneId = zoneId.id,
      fieldName = "trigger.exdates[$index]",
      allowLocalDateTime = true,
    )
  }.distinct().sorted()
  val rdatesEpochMs = rdates.mapIndexed { index, value ->
    parseScheduledTaskDateTimeEpochMs(
      value = value,
      timezoneId = zoneId.id,
      fieldName = "trigger.rdates[$index]",
      allowLocalDateTime = true,
    )
  }.distinct().sorted()
  return ScheduledTrigger.Recurrence(
    startAtEpochMs = startAtEpochMs,
    timezoneId = zoneId.id,
    rrule = rrule.trim(),
    exdatesEpochMs = exdatesEpochMs,
    rdatesEpochMs = rdatesEpochMs,
  )
}

internal fun scheduledRecurrenceTriggerSummary(
  trigger: ScheduledTrigger.Recurrence,
): String = buildString {
  append("rrule:")
  append(trigger.rrule)
  append(";start_at=")
  append(formatScheduledTaskDateTime(trigger.startAtEpochMs, trigger.timezoneId))
  append(";timezone=")
  append(trigger.timezoneId)
  if (trigger.exdatesEpochMs.isNotEmpty()) {
    append(";exdates=")
    append(trigger.exdatesEpochMs.size)
  }
  if (trigger.rdatesEpochMs.isNotEmpty()) {
    append(";rdates=")
    append(trigger.rdatesEpochMs.size)
  }
}

internal fun nextScheduledRecurrenceTriggerAtEpochMs(
  trigger: ScheduledTrigger.Recurrence,
  afterEpochMs: Long,
): Long? {
  val pattern = ParsedScheduledTaskRecurrence.from(trigger)
  val nextRule = pattern.ruleOccurrences()
    .firstOrNull { candidateEpochMs -> candidateEpochMs > afterEpochMs }
  val nextRDate = pattern.rdatesEpochMs
    .firstOrNull { candidateEpochMs -> candidateEpochMs > afterEpochMs }
  return listOfNotNull(nextRule, nextRDate).minOrNull()
}

internal fun dueScheduledRecurrenceTriggerAtEpochMs(
  trigger: ScheduledTrigger.Recurrence,
  nowEpochMs: Long,
): Long? {
  val pattern = ParsedScheduledTaskRecurrence.from(trigger)
  var latestRuleOccurrenceEpochMs: Long? = null
  for (candidateEpochMs in pattern.ruleOccurrences()) {
    if (candidateEpochMs > nowEpochMs) {
      break
    }
    latestRuleOccurrenceEpochMs = candidateEpochMs
  }
  val latestRDateEpochMs = pattern.rdatesEpochMs
    .lastOrNull { candidateEpochMs -> candidateEpochMs <= nowEpochMs }
  return listOfNotNull(latestRuleOccurrenceEpochMs, latestRDateEpochMs).maxOrNull()
}

internal fun formatScheduledTaskDateTime(
  epochMs: Long,
  timezoneId: String,
): String = Instant.ofEpochMilli(epochMs)
  .atZone(ZoneId.of(timezoneId))
  .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private fun parseScheduledTaskDateTimeEpochMs(
  value: String,
  timezoneId: String?,
  fieldName: String,
  allowLocalDateTime: Boolean,
): Long {
  val normalizedValue = value.trim()
  require(normalizedValue.isNotEmpty()) { "$fieldName must not be blank." }
  parseInstantOrNull(normalizedValue)?.let { instant ->
    return instant.toEpochMilli()
  }
  if (allowLocalDateTime && timezoneId != null) {
    runCatching {
      LocalDateTime.parse(normalizedValue)
        .atZone(ZoneId.of(timezoneId))
        .toInstant()
        .toEpochMilli()
    }.getOrNull()?.let { epochMs ->
      return epochMs
    }
  }
  val expectedFormat = if (allowLocalDateTime && timezoneId != null) {
    "an ISO-8601 date-time with offset, or a local date-time resolvable in timezone '$timezoneId'"
  } else {
    "an ISO-8601 date-time with offset"
  }
  throw IllegalArgumentException("$fieldName must be $expectedFormat.")
}

private fun resolveScheduledTaskZoneId(
  timezoneId: String?,
  startAt: String,
  fieldName: String,
): ZoneId {
  val normalizedTimezoneId = timezoneId
    ?.trim()
    ?.takeIf(String::isNotBlank)
  if (normalizedTimezoneId != null) {
    return runCatching {
      ZoneId.of(normalizedTimezoneId)
    }.getOrElse {
      throw IllegalArgumentException("$fieldName must be a valid IANA timezone or UTC offset.")
    }
  }
  runCatching { ZonedDateTime.parse(startAt.trim()).zone }.getOrNull()?.let { return it }
  runCatching { OffsetDateTime.parse(startAt.trim()).offset }.getOrNull()?.let { return it }
  runCatching { Instant.parse(startAt.trim()) }.getOrNull()?.let { return ZoneOffset.UTC }
  throw IllegalArgumentException(
    "trigger.timezone is required when trigger.start_at does not already include a timezone or offset.",
  )
}

private fun parseInstantOrNull(value: String): Instant? {
  runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
  runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()?.let { return it }
  runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()?.let { return it }
  return null
}

private data class ParsedScheduledTaskRecurrence(
  val startAt: ZonedDateTime,
  val zoneId: ZoneId,
  val rule: ParsedScheduledTaskRRule,
  val exdatesEpochMs: Set<Long>,
  val rdatesEpochMs: List<Long>,
) {
  fun ruleOccurrences(): Sequence<Long> = sequence {
    when (rule.frequency) {
      ScheduledTaskRRuleFrequency.DAILY -> {
        var emittedPeriods = 0
        var current = startAt
        while (emittedPeriods < MAX_RECURRENCE_PERIODS) {
          val candidateEpochMs = current.toInstant().toEpochMilli()
          if (candidateEpochMs !in exdatesEpochMs) {
            yield(candidateEpochMs)
          }
          current = current.plusDays(rule.interval.toLong())
          emittedPeriods += 1
        }
      }

      ScheduledTaskRRuleFrequency.WEEKLY -> {
        val weekDays = (rule.byDay.ifEmpty { listOf(startAt.dayOfWeek) })
          .sortedBy { dayOfWeek -> dayOfWeek.value }
        val firstWeekStart = startAt.toLocalDate().minusDays((startAt.dayOfWeek.value - 1).toLong())
        var emittedPeriods = 0
        while (emittedPeriods < MAX_RECURRENCE_PERIODS) {
          val weekStart = firstWeekStart.plusWeeks(emittedPeriods.toLong() * rule.interval.toLong())
          weekDays.forEach { dayOfWeek ->
            val candidate = ZonedDateTime.of(
              weekStart.plusDays((dayOfWeek.value - 1).toLong()),
              startAt.toLocalTime(),
              zoneId,
            )
            if (candidate >= startAt) {
              val candidateEpochMs = candidate.toInstant().toEpochMilli()
              if (candidateEpochMs !in exdatesEpochMs) {
                yield(candidateEpochMs)
              }
            }
          }
          emittedPeriods += 1
        }
      }

      ScheduledTaskRRuleFrequency.MONTHLY -> {
        val firstMonth = YearMonth.from(startAt)
        var emittedPeriods = 0
        while (emittedPeriods < MAX_RECURRENCE_PERIODS) {
          val targetMonth = firstMonth.plusMonths(emittedPeriods.toLong() * rule.interval.toLong())
          monthlyCandidates(targetMonth).forEach { candidate ->
            if (candidate >= startAt) {
              val candidateEpochMs = candidate.toInstant().toEpochMilli()
              if (candidateEpochMs !in exdatesEpochMs) {
                yield(candidateEpochMs)
              }
            }
          }
          emittedPeriods += 1
        }
      }
    }
  }

  private fun monthlyCandidates(targetMonth: YearMonth): List<ZonedDateTime> {
    val candidateDates: List<LocalDate> = when {
      rule.byMonthDay.isNotEmpty() -> rule.byMonthDay.mapNotNull { monthDay ->
        resolveMonthDay(targetMonth, monthDay)
      }

      rule.byDay.isNotEmpty() -> {
        val matchingDates = datesInMonthMatchingDays(
          targetMonth = targetMonth,
          days = rule.byDay,
        )
        if (rule.bySetPos.isEmpty()) {
          matchingDates
        } else {
          selectBySetPosition(
            dates = matchingDates,
            positions = rule.bySetPos,
          )
        }
      }

      else -> listOfNotNull(resolveMonthDay(targetMonth, startAt.dayOfMonth))
    }
    return candidateDates
      .distinct()
      .sorted()
      .map { date ->
        ZonedDateTime.of(date, startAt.toLocalTime(), zoneId)
      }
  }

  companion object {
    fun from(trigger: ScheduledTrigger.Recurrence): ParsedScheduledTaskRecurrence {
      val zoneId = ZoneId.of(trigger.timezoneId)
      val parsedRule = parseScheduledTaskRRule(
        rrule = trigger.rrule,
        timezoneId = trigger.timezoneId,
        startAtEpochMs = trigger.startAtEpochMs,
      )
      val exdatesEpochMs = trigger.exdatesEpochMs.toSet()
      val rdatesEpochMs = trigger.rdatesEpochMs
        .asSequence()
        .filterNot(exdatesEpochMs::contains)
        .distinct()
        .sorted()
        .toList()
      return ParsedScheduledTaskRecurrence(
        startAt = Instant.ofEpochMilli(trigger.startAtEpochMs).atZone(zoneId),
        zoneId = zoneId,
        rule = parsedRule,
        exdatesEpochMs = exdatesEpochMs,
        rdatesEpochMs = rdatesEpochMs,
      )
    }
  }
}

private enum class ScheduledTaskRRuleFrequency {
  DAILY,
  WEEKLY,
  MONTHLY,
}

private data class ParsedScheduledTaskRRule(
  val frequency: ScheduledTaskRRuleFrequency,
  val interval: Int,
  val byDay: List<DayOfWeek>,
  val byMonthDay: List<Int>,
  val bySetPos: List<Int>,
)

private fun parseScheduledTaskRRule(
  rrule: String,
  timezoneId: String,
  startAtEpochMs: Long,
): ParsedScheduledTaskRRule {
  val normalizedRule = rrule.trim()
  require(normalizedRule.isNotEmpty()) { "trigger.rrule must not be blank." }
  val pairs = normalizedRule.split(";")
    .map(String::trim)
    .filter(String::isNotBlank)
  require(pairs.isNotEmpty()) { "trigger.rrule must contain RFC5545-style key=value pairs." }
  val valuesByKey = linkedMapOf<String, String>()
  pairs.forEach { part ->
    val separatorIndex = part.indexOf('=')
    require(separatorIndex > 0 && separatorIndex < part.lastIndex) {
      "trigger.rrule entry '$part' must be KEY=VALUE."
    }
    val key = part.substring(0, separatorIndex).trim().uppercase()
    val value = part.substring(separatorIndex + 1).trim()
    require(value.isNotEmpty()) { "trigger.rrule entry '$key' must not be empty." }
    require(valuesByKey.put(key, value) == null) { "trigger.rrule key '$key' must not repeat." }
  }
  val unsupportedKeys = valuesByKey.keys - SUPPORTED_RRULE_KEYS
  require(unsupportedKeys.isEmpty()) {
    "trigger.rrule contains unsupported keys: ${unsupportedKeys.sorted().joinToString(separator = ", ")}."
  }
  val frequency = when (valuesByKey["FREQ"]?.uppercase()) {
    "DAILY" -> ScheduledTaskRRuleFrequency.DAILY
    "WEEKLY" -> ScheduledTaskRRuleFrequency.WEEKLY
    "MONTHLY" -> ScheduledTaskRRuleFrequency.MONTHLY
    null -> throw IllegalArgumentException("trigger.rrule must include FREQ.")
    else -> throw IllegalArgumentException("trigger.rrule FREQ must be DAILY, WEEKLY, or MONTHLY.")
  }
  val interval = valuesByKey["INTERVAL"]
    ?.toIntOrNull()
    ?: 1
  require(interval >= 1) { "trigger.rrule INTERVAL must be >= 1." }
  val byDay = valuesByKey["BYDAY"]
    ?.split(",")
    ?.mapIndexed { index, token -> parseRRuleDayToken(token, index) }
    ?.distinct()
    .orEmpty()
  val byMonthDay = valuesByKey["BYMONTHDAY"]
    ?.split(",")
    ?.mapIndexed { index, token ->
      token.trim().toIntOrNull()
        ?: throw IllegalArgumentException("trigger.rrule BYMONTHDAY[$index] must be an integer.")
    }
    ?.onEach { day ->
      require(day in -31..-1 || day in 1..31) {
        "trigger.rrule BYMONTHDAY values must be between 1..31 or -31..-1."
      }
    }
    ?.distinct()
    .orEmpty()
  val bySetPos = valuesByKey["BYSETPOS"]
    ?.split(",")
    ?.mapIndexed { index, token ->
      token.trim().toIntOrNull()
        ?: throw IllegalArgumentException("trigger.rrule BYSETPOS[$index] must be an integer.")
    }
    ?.onEach { position ->
      require(position in -31..-1 || position in 1..31) {
        "trigger.rrule BYSETPOS values must be between 1..31 or -31..-1."
      }
    }
    ?.distinct()
    .orEmpty()
  when (frequency) {
    ScheduledTaskRRuleFrequency.DAILY -> {
      require(byDay.isEmpty()) { "trigger.rrule BYDAY is unsupported for DAILY recurrence." }
      require(byMonthDay.isEmpty()) {
        "trigger.rrule BYMONTHDAY is unsupported for DAILY recurrence."
      }
      require(bySetPos.isEmpty()) {
        "trigger.rrule BYSETPOS is unsupported for DAILY recurrence."
      }
    }

    ScheduledTaskRRuleFrequency.WEEKLY -> {
      require(byMonthDay.isEmpty()) {
        "trigger.rrule BYMONTHDAY is unsupported for WEEKLY recurrence."
      }
      require(bySetPos.isEmpty()) {
        "trigger.rrule BYSETPOS is unsupported for WEEKLY recurrence."
      }
    }

    ScheduledTaskRRuleFrequency.MONTHLY -> {
      require(!(byMonthDay.isNotEmpty() && byDay.isNotEmpty())) {
        "trigger.rrule MONTHLY recurrence cannot combine BYMONTHDAY and BYDAY."
      }
      require(bySetPos.isEmpty() || byDay.isNotEmpty()) {
        "trigger.rrule BYSETPOS requires BYDAY for MONTHLY recurrence."
      }
    }
  }
  val startAt = Instant.ofEpochMilli(startAtEpochMs).atZone(ZoneId.of(timezoneId))
  if (frequency == ScheduledTaskRRuleFrequency.MONTHLY &&
    byMonthDay.isEmpty() &&
    byDay.isEmpty() &&
    startAt.dayOfMonth > startAt.toLocalDate().lengthOfMonth()
  ) {
    throw IllegalArgumentException("trigger.start_at resolves to an invalid monthly anchor.")
  }
  return ParsedScheduledTaskRRule(
    frequency = frequency,
    interval = interval,
    byDay = byDay,
    byMonthDay = byMonthDay,
    bySetPos = bySetPos,
  )
}

private fun parseRRuleDayToken(
  token: String,
  index: Int,
): DayOfWeek {
  val normalizedToken = token.trim().uppercase()
  require(normalizedToken.isNotEmpty()) {
    "trigger.rrule BYDAY[$index] must not be blank."
  }
  require(normalizedToken.all { character -> character.isLetter() }) {
    "trigger.rrule BYDAY[$index] must use weekday codes like MO,TU and not numeric ordinals."
  }
  return when (normalizedToken) {
    "MO" -> DayOfWeek.MONDAY
    "TU" -> DayOfWeek.TUESDAY
    "WE" -> DayOfWeek.WEDNESDAY
    "TH" -> DayOfWeek.THURSDAY
    "FR" -> DayOfWeek.FRIDAY
    "SA" -> DayOfWeek.SATURDAY
    "SU" -> DayOfWeek.SUNDAY
    else -> throw IllegalArgumentException(
      "trigger.rrule BYDAY[$index] must use weekday codes MO,TU,WE,TH,FR,SA,SU.",
    )
  }
}

private fun datesInMonthMatchingDays(
  targetMonth: YearMonth,
  days: List<DayOfWeek>,
): List<LocalDate> {
  val daySet = days.toSet()
  return (1..targetMonth.lengthOfMonth())
    .asSequence()
    .map { dayOfMonth -> targetMonth.atDay(dayOfMonth) }
    .filter { date -> date.dayOfWeek in daySet }
    .toList()
}

private fun selectBySetPosition(
  dates: List<LocalDate>,
  positions: List<Int>,
): List<LocalDate> = positions.mapNotNull { position ->
  when {
    position > 0 && position <= dates.size -> dates[position - 1]
    position < 0 && -position <= dates.size -> dates[dates.size + position]
    else -> null
  }
}.distinct()

private fun resolveMonthDay(
  targetMonth: YearMonth,
  requestedDay: Int,
): LocalDate? {
  val resolvedDay = when {
    requestedDay > 0 -> requestedDay
    requestedDay < 0 -> targetMonth.lengthOfMonth() + requestedDay + 1
    else -> return null
  }
  return resolvedDay
    .takeIf { day -> day in 1..targetMonth.lengthOfMonth() }
    ?.let(targetMonth::atDay)
}

private val SUPPORTED_RRULE_KEYS: Set<String> = setOf(
  "FREQ",
  "INTERVAL",
  "BYDAY",
  "BYMONTHDAY",
  "BYSETPOS",
)

private const val MAX_RECURRENCE_PERIODS: Int = 20_000
