package app.her.ui.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.core.games.Game
import app.her.core.games.GameDecks
import app.her.ui.LocalContainer
import app.her.ui.common.SectionCard
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(onBack: () -> Unit, onPlay: (String) -> Unit) {
    val container = LocalContainer.current
    val presence by container.director.presence.collectAsStateWithLifecycle()
    var card by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Play together") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard(title = "Quick card", subtitle = "Works offline — great to send to your partner too.") {
                    Text(card ?: "Draw a card for a question to ask each other.", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        FilledTonalButton(onClick = { card = GameDecks.draw(GameDecks.questions, Random(System.nanoTime())) }) { Text("Question") }
                        FilledTonalButton(onClick = { card = GameDecks.draw(GameDecks.truths, Random(System.nanoTime())) }) { Text("Truth") }
                        FilledTonalButton(onClick = { card = GameDecks.draw(GameDecks.dares, Random(System.nanoTime())) }) { Text("Dare") }
                    }
                }
            }
            items(Game.entries) { game ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = presence.ready) { onPlay(game.apiName) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(game.emoji, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(game.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(game.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!presence.ready) {
                item { Text("Games with your companion open once they're active.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
