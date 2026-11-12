package app.her.domain

import app.her.core.behavior.BehaviorEngine
import app.her.core.behavior.MoodEngine
import app.her.core.greeting.GreetingComposer
import app.her.core.greeting.GreetingContext
import app.her.core.model.Activity
import app.her.core.model.DayPart
import app.her.core.model.Environment
import app.her.core.model.Expression
import app.her.core.model.Mood
import app.her.core.model.Occasion
import app.her.core.model.Outfit
import app.her.core.model.OutfitRecord
import app.her.core.model.Season
import app.her.core.model.WardrobeItem
import app.her.core.occasion.MemoryRecall
import app.her.core.occasion.OccasionEngine
import app.her.core.util.Seeds
import app.her.core.wardrobe.OutfitContext
import app.her.core.wardrobe.OutfitEngine
import app.her.data.HerRepository
import app.her.data.HerState
import app.her.data.WeatherInfo
import app.her.data.WeatherRepository
import app.her.data.local.HerDatabase
import app.her.data.local.OutfitRecordEntity
import app.her.data.remote.AppearanceDto
import app.her.data.remote.ChatContextDto
import app.her.data.settings.AppSettings
import app.her.data.settings.SettingsStore
import app.her.data.toCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

enum class ReactionKind(val expression: Expression, val durationMs: Long) {
    TAP(Expression.SMILE, 1500),
    PLAYFUL(Expression.WINK, 2200),
    WAVE(Expression.SMILE, 2600),
    DANCE(Expression.LAUGH, 4000),
    KISS(Expression.LOVE, 2600),
    LAUGH(Expression.LAUGH, 2200),
    SURPRISE(Expression.SURPRISED, 1800),
    NOTIFY(Expression.SURPRISED, 2400),
}

data class Reaction(val kind: ReactionKind, val at: Long)

data class Bubble(val text: String, val at: Long, val durationMs: Long)

private data class Overrides(
    val activity: Activity? = null,
    val activitySlot: Long = -1,
    val activityDate: LocalDate? = null,
    val reaction: Reaction? = null,
    val bubble: Bubble? = null,
)

