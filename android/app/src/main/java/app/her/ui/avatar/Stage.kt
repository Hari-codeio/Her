package app.her.ui.avatar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.her.core.model.DayPart
import app.her.core.model.Environment
import app.her.domain.Presence
import kotlin.random.Random

fun seatColorFor(environment: Environment): Color = when (environment) {
    Environment.BEDROOM -> Color(0xFFE7B8C2)
    Environment.LIVING_ROOM -> Color(0xFF9C7A6B)
    Environment.CAFE -> Color(0xFF7B5442)
    Environment.APARTMENT -> Color(0xFF6E7C8C)
    Environment.GARDEN -> Color(0xFFB98B5E)
    Environment.BEACH -> Color(0xFFE76F6F)
    Environment.PARK -> Color(0xFF8B6A4E)
}

/** The companion in their scene, with the AI label always visible and touch gestures. */
@Composable
fun CompanionStage(
    presence: Presence,
    speaking: Boolean,
    modifier: Modifier = Modifier,
    framing: Framing = Framing.FULL,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onSwipe: (forward: Boolean) -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (kotlin.math.abs(drag) > 80f) onSwipe(drag < 0f) },
                    onHorizontalDrag = { _, amount -> drag += amount },
                )
            },
    ) {
        SceneBackground(presence.environment, presence.dayPart, Modifier.fillMaxSize())
        CompanionAvatar(
            appearance = presence.appearance,
            outfit = presence.outfit,
            activity = presence.activity,
            expression = presence.expression,
            framing = framing,
            speaking = speaking,
            reaction = presence.reaction?.kind,
            seatColor = seatColorFor(presence.environment),
            modifier = Modifier.fillMaxSize().padding(top = 28.dp, bottom = 6.dp),
        )
        AiBadge(Modifier.align(Alignment.TopStart).padding(12.dp))
        AnimatedVisibility(
            visible = presence.bubble != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 12.dp),
        ) {
            presence.bubble?.let { SpeechBubble(it.text, Modifier.widthIn(max = 190.dp)) }
        }
        overlay()
    }
}

@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text("AI companion", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.94f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3A2433))
    }
}

@Composable
fun SceneBackground(environment: Environment, dayPart: DayPart, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawScene(environment, dayPart) }
}

private fun skyColors(dayPart: DayPart): Pair<Color, Color> = when (dayPart) {
    DayPart.MORNING -> Color(0xFFFFD6C0) to Color(0xFFBFE3F2)
    DayPart.AFTERNOON -> Color(0xFF7FC4EE) to Color(0xFFDDF1FB)
    DayPart.EVENING -> Color(0xFF6E5A9E) to Color(0xFFF4A07E)
    DayPart.NIGHT -> Color(0xFF0E1636) to Color(0xFF2E2A5C)
}

private fun DrawScope.drawSky(dayPart: DayPart, top: Float, bottom: Float, left: Float = 0f, right: Float = size.width) {
    val (a, b) = skyColors(dayPart)
    drawRect(Brush.verticalGradient(listOf(a, b), startY = top, endY = bottom), topLeft = Offset(left, top), size = Size(right - left, bottom - top))
    val w = right - left
    when (dayPart) {
        DayPart.NIGHT -> {
            val rnd = Random(4)
            repeat(26) {
                drawCircle(Color.White.copy(alpha = 0.5f + rnd.nextFloat() * 0.5f), 0.8f + rnd.nextFloat() * 1.4f,
                    Offset(left + rnd.nextFloat() * w, top + rnd.nextFloat() * (bottom - top) * 0.7f))
            }
            drawCircle(Color(0xFFFFF4D6), w * 0.05f, Offset(left + w * 0.78f, top + (bottom - top) * 0.22f))
        }
        DayPart.EVENING -> drawCircle(Color(0xFFFFB37A).copy(alpha = 0.9f), w * 0.07f, Offset(left + w * 0.25f, bottom - (bottom - top) * 0.12f))
        else -> drawCircle(Color(0xFFFFF1B8).copy(alpha = 0.9f), w * 0.06f, Offset(left + w * 0.8f, top + (bottom - top) * 0.2f))
    }
}

