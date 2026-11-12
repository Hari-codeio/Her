package app.her.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.her.data.remote.InviteDto
import app.her.ui.LocalContainer
import app.her.ui.LocalRunner
import app.her.ui.theme.Blush
import app.her.ui.theme.Cream

@Composable
fun OnboardingScreen(snackbar: SnackbarHostState) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(settings.serverUrl) }
    var showServer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Blush, Cream)))
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Her", style = MaterialTheme.typography.displaySmall.copy(fontSize = 56.sp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "A private AI companion of the person you love, made with their consent, for the moments you're apart.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Promise(Icons.Filled.VerifiedUser, "Their likeness, their choice", "Your partner gives consent, approves every version of their character, and can revoke it at any time.")
            Promise(Icons.Filled.AutoAwesome, "Always labelled AI", "The companion is an AI version. It never pretends to be your partner or to have sent real messages.")
            Promise(Icons.Filled.Lock, "Private by design", "Nothing is public. Photos are encrypted, and you can delete anything whenever you want.")
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Your first name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showServer) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it.trim() },
                    label = { Text("Server address") },
                    supportingText = { Text("Emulator: http://10.0.2.2:8000 · Phone on Wi-Fi: http://<your computer's IP>:8000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    busy = true
                    runner.run(onDone = { busy = false }, onError = { busy = false }) {
                        container.repo.register(server, name)
                    }
                },
                enabled = name.isNotBlank() && server.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Get started")
            }
            TextButton(onClick = { showServer = !showServer }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (showServer) "Hide server settings" else "Server settings")
            }
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PairScreen(snackbar: SnackbarHostState) {
    val container = LocalContainer.current
    val runner = LocalRunner.current
    val state by container.repo.state.collectAsStateWithLifecycle()
    val status by container.repo.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var invite by remember { mutableStateOf<InviteDto?>(null) }
    var code by remember { mutableStateOf("") }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
            Text("Hi ${state.me?.displayName ?: ""}! Let's pair you with your partner.", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pairing connects your two accounts. After that, each of you decides whether the other may create an AI companion of you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            app.her.ui.common.SectionCard(title = "Share your code", subtitle = "Your partner enters it in their Her app.") {
                val current = invite
                if (current == null) {
                    Button(onClick = { runner.run { invite = container.repo.createInvite() } }) { Text("Create my invite code") }
                } else {
                    Text(current.code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text("Valid for 24 hours, works once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { copy(context, current.code); runner.toast("Code copied") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
                        }
                        OutlinedButton(onClick = { share(context, current.code) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { runner.run { container.repo.refresh() } }, enabled = !status.syncing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (status.syncing) "Checking…" else "My partner joined — continue")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text("  or  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f))
            }

            app.her.ui.common.SectionCard(title = "Enter their code") {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(9) },
                    label = { Text("XXXX-XXXX") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { runner.run(done = "You're paired 💞") { container.repo.join(code) } }, enabled = code.length >= 8, modifier = Modifier.fillMaxWidth()) {
                    Text("Pair")
                }
            }

            TextButton(onClick = { runner.run { container.repo.signOutLocally() } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Start over")
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Her invite code", text))
}

private fun share(context: Context, code: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Pair with me on Her 💞 My code: $code")
    }
    context.startActivity(Intent.createChooser(intent, "Share invite code"))
}
