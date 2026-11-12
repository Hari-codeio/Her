package app.her.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.core.model.Activity
import app.her.core.model.Expression
import app.her.data.remote.AppearanceDto
import app.her.data.remote.CompanionDto
import app.her.data.remote.PersonaDto
import app.her.data.remote.PhotoDto
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.avatar.CompanionAvatar
import app.her.ui.avatar.Framing
import app.her.ui.avatar.SceneBackground
import app.her.ui.avatar.parseColor
import app.her.ui.common.ConfirmDialog
import app.her.ui.common.EmptyState
import app.her.ui.common.PrivateImage
import app.her.ui.common.SectionCard
import app.her.ui.common.readScaledJpeg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val companion = state.companion
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Character studio") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        if (companion == null || companion.status == "awaiting_consent" || companion.status == "revoked") {
            EmptyState(
                "🔒",
                "Waiting for consent",
                "${state.partner?.displayName ?: "Your partner"} has to allow a character of them before anything can be designed or uploaded.",
            )
            return
        }
        val subject = companion.subject.displayName
        TabRow(selectedTabIndex = tab) {
            listOf("Look", "Personality", "Photos").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        when (tab) {
            0 -> LookTab(companion, isSubject = false, subjectName = subject)
            1 -> PersonaTab(companion, isSubject = false, subjectName = subject)
            else -> PhotosTab(companion, isSubject = false, subjectName = subject)
        }
    }
}

@Composable
fun LookTab(companion: CompanionDto, isSubject: Boolean, subjectName: String) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val presence by container.director.presence.collectAsStateWithLifecycle()
    val config = container.repo.state.collectAsStateWithLifecycle().value.config
    val initial = companion.pendingAppearance?.appearance ?: companion.appearance ?: AppearanceDto()
    var draft by remember(companion.id, initial) { mutableStateOf(initial) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        companion.pendingAppearance?.let {
            SectionCard(
                title = if (isSubject) "Your partner proposed a new look" else "Waiting for $subjectName's approval",
                subtitle = if (isSubject) "Review it under Approvals." else "They'll see exactly this preview. Saving again replaces the proposal.",
            ) {}
        }
        Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(24.dp))) {
            SceneBackground(presence.environment, presence.dayPart, Modifier.fillMaxSize())
            CompanionAvatar(
                appearance = draft,
                outfit = presence.outfit,
                activity = Activity.SMILING,
                expression = Expression.SMILE,
                modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            )
        }
        AppearanceEditor(draft) { draft = it }
        Button(
            onClick = {
                runner.run(done = if (isSubject) "Saved — this is how you'll look" else "Sent to $subjectName for approval") {
                    container.repo.saveAppearance(companion.id, draft)
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (isSubject) "Save my look" else "Send to $subjectName for approval") }
        SectionCard(
            title = "Realistic rendering",
            subtitle = if (config?.likenessProvider == true) "Generate a photoreal version from the approved photos and look." else "Not connected on this server yet. The stylised character is used until a likeness provider is configured.",
        ) {
            OutlinedButton(onClick = { runner.run(done = "Rendering started") { container.repo.api.generateLikeness(companion.id) } }, enabled = config?.likenessProvider == true) {
                Text("Generate realistic character")
            }
        }
    }
}

private val SKIN_TONES = listOf("#F7DCC9", "#F1C7A8", "#E3B08C", "#D6A07A", "#C68E6A", "#B57A55", "#9E6644", "#875436", "#6E432B", "#583421", "#432719", "#2F1C12")
private val HAIR_COLORS = listOf("#0F0B09", "#2B1B12", "#4A2E1F", "#6B4630", "#8D5B3A", "#B07A4F", "#D9B27C", "#E9D3A3", "#9C9C9C", "#E8E3DC", "#8E2F2F", "#6B3FA0")
private val EYE_COLORS = listOf("#3B2416", "#5A3A22", "#7A5230", "#4E6B3A", "#6A8F6A", "#3E6E8E", "#7FA7C9", "#5E5E6E")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceEditor(value: AppearanceDto, onChange: (AppearanceDto) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Presentation")
        Chips(listOf("feminine" to "Feminine", "masculine" to "Masculine", "neutral" to "Neutral"), value.presentation) { onChange(value.copy(presentation = it)) }
        Label("Skin tone")
        ColorRow(SKIN_TONES, value.skinTone) { onChange(value.copy(skinTone = it)) }
        Label("Face shape")
        Chips(listOf("oval", "round", "heart", "square", "long").map { it to it.replaceFirstChar { c -> c.uppercase() } }, value.faceShape) { onChange(value.copy(faceShape = it)) }
        Label("Hairstyle")
        Chips(
            listOf(
                "long_straight" to "Long straight", "long_wavy" to "Long wavy", "curly" to "Curly", "bob" to "Bob", "pixie" to "Pixie",
                "bun" to "Bun", "ponytail" to "Ponytail", "braids" to "Braids", "short" to "Short", "buzz" to "Buzz",
            ),
            value.hairStyle,
        ) { onChange(value.copy(hairStyle = it)) }
        Label("Hair colour")
        ColorRow(HAIR_COLORS, value.hairColor) { onChange(value.copy(hairColor = it)) }
        Label("Eye colour")
        ColorRow(EYE_COLORS, value.eyeColor) { onChange(value.copy(eyeColor = it)) }
        Label("Height")
        Slider(value.height, { onChange(value.copy(height = it)) })
        Label("Build")
        Slider(value.build, { onChange(value.copy(build = it)) })
        Label("Facial hair")
        Chips(listOf("none" to "None", "stubble" to "Stubble", "mustache" to "Moustache", "beard" to "Beard"), value.facialHair) { onChange(value.copy(facialHair = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Glasses", modifier = Modifier.weight(1f))
            Switch(value.glasses, { onChange(value.copy(glasses = it)) })
        }
        OutlinedTextField(
            value.styleNotes,
            { onChange(value.copy(styleNotes = it.take(500))) },
            label = { Text("Style notes (e.g. loves pastel kurtis)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) -> FilterChip(selected = key == selected, onClick = { onSelect(key) }, label = { Text(label) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(colors: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { hex ->
            val isSelected = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(parseColor(hex, Color.Gray))
                    .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.15f), CircleShape)
                    .clickable { onSelect(hex) },
            )
        }
    }
}

