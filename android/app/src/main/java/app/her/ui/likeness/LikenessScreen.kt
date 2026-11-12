package app.her.ui.likeness

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.core.model.Activity
import app.her.core.model.Expression
import app.her.core.wardrobe.OutfitEngine
import app.her.core.model.Outfit
import app.her.data.HerState
import app.her.data.remote.AppearanceDto
import app.her.data.remote.CompanionDto
import app.her.data.remote.ConsentEventDto
import app.her.data.remote.PersonaDto
import app.her.data.toCore
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.avatar.CompanionAvatar
import app.her.ui.avatar.SceneBackground
import app.her.core.model.DayPart
import app.her.core.model.Environment
import app.her.ui.common.ConfirmDialog
import app.her.ui.common.EmptyState
import app.her.ui.common.SectionCard
import app.her.ui.common.SettingSwitch
import app.her.ui.studio.LookTab
import app.her.ui.studio.PersonaTab
import app.her.ui.studio.PhotosTab
import app.her.ui.wardrobe.ClosetTab
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikenessScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val ofMe = state.ofMe
    var tab by remember { mutableIntStateOf(0) }
    val partner = state.partner?.displayName ?: "your partner"

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("My likeness & consent") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        if (ofMe == null) {
            EmptyState("🔗", "Not paired", "Pair with $partner first.")
            return
        }
        val tabs = listOf("Consent", "Approvals${if (state.pendingForMe > 0) " (${state.pendingForMe})" else ""}", "My look", "Personality", "Photos", "Wardrobe", "Voice")
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        val me = state.me?.displayName ?: "you"
        val likenessOn = ofMe.consents.likeness
        when (tab) {
            0 -> ConsentTab(ofMe, state, partner)
            1 -> ApprovalsTab(ofMe, state, partner)
            2 -> if (likenessOn) LookTab(ofMe, isSubject = true, subjectName = me) else NeedsConsent(partner)
            3 -> PersonaTab(ofMe, isSubject = true, subjectName = me)
            4 -> if (likenessOn) PhotosTab(ofMe, isSubject = true, subjectName = me) else NeedsConsent(partner)
            5 -> if (likenessOn) ClosetTab(ofMe.id, state.ofMeWardrobe.map { it.toCore() }, viewerIsOwner = true, ownerName = me) else NeedsConsent(partner)
            else -> VoiceTab(ofMe, state)
        }
    }
}

@Composable
private fun NeedsConsent(partner: String) {
    EmptyState("🔒", "Likeness is off", "Turn on likeness consent in the Consent tab if you'd like $partner to have a companion of you.")
}

private data class ScopeInfo(val key: String, val title: String)

private val SCOPES = listOf(
    ScopeInfo("likeness", "My likeness"),
    ScopeInfo("personality", "My personality"),
    ScopeInfo("voice", "My voice"),
    ScopeInfo("ai_training", "AI model training"),
)

