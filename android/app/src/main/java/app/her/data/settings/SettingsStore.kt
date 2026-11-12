package app.her.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.her.BuildConfig
import app.her.core.behavior.AutonomyLevel
import app.her.core.behavior.QuietHours
import app.her.core.model.Environment
import app.her.core.notify.NotificationSource
import app.her.data.remote.SessionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FloatingMode(val label: String) { HEAD("Floating head"), UPPER("Upper body"), FULL("Full body") }

data class AppSettings(
    // Session
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val token: String? = null,
    val userId: String? = null,
    // Behaviour
    val autonomy: AutonomyLevel = AutonomyLevel.BALANCED,
    val paused: Boolean = false,
    val quietStart: Int = 23,
    val quietEnd: Int = 7,
    val initiateConversations: Boolean = true,
    val morningGreeting: Boolean = true,
    val eveningGreeting: Boolean = true,
    val bedtimeChat: Boolean = true,
    val occasionReminders: Boolean = true,
    val memoryRecall: Boolean = true,
    val gameSuggestions: Boolean = true,
    val environment: Environment = Environment.LIVING_ROOM,
    // Wardrobe
    val surpriseOutfits: Boolean = true,
    val nightwearAtNight: Boolean = true,
    val weatherEnabled: Boolean = false,
    val weatherCity: String = "",
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    val southernHemisphere: Boolean = false,
    // Floating companion
    val floatingEnabled: Boolean = false,
    val floatingMode: FloatingMode = FloatingMode.UPPER,
    val floatingScale: Float = 1f,
    val floatingX: Int = 24,
    val floatingY: Int = 360,
    // Notifications
    val enabledSources: Set<NotificationSource> = NotificationSource.DEFAULT_ENABLED,
    val showPreviews: Boolean = false,
    val reactToNotifications: Boolean = true,
    val shareNotificationHint: Boolean = false,
    val partnerContactNames: List<String> = emptyList(),
    val keepNotificationDays: Int = 7,
    // Voice
    val speakReplies: Boolean = false,
    val speechRate: Float = 1f,
    val speechPitch: Float = 1f,
    val useClonedVoice: Boolean = true,
    // Bookkeeping for the companion's own initiatives (avoid repeats)
    val lastInitiatedAt: Long = 0L,
    val lastInteractionAt: Long = 0L,
    val firedKeys: Set<String> = emptySet(),
    val recentRecalls: Set<String> = emptySet(),
) {
    val signedIn: Boolean get() = token != null
    val quietHours: QuietHours get() = QuietHours(quietStart, quietEnd)
    val effectiveAutonomy: AutonomyLevel get() = if (paused) AutonomyLevel.OFF else autonomy
}

private val Context.dataStore by preferencesDataStore(name = "her_settings")

