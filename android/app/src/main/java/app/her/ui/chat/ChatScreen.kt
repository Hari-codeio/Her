package app.her.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.core.games.Game
import app.her.core.games.GameDecks
import app.her.domain.ChatEngine
import app.her.domain.ChatLine
import app.her.domain.VoiceInput
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.Routes
import app.her.ui.avatar.CompanionAvatar
import app.her.ui.avatar.Framing
import app.her.ui.avatar.SceneBackground
import app.her.ui.common.EmptyState
import kotlin.random.Random

@Composable
fun ChatScreen(game: String?, onNavigate: (String) -> Unit) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    val presence by container.director.presence.collectAsStateWithLifecycle()
    val chat by container.chat.state.collectAsStateWithLifecycle()
    val repoState by container.repo.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val speaking by container.speech.speaking.collectAsStateWithLifecycle()
    var activeGame by rememberSaveable { mutableStateOf(game) }
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    val voice = remember { VoiceInput(context) }
    val listening by voice.listening.collectAsStateWithLifecycle()
    val partial by voice.partial.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(presence.companionId) { container.chat.load() }
    LaunchedEffect(game) { if (game != null) activeGame = game }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    LaunchedEffect(chat.lines.size, chat.streaming) {
        val count = chat.lines.size + if (chat.streaming != null) 1 else 0
        if (count > 0) {
            if (chat.streaming != null) listState.scrollToItem(count - 1) else listState.animateScrollToItem(count - 1)
        }
    }

    fun send(message: String) {
        container.chat.send(message, game = activeGame, speakReply = if (voiceMode) true else null)
    }

    fun listen() {
        voice.start(onResult = { send(it) }, onError = { runner.toast(it); voiceMode = false })
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else runner.toast("Microphone permission is needed for voice chat.")
    }

    // Voice conversation: after the reply has been spoken, listen again.
    LaunchedEffect(voiceMode, chat.busy, speaking, listening) {
        if (voiceMode && !chat.busy && !speaking && !listening && chat.lines.lastOrNull()?.fromUser == false) {
            kotlinx.coroutines.delay(500)
            listen()
        }
    }

    if (!presence.ready) {
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            EmptyState("💬", "Chat opens once your companion is active", "Your partner needs to give consent and approve their character first.") {
                TextButton(onClick = { onNavigate(Routes.HOME) }) { Text("Back home") }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // Header.
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape)) {
                    SceneBackground(presence.environment, presence.dayPart, Modifier.fillMaxSize())
                    CompanionAvatar(
                        appearance = presence.appearance,
                        outfit = presence.outfit,
                        activity = presence.activity,
                        expression = presence.expression,
                        framing = Framing.HEAD,
                        speaking = speaking,
                        reaction = presence.reaction?.kind,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(presence.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            chat.busy -> "typing…"
                            speaking -> "speaking…"
                            listening -> "listening…"
                            else -> "AI companion · ${presence.mood.emoji} ${presence.activity.verb}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { container.settings.update { it.copy(speakReplies = !it.speakReplies) } }) {
                    Icon(if (settings.speakReplies) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, contentDescription = "Speak replies")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Games") }, onClick = { menu = false; onNavigate(Routes.GAMES) })
                        DropdownMenuItem(text = { Text("Clear conversation") }, onClick = { menu = false; container.chat.clear() })
                    }
                }
            }
        }

        if (repoState.config?.chatAvailable == false) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    "AI chat isn't set up on the server yet (it needs Claude API credentials). Greetings, games cards and everything else still work.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Game banner.
        activeGame?.let { apiName ->
            val g = Game.entries.firstOrNull { it.apiName == apiName }
            if (g != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${g.emoji} ${g.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { activeGame = null }) { Icon(Icons.Filled.Close, contentDescription = "End game") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { send(g.opener) }, label = { Text("Start") })
                            when (g) {
                                Game.TRUTH_OR_DARE -> {
                                    AssistChip(onClick = { send("Truth! " + GameDecks.draw(GameDecks.truths, Random(System.nanoTime()))) }, label = { Text("Draw a truth") })
                                    AssistChip(onClick = { send("Dare: " + GameDecks.draw(GameDecks.dares, Random(System.nanoTime()))) }, label = { Text("Draw a dare") })
                                }
                                Game.RANDOM_QUESTIONS -> AssistChip(onClick = { send(GameDecks.draw(GameDecks.questions, Random(System.nanoTime()))) }, label = { Text("Ask a card") })
                                Game.WOULD_YOU_RATHER -> AssistChip(onClick = { send(GameDecks.draw(GameDecks.wouldYouRather, Random(System.nanoTime()))) }, label = { Text("Draw a card") })
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }

        // Memory suggestions from the companion. Nothing is saved until the user says yes.
        repoState.suggestions.firstOrNull()?.let { suggestion ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remember this?", style = MaterialTheme.typography.labelMedium)
                        Text(suggestion.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { runner.run(done = "Saved to memories") { container.repo.acceptSuggestion(suggestion) } }) { Text("Save") }
                    TextButton(onClick = { runner.run { container.repo.dismissSuggestion(suggestion) } }) { Text("No") }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat.lines.isEmpty() && chat.streaming == null) {
                item {
                    EmptyState("💌", "Say hi to ${presence.name}", "Conversations are private. ${presence.name} is an AI companion based on ${presence.subjectName}.")
                }
            }
            items(chat.lines, key = { it.id }) { line -> Bubble(line, presence.name, onSpeak = { container.speech.speak(line.text) }) }
            chat.streaming?.let { streaming ->
                item(key = "streaming") {
                    Bubble(ChatLine("streaming", false, streaming.ifEmpty { "…" }), presence.name, onSpeak = null)
                }
            }
        }

        chat.error?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ChatEngine.describeError(error), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { container.chat.dismissError() }) { Text("OK") }
                }
            }
        }

        // Input.
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                voiceMode = !voiceMode
                if (voiceMode) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    voice.stop(); container.speech.stop()
                }
            }) {
                Icon(Icons.Filled.GraphicEq, contentDescription = "Voice conversation", tint = if (voiceMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = if (listening && partial.isNotEmpty()) partial else text,
                onValueChange = { text = it },
                placeholder = { Text(if (listening) "Listening…" else "Message ${presence.name}") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
            )
            Spacer(Modifier.size(6.dp))
            if (text.isBlank()) {
                FilledIconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (listening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Filled.Mic, contentDescription = "Speak")
                }
            } else {
                FilledIconButton(onClick = { send(text); text = "" }, enabled = !chat.busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine, name: String, onSpeak: (() -> Unit)?) {
    val mine = line.fromUser
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!mine) {
                Text("$name · AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
            }
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                    .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(line.text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
            if (line.failed) Text("No reply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (!mine && onSpeak != null) {
                TextButton(onClick = onSpeak, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("▶ Listen", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
