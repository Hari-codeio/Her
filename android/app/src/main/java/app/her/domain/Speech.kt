package app.her.domain

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.her.data.HerRepository
import app.her.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Speaks companion replies. Uses the partner's cloned voice only when they consented and a voice
 * provider is configured on the server; otherwise the phone's own text-to-speech.
 */
class SpeechController(
    private val context: Context,
    private val repo: HerRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: String? = null
    private var player: MediaPlayer? = null
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        stop()
        val state = repo.state.value
        val companion = state.companion
        val cloned = settings.settings.value.useClonedVoice &&
            state.config?.voiceProvider == true &&
            companion?.voice?.status == "ready"
        if (cloned && companion != null) {
            scope.launch {
                val ok = runCatching { playBytes(repo.api.speak(companion.id, clean.take(1000))) }.getOrDefault(false)
                if (!ok) speakWithTts(clean)
            }
        } else {
            speakWithTts(clean)
        }
    }

    fun stop() {
        tts?.stop()
        player?.runCatching { stop(); release() }
        player = null
        _speaking.value = false
    }

    private fun speakWithTts(text: String) {
        val engine = tts
        if (engine == null) {
            pending = text
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { _speaking.value = true }
                        override fun onDone(utteranceId: String?) { _speaking.value = false }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { _speaking.value = false }
                    })
                    pending?.let { speakWithTts(it) }
                    pending = null
                }
            }
            return
        }
        if (!ttsReady) {
            pending = text
            return
        }
        val s = settings.settings.value
        engine.setSpeechRate(s.speechRate)
        engine.setPitch(s.speechPitch)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "reply-${System.nanoTime()}")
    }

    private suspend fun playBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "voice-reply.mp3").apply { writeBytes(bytes) }
        }
        return withContext(Dispatchers.Main) {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { _speaking.value = false; it.release(); if (player === it) player = null }
            mp.prepare()
            player = mp
            _speaking.value = true
            mp.start()
            true
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private val emoji = Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
        fun cleanForSpeech(text: String): String =
            text.replace(emoji, "").replace(Regex("[*_~`#>]"), "").replace(Regex("\\s+"), " ").trim()
    }
}

/** Thin wrapper over the platform speech recogniser. Call from the main thread. */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onResult: (String) -> Unit, onError: (String) -> Unit = {}) {
        if (!available) {
            onError("Speech recognition isn't available on this phone.")
            return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _listening.value = false }
            override fun onError(error: Int) {
                _listening.value = false
                _partial.value = ""
                if (error != SpeechRecognizer.ERROR_CLIENT) {
                    onError(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Didn't catch that." else "Voice input error ($error).")
                }
            }
            override fun onResults(results: Bundle?) {
                _listening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _partial.value = ""
                if (text.isNotBlank()) onResult(text) else onError("Didn't catch that.")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                _partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        r.startListening(intent)
        _listening.value = true
    }

    fun stop() {
        recognizer?.runCatching { cancel(); destroy() }
        recognizer = null
        _listening.value = false
    }
}