private fun DrawScope.drawScene(environment: Environment, dayPart: DayPart) {
    val w = size.width
    val h = size.height
    val floorY = h * 0.80f
    when (environment) {
        Environment.BEDROOM -> {
            drawRect(Color(0xFFF6DDE2))
            drawRoundRect(Color(0xFFFFFFFF), topLeft = Offset(w * 0.06f, h * 0.14f), size = Size(w * 0.3f, h * 0.3f), cornerRadius = CornerRadius(12f))
            drawSky(dayPart, h * 0.15f, h * 0.43f, w * 0.07f, w * 0.35f)
            drawLine(Color.White, Offset(w * 0.21f, h * 0.15f), Offset(w * 0.21f, h * 0.43f), 4f)
            drawRoundRect(Color(0xFFB57A8E), topLeft = Offset(w * 0.62f, h * 0.42f), size = Size(w * 0.42f, h * 0.2f), cornerRadius = CornerRadius(18f))
            drawRoundRect(Color(0xFFFFF2F4), topLeft = Offset(w * 0.6f, h * 0.56f), size = Size(w * 0.44f, h * 0.2f), cornerRadius = CornerRadius(14f))
            drawRoundRect(Color(0xFFE7A1B5), topLeft = Offset(w * 0.6f, h * 0.62f), size = Size(w * 0.44f, h * 0.15f), cornerRadius = CornerRadius(14f))
            drawFloor(Color(0xFFD9B49C), floorY)
            drawLamp(Offset(w * 0.12f, floorY), h, dayPart)
        }
        Environment.LIVING_ROOM -> {
            drawRect(Color(0xFFF0E6D8))
            drawRoundRect(Color(0xFF9A7B6C), topLeft = Offset(w * 0.62f, h * 0.16f), size = Size(w * 0.24f, h * 0.17f), cornerRadius = CornerRadius(6f))
            drawRoundRect(Color(0xFFF4C7A6), topLeft = Offset(w * 0.635f, h * 0.17f), size = Size(w * 0.21f, h * 0.15f), cornerRadius = CornerRadius(4f))
            drawCircle(Color(0xFFE88E7A), w * 0.035f, Offset(w * 0.74f, h * 0.245f))
            drawRoundRect(Color(0xFFFFFFFF), topLeft = Offset(w * 0.08f, h * 0.12f), size = Size(w * 0.3f, h * 0.28f), cornerRadius = CornerRadius(10f))
            drawSky(dayPart, h * 0.13f, h * 0.39f, w * 0.09f, w * 0.37f)
            drawFloor(Color(0xFFC49A78), floorY)
            drawOval(Color(0xFFE9C9B4), topLeft = Offset(w * 0.15f, floorY + h * 0.03f), size = Size(w * 0.7f, h * 0.1f))
            drawPlant(Offset(w * 0.88f, floorY), h)
        }
        Environment.CAFE -> {
            drawRect(Color(0xFFD7B597))
            val brick = Color(0xFFC79D7F)
            var y = 0f
            var row = 0
            while (y < floorY) {
                var x = if (row % 2 == 0) 0f else -w * 0.06f
                while (x < w) { drawRoundRect(brick, topLeft = Offset(x + 2f, y + 2f), size = Size(w * 0.12f - 4f, h * 0.04f - 4f), cornerRadius = CornerRadius(3f)); x += w * 0.12f }
                y += h * 0.04f
                row++
            }
            drawRoundRect(Color(0xFF3E3A39), topLeft = Offset(w * 0.06f, h * 0.12f), size = Size(w * 0.28f, h * 0.18f), cornerRadius = CornerRadius(8f))
            for (i in 0..2) drawLine(Color.White.copy(alpha = 0.7f), Offset(w * 0.09f, h * (0.16f + i * 0.04f)), Offset(w * 0.28f, h * (0.16f + i * 0.04f)), 2f)
            for (x in listOf(0.55f, 0.8f)) {
                drawLine(Color(0xFF3E3A39), Offset(w * x, 0f), Offset(w * x, h * 0.12f), 2f)
                drawArc(Color(0xFFE9B44C), 180f, 180f, true, topLeft = Offset(w * x - w * 0.05f, h * 0.1f), size = Size(w * 0.1f, h * 0.06f))
                drawCircle(Color(0xFFFFE8A3).copy(alpha = if (dayPart == DayPart.NIGHT || dayPart == DayPart.EVENING) 0.5f else 0.2f), w * 0.08f, Offset(w * x, h * 0.15f))
            }
            drawFloorChecker(floorY)
            drawRoundRect(Color(0xFF6B4A3A), topLeft = Offset(w * 0.7f, h * 0.6f), size = Size(w * 0.26f, h * 0.03f), cornerRadius = CornerRadius(6f))
            drawRect(Color(0xFF6B4A3A), topLeft = Offset(w * 0.82f, h * 0.62f), size = Size(w * 0.02f, floorY - h * 0.62f))
        }
        Environment.APARTMENT -> {
            drawRect(Color(0xFFE6E8EC))
            drawSky(dayPart, h * 0.06f, h * 0.62f, w * 0.05f, w * 0.95f)
            val rnd = Random(9)
            var x = w * 0.05f
            while (x < w * 0.95f) {
                val bw = w * (0.06f + rnd.nextFloat() * 0.06f)
                val bh = h * (0.12f + rnd.nextFloat() * 0.22f)
                val c = if (dayPart == DayPart.NIGHT) Color(0xFF1C2140) else lerp(Color(0xFF8C9AB4), Color(0xFF5D6B86), rnd.nextFloat())
                drawRect(c, topLeft = Offset(x, h * 0.62f - bh), size = Size(bw, bh))
                if (dayPart == DayPart.NIGHT || dayPart == DayPart.EVENING) {
                    repeat(4) { drawRect(Color(0xFFFFE08A).copy(alpha = 0.8f), topLeft = Offset(x + rnd.nextFloat() * (bw - 4f), h * 0.62f - bh + rnd.nextFloat() * (bh - 6f)), size = Size(3f, 4f)) }
                }
                x += bw + 2f
            }
            for (i in 0..3) drawLine(Color(0xFFCDD2DA), Offset(w * (0.05f + i * 0.3f), h * 0.06f), Offset(w * (0.05f + i * 0.3f), h * 0.62f), 6f)
            drawRect(Color(0xFFCDD2DA), topLeft = Offset(w * 0.05f, h * 0.6f), size = Size(w * 0.9f, h * 0.03f))
            drawFloor(Color(0xFF8D7461), floorY)
            drawLamp(Offset(w * 0.88f, floorY), h, dayPart)
        }
        Environment.GARDEN -> {
            drawSky(dayPart, 0f, h * 0.6f)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF9CCB7A), Color(0xFF6FA35A)), startY = h * 0.55f, endY = h), topLeft = Offset(0f, h * 0.55f), size = Size(w, h * 0.45f))
            for (i in 0..8) drawRect(Color(0xFFF2E3C9), topLeft = Offset(w * (0.02f + i * 0.12f), h * 0.44f), size = Size(w * 0.03f, h * 0.13f))
            drawRect(Color(0xFFF2E3C9), topLeft = Offset(0f, h * 0.48f), size = Size(w, h * 0.015f))
            drawTree(Offset(w * 0.85f, h * 0.6f), h, Color(0xFF5E9B54))
            val rnd = Random(2)
            repeat(14) {
                val c = Offset(rnd.nextFloat() * w, h * (0.66f + rnd.nextFloat() * 0.3f))
                val petal = listOf(Color(0xFFF48FB1), Color(0xFFFFD54F), Color(0xFFFFFFFF), Color(0xFFCE93D8))[it % 4]
                for (k in 0 until 5) drawCircle(petal, 3.5f, c + Offset(4f * kotlin.math.cos(k * 1.256f), 4f * kotlin.math.sin(k * 1.256f)))
                drawCircle(Color(0xFFFFB300), 2.2f, c)
            }
        }
        Environment.BEACH -> {
            drawSky(dayPart, 0f, h * 0.5f)
            val sea = if (dayPart == DayPart.NIGHT) Color(0xFF1D3557) else Color(0xFF4FB3C8)
            drawRect(Brush.verticalGradient(listOf(sea, lerp(sea, Color.White, 0.25f)), startY = h * 0.5f, endY = h * 0.66f), topLeft = Offset(0f, h * 0.5f), size = Size(w, h * 0.16f))
            drawRect(Brush.verticalGradient(listOf(Color(0xFFF6E2B8), Color(0xFFE9C990)), startY = h * 0.64f, endY = h), topLeft = Offset(0f, h * 0.64f), size = Size(w, h * 0.36f))
            val foam = Path().apply {
                moveTo(0f, h * 0.65f)
                var x = 0f
                while (x < w) { quadraticBezierTo(x + w * 0.05f, h * 0.63f, x + w * 0.1f, h * 0.65f); x += w * 0.1f }
            }
            drawPath(foam, Color.White.copy(alpha = 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            drawLine(Color(0xFF8D6E63), Offset(w * 0.86f, h * 0.36f), Offset(w * 0.9f, h * 0.8f), 4f)
            drawArc(Color(0xFFEF5350), 180f, 180f, true, topLeft = Offset(w * 0.72f, h * 0.3f), size = Size(w * 0.28f, h * 0.12f))
        }
        Environment.PARK -> {
            drawSky(dayPart, 0f, h * 0.58f)
            drawRect(Brush.verticalGradient(listOf(Color(0xFFA8D58C), Color(0xFF79B064)), startY = h * 0.55f, endY = h), topLeft = Offset(0f, h * 0.55f), size = Size(w, h * 0.45f))
            drawPath(Path().apply {
                moveTo(w * 0.35f, h); lineTo(w * 0.47f, h * 0.56f); lineTo(w * 0.53f, h * 0.56f); lineTo(w * 0.75f, h); close()
            }, Color(0xFFE8D7B5))
            drawTree(Offset(w * 0.12f, h * 0.6f), h, Color(0xFF6BAA5F))
            drawTree(Offset(w * 0.86f, h * 0.58f), h * 0.9f, Color(0xFF5A9A50))
            drawRoundRect(Color(0xFF8B6A4E), topLeft = Offset(w * 0.6f, h * 0.62f), size = Size(w * 0.22f, h * 0.025f), cornerRadius = CornerRadius(4f))
        }
    }
    if (dayPart == DayPart.NIGHT) drawRect(Color(0xFF0B1030).copy(alpha = if (environment.indoor) 0.18f else 0.28f))
}

