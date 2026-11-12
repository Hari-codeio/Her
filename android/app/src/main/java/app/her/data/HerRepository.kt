package app.her.data

import android.content.Context
import android.util.Log
import app.her.data.remote.ApiException
import app.her.data.remote.AppearanceDto
import app.her.data.remote.CompanionDto
import app.her.data.remote.ConfigDto
import app.her.data.remote.ConsentUpdate
import app.her.data.remote.CoupleDto
import app.her.data.remote.HerApi
import app.her.data.remote.HerJson
import app.her.data.remote.InviteDto
import app.her.data.remote.MemoryDto
import app.her.data.remote.MemoryInput
import app.her.data.remote.MemorySuggestionDto
import app.her.data.remote.OccasionDto
import app.her.data.remote.OccasionInput
import app.her.data.remote.PersonaDto
import app.her.data.remote.UserDto
import app.her.data.remote.WardrobeItemDto
import app.her.data.remote.WardrobeItemInput
import app.her.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Everything the app knows about the couple, as last synced from the server. */
@Serializable
data class HerState(
    val config: ConfigDto? = null,
    val me: UserDto? = null,
    val couple: CoupleDto? = null,
    val partner: UserDto? = null,
    /** The companion I talk to (I'm the viewer; it represents my partner). */
    val companion: CompanionDto? = null,
    /** The companion that represents me (I'm the subject; my partner sees it). */
    val ofMe: CompanionDto? = null,
    val wardrobe: List<WardrobeItemDto> = emptyList(),
    val ofMeWardrobe: List<WardrobeItemDto> = emptyList(),
    val memories: List<MemoryDto> = emptyList(),
    val occasions: List<OccasionDto> = emptyList(),
    val suggestions: List<MemorySuggestionDto> = emptyList(),
    val lastSync: Long = 0L,
) {
    val paired: Boolean get() = couple != null && partner != null
    val pendingForMe: Int
        get() = (if (ofMe?.pendingAppearance != null) 1 else 0) +
            (if (ofMe?.pendingPersona != null) 1 else 0) +
            ofMeWardrobe.count { !it.approved }
}

data class SyncStatus(val syncing: Boolean = false, val error: String? = null, val offline: Boolean = false)

