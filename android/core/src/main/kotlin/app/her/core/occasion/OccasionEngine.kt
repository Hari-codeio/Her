package app.her.core.occasion

import app.her.core.model.Memory
import app.her.core.model.MemoryKind
import app.her.core.model.Occasion
import app.her.core.model.OccasionKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

object OccasionEngine {
    val REMINDER_DAYS = setOf(7L, 3L, 1L)

    fun nextOccurrence(occasion: Occasion, today: LocalDate): LocalDate? {
        if (!occasion.recursYearly) return occasion.date.takeIf { !it.isBefore(today) }
        var next = withYearSafe(occasion.date, today.year)
        if (next.isBefore(today)) next = withYearSafe(occasion.date, today.year + 1)
        return next
    }

    fun daysUntil(occasion: Occasion, today: LocalDate): Long? =
        nextOccurrence(occasion, today)?.let { ChronoUnit.DAYS.between(today, it) }

    fun happeningToday(occasions: List<Occasion>, today: LocalDate): List<Occasion> =
        occasions.filter { daysUntil(it, today) == 0L }

    fun upcoming(occasions: List<Occasion>, today: LocalDate, withinDays: Long = 366): List<Pair<Occasion, Long>> =
        occasions.mapNotNull { o -> daysUntil(o, today)?.takeIf { it <= withinDays }?.let { o to it } }
            .sortedBy { it.second }

    /** Years since the original date on its next occurrence, e.g. "5th anniversary". */
    fun yearsOn(occasion: Occasion, today: LocalDate): Int? {
        val next = nextOccurrence(occasion, today) ?: return null
        return (next.year - occasion.date.year).takeIf { it > 0 && occasion.recursYearly }
    }

    /** The most important occasion today: anniversaries and birthdays outrank custom days. */
    fun primaryToday(occasions: List<Occasion>, today: LocalDate): Occasion? =
        happeningToday(occasions, today).minByOrNull {
            when (it.kind) {
                OccasionKind.ANNIVERSARY -> 0
                OccasionKind.BIRTHDAY -> 1
                OccasionKind.CUSTOM -> 2
            }
        }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }

    private fun withYearSafe(date: LocalDate, year: Int): LocalDate =
        if (date.monthValue == 2 && date.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) {
            LocalDate.of(year, 2, 28)
        } else {
            date.withYear(year)
        }
}

object MemoryRecall {
    data class Recall(val memory: Memory, val framing: String)

    fun pick(memories: List<Memory>, today: LocalDate, recentlyRecalled: Set<String>, random: Random): Recall? {
        val eligible = memories.filter { it.recallAllowed && it.id !in recentlyRecalled }
        if (eligible.isEmpty()) return null

        val onThisDay = eligible.filter { m ->
            val d = m.happenedOn ?: return@filter false
            d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year
        }
        if (onThisDay.isNotEmpty()) {
            val m = onThisDay[random.nextInt(onThisDay.size)]
            val years = today.year - m.happenedOn!!.year
            val framing = if (years == 1) "On this day last year" else "On this day $years years ago"
            return Recall(m, framing)
        }

        val weighted = eligible.associateWith { m ->
            val base = when (m.kind) {
                MemoryKind.DATE, MemoryKind.PLACE, MemoryKind.MOMENT -> 2.0
                MemoryKind.MESSAGE -> 1.5
                MemoryKind.PREFERENCE -> 1.0
            }
            if (m.isFictional) base * 0.5 else base
        }
        var roll = random.nextDouble() * weighted.values.sum()
        var chosen = eligible.last()
        for ((m, w) in weighted) {
            roll -= w
            if (roll <= 0) { chosen = m; break }
        }
        val framing = if (chosen.isFictional) "Remember the story we made up" else "I was just thinking about"
        return Recall(chosen, framing)
    }
}
