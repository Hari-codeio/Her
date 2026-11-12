package app.her.core.behavior

import app.her.core.model.Activity
import app.her.core.model.DayPart
import app.her.core.model.Environment
import app.her.core.model.Expression
import app.her.core.model.Mood
import app.her.core.model.OccasionKind
import app.her.core.util.weightedPick
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** How lively the companion is on its own. OFF keeps one activity per part of the day and never initiates. */
enum class AutonomyLevel(val label: String, val slotMinutes: Int, val initiateEveryMinutes: Int) {
    OFF("Off", 0, 0),
    CALM("Calm", 45, 360),
    BALANCED("Balanced", 20, 180),
    LIVELY("Lively", 8, 90);
}

data class QuietHours(val startHour: Int = 23, val endHour: Int = 7) {
    fun contains(hour: Int): Boolean =
        if (startHour == endHour) false
        else if (startHour < endHour) hour in startHour until endHour
        else hour >= startHour || hour < endHour
}

object BehaviorEngine {

    fun activityAt(
        time: LocalDateTime,
        mood: Mood,
        environment: Environment,
        level: AutonomyLevel,
        seed: Long,
    ): Activity {
        val dayPart = DayPart.ofHour(time.hour)
        val slot = slotIndex(time, level)
        val random = Random(seed * 1_000_003L + time.toLocalDate().toEpochDay() * 997L + slot)
        return weights(environment, dayPart, mood).weightedPick(random)
    }

    /** Index of the activity slot that [time] falls in; activities change at slot boundaries. */
    fun slotIndex(time: LocalDateTime, level: AutonomyLevel): Long =
        if (level == AutonomyLevel.OFF) DayPart.ofHour(time.hour).ordinal.toLong() + 100L
        else (time.toLocalTime().toSecondOfDay() / 60L) / level.slotMinutes

    fun weights(environment: Environment, dayPart: DayPart, mood: Mood): Map<Activity, Double> {
        val w = mutableMapOf<Activity, Double>()
        when (dayPart) {
            DayPart.MORNING -> {
                w[Activity.STRETCHING] = 3.0; w[Activity.DRINKING_COFFEE] = 3.0; w[Activity.WAVING] = 2.0
                w[Activity.LOOKING_AROUND] = 1.0; w[Activity.SMILING] = 2.0; w[Activity.READING] = 1.0
                w[Activity.LOOKING_AT_YOU] = 1.5
            }
            DayPart.AFTERNOON -> {
                w[Activity.READING] = 2.0; w[Activity.LISTENING_MUSIC] = 2.0; w[Activity.SITTING_RELAXING] = 1.5
                w[Activity.LOOKING_AROUND] = 2.0; w[Activity.DANCING] = 1.0; w[Activity.SMILING] = 1.5
                w[Activity.DRINKING_COFFEE] = 1.0; w[Activity.LOOKING_AT_YOU] = 1.0; w[Activity.WAVING] = 0.8
            }
            DayPart.EVENING -> {
                w[Activity.SITTING_RELAXING] = 2.5; w[Activity.LISTENING_MUSIC] = 2.0; w[Activity.DANCING] = 1.5
                w[Activity.READING] = 1.5; w[Activity.LOOKING_AT_YOU] = 2.0; w[Activity.SMILING] = 1.5
                w[Activity.STARGAZING] = 0.8; w[Activity.YAWNING] = 0.5
            }
            DayPart.NIGHT -> {
                w[Activity.YAWNING] = 2.5; w[Activity.READING] = 2.0; w[Activity.SITTING_RELAXING] = 2.0
                w[Activity.STARGAZING] = 1.5; w[Activity.LOOKING_AT_YOU] = 1.5; w[Activity.LISTENING_MUSIC] = 1.0
            }
        }

        // Environment rules.
        if (environment == Environment.GARDEN && dayPart != DayPart.NIGHT) w[Activity.WATERING_PLANTS] = 3.0
        val canSeeSky = !environment.indoor || environment == Environment.APARTMENT
        if (!canSeeSky || dayPart == DayPart.MORNING || dayPart == DayPart.AFTERNOON) w.remove(Activity.STARGAZING)
        if (environment == Environment.CAFE) {
            w.multiply(Activity.DRINKING_COFFEE, 2.0)
            w.multiply(Activity.DANCING, 0.3)
        }
        if (environment == Environment.BEACH || environment == Environment.PARK) w.multiply(Activity.LOOKING_AROUND, 1.5)

        // Mood colours the day.
        when (mood) {
            Mood.PLAYFUL, Mood.EXCITED -> { w.multiply(Activity.DANCING, 2.0); w.multiply(Activity.WAVING, 1.5) }
            Mood.SLEEPY -> { w[Activity.YAWNING] = (w[Activity.YAWNING] ?: 1.0) * 3.0; w.multiply(Activity.STRETCHING, 1.5) }
            Mood.CALM, Mood.RELAXED -> {
                w.multiply(Activity.READING, 1.5); w.multiply(Activity.SITTING_RELAXING, 1.5); w.multiply(Activity.LISTENING_MUSIC, 1.3)
            }
            Mood.ROMANTIC -> { w.multiply(Activity.LOOKING_AT_YOU, 2.0); w.multiply(Activity.SMILING, 1.5) }
            Mood.CURIOUS -> { w.multiply(Activity.LOOKING_AROUND, 2.0); w.multiply(Activity.READING, 1.5) }
            Mood.HAPPY -> { w.multiply(Activity.SMILING, 1.5); w.multiply(Activity.WAVING, 1.2) }
        }
        if (mood != Mood.SLEEPY && (dayPart == DayPart.MORNING || dayPart == DayPart.AFTERNOON)) w.remove(Activity.YAWNING)
        return w
    }