class HerRepository(
    context: Context,
    val api: HerApi,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val cacheFile = File(context.filesDir, "her_state.json")
    private val _state = MutableStateFlow(loadCache())
    val state: StateFlow<HerState> = _state.asStateFlow()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        if (!settings.settings.value.signedIn) return
        _status.update { it.copy(syncing = true, error = null) }
        try {
            coroutineScope {
                val config = async { runCatching { api.config() }.getOrNull() }
                val me = api.me()
                if (me.couple == null) {
                    val cfg = config.await()
                    commit { HerState(config = cfg ?: it.config, me = me.user, lastSync = System.currentTimeMillis()) }
                    return@coroutineScope
                }
                val companion = async { optional { api.myCompanion() } }
                val ofMe = async { optional { api.companionOfMe() } }
                val memories = async { optional { api.memories() } ?: emptyList() }
                val occasions = async { optional { api.occasions() } ?: emptyList() }
                val mine = companion.await()
                val mineOfMe = ofMe.await()
                val wardrobe = async { mine?.let { c -> optional { api.wardrobe(c.id) } } ?: emptyList() }
                val ofMeWardrobe = async { mineOfMe?.let { c -> optional { api.wardrobe(c.id) } } ?: emptyList() }
                val suggestions = async {
                    mine?.takeIf { it.isActive }?.let { c -> optional { api.suggestions(c.id) } } ?: emptyList()
                }
                val next = HerState(
                    config = config.await(),
                    me = me.user,
                    couple = me.couple,
                    partner = me.partner,
                    companion = mine,
                    ofMe = mineOfMe,
                    wardrobe = wardrobe.await(),
                    ofMeWardrobe = ofMeWardrobe.await(),
                    memories = memories.await(),
                    occasions = occasions.await(),
                    suggestions = suggestions.await(),
                    lastSync = System.currentTimeMillis(),
                )
                commit { next.copy(config = next.config ?: it.config) }
            }
            _status.value = SyncStatus()
        } catch (e: ApiException) {
            if (e.status == 401) {
                Log.w(TAG, "Session expired; signing out")
                signOutLocally()
            }
            _status.value = SyncStatus(error = e.code, offline = e.isNetwork)
        } catch (e: Exception) {
            Log.e(TAG, "refresh failed", e)
            _status.value = SyncStatus(error = e.message ?: "error")
        }
    }

    // ------------------------------------------------------------------ account

    suspend fun register(serverUrl: String, name: String) {
        settings.updateNow { it.copy(serverUrl = serverUrl.trim().trimEnd('/'), token = null, userId = null) }
        val result = api.register(name.trim())
        settings.updateNow { it.copy(token = result.token, userId = result.user.id) }
        commit { HerState(me = result.user) }
        refresh()
    }

    suspend fun createInvite(): InviteDto = api.createInvite()

    suspend fun join(code: String) {
        api.join(code)
        refresh()
    }

    suspend fun setAnniversary(date: String?) {
        val couple = api.setAnniversary(date)
        commit { it.copy(couple = couple) }
    }

    suspend fun rename(name: String) {
        val user = api.updateMe(name)
        commit { it.copy(me = user) }
    }

    suspend fun unpair() {
        api.unpair()
        refresh()
    }

    suspend fun deleteAccount() {
        api.deleteAccount()
        signOutLocally()
    }

    suspend fun signOutLocally() {
        settings.updateNow { it.copy(token = null, userId = null) }
        commit { HerState() }
    }

    // ------------------------------------------------------------------ companion (both roles)

    suspend fun setConsent(likeness: Boolean, voice: Boolean, personality: Boolean, aiTraining: Boolean) {
        val target = state.value.ofMe ?: return
        val version = state.value.config?.consentStatementVersion ?: api.config().consentStatementVersion
        val updated = api.setConsent(target.id, ConsentUpdate(likeness, voice, personality, aiTraining, version))
        commit { it.copy(ofMe = updated) }
        if (!likeness) refresh()
    }

    suspend fun saveAppearance(companionId: String, appearance: AppearanceDto) = applyCompanion(api.putAppearance(companionId, appearance))
    suspend fun approveAppearance(companionId: String, versionId: String) = applyCompanion(api.approveAppearance(companionId, versionId))
    suspend fun rejectAppearance(companionId: String, versionId: String, reason: String?) = applyCompanion(api.rejectAppearance(companionId, versionId, reason))
    suspend fun savePersona(companionId: String, persona: PersonaDto) = applyCompanion(api.putPersona(companionId, persona))
    suspend fun approvePersona(companionId: String) = applyCompanion(api.approvePersona(companionId))
    suspend fun rejectPersona(companionId: String) = applyCompanion(api.rejectPersona(companionId))
    suspend fun renameCompanion(companionId: String, name: String) = applyCompanion(api.renameCompanion(companionId, name))

    private fun applyCompanion(updated: CompanionDto) {
        commit {
            when (updated.id) {
                it.companion?.id -> it.copy(companion = updated)
                it.ofMe?.id -> it.copy(ofMe = updated)
                else -> it
            }
        }
    }

    // ------------------------------------------------------------------ wardrobe

    suspend fun addWardrobeItem(companionId: String, input: WardrobeItemInput) {
        api.addWardrobeItem(companionId, input)
        reloadWardrobe(companionId)
    }

    suspend fun updateWardrobeItem(companionId: String, itemId: String, input: WardrobeItemInput) {
        api.updateWardrobeItem(companionId, itemId, input)
        reloadWardrobe(companionId)
    }

    suspend fun deleteWardrobeItem(companionId: String, itemId: String) {
        api.deleteWardrobeItem(companionId, itemId)
        reloadWardrobe(companionId)
    }

    suspend fun approveWardrobeItem(companionId: String, itemId: String, approved: Boolean) {
        api.approveWardrobeItem(companionId, itemId, approved)
        reloadWardrobe(companionId)
    }

    private suspend fun reloadWardrobe(companionId: String) {
        val items = api.wardrobe(companionId)
        commit {
            when (companionId) {
                it.companion?.id -> it.copy(wardrobe = items)
                it.ofMe?.id -> it.copy(ofMeWardrobe = items)
                else -> it
            }
        }
    }

    // ------------------------------------------------------------------ memories & occasions

    suspend fun saveMemory(id: String?, input: MemoryInput): MemoryDto {
        val saved = if (id == null) api.addMemory(input) else api.updateMemory(id, input)
        commit { s -> s.copy(memories = (s.memories.filterNot { it.id == saved.id } + saved)) }
        return saved
    }

    suspend fun uploadMemoryPhoto(id: String, bytes: ByteArray, mime: String) {
        val saved = api.uploadMemoryPhoto(id, bytes, mime)
        commit { s -> s.copy(memories = s.memories.map { if (it.id == id) saved else it }) }
    }

    suspend fun deleteMemory(id: String) {
        api.deleteMemory(id)
        commit { s -> s.copy(memories = s.memories.filterNot { it.id == id }) }
    }

    suspend fun saveOccasion(id: String?, input: OccasionInput) {
        val saved = if (id == null) api.addOccasion(input) else api.updateOccasion(id, input)
        commit { s -> s.copy(occasions = s.occasions.filterNot { it.id == saved.id } + saved) }
    }

    suspend fun deleteOccasion(id: String) {
        api.deleteOccasion(id)
        commit { s -> s.copy(occasions = s.occasions.filterNot { it.id == id }) }
    }

    suspend fun acceptSuggestion(suggestion: MemorySuggestionDto) {
        val companionId = state.value.companion?.id ?: return
        val memory = api.acceptSuggestion(companionId, suggestion.id)
        commit { s -> s.copy(memories = s.memories + memory, suggestions = s.suggestions.filterNot { it.id == suggestion.id }) }
    }

    suspend fun dismissSuggestion(suggestion: MemorySuggestionDto) {
        val companionId = state.value.companion?.id ?: return
        api.dismissSuggestion(companionId, suggestion.id)
        commit { s -> s.copy(suggestions = s.suggestions.filterNot { it.id == suggestion.id }) }
    }

    fun addSuggestion(suggestion: MemorySuggestionDto) {
        commit { s -> if (s.suggestions.any { it.id == suggestion.id }) s else s.copy(suggestions = s.suggestions + suggestion) }
    }

    // ------------------------------------------------------------------ cache

    private fun commit(transform: (HerState) -> HerState) {
        _state.update(transform)
        val snapshot = _state.value
        scope.launch(Dispatchers.IO) {
            runCatching { cacheFile.writeText(HerJson.encodeToString(snapshot)) }
        }
    }

    private fun loadCache(): HerState =
        runCatching { HerJson.decodeFromString<HerState>(cacheFile.readText()) }.getOrDefault(HerState())

    suspend fun clearLocalCache() = withContext(Dispatchers.IO) { cacheFile.delete() }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: ApiException) {
        if (e.status == 404 || e.status == 403) null else throw e
    }

    companion object {
        private const val TAG = "HerRepository"
    }
}
