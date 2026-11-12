package app.her.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.BuildConfig
import app.her.core.behavior.AutonomyLevel
import app.her.core.model.Environment
import app.her.core.notify.NotificationSource
import app.her.data.settings.FloatingMode
import app.her.domain.NotificationHub
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.Routes
import app.her.ui.canDrawOverlays
import app.her.ui.common.ConfirmDialog
import app.her.ui.common.EmptyState
import app.her.ui.common.SectionCard
import app.her.ui.common.SettingSwitch
import app.her.ui.notificationListenerEnabled
import app.her.ui.notificationListenerIntent
import app.her.ui.overlayPermissionIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val state by container.repo.state.collectAsStateWithLifecycle()
    val weather by container.weather.weather.collectAsStateWithLifecycle()
    val store = container.settings
    val name = state.companion?.name ?: "your companion"
    var city by remember { mutableStateOf(s.weatherCity) }
    var myName by remember { mutableStateOf(state.me?.displayName ?: "") }

    ScreenScaffold("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard(title = "Behaviour", subtitle = "How lively $name is on their own") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AutonomyLevel.entries.forEach { level ->
                        FilterChip(selected = s.autonomy == level, onClick = { store.update { it.copy(autonomy = level) } }, label = { Text(level.label) })
                    }
                }
                SettingSwitch("Pause independent behaviour", "Stays in one calm activity and doesn't start conversations", s.paused) { v -> store.update { it.copy(paused = v) } }
                SettingSwitch("Can start conversations", checked = s.initiateConversations) { v -> store.update { it.copy(initiateConversations = v) } }
                SettingSwitch("Morning greeting", checked = s.morningGreeting) { v -> store.update { it.copy(morningGreeting = v) } }
                SettingSwitch("Evening check-in", checked = s.eveningGreeting) { v -> store.update { it.copy(eveningGreeting = v) } }
                SettingSwitch("Bedtime chat", checked = s.bedtimeChat) { v -> store.update { it.copy(bedtimeChat = v) } }
                SettingSwitch("Special-day celebrations & reminders", checked = s.occasionReminders) { v -> store.update { it.copy(occasionReminders = v) } }
                SettingSwitch("Bring up saved memories", checked = s.memoryRecall) { v -> store.update { it.copy(memoryRecall = v) } }
                SettingSwitch("Suggest games", checked = s.gameSuggestions) { v -> store.update { it.copy(gameSuggestions = v) } }
                Text("Quiet hours: ${s.quietStart}:00 – ${s.quietEnd}:00", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(s.quietStart.toFloat(), { v -> store.update { it.copy(quietStart = v.toInt()) } }, valueRange = 18f..23f, steps = 4)
                Slider(s.quietEnd.toFloat(), { v -> store.update { it.copy(quietEnd = v.toInt()) } }, valueRange = 5f..10f, steps = 4)
            }

            SectionCard(title = "Scene") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Environment.entries.forEach { env -> FilterChip(selected = s.environment == env, onClick = { store.update { it.copy(environment = env) } }, label = { Text(env.label) }) }
                }
            }

            SectionCard(title = "Outfits") {
                SettingSwitch("Occasional surprise outfits", checked = s.surpriseOutfits) { v -> store.update { it.copy(surpriseOutfits = v) } }
                SettingSwitch("Nightwear at bedtime", checked = s.nightwearAtNight) { v -> store.update { it.copy(nightwearAtNight = v) } }
                SettingSwitch("Dress for the weather", "Sends only the city name to Open-Meteo", s.weatherEnabled) { v -> store.update { it.copy(weatherEnabled = v) } }
                if (s.weatherEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(city, { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            runner.run {
                                val resolved = container.weather.setCity(city)
                                runner.toast(resolved?.let { "Weather set for $it" } ?: "Couldn't find that city")
                            }
                        }) { Text("Set") }
                    }
                    weather?.let { Text("Now: ${it.emoji} ${it.summary}", style = MaterialTheme.typography.bodySmall) }
                }
                SettingSwitch("Southern hemisphere seasons", checked = s.southernHemisphere) { v -> store.update { it.copy(southernHemisphere = v) } }
            }

            SectionCard(title = "Floating companion", subtitle = "Over other apps. Drag to move, tap to react, long-press for controls.") {
                val allowed = canDrawOverlays(context)
                SettingSwitch("Show floating companion", if (allowed) null else "Needs permission to display over other apps", s.floatingEnabled) { v ->
                    if (v && !canDrawOverlays(context)) context.startActivity(overlayPermissionIntent(context))
                    else store.update { it.copy(floatingEnabled = v) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FloatingMode.entries.forEach { mode -> FilterChip(selected = s.floatingMode == mode, onClick = { store.update { it.copy(floatingMode = mode) } }, label = { Text(mode.label) }) }
                }
                Text("Size", style = MaterialTheme.typography.labelLarge)
                Slider(s.floatingScale, { v -> store.update { it.copy(floatingScale = v) } }, valueRange = 0.7f..1.5f)
                TextButton(onClick = { onNavigate(Routes.NOTIFICATIONS) }) { Text("Message notifications under the companion →") }
            }

            SectionCard(title = "Voice") {
                SettingSwitch("Speak replies aloud", checked = s.speakReplies) { v -> store.update { it.copy(speakReplies = v) } }
                SettingSwitch("Use ${state.partner?.displayName ?: "partner"}'s voice when available", "Only with their voice consent", s.useClonedVoice) { v -> store.update { it.copy(useClonedVoice = v) } }
                Text("Speaking speed ${"%.1f".format(s.speechRate)}×", style = MaterialTheme.typography.labelLarge)
                Slider(s.speechRate, { v -> store.update { it.copy(speechRate = v) } }, valueRange = 0.6f..1.6f)
                Text("Pitch ${"%.1f".format(s.speechPitch)}", style = MaterialTheme.typography.labelLarge)
                Slider(s.speechPitch, { v -> store.update { it.copy(speechPitch = v) } }, valueRange = 0.6f..1.6f)
                OutlinedButton(onClick = { container.speech.speak("Hi! This is how I sound right now.") }) { Text("Test voice") }
            }

            SectionCard(title = "Account") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(myName, { myName = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { runner.run(done = "Saved") { container.repo.rename(myName.trim()) } }, enabled = myName.isNotBlank()) { Text("Save") }
                }
                Text("Server: ${s.serverUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "$name is an AI companion. It isn't ${state.partner?.displayName ?: "your partner"}, doesn't read their messages, and moods are part of the character, not their real feelings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val state by container.repo.state.collectAsStateWithLifecycle()
    val history by container.db.notifications().observe().collectAsStateWithLifecycle(emptyList())
    val store = container.settings
    var names by remember { mutableStateOf(s.partnerContactNames.joinToString(", ")) }
    val listener = notificationListenerEnabled(context)
    var confirmClear by remember { mutableStateOf(false) }

    ScreenScaffold("Message notifications", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(
                    title = if (listener) "Notification access is on" else "Allow notification access",
                    subtitle = "Her reads notifications only from the apps you switch on below, on this phone only. It never reads anyone else's account, and message text never leaves your phone.",
                ) {
                    OutlinedButton(onClick = { context.startActivity(notificationListenerIntent()) }) { Text(if (listener) "Manage access" else "Open settings") }
                }
            }
            item {
                SectionCard(title = "Apps") {
                    NotificationSource.entries.forEachIndexed { i, source ->
                        if (i > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            appIcon(context, source.packages.first())?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)) }
                            Column(Modifier.weight(1f)) {
                                SettingSwitch(source.label, checked = source in s.enabledSources) { v ->
                                    store.update { it.copy(enabledSources = if (v) it.enabledSources + source else it.enabledSources - source) }
                                    if (!v) NotificationHub.clear(source)
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Display") {
                    SettingSwitch("Show message previews", "Otherwise only the app and sender are shown", s.showPreviews) { v -> store.update { it.copy(showPreviews = v) } }
                    SettingSwitch("Companion reacts", "e.g. \"Looks like the real ${state.partner?.displayName ?: "partner"} messaged you\"", s.reactToNotifications) { v -> store.update { it.copy(reactToNotifications = v) } }
                    SettingSwitch("Let the AI know a message arrived", "Shares only \"WhatsApp message from Maya\" with the chat, never the text", s.shareNotificationHint) { v -> store.update { it.copy(shareNotificationHint = v) } }
                    OutlinedTextField(
                        names,
                        { names = it },
                        label = { Text("Your partner's name in your contacts") },
                        supportingText = { Text("Comma separated, e.g. Maya, Maya ❤️. Used to spot their messages.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        store.update { it.copy(partnerContactNames = names.split(',').map { n -> n.trim() }.filter { n -> n.isNotEmpty() }) }
                        runner.toast("Saved")
                    }) { Text("Save names") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("History (last ${s.keepNotificationDays} days, on this phone)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmClear = true }, enabled = history.isNotEmpty()) { Text("Clear") }
                }
            }
            if (history.isEmpty()) item { EmptyState("🔔", "No notifications yet", "Messages from the apps you allow will appear here and under the floating companion.") }
            items(history, key = { it.id }) { n ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        context.packageManager.getLaunchIntentForPackage(n.packageName)?.let { context.startActivity(it) }
                        runner.run { container.db.notifications().markRead(n.key) }
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    appIcon(context, n.packageName)?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (n.fromPartner) "💌 " else "") + (n.title ?: n.source), style = MaterialTheme.typography.bodyLarge)
                        n.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(n.postedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!n.read) Text("•", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear history?", "Notification history on this phone will be deleted.", confirm = "Clear", destructive = true,
            onConfirm = { runner.run { container.db.notifications().clear(); NotificationHub.clear() } }, onDismiss = { confirmClear = false })
    }
}

private fun appIcon(context: Context, pkg: String): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
}.getOrNull()

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val partner = state.partner?.displayName ?: "your partner"
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runner.run(done = "Exported") {
            val json = container.repo.api.exportData()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
        }
    }

    ScreenScaffold("Privacy & data", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard(title = "Where your data lives") {
                Text("• Photos and voice samples: on the server, encrypted, visible only to you two.", style = MaterialTheme.typography.bodyMedium)
                Text("• Conversations, memories and special days: on the server, shared with your partner where relevant.", style = MaterialTheme.typography.bodyMedium)
                Text("• Message notifications and outfit history: only on this phone.", style = MaterialTheme.typography.bodyMedium)
                Text("• Nothing is ever used to train AI unless you turn that on.", style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard(title = "Your data") {
                OutlinedButton(onClick = { exporter.launch("her-export.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export my data") }
                OutlinedButton(onClick = { confirm = Triple("Clear conversation?", "Your chat history with the companion will be deleted.") { container.chat.clear() } }, modifier = Modifier.fillMaxWidth()) { Text("Clear chat history") }
                OutlinedButton(onClick = {
                    confirm = Triple("Delete all photos?", "Every photo of you, and every photo you uploaded, will be permanently deleted.") {
                        runner.run(done = "Photos deleted") { container.repo.api.deleteAllPhotos(); container.repo.refresh() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Delete all photos") }
                OutlinedButton(onClick = {
                    confirm = Triple("Delete voice data?", "Your voice recordings and any voice made from them will be permanently deleted.") {
                        runner.run(done = "Voice data deleted") { container.repo.api.deleteVoice(); container.repo.refresh() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Delete my voice data") }
                OutlinedButton(onClick = {
                    confirm = Triple("Delete your memories?", "Memories you created will be deleted for both of you.") {
                        runner.run(done = "Memories deleted") { container.repo.api.deleteMyMemories(); container.repo.refresh() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Delete memories I created") }
                OutlinedButton(onClick = {
                    confirm = Triple("Clear data on this phone?", "Notification history and outfit history on this phone will be deleted.") {
                        runner.run(done = "Cleared") { container.db.notifications().clear(); container.db.outfits().clear(); NotificationHub.clear() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Clear data on this phone") }
            }
            SectionCard(title = "Relationship & account") {
                OutlinedButton(onClick = {
                    confirm = Triple("Unpair from $partner?", "Both companions, all photos, voice data, wardrobes, memories, special days and conversations will be permanently deleted for both of you.") {
                        runner.run(done = "Unpaired") { container.repo.unpair() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = {
                    confirm = Triple("Delete your account?", "Your account and everything connected to it will be permanently deleted. This can't be undone.") {
                        runner.run { container.repo.deleteAccount(); container.db.notifications().clear(); container.db.outfits().clear() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { runner.run { container.repo.signOutLocally() } }) { Text("Sign out on this phone") }
            }
        }
    }
    confirm?.let { (title, text, action) ->
        ConfirmDialog(title, text, confirm = "Delete", destructive = true, onConfirm = action, onDismiss = { confirm = null })
    }
}
