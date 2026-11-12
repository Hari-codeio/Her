package app.her.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.AppContainer
import app.her.core.greeting.GreetingComposer
import app.her.data.settings.FloatingMode
import app.her.domain.NotificationHub
import app.her.domain.ReactionKind
import app.her.domain.VoiceInput
import app.her.ui.avatar.CompanionAvatar
import app.her.ui.avatar.Framing
import app.her.ui.avatar.SceneBackground
import app.her.ui.avatar.SpeechBubble
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

@Composable
fun FloatingCompanionUi(
    container: AppContainer,
    voice: VoiceInput,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onBubbleHeight: (Int) -> Unit,
    onOpenApp: (String) -> Unit,
    onHide: () -> Unit,
    hasMicPermission: () -> Boolean,
) {
    val presence by container.director.presence.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val speaking by container.speech.speaking.collectAsStateWithLifecycle()
    val chat by container.chat.state.collectAsStateWithLifecycle()
    val unread by NotificationHub.unread.collectAsStateWithLifecycle()
    val listening by voice.listening.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var reactionsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Show the companion's latest reply as a bubble.
    LaunchedEffect(chat.lines.lastOrNull()?.id) {
        val last = chat.lines.lastOrNull()
        if (last != null && !last.fromUser) container.director.say(last.text.take(180), 8000)
    }

    val k = settings.floatingScale
    val (w, h) = when (settings.floatingMode) {
        FloatingMode.HEAD -> 92 to 92
        FloatingMode.UPPER -> 128 to 168
        FloatingMode.FULL -> 118 to 236
    }
    val framing = when (settings.floatingMode) {
        FloatingMode.HEAD -> Framing.HEAD
        FloatingMode.UPPER -> Framing.UPPER
        FloatingMode.FULL -> Framing.FULL
    }

    // Left-aligned so the bubble, badges and panel grow to the right and the character never moves.
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.padding(6.dp)) {
        val bubbleText = when {
            chat.streaming != null -> chat.streaming!!.ifEmpty { "…" }
            listening -> "Listening…"
            else -> presence.bubble?.text
        }
        // The window grows upwards for the bubble; report its height so the character stays put.
        Box(Modifier.onSizeChanged { onBubbleHeight(it.height) }) {
            androidx.compose.animation.AnimatedVisibility(visible = bubbleText != null, enter = fadeIn(), exit = fadeOut()) {
                SpeechBubble(bubbleText.orEmpty().take(200), Modifier.widthIn(max = 220.dp).padding(bottom = 4.dp))
            }
        }

        Box(
            Modifier
                .size((w * k).dp, (h * k).dp)
                .then(
                    if (settings.floatingMode == FloatingMode.HEAD) Modifier.shadow(8.dp, CircleShape).clip(CircleShape).border(2.dp, Color.White, CircleShape)
                    else Modifier,
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            container.director.react(ReactionKind.TAP)
                            container.director.say(GreetingComposer.tapReaction(Random(System.nanoTime()), container.director.presence.value.greetingContext), 2500)
                            container.settings.markInteraction()
                        },
                        onDoubleTap = { container.director.react(ReactionKind.PLAYFUL) },
                        onLongPress = { expanded = !expanded },
                    )
                }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { tracker.resetTracking(); total = Offset.Zero },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity()
                            if (abs(velocity.x) > 2200f && abs(total.x) > abs(total.y) * 1.5f) {
                                // A quick flick changes the activity instead of moving the companion.
                                onDrag(-total.x, -total.y)
                                container.director.cycleActivity(velocity.x < 0)
                            }
                            onDragEnd()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            total += amount
                            onDrag(amount.x, amount.y)
                        },
                    )
                },
        ) {
            if (settings.floatingMode == FloatingMode.HEAD) SceneBackground(presence.environment, presence.dayPart, Modifier.fillMaxSize())
            CompanionAvatar(
                appearance = presence.appearance,
                outfit = presence.outfit,
                activity = presence.activity,
                expression = presence.expression,
                framing = framing,
                speaking = speaking,
                reaction = presence.reaction?.kind,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "AI",
                color = Color.White,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(if (settings.floatingMode == FloatingMode.HEAD) Alignment.BottomCenter else Alignment.TopEnd)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        // Notification badges from the apps the user allowed.
        if (unread.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                unread.groupBy { it.source }.forEach { (source, items) ->
                    val latest = items.maxBy { it.postedAt }
                    Box(Modifier.clickable { NotificationHub.open(context, latest) }) {
                        val icon = remember(latest.packageName) {
                            runCatching { context.packageManager.getApplicationIcon(latest.packageName).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                        }
                        if (icon != null) {
                            Image(icon, contentDescription = source.label, modifier = Modifier.size(26.dp).clip(CircleShape).border(1.5.dp, if (items.any { it.fromPartner }) Color(0xFFE5435E) else Color.White, CircleShape))
                        } else {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                Text(source.label.take(1), color = Color.White, modifier = Modifier.padding(6.dp), fontSize = 11.sp)
                            }
                        }
                        if (items.size > 1) {
                            Text(
                                "${items.size}",
                                color = Color.White,
                                fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.TopEnd).background(Color(0xFFE5435E), CircleShape).padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        if (expanded) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${presence.name} · AI", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        PanelButton(Icons.Filled.Mic, if (listening) "Stop" else "Talk") {
                            if (listening) voice.stop()
                            else if (!hasMicPermission()) onOpenApp("chat")
                            else voice.start(onResult = { container.chat.send(it, speakReply = true) }, onError = { container.director.say(it, 2500) })
                        }
                        PanelButton(Icons.Filled.ChatBubble, "Chat") { expanded = false; onOpenApp("chat") }
                        PanelButton(Icons.Filled.Checkroom, "Outfit") { scope.launch { container.director.shuffleOutfit() } }
                        PanelButton(Icons.Filled.DirectionsRun, "Activity") { container.director.cycleActivity(true) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        PanelButton(Icons.Filled.EmojiEmotions, "React") { reactionsOpen = !reactionsOpen }
                        PanelButton(Icons.Filled.Casino, "Games") { expanded = false; onOpenApp("games") }
                        PanelButton(if (settings.floatingMode == FloatingMode.HEAD) Icons.Filled.OpenInFull else Icons.Filled.Minimize, if (settings.floatingMode == FloatingMode.HEAD) "Expand" else "Minimise") {
                            container.settings.update { it.copy(floatingMode = if (it.floatingMode == FloatingMode.HEAD) FloatingMode.UPPER else FloatingMode.HEAD) }
                        }
                        PanelButton(Icons.Filled.Close, "Hide") { onHide() }
                    }
                    if (reactionsOpen) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                            listOf("👋" to ReactionKind.WAVE, "💃" to ReactionKind.DANCE, "😘" to ReactionKind.KISS, "😂" to ReactionKind.LAUGH).forEach { (emoji, kind) ->
                                Text(emoji, fontSize = 22.sp, modifier = Modifier.clickable {
                                    container.director.react(kind)
                                    if (kind == ReactionKind.WAVE) container.director.setActivity(app.her.core.model.Activity.WAVING)
                                    if (kind == ReactionKind.DANCE) container.director.setActivity(app.her.core.model.Activity.DANCING)
                                }.padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp).width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}