private fun DrawScope.drawFloor(color: Color, floorY: Float) {
    drawRect(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.85f)), startY = floorY, endY = size.height), topLeft = Offset(0f, floorY), size = Size(size.width, size.height - floorY))
    drawLine(lerp(color, Color.Black, 0.15f), Offset(0f, floorY), Offset(size.width, floorY), 3f)
}

private fun DrawScope.drawFloorChecker(floorY: Float) {
    val tile = size.width / 10f
    var y = floorY
    var row = 0
    while (y < size.height) {
        var x = 0f
        var col = row
        while (x < size.width) {
            drawRect(if (col % 2 == 0) Color(0xFFF1E6D8) else Color(0xFF5B4637), topLeft = Offset(x, y), size = Size(tile, tile * 0.5f))
            x += tile
            col++
        }
        y += tile * 0.5f
        row++
    }
}

private fun DrawScope.drawLamp(base: Offset, h: Float, dayPart: DayPart) {
    drawLine(Color(0xFF4E4A4F), base, base - Offset(0f, h * 0.36f), 4f)
    drawPath(Path().apply {
        moveTo(base.x - h * 0.05f, base.y - h * 0.34f)
        lineTo(base.x + h * 0.05f, base.y - h * 0.34f)
        lineTo(base.x + h * 0.035f, base.y - h * 0.42f)
        lineTo(base.x - h * 0.035f, base.y - h * 0.42f)
        close()
    }, Color(0xFFF7E3C4))
    if (dayPart == DayPart.EVENING || dayPart == DayPart.NIGHT) {
        drawCircle(Color(0xFFFFE6A8).copy(alpha = 0.35f), h * 0.12f, base - Offset(0f, h * 0.36f))
    }
}

