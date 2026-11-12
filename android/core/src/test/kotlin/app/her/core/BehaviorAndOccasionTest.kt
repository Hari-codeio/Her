package app.her.core

import app.her.core.behavior.AutonomyLevel
import app.her.core.behavior.BehaviorEngine
import app.her.core.behavior.MoodEngine
import app.her.core.behavior.QuietHours
import app.her.core.model.Activity
import app.her.core.model.DayPart
import app.her.core.model.Environment
import app.her.core.model.Memory
import app.her.core.model.MemoryKind
import app.her.core.model.Mood
import app.her.core.model.Occasion
import app.her.core.model.OccasionKind
import app.her.core.notify.NotificationSource
import app.her.core.notify.PartnerMatcher
import app.her.core.occasion.MemoryRecall
import app.her.core.occasion.OccasionEngine
import app.her.core.util.ColorTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

class BehaviorAndOccasionTest {

    @Test
    fun `activity is deterministic within a slot`() {
        val t = LocalDateTime.of(2026, 11, 12, 10, 3)
        val a = BehaviorEngine.activityAt(t, Mood.HAPPY, Environment.LIVING_ROOM, AutonomyLevel.BALANCED, 5)
        val b = BehaviorEngine.activityAt(t.plusMinutes(5), Mood.HAPPY, Environment.LIVING_ROOM, AutonomyLevel.BALANCED, 5)
        assertEquals(a, b)
    }

    @Test
    fun `off level keeps one activity per part of day`() {
        val morning = (5..11).map { h ->
            BehaviorEngine.activityAt(LocalDateTime.of(2026, 11, 12, h, 30), Mood.CALM, Environment.CAFE, AutonomyLevel.OFF, 1)
        }.toSet()
        assertEquals(1, morning.size)
    }

    @Test
    fun `environment rules are respected`() {
        for (env in Environment.entries) for (part in DayPart.entries) for (mood in Mood.entries) {
            val w = BehaviorEngine.weights(env, part, mood)
            if (env != Environment.GARDEN) assertFalse(Activity.WATERING_PLANTS in w)
            if (env.indoor && env != Environment.APARTMENT) assertFalse(Activity.STARGAZING in w)
            if (part == DayPart.MORNING) assertFalse(Activity.STARGAZING in w)
            assertTrue(w.values.any { it > 0 })
        }
    }

    @Test
    fun `initiation respects quiet hours, interaction and level`() {
        val quiet = QuietHours(23, 7)
        val noon = LocalDateTime.of(2026, 11, 12, 12, 0)
        assertTrue(BehaviorEngine.shouldInitiate(noon, null, null, AutonomyLevel.BALANCED, quiet))
        assertFalse(BehaviorEngine.shouldInitiate(noon, null, null, AutonomyLevel.OFF, quiet))
        assertFalse(BehaviorEngine.shouldInitiate(noon.withHour(2), null, null, AutonomyLevel.LIVELY, quiet))
        assertFalse(BehaviorEngine.shouldInitiate(noon, null, noon.minusMinutes(10), AutonomyLevel.LIVELY, quiet))
        assertFalse(BehaviorEngine.shouldInitiate(noon, noon.minusMinutes(60), null, AutonomyLevel.BALANCED, quiet))
        assertTrue(BehaviorEngine.shouldInitiate(noon, noon.minusMinutes(200), null, AutonomyLevel.BALANCED, quiet))
    }

    @Test
    fun `occasions drive the mood`() {
        val d = LocalDate.of(2026, 3, 9)
        assertEquals(Mood.EXCITED, MoodEngine.moodOf(d, DayPart.MORNING, OccasionKind.BIRTHDAY, 1))
        assertEquals(Mood.ROMANTIC, MoodEngine.moodOf(d, DayPart.MORNING, OccasionKind.ANNIVERSARY, 1))
    }

    @Test
    fun `next occurrence handles recurrence and leap days`() {
        val today = LocalDate.of(2026, 11, 12)
        val bday = Occasion("b", OccasionKind.BIRTHDAY, "Birthday", LocalDate.of(1996, 3, 9))
        assertEquals(LocalDate.of(2027, 3, 9), OccasionEngine.nextOccurrence(bday, today))
        val leap = Occasion("l", OccasionKind.CUSTOM, "Leap", LocalDate.of(2020, 2, 29))
        assertEquals(LocalDate.of(2027, 2, 28), OccasionEngine.nextOccurrence(leap, today))
        val once = Occasion("o", OccasionKind.CUSTOM, "Trip", LocalDate.of(2026, 1, 1), recursYearly = false)
        assertNull(OccasionEngine.nextOccurrence(once, today))
        val anniv = Occasion("a", OccasionKind.ANNIVERSARY, "Anniversary", LocalDate.of(2021, 11, 12))
        assertEquals(0L, OccasionEngine.daysUntil(anniv, today))
        assertEquals(5, OccasionEngine.yearsOn(anniv, today))
        assertEquals(anniv, OccasionEngine.primaryToday(listOf(bday, anniv), today))
    }

    @Test
    fun `memory recall prefers on-this-day and respects recall flag`() {
        val today = LocalDate.of(2026, 11, 12)
        val memories = listOf(
            Memory("m1", MemoryKind.DATE, "Goa trip", happenedOn = LocalDate.of(2022, 12, 28)),
            Memory("m2", MemoryKind.MOMENT, "First coffee", happenedOn = LocalDate.of(2021, 11, 12)),
            Memory("m3", MemoryKind.PLACE, "Hidden", happenedOn = LocalDate.of(2020, 11, 12), recallAllowed = false),
        )
        val recall = MemoryRecall.pick(memories, today, emptySet(), Random(1))
        assertEquals("m2", recall?.memory?.id)
        assertTrue(recall!!.framing.contains("5 years"))
        repeat(20) { i ->
            assertTrue(MemoryRecall.pick(memories, today, setOf("m2"), Random(i))?.memory?.id != "m3")
        }
        assertNull(MemoryRecall.pick(memories.take(1), today, setOf("m1"), Random(1)))
    }

    @Test
    fun `colour harmony`() {
        assertTrue(ColorTools.isNeutral("#FFFFFF"))
        assertTrue(ColorTools.isNeutral("#1A1A1A"))
        assertTrue(ColorTools.pairScore("#C0263A", "#F4F1EC") > 0)
        assertTrue(ColorTools.pairScore("#FF0000", "#00FF00") < 0)
    }

    @Test
    fun `partner matching and sources`() {
        assertTrue(PartnerMatcher.isPartner("Maya ❤️", listOf("Maya")))
        assertTrue(PartnerMatcher.isPartner("Maya Sharma (2 messages)", listOf("maya sharma")))
        assertFalse(PartnerMatcher.isPartner("Mayank", listOf("Maya")))
        assertFalse(PartnerMatcher.isPartner(null, listOf("Maya")))
        assertTrue(PartnerMatcher.isPartner("555-1234", listOf("5551234")))
        assertTrue(PartnerMatcher.isPartner("+91 98450 12345", listOf("9845012345")))
        assertFalse(PartnerMatcher.isPartner("555-9999", listOf("5551234")))
        assertEquals(NotificationSource.WHATSAPP, NotificationSource.forPackage("com.whatsapp.w4b"))
        assertNull(NotificationSource.forPackage("com.example"))
    }
}
