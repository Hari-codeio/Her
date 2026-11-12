package app.her.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.core.behavior.BehaviorEngine
import app.her.core.greeting.GreetingComposer
import app.her.core.model.Environment
import app.her.core.occasion.OccasionEngine
import app.her.data.HerState
import app.her.domain.Presence
import app.her.domain.ReactionKind
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.Routes
import app.her.ui.avatar.CompanionStage
import app.her.ui.canDrawOverlays
import app.her.ui.common.Pill
import app.her.ui.common.SectionCard
import app.her.ui.common.Swatch
import app.her.ui.overlayPermissionIntent
import java.time.format.DateTimeFormatter
import kotlin.random.Random

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val container = LocalContainer.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val status by container.repo.status.collectAsStateWithLifecycle()
    val presence by container.director.presence.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(presence.now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (presence.ready) presence.name else "Hi ${state.me?.displayName ?: ""}",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            if (status.offline) Pill("Offline")
            IconButton(onClick = { container.repo.refreshAsync() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }

        if (state.pendingForMe > 0) {
            SectionCard(title = "${state.partner?.displayName ?: "Your partner"} is waiting on you", subtitle = "${state.pendingForMe} change(s) to your own character need your approval.") {
                FilledTonalButton(onClick = { onNavigate(Routes.LIKENESS) }) { Text("Review") }
            }
        }

        if (presence.ready) {
            ActiveHome(presence, state, onNavigate)
        } else {
            NotReady(state, onNavigate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ActiveHome(p: Presence, state: HerState, onNavigate: (String) -> Unit) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    val speaking by container.speech.speaking.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }

    CompanionStage(
        presence = p,
        speaking = speaking,
        modifier = Modifier.fillMaxWidth().height(470.dp),
        onTap = {
            container.director.react(ReactionKind.TAP)
            container.director.say(GreetingComposer.tapReaction(Random(System.nanoTime()), p.greetingContext), 2500)
            container.settings.markInteraction()
        },
        onDoubleTap = { container.director.react(ReactionKind.PLAYFUL) },
        onLongPress = { sheet = true },
        onSwipe = { forward -> container.director.cycleActivity(forward) },
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("${p.mood.emoji} ${p.mood.label} today")
        Pill(p.activity.label)
        Pill(p.environment.label)
        p.weather?.let { Pill("${it.emoji} ${it.summary}") }
    }
    Text(
        "Tap to say hi · double-tap to play · swipe to change activity · long-press for more",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    p.occasionToday?.let { occasion ->
        SectionCard(title = "🎉 ${occasion.title}", subtitle = p.occasionYears?.let { "${OccasionEngine.ordinal(it)} year" }) {
            Text(GreetingComposer.occasion(occasion, p.occasionYears, p.greetingContext))
        }
    }

    SectionCard(title = "${p.name} says") {
        Text(p.greeting, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(Routes.CHAT) }) {
                Icon(Icons.Filled.ChatBubble, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reply")
            }
            OutlinedButton(onClick = { container.speech.speak(p.greeting) }) { Text("Hear it") }
        }
    }

    SectionCard(
        title = if (p.isBedtimeLook) "Bedtime look" else "Today ${p.name} is wearing",
        subtitle = p.outfit.note ?: if (p.outfit.isDefault) "Add clothes to the wardrobe and they'll start choosing outfits." else null,
    ) {
        p.outfit.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Swatch(item.primaryColor)
                Spacer(Modifier.width(10.dp))
                Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(item.category.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!p.outfit.isDefault && !p.isBedtimeLook) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val liked = p.record?.liked
                IconButton(onClick = { runner.run { container.director.rateOutfit(if (liked == 1) null else true) } }) {
                    Icon(if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, contentDescription = "Like outfit")
                }
                IconButton(onClick = { runner.run { container.director.rateOutfit(if (liked == -1) null else false) } }) {
                    Icon(if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, contentDescription = "Dislike outfit")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { runner.run { container.director.shuffleOutfit() } }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Change")
                }
                TextButton(onClick = { onNavigate(Routes.WARDROBE) }) { Text("Wardrobe") }
            }
        }
    }

    p.recall?.let { recall ->
        SectionCard(title = "💭 ${recall.framing}", subtitle = if (recall.memory.isFictional) "A story you made up together" else null) {
            Text(recall.memory.title, style = MaterialTheme.typography.titleMedium)
            if (recall.memory.body.isNotBlank()) Text(recall.memory.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = {
                container.chat.send("Do you remember \"${recall.memory.title}\"?")
                container.settings.update { it.copy(recentRecalls = (it.recentRecalls + recall.memory.id).toList().takeLast(20).toSet()) }
                onNavigate(Routes.CHAT)
            }) { Text("Reminisce together") }
        }
    }

    p.upcoming.firstOrNull { it.second in 1..30 }?.let { (occasion, days) ->
        SectionCard(title = "Coming up", subtitle = "${occasion.title} in $days day${if (days == 1L) "" else "s"}") {
            TextButton(onClick = { onNavigate(Routes.OCCASIONS) }) { Text("See all dates") }
        }
    }

    if (!settings.floatingEnabled) {
        SectionCard(title = "Keep ${p.name} nearby", subtitle = "Show your companion floating over other apps. Hide it any time.") {
            FilledTonalButton(onClick = {
                if (canDrawOverlays(context)) container.settings.update { it.copy(floatingEnabled = true) }
                else context.startActivity(overlayPermissionIntent(context))
            }) { Text(if (canDrawOverlays(context)) "Turn on floating companion" else "Allow display over other apps") }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { onNavigate(Routes.GAMES) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Casino, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play")
        }
        OutlinedButton(onClick = { onNavigate(Routes.MEMORIES) }, modifier = Modifier.weight(1f)) { Text("Memories") }
    }
    Spacer(Modifier.height(8.dp))

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Interact with ${p.name}", style = MaterialTheme.typography.titleLarge)
                Text("Reactions", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("👋 Wave" to ReactionKind.WAVE, "💃 Dance" to ReactionKind.DANCE, "😘 Blow a kiss" to ReactionKind.KISS, "😂 Laugh" to ReactionKind.LAUGH)
                        .forEach { (label, kind) ->
                            AssistChip(onClick = {
                                container.director.react(kind)
                                when (kind) {
                                    ReactionKind.WAVE -> container.director.setActivity(app.her.core.model.Activity.WAVING)
                                    ReactionKind.DANCE -> container.director.setActivity(app.her.core.model.Activity.DANCING)
                                    else -> Unit
                                }
                                sheet = false
                            }, label = { Text(label) })
                        }
                }
                Text("Activity", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BehaviorEngine.weights(p.environment, p.dayPart, p.mood).keys.sortedBy { it.ordinal }.forEach { activity ->
                        FilterChip(selected = activity == p.activity, onClick = { container.director.setActivity(activity) }, label = { Text(activity.label) })
                    }
                }
                Text("Scene", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Environment.entries.forEach { env ->
                        FilterChip(selected = env == p.environment, onClick = { container.director.setEnvironment(env) }, label = { Text(env.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sheet = false; onNavigate(Routes.CHAT) }) { Text("Talk") }
                    OutlinedButton(onClick = { sheet = false; runner.run { container.director.shuffleOutfit() } }) { Text("New outfit") }
                    OutlinedButton(onClick = { sheet = false; onNavigate(Routes.GAMES) }) { Text("Games") }
                }
            }
        }
    }
}