/** Everything needed to draw and talk as the companion right now. */
data class Presence(
    val ready: Boolean,
    val status: String?,
    val companionId: String?,
    val name: String,
    val subjectName: String,
    val viewerName: String,
    val nickname: String?,
    val appearance: AppearanceDto,
    val now: LocalDateTime,
    val dayPart: DayPart,
    val mood: Mood,
    val activity: Activity,
    val expression: Expression,
    val environment: Environment,
    val outfit: Outfit,
    val dailyOutfit: Outfit,
    val isBedtimeLook: Boolean,
    val record: OutfitRecordEntity?,
    val greeting: String,
    val occasionToday: Occasion?,
    val occasionYears: Int?,
    val upcoming: List<Pair<Occasion, Long>>,
    val recall: MemoryRecall.Recall?,
    val weather: WeatherInfo?,
    val reaction: Reaction?,
    val bubble: Bubble?,
) {
    val greetingContext: GreetingContext
        get() = GreetingContext(
            companionName = name,
            viewerName = viewerName,
            subjectName = subjectName,
            nickname = nickname,
            dayPart = dayPart,
            mood = mood,
            activity = activity,
            outfitSummary = outfit.summary(),
        )

    fun chatContext(notificationHint: String? = null): ChatContextDto = ChatContextDto(
        localTime = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        dayPart = dayPart.apiName,
        mood = mood.apiName,
        activity = activity.verb,
        environment = environment.label,
        outfitSummary = outfit.summary(),
        weather = weather?.summary,
        occasionToday = occasionToday?.let { o -> o.title + (occasionYears?.let { " (${OccasionEngine.ordinal(it)})" } ?: "") },
        notificationHint = notificationHint,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionDirector(
    private val repo: HerRepository,
    private val settings: SettingsStore,
    weather: WeatherRepository,
    private val db: HerDatabase,
    private val scope: CoroutineScope,
) {
    private val engine = OutfitEngine()
    private val overrides = MutableStateFlow(Overrides())
    private var reactionJob: Job? = null
    private var bubbleJob: Job? = null

    private val ticker: Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }

    private val records: Flow<List<OutfitRecordEntity>> = repo.state
        .map { it.companion?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.outfits().observe(id) }

    private val inputs = combine(repo.state, settings.settings, weather.weather) { s, st, w -> Triple(s, st, w) }

    val presence: StateFlow<Presence> = combine(inputs, ticker, overrides, records) { (state, st, w), now, ov, recs ->
        compute(state, st, now, w, recs, ov)
    }.stateIn(scope, SharingStarted.Eagerly, compute(repo.state.value, settings.settings.value, LocalDateTime.now(), null, emptyList(), Overrides()))

    init {
        // Remember today's outfit the first time it is chosen, so it stays the same all day.
        scope.launch {
            presence
                .map { Triple(it.companionId, it.now.toLocalDate(), it) }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second && a.third.dailyOutfit.key == b.third.dailyOutfit.key }
                .collect { (id, date, p) ->
                    if (id != null && p.ready && p.record == null && !p.dailyOutfit.isDefault) {
                        db.outfits().upsert(
                            OutfitRecordEntity(
                                companionId = id,
                                date = date.toString(),
                                itemIds = p.dailyOutfit.items.joinToString(",") { it.id },
                                summary = p.dailyOutfit.summary(),
                                source = if (p.dailyOutfit.isSurprise) "surprise" else "auto",
                            ),
                        )
                    }
                }
        }
    }

    // ------------------------------------------------------------------ actions

    suspend fun shuffleOutfit() {
        val p = presence.value
        val id = p.companionId ?: return
        val shuffles = (p.record?.shuffles ?: 0) + 1
        val wardrobe = repo.state.value.wardrobe.map { it.toCore() }
        val recs = db.outfits().observe(id).first()
        val ctx = outfitContext(p.now, id, settings.settings.value, recs, p, salt = shuffles.toLong())
        val outfit = engine.dailyOutfit(wardrobe, ctx.copy(allowSurprise = false))
        db.outfits().upsert(
            OutfitRecordEntity(id, p.now.toLocalDate().toString(), outfit.items.joinToString(",") { it.id }, outfit.summary(), "shuffle", shuffles = shuffles),
        )
        react(ReactionKind.PLAYFUL)
    }

    suspend fun wearOutfit(items: List<WardrobeItem>) {
        val p = presence.value
        val id = p.companionId ?: return
        db.outfits().upsert(
            OutfitRecordEntity(id, p.now.toLocalDate().toString(), items.joinToString(",") { it.id }, Outfit(items).summary(), "manual", shuffles = p.record?.shuffles ?: 0),
        )
        react(ReactionKind.TAP)
    }

    suspend fun rateOutfit(liked: Boolean?) {
        val record = presence.value.record ?: return
        db.outfits().upsert(record.copy(liked = when (liked) { true -> 1; false -> -1; null -> null }))
        if (liked == true) react(ReactionKind.KISS) else if (liked == false) react(ReactionKind.SURPRISE)
    }

    suspend fun toggleSaved() {
        val record = presence.value.record ?: return
        db.outfits().upsert(record.copy(saved = !record.saved))
    }

    suspend fun rateRecord(record: OutfitRecordEntity, liked: Boolean?) {
        db.outfits().upsert(record.copy(liked = when (liked) { true -> 1; false -> -1; null -> null }))
    }

    fun setActivity(activity: Activity) {
        val now = LocalDateTime.now()
        val slot = BehaviorEngine.slotIndex(now, settings.settings.value.effectiveAutonomy)
        overrides.update { it.copy(activity = activity, activitySlot = slot, activityDate = now.toLocalDate()) }
    }

    /** Swipe: move to the next (or previous) activity that fits the scene. */
    fun cycleActivity(forward: Boolean = true) {
        val p = presence.value
        val options = BehaviorEngine.weights(p.environment, p.dayPart, p.mood).keys.sortedBy { it.ordinal }
        if (options.isEmpty()) return
        val index = options.indexOf(p.activity).let { if (it < 0) 0 else it }
        val next = options[(index + if (forward) 1 else options.size - 1) % options.size]
        setActivity(next)
    }

    fun react(kind: ReactionKind) {
        overrides.update { it.copy(reaction = Reaction(kind, System.currentTimeMillis())) }
        reactionJob?.cancel()
        reactionJob = scope.launch {
            delay(kind.durationMs)
            overrides.update { it.copy(reaction = null) }
        }
    }

    fun say(text: String, durationMs: Long = 7000) {
        overrides.update { it.copy(bubble = Bubble(text, System.currentTimeMillis(), durationMs)) }
        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            delay(durationMs)
            overrides.update { it.copy(bubble = null) }
        }
    }

    fun setEnvironment(environment: Environment) = settings.update { it.copy(environment = environment) }

    // ------------------------------------------------------------------ the model of "right now"

    /** Pure: the presence for any moment. Also used by the background worker. */
    fun computeAt(now: LocalDateTime, records: List<OutfitRecordEntity>): Presence =
        compute(repo.state.value, settings.settings.value, now, presence.value.weather, records, Overrides())

    private fun compute(
        state: HerState,
        st: AppSettings,
        now: LocalDateTime,
        weather: WeatherInfo?,
        recs: List<OutfitRecordEntity>,
        ov: Overrides,
    ): Presence {
        val companion = state.companion
        val id = companion?.id
        val today = now.toLocalDate()
        val dayPart = DayPart.ofHour(now.hour)
        val subjectName = companion?.subject?.displayName ?: state.partner?.displayName ?: "your partner"
        val name = companion?.name ?: subjectName
        val viewerName = state.me?.displayName ?: "you"
        val seed = Seeds.stableHash(id ?: "none")

        val occasions = state.occasions.mapNotNull { it.toCore(state.me?.id, state.partner?.id) }
        val occasionToday = OccasionEngine.primaryToday(occasions, today)
        val upcoming = OccasionEngine.upcoming(occasions, today, 60)
        val mood = MoodEngine.moodOf(today, dayPart, occasionToday?.kind, seed)
        val environment = st.environment

        val slot = BehaviorEngine.slotIndex(now, st.effectiveAutonomy)
        val activity = if (ov.activity != null && ov.activitySlot == slot && ov.activityDate == today) ov.activity
        else BehaviorEngine.activityAt(now, mood, environment, st.effectiveAutonomy, seed + 1)

        val reaction = ov.reaction?.takeIf { System.currentTimeMillis() - it.at < it.kind.durationMs }
        val expression = reaction?.kind?.expression ?: BehaviorEngine.expressionFor(activity, mood)

        val wardrobe = state.wardrobe.map { it.toCore() }
        val todayRecord = recs.firstOrNull { it.date == today.toString() }
        val byId = (wardrobe + OutfitEngine.DEFAULT_ITEMS).associateBy { it.id }
        val recordedOutfit = todayRecord?.ids?.mapNotNull { byId[it] }?.takeIf { it.size == todayRecord.ids.size && it.isNotEmpty() }
            ?.let { Outfit(it, isSurprise = todayRecord.source == "surprise") }
        val partial = Presence(
            ready = companion?.isActive == true,
            status = companion?.status,
            companionId = id,
            name = name,
            subjectName = subjectName,
            viewerName = viewerName,
            nickname = companion?.persona?.nicknameForViewer?.takeIf { it.isNotBlank() },
            appearance = companion?.appearance ?: AppearanceDto(),
            now = now,
            dayPart = dayPart,
            mood = mood,
            activity = activity,
            expression = expression,
            environment = environment,
            outfit = Outfit(OutfitEngine.DEFAULT_ITEMS, isDefault = true),
            dailyOutfit = Outfit(OutfitEngine.DEFAULT_ITEMS, isDefault = true),
            isBedtimeLook = false,
            record = todayRecord?.takeIf { recordedOutfit != null },
            greeting = "",
            occasionToday = occasionToday,
            occasionYears = occasionToday?.let { OccasionEngine.yearsOn(it, today) },
            upcoming = upcoming,
            recall = null,
            weather = weather,
            reaction = reaction,
            bubble = ov.bubble?.takeIf { System.currentTimeMillis() - it.at < it.durationMs },
        )
        val daily = recordedOutfit ?: engine.dailyOutfit(wardrobe, outfitContext(now, id ?: "none", st, recs, partial, 0))
        val bedtime = if (st.nightwearAtNight && (now.hour >= 22 || now.hour < 5)) {
            engine.bedtimeOutfit(wardrobe, outfitContext(now, id ?: "none", st, recs, partial, 0))
        } else null
        val shown = bedtime ?: daily

        val withOutfit = partial.copy(outfit = shown, dailyOutfit = daily, isBedtimeLook = bedtime != null)
        val greeting = occasionToday?.let { GreetingComposer.occasion(it, withOutfit.occasionYears, withOutfit.greetingContext) }
            ?: GreetingComposer.greeting(withOutfit.greetingContext, Random(seed + today.toEpochDay() * 13 + dayPart.ordinal))
        val recall = if (st.memoryRecall) {
            MemoryRecall.pick(state.memories.map { it.toCore() }, today, st.recentRecalls, Random(seed + today.toEpochDay()))
        } else null
        return withOutfit.copy(greeting = greeting, recall = recall)
    }

    private fun outfitContext(
        now: LocalDateTime,
        companionId: String,
        st: AppSettings,
        recs: List<OutfitRecordEntity>,
        p: Presence,
        salt: Long,
    ): OutfitContext {
        val today = now.toLocalDate()
        return OutfitContext(
            date = today,
            season = Season.of(today, st.southernHemisphere),
            dayPart = p.dayPart,
            temperatureC = p.weather?.temperatureC,
            occasion = p.occasionToday?.kind,
            environment = p.environment,
            mood = p.mood,
            history = recs.mapNotNull { r ->
                runCatching { LocalDate.parse(r.date) }.getOrNull()?.let {
                    OutfitRecord(it, r.ids, when (r.liked) { 1 -> true; -1 -> false; else -> null })
                }
            },
            seed = Seeds.daily(companionId, today, salt),
            allowSurprise = st.surpriseOutfits,
        )
    }
}
