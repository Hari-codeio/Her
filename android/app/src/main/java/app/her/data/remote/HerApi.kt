package app.her.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String) : Exception(code) {
    val isNetwork: Boolean get() = status == 0
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Suggestion(val suggestion: MemorySuggestionDto) : ChatEvent
    data class Done(val userMessageId: String?, val messageId: String?) : ChatEvent
    data class Failed(val detail: String) : ChatEvent
}

/** Where the API lives and who we are. Backed by settings; read on every request. */
interface SessionSource {
    val serverUrl: String
    val token: String?
}

val HerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    coerceInputValues = true
}

class HerApi(private val session: SessionSource) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    val baseUrl: String get() = session.serverUrl.trimEnd('/')
    val authHeader: String? get() = session.token?.let { "Bearer $it" }

    // ------------------------------------------------------------------ service
    suspend fun config(): ConfigDto = get("/v1/config")

    // ------------------------------------------------------------------ account
    suspend fun register(name: String): RegisterResponse = post("/v1/auth/register", RegisterRequest(name))
    suspend fun me(): MeResponse = get("/v1/me")
    suspend fun updateMe(name: String): UserDto = send("PATCH", "/v1/me", DisplayNameUpdate(name))
    suspend fun deleteAccount() = delete("/v1/me")

    // ------------------------------------------------------------------ pairing
    suspend fun createInvite(): InviteDto = send("POST", "/v1/couple/invite", null as String?)
    suspend fun join(code: String): CoupleDto = post("/v1/couple/join", JoinRequest(code.trim().uppercase()))
    suspend fun setAnniversary(date: String?): CoupleDto = send("PATCH", "/v1/couple", AnniversaryUpdate(date))
    suspend fun unpair() = delete("/v1/couple")

    // ------------------------------------------------------------------ companions
    suspend fun myCompanion(): CompanionDto = get("/v1/companions/mine")
    suspend fun companionOfMe(): CompanionDto = get("/v1/companions/of-me")
    suspend fun renameCompanion(id: String, name: String): CompanionDto = send("PATCH", "/v1/companions/$id", CompanionNameUpdate(name))
    suspend fun setConsent(id: String, update: ConsentUpdate): CompanionDto = send("PUT", "/v1/companions/$id/consent", update)
    suspend fun consentHistory(id: String): List<ConsentEventDto> = get("/v1/companions/$id/consent-history")
    suspend fun putAppearance(id: String, appearance: AppearanceDto): CompanionDto = send("PUT", "/v1/companions/$id/appearance", appearance)
    suspend fun approveAppearance(id: String, versionId: String): CompanionDto = send("POST", "/v1/companions/$id/appearance/$versionId/approve", null as String?)
    suspend fun rejectAppearance(id: String, versionId: String, reason: String?): CompanionDto = post("/v1/companions/$id/appearance/$versionId/reject", RejectRequest(reason))
    suspend fun putPersona(id: String, persona: PersonaDto): CompanionDto = send("PUT", "/v1/companions/$id/persona", persona)
    suspend fun approvePersona(id: String): CompanionDto = send("POST", "/v1/companions/$id/persona/approve", null as String?)
    suspend fun rejectPersona(id: String): CompanionDto = send("POST", "/v1/companions/$id/persona/reject", null as String?)
    suspend fun photos(id: String): List<PhotoDto> = get("/v1/companions/$id/photos")
    suspend fun uploadPhoto(id: String, bytes: ByteArray, mime: String): PhotoDto = upload("/v1/companions/$id/photos", bytes, mime, "photo")
    suspend fun deletePhoto(id: String, photoId: String) = delete("/v1/companions/$id/photos/$photoId")
    fun photoUrl(id: String, photoId: String) = "$baseUrl/v1/companions/$id/photos/$photoId"
    suspend fun generateLikeness(id: String): LikenessDto = send("POST", "/v1/companions/$id/likeness/generate", null as String?)
    suspend fun uploadVoiceSample(id: String, bytes: ByteArray, mime: String): StatusDto = upload("/v1/companions/$id/voice/samples", bytes, mime, "sample")
    suspend fun speak(id: String, text: String): ByteArray = bytes("POST", "/v1/companions/$id/voice/speak", SpeakRequest(text))

    // ------------------------------------------------------------------ wardrobe
    suspend fun wardrobe(id: String): List<WardrobeItemDto> = get("/v1/companions/$id/wardrobe")
    suspend fun addWardrobeItem(id: String, input: WardrobeItemInput): WardrobeItemDto = post("/v1/companions/$id/wardrobe", input)
    suspend fun updateWardrobeItem(id: String, itemId: String, input: WardrobeItemInput): WardrobeItemDto = send("PATCH", "/v1/companions/$id/wardrobe/$itemId", input)
    suspend fun deleteWardrobeItem(id: String, itemId: String) = delete("/v1/companions/$id/wardrobe/$itemId")
    suspend fun approveWardrobeItem(id: String, itemId: String, approved: Boolean) {
        execute("POST", "/v1/companions/$id/wardrobe/$itemId/approve", jsonBody(ApproveRequest(approved)))
    }

    // ------------------------------------------------------------------ memories & occasions
    suspend fun memories(): List<MemoryDto> = get("/v1/memories")
    suspend fun addMemory(input: MemoryInput): MemoryDto = post("/v1/memories", input)
    suspend fun updateMemory(id: String, input: MemoryInput): MemoryDto = send("PATCH", "/v1/memories/$id", input)
    suspend fun deleteMemory(id: String) = delete("/v1/memories/$id")
    suspend fun uploadMemoryPhoto(id: String, bytes: ByteArray, mime: String): MemoryDto =
        upload("/v1/memories/$id/photo", bytes, mime, "photo", method = "PUT")
    fun memoryPhotoUrl(id: String) = "$baseUrl/v1/memories/$id/photo"
    suspend fun occasions(): List<OccasionDto> = get("/v1/occasions")
    suspend fun addOccasion(input: OccasionInput): OccasionDto = post("/v1/occasions", input)
    suspend fun updateOccasion(id: String, input: OccasionInput): OccasionDto = send("PATCH", "/v1/occasions/$id", input)
    suspend fun deleteOccasion(id: String) = delete("/v1/occasions/$id")

    // ------------------------------------------------------------------ chat
    suspend fun messages(id: String, limit: Int = 50, before: String? = null): List<ChatMessageDto> =
        get("/v1/companions/$id/messages?limit=$limit" + (before?.let { "&before=$it" } ?: ""))
    suspend fun clearMessages(id: String) = delete("/v1/companions/$id/messages")
    suspend fun suggestions(id: String): List<MemorySuggestionDto> = get("/v1/companions/$id/memory-suggestions")
    suspend fun acceptSuggestion(id: String, sid: String): MemoryDto = send("POST", "/v1/companions/$id/memory-suggestions/$sid/accept", null as String?)
    suspend fun dismissSuggestion(id: String, sid: String) {
        execute("POST", "/v1/companions/$id/memory-suggestions/$sid/dismiss", jsonBody(null as String?))
    }
    suspend fun greeting(id: String, context: ChatContextDto): TextDto = post("/v1/companions/$id/greeting", GreetingRequest(context))

    fun chat(id: String, request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val req = Request.Builder()
            .url("$baseUrl/v1/companions/$id/chat")
            .header("Accept", "text/event-stream")
            .apply { authHeader?.let { header("Authorization", it) } }
            .post(HerJson.encodeToString(request).toRequestBody(jsonType))
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    when (type) {
                        "delta" -> trySend(ChatEvent.Delta(HerJson.decodeFromString<TextDto>(data).text))
                        "memory_suggestion" -> trySend(ChatEvent.Suggestion(HerJson.decodeFromString<MemorySuggestionDto>(data)))
                        "done" -> {
                            val done = HerJson.decodeFromString<DoneDto>(data)
                            trySend(ChatEvent.Done(done.userMessageId, done.messageId))
                            channel.close()
                        }
                        "error" -> {
                            trySend(ChatEvent.Failed(parseDetail(data)))
                            channel.close()
                        }
                    }
                } catch (e: Exception) {
                    trySend(ChatEvent.Failed("bad_event"))
                    channel.close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val detail = runCatching { response?.body?.string() }.getOrNull()?.let(::parseDetail)
                    ?: if (response == null) "network" else "http_${response.code}"
                trySend(ChatEvent.Failed(detail))
                channel.close()
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }
        }
        val source = EventSources.createFactory(streamClient).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }

    // ------------------------------------------------------------------ privacy
    suspend fun deleteAllPhotos() = delete("/v1/privacy/photos")
    suspend fun deleteVoice() = delete("/v1/privacy/voice")
    suspend fun deleteMyMemories() = delete("/v1/privacy/memories")
    suspend fun exportData(): String = execute("GET", "/v1/privacy/export", null)

    // ------------------------------------------------------------------ plumbing
    private suspend inline fun <reified T> get(path: String): T =
        HerJson.decodeFromString(execute("GET", path, null))

    private suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        HerJson.decodeFromString(execute("POST", path, jsonBody(body)))

    private suspend inline fun <reified T, reified B> send(method: String, path: String, body: B): T =
        HerJson.decodeFromString(execute(method, path, jsonBody(body)))

    private suspend fun delete(path: String) {
        execute("DELETE", path, null)
    }

    private inline fun <reified B> jsonBody(body: B): RequestBody =
        (if (body == null) "{}" else HerJson.encodeToString(body)).toRequestBody(jsonType)

    private suspend inline fun <reified T> upload(path: String, bytes: ByteArray, mime: String, name: String, method: String = "POST"): T {
        val extension = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
            mime.startsWith("audio") -> "audio"
            else -> "bin"
        }
        return HerJson.decodeFromString(uploadRaw(path, bytes, mime, "$name.$extension", method))
    }

    private suspend fun bytes(method: String, path: String, body: SpeakRequest): ByteArray = withContext(Dispatchers.IO) {
        val request = buildRequest(method, path, jsonBody(body))
        call(request) { it.body?.bytes() ?: ByteArray(0) }
    }

    suspend fun execute(method: String, path: String, body: RequestBody?): String = withContext(Dispatchers.IO) {
        call(buildRequest(method, path, body)) { it.body?.string().orEmpty() }
    }

    private fun buildRequest(method: String, path: String, body: RequestBody?): Request =
        Request.Builder()
            .url(baseUrl + path)
            .apply { authHeader?.let { header("Authorization", it) } }
            .method(method, body)
            .build()

    private fun <R> call(request: Request, read: (Response) -> R): R {
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw ApiException(0, "network")
        }
        response.use {
            if (!it.isSuccessful) throw ApiException(it.code, parseDetail(it.body?.string().orEmpty()))
            return read(it)
        }
    }

    internal suspend fun uploadRaw(path: String, bytes: ByteArray, mime: String, filename: String, method: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType()))
            .build()
        return execute(method, path, body)
    }

    companion object {
        fun parseDetail(text: String): String {
            if (text.isBlank()) return "unknown_error"
            return runCatching {
                when (val detail = HerJson.parseToJsonElement(text).jsonObject["detail"]) {
                    is JsonPrimitive -> detail.content
                    is JsonArray -> "invalid_input"
                    else -> "unknown_error"
                }
            }.getOrDefault(text.take(120))
        }
    }
}