@Composable
private fun NotReady(state: HerState, onNavigate: (String) -> Unit) {
    val partner = state.partner?.displayName ?: "your partner"
    val companion = state.companion
    when (companion?.status) {
        null, "awaiting_consent" -> SectionCard(
            title = "Waiting for $partner",
            subtitle = "Before any character of $partner can exist, they need to say yes from their own phone.",
        ) {
            Text(
                "Ask $partner to open Her → More → My likeness & consent. They choose what you can see (their look, voice, personality) and approve every version.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        "revoked" -> SectionCard(
            title = "$partner turned off their companion",
            subtitle = "Their photos and character were deleted, as they asked. They can turn it back on whenever they like.",
        ) {}
        else -> {
            val pending = companion.pendingAppearance != null
            SectionCard(
                title = if (pending) "Waiting for $partner to approve the look" else "Create $partner's look",
                subtitle = if (pending) "You'll see the companion as soon as they approve it." else "$partner said yes. Design their character and send it to them for approval.",
            ) {
                Button(onClick = { onNavigate(Routes.STUDIO) }) { Text(if (pending) "Open character studio" else "Start designing") }
            }
        }
    }
    val ofMe = state.ofMe
    if (ofMe != null && !ofMe.consents.likeness) {
        SectionCard(
            title = "What about your own companion?",
            subtitle = "${state.partner?.displayName ?: "Your partner"} can have an AI companion of you only if you allow it.",
        ) {
            FilledTonalButton(onClick = { onNavigate(Routes.LIKENESS) }) { Text("Review consent") }
        }
    }
}