    fun expressionFor(activity: Activity, mood: Mood): Expression = when (activity) {
        Activity.WAVING, Activity.SMILING, Activity.STARGAZING, Activity.WATERING_PLANTS -> Expression.SMILE
        Activity.DANCING -> Expression.LAUGH
        Activity.YAWNING -> Expression.SLEEPY
        Activity.READING -> Expression.THINKING
        Activity.LOOKING_AT_YOU -> if (mood == Mood.ROMANTIC) Expression.LOVE else Expression.SMILE
        else -> when (mood) {
            Mood.ROMANTIC -> Expression.BLUSH
            Mood.SLEEPY -> Expression.SLEEPY
            Mood.EXCITED -> Expression.LAUGH
            Mood.CURIOUS -> Expression.THINKING
            Mood.CALM -> Expression.NEUTRAL
            else -> Expression.SMILE
        }
    }

    fun shouldInitiate(
        now: LocalDateTime,
        lastInitiatedAt: LocalDateTime?,
        lastInteractionAt: LocalDateTime?,
        level: AutonomyLevel,
        quietHours: QuietHours,
    ): Boolean {
        if (level == AutonomyLevel.OFF) return false
        if (quietHours.contains(now.hour)) return false
        if (lastInteractionAt != null && Duration.between(lastInteractionAt, now).toMinutes() < 30) return false
        if (lastInitiatedAt != null && Duration.between(lastInitiatedAt, now).toMinutes() < level.initiateEveryMinutes) return false
        return true
    }

    private fun MutableMap<Activity, Double>.multiply(activity: Activity, factor: Double) {
        get(activity)?.let { put(activity, it * factor) }
    }
}

object MoodEngine {
    fun moodOf(date: LocalDate, dayPart: DayPart, occasion: OccasionKind?, seed: Long): Mood {
        if (occasion == OccasionKind.BIRTHDAY) return Mood.EXCITED
        if (occasion == OccasionKind.ANNIVERSARY) return Mood.ROMANTIC
        val random = Random(seed xor (date.toEpochDay() * 31L))
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val weights = mutableMapOf(
            Mood.HAPPY to 3.0, Mood.PLAYFUL to 2.0, Mood.CALM to 2.0, Mood.SLEEPY to 0.5,
            Mood.EXCITED to 1.0, Mood.RELAXED to 2.0, Mood.ROMANTIC to 1.5, Mood.CURIOUS to 1.5,
        )
        if (weekend) {
            weights[Mood.RELAXED] = weights.getValue(Mood.RELAXED) + 1.5
            weights[Mood.PLAYFUL] = weights.getValue(Mood.PLAYFUL) + 1.0
        }
        val daily = weights.weightedPick(random)
        val partRoll = Random(seed + date.toEpochDay() * 7L + dayPart.ordinal).nextDouble()
        return when (dayPart) {
            DayPart.NIGHT -> when {
                partRoll < 0.55 -> Mood.SLEEPY
                daily == Mood.EXCITED -> Mood.CALM
                else -> daily
            }
            DayPart.EVENING -> if ((daily == Mood.CALM || daily == Mood.RELAXED) && partRoll < 0.3) Mood.ROMANTIC else daily
            else -> daily
        }
    }
}
