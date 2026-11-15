package com.tubepilot.core.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/** Injectable clock so time-dependent logic (quota reset, scheduling) is testable. */
interface TimeSource {
    fun now(): Long
}

@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Long = System.currentTimeMillis()
}

object TimeUtil {
    /** YouTube quota resets at midnight Pacific Time. */
    val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
    private val DAY_KEY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun quotaDayKey(nowMs: Long): String = Instant.ofEpochMilli(nowMs).atZone(PACIFIC).toLocalDate().format(DAY_KEY)

    fun nextQuotaResetMs(nowMs: Long): Long {
        val today = Instant.ofEpochMilli(nowMs).atZone(PACIFIC).toLocalDate()
        return today.plusDays(1).atStartOfDay(PACIFIC).toInstant().toEpochMilli()
    }

    fun startOfMonthMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun startOfWeekMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun rfc3339(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS))

    fun parseRfc3339(text: String?): Long? = text?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }

    fun isoDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().format(DAY_KEY)

    fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun zoneOrDefault(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}
