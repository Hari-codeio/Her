package app.her.ui.wardrobe

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.core.model.Category
import app.her.core.model.Outfit
import app.her.core.model.Pattern
import app.her.core.model.Season
import app.her.core.model.Slot
import app.her.core.model.StyleTag
import app.her.core.model.WardrobeItem
import app.her.data.local.OutfitRecordEntity
import app.her.data.remote.WardrobeItemInput
import app.her.data.toCore
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.avatar.CompanionStage
import app.her.ui.common.ChipGroup
import app.her.ui.common.ColorPicker
import app.her.ui.common.ConfirmDialog
import app.her.ui.common.EmptyState
import app.her.ui.common.Pill
import app.her.ui.common.SectionCard
import app.her.ui.common.Swatch
import kotlinx.coroutines.flow.flowOf

@Composable
fun WardrobeScreen() {
    val container = LocalContainer.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val companion = state.companion

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "${companion?.name ?: state.partner?.displayName ?: "Their"}'s wardrobe",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
        )
        if (companion == null || companion.status == "awaiting_consent" || companion.status == "revoked") {
            EmptyState("👗", "Wardrobe opens after consent", "The wardrobe belongs to your partner's character, so it opens once they've said yes.")
            return
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Today", "Closet", "History").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> TodayTab()
            1 -> ClosetTab(companion.id, state.wardrobe.map { it.toCore() }, viewerIsOwner = false, ownerName = companion.subject.displayName)
            else -> HistoryTab()
        }
    }
}

