package app.her.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.her.HerApplication
import app.her.core.behavior.BehaviorEngine
import app.her.core.greeting.GreetingComposer
import app.her.core.greeting.InitiationKind
import app.her.core.model.DayPart
import app.her.core.occasion.OccasionEngine
import app.her.data.toCore
import app.her.domain.Notifier
import app.her.domain.Presence
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Every 15 minutes: lets the companion greet, remind and occasionally start a conversation,
 * within the user's settings and quiet hours. Each kind of message fires at most once a day.
 */
class CompanionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as HerApplication).container
        val settingsStore = container.settings
        val s = settingsStore.settings.value
        if (!s.signedIn) return Result.success()

        container.db.notifications().prune(System.currentTimeMillis() - s.keepNotificationDays * 86_400_000L)
        container.repo.refresh()
        container.weather.refreshIfStale()

        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        val companionId = container.repo.state.value.companion?.id ?: return Result.success()
        val records = container.db.outfits().observe(companionId).first()
        val p = container.director.computeAt(now, records)
        if (!p.ready || s.paused || s.quietHours.contains(now.hour)) return Result.success()

        val fired = s.firedKeys.filter { key -> key.endsWith(today) }.toMutableSet()
        val random = Random(System.nanoTime())
        var sent = false

        fun fire(key: String, text: String, route: String = "chat") {
            if (sent || "$key:$today" in fired) return
            Notifier.companionMessage(applicationContext, (key + today).hashCode(), p.name, text, route)
            container.director.say(text)
            fired += "$key:$today"
            sent = true
        }

        // Special days first.
        val occasions = container.repo.state.value.occasions.mapNotNull { it.toCoreOccasion(container) }
        if (s.occasionReminders) {
            OccasionEngine.primaryToday(occasions, now.toLocalDate())?.takeIf { it.companionMayInitiate && now.hour >= 8 }?.let { o ->
                fire("occasion-${o.id}", GreetingComposer.occasion(o, OccasionEngine.yearsOn(o, now.toLocalDate()), p.greetingContext))
            }
            if (now.hour >= 10) {
                OccasionEngine.upcoming(occasions, now.toLocalDate(), 7)
                    .firstOrNull { (o, days) -> o.companionMayInitiate && days in OccasionEngine.REMINDER_DAYS }
                    ?.let { (o, days) -> fire("countdown-${o.id}", GreetingComposer.countdown(o, days, p.greetingContext), "occasions") }
            }
        }

        // Daily rhythm.
        if (s.morningGreeting && p.dayPart == DayPart.MORNING && now.hour >= 7) {
            fire("morning", aiOrLocal(container, p) { GreetingComposer.initiation(InitiationKind.MORNING, p.greetingContext, random) })
        }
        if (s.eveningGreeting && p.dayPart == DayPart.EVENING && now.hour >= 18) {
            fire("evening", GreetingComposer.initiation(InitiationKind.EVENING, p.greetingContext, random))
        }
        if (s.bedtimeChat && now.hour == 22) {
            fire("bedtime", GreetingComposer.initiation(InitiationKind.BEDTIME, p.greetingContext, random))
        }

        // Occasional, self-paced conversation starters.
        val lastInitiated = s.lastInitiatedAt.takeIf { it > 0 }?.let { toLocal(it) }
        val lastInteraction = s.lastInteractionAt.takeIf { it > 0 }?.let { toLocal(it) }
        var recalledId: String? = null
        if (!sent && s.initiateConversations && BehaviorEngine.shouldInitiate(now, lastInitiated, lastInteraction, s.effectiveAutonomy, s.quietHours)) {
            val options = buildList {
                add(InitiationKind.THOUGHT)
                if ("outfit:$today" !in fired) add(InitiationKind.OUTFIT)
                if (s.memoryRecall && p.recall != null) add(InitiationKind.MEMORY)
                if (s.gameSuggestions) add(InitiationKind.GAME)
            }
            val kind = options[random.nextInt(options.size)]
            val extra = if (kind == InitiationKind.MEMORY) p.recall?.let { "${it.framing}: ${it.memory.title}" } else null
            if (kind == InitiationKind.MEMORY) recalledId = p.recall?.memory?.id
            val route = if (kind == InitiationKind.GAME) "games" else "chat"
            fire(if (kind == InitiationKind.OUTFIT) "outfit" else "initiate-${now.hour}", GreetingComposer.initiation(kind, p.greetingContext, random, extra), route)
        }

        if (sent) {
            settingsStore.updateNow { cur ->
                cur.copy(
                    firedKeys = fired,
                    lastInitiatedAt = System.currentTimeMillis(),
                    recentRecalls = (cur.recentRecalls + listOfNotNull(recalledId)).toList().takeLast(20).toSet(),
                )
            }
        } else if (fired.size != s.firedKeys.size) {
            settingsStore.updateNow { it.copy(firedKeys = fired) }
        }
        return Result.success()
    }

    private suspend fun aiOrLocal(container: app.her.AppContainer, p: Presence, local: () -> String): String {
        val id = p.companionId ?: return local()
        if (container.repo.state.value.config?.chatAvailable != true) return local()
        return runCatching { container.api.greeting(id, p.chatContext()).text }.getOrNull()?.takeIf { it.isNotBlank() } ?: local()
    }

    private fun app.her.data.remote.OccasionDto.toCoreOccasion(container: app.her.AppContainer) =
        toCore(container.repo.state.value.me?.id, container.repo.state.value.partner?.id)

    private fun toLocal(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    companion object {
        private const val NAME = "companion-rhythm"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CompanionWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