@Composable
private fun ConsentTab(ofMe: CompanionDto, state: HerState, partner: String) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val statements = state.config?.consentStatements.orEmpty()
    var asking by remember { mutableStateOf<ScopeInfo?>(null) }
    var revoking by remember { mutableStateOf<ScopeInfo?>(null) }
    var history by remember { mutableStateOf<List<ConsentEventDto>?>(null) }
    val c = ofMe.consents

    fun current(key: String) = when (key) { "likeness" -> c.likeness; "voice" -> c.voice; "personality" -> c.personality; else -> c.aiTraining }
    fun apply(key: String, value: Boolean) = runner.run(done = if (value) "Consent given" else "Consent withdrawn") {
        container.repo.setConsent(
            likeness = if (key == "likeness") value else c.likeness,
            voice = if (key == "voice") value else c.voice,
            personality = if (key == "personality") value else c.personality,
            aiTraining = if (key == "ai_training") value else c.aiTraining,
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            title = "You're in control",
            subtitle = "$partner can only have a companion of you with your consent. Every version needs your approval, and you can withdraw at any time.",
        ) {
            Text("Status: ${statusLabel(ofMe.status)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        SectionCard {
            SCOPES.forEachIndexed { i, scope ->
                if (i > 0) HorizontalDivider()
                val enabled = scope.key == "likeness" || c.likeness || scope.key == "ai_training"
                SettingSwitch(
                    title = scope.title,
                    subtitle = statements[scope.key] ?: "",
                    checked = current(scope.key),
                    enabled = enabled,
                    onChange = { on -> if (on) asking = scope else revoking = scope },
                )
            }
        }
        OutlinedButton(onClick = { runner.run { history = container.repo.api.consentHistory(ofMe.id) } }) { Text("View consent history") }
        history?.let { events ->
            SectionCard(title = "Consent history") {
                if (events.isEmpty()) Text("No changes yet.")
                events.forEach { e ->
                    Text("${e.createdAt.take(16).replace('T', ' ')} · ${e.scope} · ${if (e.granted) "granted" else "withdrawn"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    asking?.let { scope ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(scope.title) },
            text = { Text(statements[scope.key] ?: "") },
            confirmButton = { TextButton(onClick = { apply(scope.key, true); asking = null }) { Text("I agree") } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Not now") } },
        )
    }
    revoking?.let { scope ->
        val warning = when (scope.key) {
            "likeness" -> "Your photos, character and wardrobe will be permanently deleted and $partner's companion of you will stop immediately."
            "voice" -> "Your voice recordings and any voice made from them will be permanently deleted."
            "personality" -> "Your personality details will no longer be used in conversations."
            else -> "Your data will not be used for AI training."
        }
        ConfirmDialog("Withdraw consent?", warning, confirm = "Withdraw", destructive = scope.key == "likeness" || scope.key == "voice",
            onConfirm = { apply(scope.key, false) }, onDismiss = { revoking = null })
    }
}

private fun statusLabel(status: String) = when (status) {
    "awaiting_consent" -> "No consent given yet"
    "draft" -> "Consent given — waiting for an approved look"
    "active" -> "Active"
    "revoked" -> "Withdrawn"
    else -> status
}

@Composable
private fun ApprovalsTab(ofMe: CompanionDto, state: HerState, partner: String) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    var rejectReason by remember { mutableStateOf("") }
    val pendingItems = state.ofMeWardrobe.filter { !it.approved }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ofMe.pendingAppearance == null && ofMe.pendingPersona == null && pendingItems.isEmpty()) {
            EmptyState("✅", "Nothing to approve", "When $partner proposes a look, personality or clothes for your character, it shows up here.")
        }
        ofMe.pendingAppearance?.let { version ->
            SectionCard(title = "$partner proposed a new look for you", subtitle = "Left: current · Right: proposed") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Preview(ofMe.appearance, Modifier.weight(1f))
                    Preview(version.appearance, Modifier.weight(1f))
                }
                OutlinedTextField(rejectReason, { rejectReason = it.take(200) }, label = { Text("Note if you reject (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runner.run(done = "Approved") { container.repo.approveAppearance(ofMe.id, version.id) } }) { Text("Approve") }
                    OutlinedButton(onClick = { runner.run(done = "Rejected") { container.repo.rejectAppearance(ofMe.id, version.id, rejectReason.ifBlank { null }) } }) { Text("Reject") }
                }
            }
        }
        ofMe.pendingPersona?.let { persona ->
            SectionCard(title = "$partner described your personality", subtitle = "This shapes how your companion talks. Approve only what feels like you.") {
                PersonaSummary(persona)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runner.run(done = "Approved") { container.repo.approvePersona(ofMe.id) } }) { Text("Approve") }
                    OutlinedButton(onClick = { runner.run(done = "Rejected") { container.repo.rejectPersona(ofMe.id) } }) { Text("Reject") }
                }
            }
        }
        if (pendingItems.isNotEmpty()) {
            SectionCard(title = "Clothes $partner added", subtitle = "Approved clothes can be worn by your character.") {
                pendingItems.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        app.her.ui.common.Swatch(item.colors.firstOrNull() ?: "#CCCCCC")
                        Spacer(Modifier.width(8.dp))
                        Text(item.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { runner.run { container.repo.approveWardrobeItem(ofMe.id, item.id, true) } }) { Text("Approve") }
                        TextButton(onClick = { runner.run { container.repo.approveWardrobeItem(ofMe.id, item.id, false) } }) { Text("Decline") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(appearance: AppearanceDto?, modifier: Modifier) {
    Box(modifier.height(240.dp).clip(RoundedCornerShape(18.dp))) {
        SceneBackground(Environment.LIVING_ROOM, DayPart.AFTERNOON, Modifier.fillMaxSize())
        if (appearance != null) {
            CompanionAvatar(appearance, Outfit(OutfitEngine.DEFAULT_ITEMS, isDefault = true), Activity.SMILING, Expression.SMILE, Modifier.fillMaxSize().padding(top = 8.dp), animate = false)
        } else {
            Text("None yet", modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PersonaSummary(p: PersonaDto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        p.nicknameForViewer?.takeIf { it.isNotBlank() }?.let { Text("Calls them: $it") }
        p.speakingStyle?.takeIf { it.isNotBlank() }?.let { Text("Style: $it") }
        p.about?.takeIf { it.isNotBlank() }?.let { Text("About: $it") }
        if (p.interests.isNotEmpty()) Text("Interests: ${p.interests.joinToString()}")
        if (p.favoriteThings.isNotEmpty()) Text("Favourites: ${p.favoriteThings.joinToString()}")
        if (p.petPhrases.isNotEmpty()) Text("Phrases: ${p.petPhrases.joinToString()}")
        if (p.boundaries.isNotEmpty()) Text("Avoid: ${p.boundaries.joinToString()}")
    }
}

// ----------------------------------------------------------------------------------------------- voice

private val READ_ALOUD = listOf(
    "Good morning! I hope you slept well. Did you have breakfast yet?",
    "I was thinking about our last trip together — the sunset was unreal.",
    "Tell me everything about your day. Start with the best part.",
    "Okay, okay, you win this one. But I'm getting my revenge tomorrow!",
    "Goodnight. Sleep well, and don't stay up scrolling too long.",
)

private class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    val file = File(context.cacheDir, "voice-sample.m4a")

    fun start() {
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(128_000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
    }

    fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        return runCatching { r.stop(); r.release(); file.readBytes() }.getOrNull().also { file.delete() }
    }

    fun cancel() {
        recorder?.runCatching { stop(); release() }
        recorder = null
        file.delete()
    }
}

@Composable
private fun VoiceTab(ofMe: CompanionDto, state: HerState) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var line by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { recorder.start(); recording = true } else runner.toast("Microphone permission is needed to record.")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!ofMe.consents.voice) {
            EmptyState("🎙️", "Voice is off", "Turn on voice consent in the Consent tab to record samples. Until then, the companion uses the phone's own voice.")
            return
        }
        SectionCard(
            title = "Your companion's voice",
            subtitle = if (state.config?.voiceProvider == true) "Record a few short clips. Your partner will hear a voice made from them." else "Voice cloning isn't connected on this server yet. Samples are stored encrypted and used once it is; until then the phone's voice is used.",
        ) {
            Text("Samples recorded: ${ofMe.voice.samples} · Status: ${ofMe.voice.status}", style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(title = "Read this aloud") {
            Text("“${READ_ALOUD[line % READ_ALOUD.size]}”", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!recording) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            runCatching { recorder.start(); recording = true }.onFailure { runner.toast("Couldn't start recording") }
                        } else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Filled.FiberManualRecord, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Record") }
                } else {
                    Button(onClick = {
                        recording = false
                        val bytes = recorder.stop()
                        if (bytes == null || bytes.size < 2_000) runner.toast("That was too short — try again.")
                        else runner.run(done = "Sample saved", onDone = { line++ }) {
                            container.repo.api.uploadVoiceSample(ofMe.id, bytes, "audio/mp4")
                            container.repo.refresh()
                        }
                    }) { Icon(Icons.Filled.Stop, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Stop & save") }
                }
                TextButton(onClick = { line++ }, enabled = !recording) { Text("Another line") }
            }
        }
        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete all my voice data", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete voice data?", "All recordings and any voice made from them will be permanently deleted.", confirm = "Delete", destructive = true,
            onConfirm = { runner.run(done = "Voice data deleted") { container.repo.api.deleteVoice(); container.repo.refresh() } },
            onDismiss = { confirmDelete = false })
    }
}