@Composable
private fun TodayTab() {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val presence by container.director.presence.collectAsStateWithLifecycle()
    val state by container.repo.state.collectAsStateWithLifecycle()
    val speaking by container.speech.speaking.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (presence.ready) {
            CompanionStage(presence, speaking, Modifier.fillMaxWidth().height(380.dp), onSwipe = { runner.run { container.director.shuffleOutfit() } })
        }
        SectionCard(
            title = if (presence.isBedtimeLook) "Bedtime look" else "Outfit of the day",
            subtitle = presence.outfit.note ?: if (presence.outfit.isSurprise) "A surprise combination!" else "Chosen from approved clothes, the weather and the day.",
        ) {
            presence.outfit.items.forEach { ItemRow(it) }
            if (!presence.outfit.isDefault) {
                Spacer(Modifier.height(8.dp))
                val record = presence.record
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { runner.run { container.director.rateOutfit(if (record?.liked == 1) null else true) } }) {
                        Icon(if (record?.liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, contentDescription = "Like")
                    }
                    IconButton(onClick = { runner.run { container.director.rateOutfit(if (record?.liked == -1) null else false) } }) {
                        Icon(if (record?.liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, contentDescription = "Dislike")
                    }
                    IconButton(onClick = { runner.run { container.director.toggleSaved() } }) {
                        Icon(if (record?.saved == true) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, contentDescription = "Save outfit")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { runner.run { container.director.shuffleOutfit() } }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Another")
                    }
                    TextButton(onClick = { picking = true }) { Text("Pick") }
                }
            } else {
                Text("Add some clothes in the Closet tab and they'll start dressing themselves.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (picking) {
        OutfitPicker(
            items = state.wardrobe.map { it.toCore() }.filter { it.approved },
            current = presence.dailyOutfit,
            onDismiss = { picking = false },
            onWear = { items -> picking = false; runner.run(done = "Outfit changed") { container.director.wearOutfit(items) } },
        )
    }
}

@Composable
private fun ItemRow(item: WardrobeItem) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        item.colors.forEach { Swatch(it, 16); Spacer(Modifier.width(2.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(item.name, modifier = Modifier.weight(1f))
        Text(item.category.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutfitPicker(items: List<WardrobeItem>, current: Outfit, onDismiss: () -> Unit, onWear: (List<WardrobeItem>) -> Unit) {
    var chosen by remember { mutableStateOf(current.items.filter { c -> items.any { it.id == c.id } }.associateBy { it.slot }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the outfit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Slot.entries.filter { it != Slot.NIGHT }.forEach { slot ->
                    val options = items.filter { it.slot == slot }
                    if (options.isNotEmpty()) {
                        Text(slot.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { item ->
                                FilterChip(
                                    selected = chosen[slot]?.id == item.id,
                                    onClick = {
                                        chosen = chosen.toMutableMap().apply {
                                            if (this[slot]?.id == item.id) remove(slot) else put(slot, item)
                                            // A dress replaces top + bottom and vice versa.
                                            if (slot == Slot.ONE_PIECE && containsKey(Slot.ONE_PIECE)) { remove(Slot.TOP); remove(Slot.BOTTOM) }
                                            if ((slot == Slot.TOP || slot == Slot.BOTTOM) && containsKey(slot)) remove(Slot.ONE_PIECE)
                                        }
                                    },
                                    label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Swatch(item.primaryColor, 14) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val valid = chosen.containsKey(Slot.ONE_PIECE) || (chosen.containsKey(Slot.TOP) || chosen.containsKey(Slot.BOTTOM))
            TextButton(onClick = { onWear(chosen.values.toList()) }, enabled = valid) { Text("Wear this") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HistoryTab() {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val companionId = state.companion?.id
    val records by remember(companionId) {
        if (companionId == null) flowOf(emptyList()) else container.db.outfits().observe(companionId, 90)
    }.collectAsStateWithLifecycle(emptyList())
    val byId = state.wardrobe.map { it.toCore() }.associateBy { it.id }
    var savedOnly by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !savedOnly, onClick = { savedOnly = false }, label = { Text("All days") })
                FilterChip(selected = savedOnly, onClick = { savedOnly = true }, label = { Text("Saved") })
            }
        }
        val shown = records.filter { !savedOnly || it.saved }
        if (shown.isEmpty()) item { EmptyState("📅", "No outfits yet", "Every day's outfit is remembered here, on your phone.") }
        items(shown, key = { it.companionId + it.date }) { record ->
            HistoryCard(
                record = record,
                onLike = { liked -> runner.run { container.director.rateRecord(record, liked) } },
                onWear = {
                    val items = record.ids.mapNotNull { byId[it] }
                    if (items.isNotEmpty()) runner.run(done = "Wearing it again today") { container.director.wearOutfit(items) }
                },
            )
        }
    }
}

@Composable
private fun HistoryCard(record: OutfitRecordEntity, onLike: (Boolean?) -> Unit, onWear: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.date, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (record.saved) Pill("Saved")
                if (record.source == "surprise") { Spacer(Modifier.width(4.dp)); Pill("Surprise") }
                if (record.source == "manual") { Spacer(Modifier.width(4.dp)); Pill("Your pick") }
            }
            Text(record.summary, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onLike(if (record.liked == 1) null else true) }) {
                    Icon(if (record.liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, contentDescription = "Like")
                }
                IconButton(onClick = { onLike(if (record.liked == -1) null else false) }) {
                    Icon(if (record.liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, contentDescription = "Dislike")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onWear) { Text("Wear again") }
            }
        }
    }
}

/** The closet grid. Used by the viewer (their partner's wardrobe) and by the owner (their own). */
@Composable
fun ClosetTab(companionId: String, items: List<WardrobeItem>, viewerIsOwner: Boolean, ownerName: String) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    var filter by remember { mutableStateOf<Category?>(null) }
    var editing by remember { mutableStateOf<WardrobeItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    if (!viewerIsOwner) {
                        Text(
                            "Clothes you add wait for $ownerName's approval before they're worn.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All (${items.size})") }) }
                        items(Category.entries.filter { c -> items.any { it.category == c } }) { c ->
                            FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(c.label) })
                        }
                    }
                }
            }
            val shown = items.filter { filter == null || it.category == filter }
            if (shown.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyState("🧺", "Nothing here yet", "Add tops, dresses, traditional wear, shoes and accessories.")
                }
            }
            items(shown, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.clickable { editing = item },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row { item.colors.forEach { Swatch(it, 26); Spacer(Modifier.width(4.dp)) } }
                        Spacer(Modifier.height(8.dp))
                        Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.category.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!item.approved) {
                            Spacer(Modifier.height(6.dp))
                            Pill(if (viewerIsOwner) "Needs your approval" else "Awaiting approval", color = MaterialTheme.colorScheme.tertiary, textColor = MaterialTheme.colorScheme.onTertiary)
                            if (viewerIsOwner) {
                                Row {
                                    TextButton(onClick = { runner.run(done = "Approved") { container.repo.approveWardrobeItem(companionId, item.id, true) } }) { Text("Approve") }
                                    TextButton(onClick = { runner.run(done = "Removed") { container.repo.approveWardrobeItem(companionId, item.id, false) } }) { Text("Decline") }
                                }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add clothes") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (adding) {
        WardrobeItemEditor(null, onDismiss = { adding = false }, onSave = { input ->
            adding = false
            runner.run(done = if (viewerIsOwner) "Added" else "Added — waiting for $ownerName's approval") { container.repo.addWardrobeItem(companionId, input) }
        })
    }
    editing?.let { item ->
        WardrobeItemEditor(
            item,
            onDismiss = { editing = null },
            onSave = { input -> editing = null; runner.run(done = "Saved") { container.repo.updateWardrobeItem(companionId, item.id, input) } },
            onDelete = { editing = null; runner.run(done = "Removed") { container.repo.deleteWardrobeItem(companionId, item.id) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WardrobeItemEditor(existing: WardrobeItem?, onDismiss: () -> Unit, onSave: (WardrobeItemInput) -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: Category.TOP) }
    var colors by remember { mutableStateOf(existing?.colors ?: emptyList()) }
    var pattern by remember { mutableStateOf(existing?.pattern ?: Pattern.SOLID) }
    var styles by remember { mutableStateOf(existing?.styles ?: setOf(StyleTag.CASUAL)) }
    var seasons by remember { mutableStateOf(existing?.seasons ?: emptySet()) }
    var warmth by remember { mutableStateOf((existing?.warmth ?: 2).toFloat()) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add to wardrobe" else "Edit item") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name, e.g. Lavender kurti") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Type", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Category.entries.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) }) }
                }
                Text("Colours (up to 3, main first)", style = MaterialTheme.typography.labelLarge)
                ColorPicker(colors) { colors = it }
                Text("Pattern", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pattern.entries.forEach { p -> FilterChip(selected = pattern == p, onClick = { pattern = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                Text("Style", style = MaterialTheme.typography.labelLarge)
                ChipGroup(StyleTag.entries, styles, { it.label }, { s -> styles = if (s in styles) styles - s else styles + s })
                Text("Seasons (none = all year)", style = MaterialTheme.typography.labelLarge)
                ChipGroup(Season.entries, seasons, { it.label }, { s -> seasons = if (s in seasons) seasons - s else seasons + s })
                Text("Warmth: ${listOf("", "very light", "light", "medium", "warm", "very warm")[warmth.toInt()]}", style = MaterialTheme.typography.labelLarge)
                Slider(value = warmth, onValueChange = { warmth = it }, valueRange = 1f..5f, steps = 3)
                if (onDelete != null) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Remove from wardrobe", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        WardrobeItemInput(
                            name = name.trim(),
                            category = category.apiName,
                            colors = colors,
                            pattern = pattern.apiName,
                            styles = styles.map { it.apiName },
                            seasons = seasons.map { it.apiName },
                            warmth = warmth.toInt(),
                        ),
                    )
                },
                enabled = name.isNotBlank() && colors.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Remove item?", "\"$name\" will be removed from the wardrobe.", confirm = "Remove", destructive = true, onConfirm = onDelete, onDismiss = { confirmDelete = false })
    }
}