class SettingsStore(private val context: Context, private val scope: CoroutineScope) : SessionSource {
    private val mutex = Mutex()
    private val state = MutableStateFlow(runBlocking { read(context.dataStore.data.first()) })
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    override val serverUrl: String get() = state.value.serverUrl
    override val token: String? get() = state.value.token

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { updateNow(transform) }
    }

    suspend fun updateNow(transform: (AppSettings) -> AppSettings) {
        mutex.withLock {
            val next = transform(state.value)
            state.value = next
            context.dataStore.edit { write(it, next) }
        }
    }

    fun markInteraction() = update { it.copy(lastInteractionAt = System.currentTimeMillis()) }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            serverUrl = p[K.serverUrl] ?: d.serverUrl,
            token = p[K.token],
            userId = p[K.userId],
            autonomy = p[K.autonomy]?.let { runCatching { AutonomyLevel.valueOf(it) }.getOrNull() } ?: d.autonomy,
            paused = p[K.paused] ?: d.paused,
            quietStart = p[K.quietStart] ?: d.quietStart,
            quietEnd = p[K.quietEnd] ?: d.quietEnd,
            initiateConversations = p[K.initiate] ?: d.initiateConversations,
            morningGreeting = p[K.morning] ?: d.morningGreeting,
            eveningGreeting = p[K.evening] ?: d.eveningGreeting,
            bedtimeChat = p[K.bedtime] ?: d.bedtimeChat,
            occasionReminders = p[K.occasionReminders] ?: d.occasionReminders,
            memoryRecall = p[K.memoryRecall] ?: d.memoryRecall,
            gameSuggestions = p[K.gameSuggestions] ?: d.gameSuggestions,
            environment = p[K.environment]?.let { runCatching { Environment.valueOf(it) }.getOrNull() } ?: d.environment,
            surpriseOutfits = p[K.surprise] ?: d.surpriseOutfits,
            nightwearAtNight = p[K.nightwear] ?: d.nightwearAtNight,
            weatherEnabled = p[K.weatherEnabled] ?: d.weatherEnabled,
            weatherCity = p[K.weatherCity] ?: d.weatherCity,
            weatherLat = p[K.weatherLat],
            weatherLon = p[K.weatherLon],
            southernHemisphere = p[K.southern] ?: d.southernHemisphere,
            floatingEnabled = p[K.floatingEnabled] ?: d.floatingEnabled,
            floatingMode = p[K.floatingMode]?.let { runCatching { FloatingMode.valueOf(it) }.getOrNull() } ?: d.floatingMode,
            floatingScale = p[K.floatingScale] ?: d.floatingScale,
            floatingX = p[K.floatingX] ?: d.floatingX,
            floatingY = p[K.floatingY] ?: d.floatingY,
            enabledSources = p[K.sources]?.mapNotNull { runCatching { NotificationSource.valueOf(it) }.getOrNull() }?.toSet() ?: d.enabledSources,
            showPreviews = p[K.previews] ?: d.showPreviews,
            reactToNotifications = p[K.react] ?: d.reactToNotifications,
            shareNotificationHint = p[K.shareHint] ?: d.shareNotificationHint,
            partnerContactNames = p[K.partnerNames]?.split('\n')?.filter { it.isNotBlank() } ?: d.partnerContactNames,
            keepNotificationDays = p[K.keepDays] ?: d.keepNotificationDays,
            speakReplies = p[K.speak] ?: d.speakReplies,
            speechRate = p[K.rate] ?: d.speechRate,
            speechPitch = p[K.pitch] ?: d.speechPitch,
            useClonedVoice = p[K.cloned] ?: d.useClonedVoice,
            lastInitiatedAt = p[K.lastInitiated] ?: 0L,
            lastInteractionAt = p[K.lastInteraction] ?: 0L,
            firedKeys = p[K.fired] ?: emptySet(),
            recentRecalls = p[K.recalls] ?: emptySet(),
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        p[K.serverUrl] = s.serverUrl
        if (s.token != null) p[K.token] = s.token else p.remove(K.token)
        if (s.userId != null) p[K.userId] = s.userId else p.remove(K.userId)
        p[K.autonomy] = s.autonomy.name
        p[K.paused] = s.paused
        p[K.quietStart] = s.quietStart
        p[K.quietEnd] = s.quietEnd
        p[K.initiate] = s.initiateConversations
        p[K.morning] = s.morningGreeting
        p[K.evening] = s.eveningGreeting
        p[K.bedtime] = s.bedtimeChat
        p[K.occasionReminders] = s.occasionReminders
        p[K.memoryRecall] = s.memoryRecall
        p[K.gameSuggestions] = s.gameSuggestions
        p[K.environment] = s.environment.name
        p[K.surprise] = s.surpriseOutfits
        p[K.nightwear] = s.nightwearAtNight
        p[K.weatherEnabled] = s.weatherEnabled
        p[K.weatherCity] = s.weatherCity
        if (s.weatherLat != null) p[K.weatherLat] = s.weatherLat else p.remove(K.weatherLat)
        if (s.weatherLon != null) p[K.weatherLon] = s.weatherLon else p.remove(K.weatherLon)
        p[K.southern] = s.southernHemisphere
        p[K.floatingEnabled] = s.floatingEnabled
        p[K.floatingMode] = s.floatingMode.name
        p[K.floatingScale] = s.floatingScale
        p[K.floatingX] = s.floatingX
        p[K.floatingY] = s.floatingY
        p[K.sources] = s.enabledSources.map { it.name }.toSet()
        p[K.previews] = s.showPreviews
        p[K.react] = s.reactToNotifications
        p[K.shareHint] = s.shareNotificationHint
        p[K.partnerNames] = s.partnerContactNames.joinToString("\n")
        p[K.keepDays] = s.keepNotificationDays
        p[K.speak] = s.speakReplies
        p[K.rate] = s.speechRate
        p[K.pitch] = s.speechPitch
        p[K.cloned] = s.useClonedVoice
        p[K.lastInitiated] = s.lastInitiatedAt
        p[K.lastInteraction] = s.lastInteractionAt
        p[K.fired] = s.firedKeys
        p[K.recalls] = s.recentRecalls
    }

    private object K {
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val autonomy = stringPreferencesKey("autonomy")
        val paused = booleanPreferencesKey("paused")
        val quietStart = intPreferencesKey("quiet_start")
        val quietEnd = intPreferencesKey("quiet_end")
        val initiate = booleanPreferencesKey("initiate")
        val morning = booleanPreferencesKey("morning_greeting")
        val evening = booleanPreferencesKey("evening_greeting")
        val bedtime = booleanPreferencesKey("bedtime_chat")
        val occasionReminders = booleanPreferencesKey("occasion_reminders")
        val memoryRecall = booleanPreferencesKey("memory_recall")
        val gameSuggestions = booleanPreferencesKey("game_suggestions")
        val environment = stringPreferencesKey("environment")
        val surprise = booleanPreferencesKey("surprise_outfits")
        val nightwear = booleanPreferencesKey("nightwear_at_night")
        val weatherEnabled = booleanPreferencesKey("weather_enabled")
        val weatherCity = stringPreferencesKey("weather_city")
        val weatherLat = doublePreferencesKey("weather_lat")
        val weatherLon = doublePreferencesKey("weather_lon")
        val southern = booleanPreferencesKey("southern_hemisphere")
        val floatingEnabled = booleanPreferencesKey("floating_enabled")
        val floatingMode = stringPreferencesKey("floating_mode")
        val floatingScale = floatPreferencesKey("floating_scale")
        val floatingX = intPreferencesKey("floating_x")
        val floatingY = intPreferencesKey("floating_y")
        val sources = stringSetPreferencesKey("notification_sources")
        val previews = booleanPreferencesKey("show_previews")
        val react = booleanPreferencesKey("react_to_notifications")
        val shareHint = booleanPreferencesKey("share_notification_hint")
        val partnerNames = stringPreferencesKey("partner_contact_names")
        val keepDays = intPreferencesKey("keep_notification_days")
        val speak = booleanPreferencesKey("speak_replies")
        val rate = floatPreferencesKey("speech_rate")
        val pitch = floatPreferencesKey("speech_pitch")
        val cloned = booleanPreferencesKey("use_cloned_voice")
        val lastInitiated = longPreferencesKey("last_initiated_at")
        val lastInteraction = longPreferencesKey("last_interaction_at")
        val fired = stringSetPreferencesKey("fired_keys")
        val recalls = stringSetPreferencesKey("recent_recalls")
    }
}