@Composable
fun PersonaTab(companion: CompanionDto, isSubject: Boolean, subjectName: String) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val initial = companion.pendingPersona ?: companion.persona ?: PersonaDto()
    var draft by remember(companion.id, initial) { mutableStateOf(initial) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (isSubject) "How should your companion talk with your partner? Only what you approve here is ever used."
            else "Describe how $subjectName talks. Nothing is used until $subjectName approves it, and the companion never invents facts about them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isSubject && companion.pendingPersona != null) {
            SectionCard(title = "Waiting for $subjectName's approval") {}
        }
        if (!companion.consents.personality) {
            SectionCard(title = "Personality is off", subtitle = "$subjectName hasn't allowed personality details, so the companion uses a neutral, warm style.") {}
        }
        Field("What they call you", draft.nicknameForViewer.orEmpty()) { draft = draft.copy(nicknameForViewer = it.take(60)) }
        Field("Speaking style", draft.speakingStyle.orEmpty(), lines = 2) { draft = draft.copy(speakingStyle = it.take(500)) }
        Field("About", draft.about.orEmpty(), lines = 3) { draft = draft.copy(about = it.take(500)) }
        ListField("Languages", draft.languages) { draft = draft.copy(languages = it) }
        ListField("Interests", draft.interests) { draft = draft.copy(interests = it) }
        ListField("Favourite things", draft.favoriteThings) { draft = draft.copy(favoriteThings = it) }
        ListField("Pet phrases", draft.petPhrases) { draft = draft.copy(petPhrases = it) }
        ListField("Topics to avoid", draft.boundaries) { draft = draft.copy(boundaries = it) }
        Button(
            onClick = { runner.run(done = if (isSubject) "Saved" else "Sent to $subjectName for approval") { container.repo.savePersona(companion.id, draft) } },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (isSubject) "Save" else "Send to $subjectName for approval") }
    }
}

@Composable
private fun Field(label: String, value: String, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, minLines = lines, singleLine = lines == 1, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ListField(label: String, value: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember(value) { mutableStateOf(value.joinToString(", ")) }
    OutlinedTextField(
        text,
        {
            text = it
            onChange(it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.take(20).map { s -> s.take(500) })
        },
        label = { Text("$label (comma separated)") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotosTab(companion: CompanionDto, isSubject: Boolean, subjectName: String) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    val me = container.repo.state.collectAsStateWithLifecycle().value.me
    var photos by remember { mutableStateOf<List<PhotoDto>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<PhotoDto?>(null) }
    LaunchedEffect(companion.id, reload) {
        photos = runCatching { container.repo.api.photos(companion.id) }.getOrDefault(emptyList())
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) runner.run(done = "Uploaded ${uris.size} photo(s)", onDone = { reload++ }) {
            uris.forEach { uri -> container.repo.api.uploadPhoto(companion.id, readScaledJpeg(context, uri), "image/jpeg") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (isSubject) "Photos of you used to shape your character. Only you two can see them; they're encrypted and you can delete any of them."
            else "Photos of $subjectName, uploaded with their consent. $subjectName can see and delete every photo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { photo ->
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(14.dp))) {
                    PrivateImage(container.repo.api.photoUrl(companion.id, photo.id), container.repo.api.authHeader, "photo-${photo.id}", Modifier.fillMaxSize())
                    if (isSubject || photo.uploadedBy == me?.id) {
                        IconButton(onClick = { deleting = photo }, modifier = Modifier.align(Alignment.TopEnd).size(30.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                            Icon(Icons.Filled.Close, contentDescription = "Delete photo", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.AddAPhoto, contentDescription = "Add photos") }
        }
        Text("Photos are resized and stripped of location data on your phone before upload.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    deleting?.let { photo ->
        ConfirmDialog("Delete photo?", "It will be permanently deleted.", confirm = "Delete", destructive = true,
            onConfirm = { runner.run(done = "Deleted", onDone = { reload++ }) { container.repo.api.deletePhoto(companion.id, photo.id) } },
            onDismiss = { deleting = null })
    }
}
