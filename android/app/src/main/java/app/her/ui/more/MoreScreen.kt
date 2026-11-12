package app.her.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.ui.LocalContainer
import app.her.ui.Routes
import app.her.ui.common.SectionCard

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val container = LocalContainer.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val partner = state.partner?.displayName ?: "your partner"
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("More", style = MaterialTheme.typography.headlineSmall)
        SectionCard(title = "Your companion of $partner") {
            Item(Icons.Filled.Brush, "Character studio", "Look, personality and photos — sent to $partner for approval") { onNavigate(Routes.STUDIO) }
            HorizontalDivider()
            Item(Icons.Filled.Cake, "Special days", "Birthdays, anniversaries and countdowns") { onNavigate(Routes.OCCASIONS) }
            HorizontalDivider()
            Item(Icons.Filled.Casino, "Games", "Truth or dare, quizzes, stories, virtual dates") { onNavigate(Routes.GAMES) }
        }
        SectionCard(title = "Your own likeness") {
            Item(Icons.Filled.VerifiedUser, "My likeness & consent", "What $partner can see of you, approvals, voice", badge = state.pendingForMe) { onNavigate(Routes.LIKENESS) }
        }
        SectionCard(title = "App") {
            Item(Icons.Filled.Notifications, "Message notifications", "WhatsApp, Instagram and other apps you allow") { onNavigate(Routes.NOTIFICATIONS) }
            HorizontalDivider()
            Item(Icons.Filled.Settings, "Settings", "Behaviour, floating companion, voice, weather") { onNavigate(Routes.SETTINGS) }
            HorizontalDivider()
            Item(Icons.Filled.Security, "Privacy & data", "Export, delete, unpair, delete account") { onNavigate(Routes.PRIVACY) }
        }
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String, badge: Int = 0, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badge > 0) Badge { Text("$badge") }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
