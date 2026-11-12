package app.her.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.her.ui.avatar.parseColor

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipGroup(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(label(option)) },
                leadingIcon = if (option in selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

val SWATCHES = listOf(
    "#FFFFFF", "#F4F1EC", "#E8DCC8", "#C8B59A", "#8B6E52", "#5A4032", "#1E1E1E", "#6B6B6B",
    "#C0263A", "#E8667A", "#F7C6D0", "#B9A6E0", "#7B4FA0", "#3F2A6B", "#1F3A68", "#3E5C8A",
    "#7FB3E0", "#2E8B8B", "#5E9B54", "#A3C47F", "#F2C14E", "#D4A017", "#E07A3F", "#8E3B46",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: List<String>, max: Int = 3, onChange: (List<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SWATCHES.forEach { hex ->
            val index = selected.indexOf(hex)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(parseColor(hex, Color.Gray))
                    .border(
                        BorderStroke(if (index >= 0) 3.dp else 1.dp, if (index >= 0) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.15f)),
                        CircleShape,
                    )
                    .clickable {
                        onChange(
                            when {
                                index >= 0 -> selected - hex
                                selected.size >= max -> selected.drop(1) + hex
                                else -> selected + hex
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (index >= 0) Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (hex in listOf("#FFFFFF", "#F4F1EC", "#E8DCC8", "#F7C6D0")) Color.Black else Color.White)
            }
        }
    }
}

@Composable
fun Swatch(hex: String, size: Int = 18) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(parseColor(hex, Color.Gray))
            .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "Confirm",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}
