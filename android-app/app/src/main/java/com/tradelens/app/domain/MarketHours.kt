package com.tradelens.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class MarketPhase { PRE_OPEN, OPEN, CLOSED, HOLIDAY, WEEKEND }

/**
 * NSE/BSE session calendar. Holidays can be refreshed from the engine (/calendar/holidays), which reads the
 * exchange's published list; the built-in list is the NSE 2026 trading-holiday calendar as a fallback.
 */
object MarketHours {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    val PRE_OPEN_START: LocalTime = LocalTime.of(9, 0)
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    @Volatile
    var holidays: Set<LocalDate> = setOf(
        "2026-01-26", "2026-03-03", "2026-03-26", "2026-03-31", "2026-04-03", "2026-04-14", "2026-05-01",
        "2026-05-28", "2026-06-26", "2026-09-14", "2026-10-02", "2026-10-20", "2026-11-10", "2026-11-24", "2026-12-25",
    ).map { LocalDate.parse(it) }.toSet()

    fun phase(now: ZonedDateTime = ZonedDateTime.now(IST)): MarketPhase {
        val t = now.withZoneSameInstant(IST)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return MarketPhase.WEEKEND
        if (t.toLocalDate() in holidays) return MarketPhase.HOLIDAY
        val lt = t.toLocalTime()
        return when {
            lt >= PRE_OPEN_START && lt < OPEN -> MarketPhase.PRE_OPEN
            lt >= OPEN && lt < CLOSE -> MarketPhase.OPEN
            else -> MarketPhase.CLOSED
        }
    }

    fun isOpen(i: Instrument, now: ZonedDateTime = ZonedDateTime.now(IST)): Boolean =
        if (i.isCrypto) true else if (i.exchange == Exchange.MCX) mcxOpen(now) else phase(now) == MarketPhase.OPEN

    private fun mcxOpen(now: ZonedDateTime): Boolean {
        val t = now.withZoneSameInstant(IST)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY || t.toLocalDate() in holidays) return false
        val lt = t.toLocalTime()
        return lt >= LocalTime.of(9, 0) && lt < LocalTime.of(23, 30)
    }

    /** True inside the first/last 5 minutes of the cash session (smart-execution avoidance window). */
    fun inEdgeWindow(now: ZonedDateTime = ZonedDateTime.now(IST)): Boolean {
        val lt = now.withZoneSameInstant(IST).toLocalTime()
        return (lt >= OPEN && lt < OPEN.plusMinutes(5)) || (lt >= CLOSE.minusMinutes(5) && lt < CLOSE)
    }

    fun nextOpen(now: ZonedDateTime = ZonedDateTime.now(IST)): ZonedDateTime {
        var d = now.withZoneSameInstant(IST)
        if (d.toLocalTime() >= OPEN) d = d.plusDays(1)
        d = d.with(OPEN)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d.toLocalDate() in holidays) d = d.plusDays(1)
        return d
    }
}