private fun DrawScope.drawPlant(base: Offset, h: Float) {
    drawRoundRect(Color(0xFFD08C6A), topLeft = base - Offset(h * 0.035f, h * 0.07f), size = Size(h * 0.07f, h * 0.07f), cornerRadius = CornerRadius(6f))
    val leaf = Color(0xFF5E9B54)
    for (i in -2..2) drawOval(leaf, topLeft = base + Offset(i * h * 0.018f - h * 0.015f, -h * 0.2f + kotlin.math.abs(i) * h * 0.03f), size = Size(h * 0.03f, h * 0.13f))
}

private fun DrawScope.drawTree(base: Offset, h: Float, leaf: Color) {
    drawRect(Color(0xFF7A5A43), topLeft = base - Offset(h * 0.015f, h * 0.18f), size = Size(h * 0.03f, h * 0.18f))
    drawCircle(leaf, h * 0.1f, base - Offset(0f, h * 0.24f))
    drawCircle(lerp(leaf, Color.White, 0.1f), h * 0.075f, base - Offset(h * 0.06f, h * 0.2f))
    drawCircle(lerp(leaf, Color.Black, 0.1f), h * 0.07f, base - Offset(-h * 0.06f, h * 0.2f))
}

@Suppress("unused")
private fun Modifier.stageFill() = this.fillMaxWidth()
