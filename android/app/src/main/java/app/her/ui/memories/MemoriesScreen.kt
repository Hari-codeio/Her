package app.her.ui.memories

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.core.model.MemoryKind
import app.her.core.model.OccasionKind
import app.her.core.occasion.OccasionEngine
import app.her.data.remote.MemoryDto
import app.her.data.remote.MemoryInput
import app.her.data.remote.OccasionDto
import app.her.data.remote.OccasionInput
import app.her.data.toCore
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.common.ConfirmDialog
import app.her.ui.common.EmptyState
import app.her.ui.common.Pill
import app.her.ui.common.PrivateImage
import app.her.ui.common.SectionCard
import app.her.ui.common.SettingSwitch
import app.her.ui.common.readScaledJpeg
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val prettyDate = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun kindEmoji(kind: String) = when (kind) {
    "date" -> "🥂"; "place" -> "📍"; "message" -> "💌"; "preference" -> "⭐"; else -> "✨"
}

@Composable
fun MemoriesScreen() {
    val container = LocalContainer.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MemoryDto?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val sorted = state.memories.sortedWith(compareByDescending<MemoryDto> { it.happenedOn ?: "" }.thenByDescending { it.createdAt ?: "" })
    val onThisDay = sorted.filter { m ->
        m.happenedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { it.monthValue == today.monthValue && it.dayOfMonth == today.dayOfMonth && it.year < today.year } == true
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Our memories", style = MaterialTheme.typography.headlineSmall)
                Text("Shared between you two. ${state.companion?.name ?: "Your companion"} may bring up the ones you allow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onThisDay.isNotEmpty()) {
                item {
                    SectionCard(title = "On this day") {
                        onThisDay.forEach { Text("${kindEmoji(it.kind)} ${it.title} · ${it.happenedOn?.take(4)}", modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                }
            }
            if (sorted.isEmpty()) {
                item { EmptyState("📸", "Start your timeline", "Save first dates, favourite places, songs, inside jokes and the messages you never want to lose.") }
            }
            var lastYear: String? = null
            sorted.forEach { memory ->
                val year = memory.happenedOn?.take(4) ?: "Undated"
                if (year != lastYear) {
                    lastYear = year
                    item(key = "year-$year") { Text(year, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                }
                item(key = memory.id) { MemoryCard(memory, onClick = { editing = memory }) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add memory") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding) MemoryEditor(null, onDismiss = { adding = false })
    editing?.let { MemoryEditor(it, onDismiss = { editing = null }) }
}

@Composable
private fun MemoryCard(memory: MemoryDto, onClick: () -> Unit) {
    val container = LocalContainer.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (memory.hasPhoto) {
            PrivateImage(
                url = container.api.memoryPhotoUrl(memory.id),
                authHeader = container.api.authHeader,
                cacheKey = "memory-${memory.id}-${memory.updatedAt}",
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
            )
        }
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${kindEmoji(memory.kind)} ${memory.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            val meta = listOfNotNull(
                memory.happenedOn?.let { runCatching { LocalDate.parse(it).format(prettyDate) }.getOrNull() },
                memory.place,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (memory.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(memory.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            }
            if (memory.isFictional || !memory.recallAllowed) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (memory.isFictional) Pill("Made-up story")
                    if (!memory.recallAllowed) Pill("Companion won't mention")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditor(existing: MemoryDto?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val context = LocalContext.current
    var kind by remember { mutableStateOf(existing?.kind ?: "moment") }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var place by remember { mutableStateOf(existing?.place ?: "") }
    var date by remember { mutableStateOf(existing?.happenedOn) }
    var recall by remember { mutableStateOf(existing?.recallAllowed ?: true) }
    var fictional by remember { mutableStateOf(existing?.isFictional ?: false) }
    var photo by remember { mutableStateOf<android.net.Uri?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New memory" else "Edit memory") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryKind.entries.forEach { k ->
                        FilterChip(selected = kind == k.apiName, onClick = { kind = k.apiName }, label = { Text("${kindEmoji(k.apiName)} ${k.label}") })
                    }
                }
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it.take(2000) }, label = { Text("What happened? (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(place, { place = it.take(120) }, label = { Text("Place (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.Event, contentDescription = null); Spacer(Modifier.width(6.dp))
                    Text(date?.let { runCatching { LocalDate.parse(it).format(prettyDate) }.getOrNull() } ?: "Add a date")
                }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (photo != null) "Photo selected ✓" else if (existing?.hasPhoto == true) "Replace photo" else "Add a photo")
                }
                SettingSwitch("Companion may bring this up", checked = recall, onChange = { recall = it })
                SettingSwitch("This is a made-up story", subtitle = "Kept separate from real memories", checked = fictional, onChange = { fictional = it })
                if (existing != null) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete memory", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val input = MemoryInput(kind, title.trim(), body.trim(), date, place.trim().ifEmpty { null }, fictional, recall)
                val picked = photo
                onDismiss()
                runner.run(done = "Saved") {
                    val saved = container.repo.saveMemory(existing?.id, input)
                    if (picked != null) container.repo.uploadMemoryPhoto(saved.id, readScaledJpeg(context, picked), "image/jpeg")
                }
            }, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() })
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    date = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { date = null; picking = false }) { Text("No date") } },
        ) { DatePicker(pickerState) }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog("Delete memory?", "\"${existing.title}\" will be deleted for both of you.", confirm = "Delete", destructive = true,
            onConfirm = { onDismiss(); runner.run(done = "Deleted") { container.repo.deleteMemory(existing.id) } },
            onDismiss = { confirmDelete = false })
    }
}

// ------------------------------------------------------------------------------------------- occasions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OccasionsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<OccasionDto?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val withDays = state.occasions.mapNotNull { dto ->
        dto.toCore(state.me?.id, state.partner?.id)?.let { core -> Triple(dto, core, OccasionEngine.daysUntil(core, today)) }
    }.sortedBy { it.third ?: Long.MAX_VALUE }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Special days") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Box(Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "Birthdays, anniversaries and your own special days. ${state.companion?.name ?: "Your companion"} dresses up, celebrates and reminds you — only for the days you allow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (withDays.isEmpty()) item { EmptyState("🎂", "No dates yet", "Add your anniversary and both birthdays to begin.") }
                items(withDays, key = { it.first.id }) { (dto, core, days) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { editing = dto },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (core.kind) { OccasionKind.BIRTHDAY -> "🎂"; OccasionKind.ANNIVERSARY -> "💞"; OccasionKind.CUSTOM -> "✨" }, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(dto.title, style = MaterialTheme.typography.titleMedium)
                                Text(runCatching { LocalDate.parse(dto.date).format(DateTimeFormatter.ofPattern("d MMMM")) }.getOrDefault(dto.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Pill(when (days) { null -> "Past"; 0L -> "Today!"; 1L -> "Tomorrow"; else -> "in $days days" })
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add date") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
    if (adding) OccasionEditor(null, onDismiss = { adding = false })
    editing?.let { OccasionEditor(it, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OccasionEditor(existing: OccasionDto?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf(existing?.kind ?: "anniversary") }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var date by remember { mutableStateOf(existing?.date) }
    var yearly by remember { mutableStateOf(existing?.recursYearly ?: true) }
    var person by remember { mutableStateOf(existing?.personUserId) }
    var initiate by remember { mutableStateOf(existing?.companionMayInitiate ?: true) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New special day" else "Edit special day") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("anniversary" to "💞 Anniversary", "birthday" to "🎂 Birthday", "custom" to "✨ Other").forEach { (k, label) ->
                        FilterChip(selected = kind == k, onClick = {
                            kind = k
                            if (title.isBlank()) title = when (k) { "anniversary" -> "Our anniversary"; "birthday" -> "${state.partner?.displayName ?: ""}'s birthday"; else -> "" }
                        }, label = { Text(label) })
                    }
                }
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.Event, contentDescription = null); Spacer(Modifier.width(6.dp))
                    Text(date?.let { runCatching { LocalDate.parse(it).format(prettyDate) }.getOrNull() } ?: "Pick the date")
                }
                if (kind == "birthday") {
                    Text("Whose birthday?", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = person == state.me?.id, onClick = { person = state.me?.id }, label = { Text("Mine") })
                        FilterChip(selected = person == state.partner?.id, onClick = { person = state.partner?.id }, label = { Text(state.partner?.displayName ?: "Partner") })
                    }
                }
                SettingSwitch("Every year", checked = yearly, onChange = { yearly = it })
                SettingSwitch("Companion may celebrate & remind me", checked = initiate, onChange = { initiate = it })
                if (existing != null) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val input = OccasionInput(kind, title.trim(), date!!, yearly, if (kind == "birthday") person else null, initiate)
                onDismiss()
                runner.run(done = "Saved") { container.repo.saveOccasion(existing?.id, input) }
            }, enabled = title.isNotBlank() && date != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() })
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    date = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: date
                    picking = false
                }) { Text("OK") }
            },
        ) { DatePicker(pickerState) }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog("Delete this day?", "\"${existing.title}\" will be removed for both of you.", confirm = "Delete", destructive = true,
            onConfirm = { onDismiss(); runner.run(done = "Deleted") { container.repo.deleteOccasion(existing.id) } },
            onDismiss = { confirmDelete = false })
    }
}
