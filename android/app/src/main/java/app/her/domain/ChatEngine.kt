package app.her.domain

import app.her.data.HerRepository
import app.her.data.remote.ApiException
import app.her.data.remote.ChatEvent
import app.her.data.remote.ChatMessageDto
import app.her.data.remote.ChatRequest
import app.her.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatLine(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val kind: String = "chat",
    val game: String? = null,
    val failed: Boolean = false,
)

data class ChatUiState(
    val lines: List<ChatLine> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val loadedFor: String? = null,
)

/** One conversation shared by the chat screen and the floating companion. */
class ChatEngine(
    private val repo: HerRepository,
    private val director: CompanionDirector,
    private val settings: SettingsStore,
    private val speech: SpeechController,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun load(force: Boolean = false) {
        val id = repo.state.value.companion?.takeIf { it.isActive }?.id ?: return
        if (!force && _state.value.loadedFor == id) return
        scope.launch {
            try {
                val messages = repo.api.messages(id, limit = 80)
                _state.update { it.copy(lines = messages.map(::toLine), loadedFor = id, error = null) }
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.code) }
            }
        }
    }

    fun send(text: String, game: String? = null, notificationHint: String? = null, speakReply: Boolean? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        val id = repo.state.value.companion?.takeIf { it.isActive }?.id ?: run {
            _state.update { it.copy(error = "companion_not_active") }
            return
        }
        settings.markInteraction()
        val local = ChatLine("local-${System.nanoTime()}", true, trimmed, if (game != null) "game" else "chat", game)
        _state.update { it.copy(lines = it.lines + local, streaming = "", busy = true, error = null) }
        val presence = director.presence.value
        val hint = notificationHint ?: if (settings.settings.value.shareNotificationHint) NotificationHub.latestHint() else null
        val request = ChatRequest(
            text = trimmed,
            mode = if (game != null) "game" else "chat",
            game = game,
            context = presence.chatContext(hint),
        )
        job = scope.launch {
            val buffer = StringBuilder()
            var failure: String? = null
            var replyId: String? = null
            repo.api.chat(id, request).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        buffer.append(event.text)
                        _state.update { it.copy(streaming = buffer.toString()) }
                    }
                    is ChatEvent.Suggestion -> repo.addSuggestion(event.suggestion)
                    is ChatEvent.Done -> replyId = event.messageId
                    is ChatEvent.Failed -> failure = event.detail
                }
            }
            // The server stores a reply only when the stream completes; drop partial text otherwise
            // (for example after a refusal) so the screen matches the saved conversation.
            val reply = if (failure == null) buffer.toString().trim() else ""
            _state.update { s ->
                val lines = if (reply.isNotEmpty()) {
                    s.lines + ChatLine(replyId ?: "reply-${System.nanoTime()}", false, reply, request.mode, game)
                } else s.lines
                s.copy(
                    lines = lines.map { if (it.id == local.id && failure != null && reply.isEmpty()) it.copy(failed = true) else it },
                    streaming = null,
                    busy = false,
                    error = failure,
                )
            }
            if (reply.isNotEmpty()) {
                director.react(ReactionKind.TAP)
                if (speakReply ?: settings.settings.value.speakReplies) speech.speak(reply)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(busy = false, streaming = null) }
    }

    fun clear() {
        val id = repo.state.value.companion?.id ?: return
        scope.launch {
            runCatching { repo.api.clearMessages(id) }
            _state.update { ChatUiState(loadedFor = id) }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun toLine(m: ChatMessageDto) = ChatLine(m.id, m.role == "user", m.text, m.kind, m.game)

    companion object {
        fun describeError(code: String): String = when (code) {
            "chat_unavailable" -> "The AI service isn't reachable right now. Try again in a moment."
            "rate_limited" -> "That's a lot of messages! Give it a minute."
            "declined" -> "Let's talk about something else."
            "network" -> "No connection to the server."
            "companion_not_active" -> "Your companion isn't active yet."
            "likeness_consent_required" -> "Your partner hasn't given consent yet."
            else -> "Something went wrong ($code)."
        }
    }
}
