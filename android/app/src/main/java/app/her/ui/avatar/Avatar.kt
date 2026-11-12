package app.her.ui.avatar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import app.her.core.model.Activity
import app.her.core.model.Category
import app.her.core.model.Expression
import app.her.core.model.Outfit
import app.her.core.model.Pattern
import app.her.core.model.Pose
import app.her.core.model.Slot
import app.her.core.model.StyleTag
import app.her.core.model.WardrobeItem
import app.her.data.remote.AppearanceDto
import app.her.domain.ReactionKind
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class Framing(val viewport: Rect) {
    HEAD(Rect(52f, 18f, 148f, 124f)),
    UPPER(Rect(18f, 6f, 182f, 236f)),
    FULL(Rect(0f, 0f, 200f, 400f)),
}

/**
 * The stylised, animated companion. Everything is drawn in a 200 x 400 unit space and scaled to the
 * available size. Appearance comes from the partner-approved appearance; clothes from today's outfit.
 */
@Composable
fun CompanionAvatar(
    appearance: AppearanceDto,
    outfit: Outfit,
    activity: Activity,
    expression: Expression,
    modifier: Modifier = Modifier,
    framing: Framing = Framing.FULL,
    speaking: Boolean = false,
    reaction: ReactionKind? = null,
    seatColor: Color = Color(0xFFB98B73),
    animate: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "avatar")
    val breath by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "breath")
    val wave by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wave")
    val sway by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val talk by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "talk")
    val drift by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "drift")

    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val r = Random(System.nanoTime())
        while (true) {
            delay(2200L + r.nextLong(3200))
            blink = true
            delay(120)
            blink = false
        }
    }

    var lookTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(activity, animate) {
        lookTarget = 0f
        if (!animate || activity != Activity.LOOKING_AROUND) return@LaunchedEffect
        val targets = listOf(-1f, 0f, 1f, 0f)
        var i = 0
        while (true) {
            delay(1400)
            lookTarget = targets[i++ % targets.size]
        }
    }
    val look by animateFloatAsState(lookTarget, tween(500), label = "look")

    val hop = remember { Animatable(0f) }
    LaunchedEffect(reaction) {
        if (reaction == null || !animate) return@LaunchedEffect
        hop.snapTo(0f)
        hop.animateTo(1f, tween(160))
        hop.animateTo(0f, tween(260))
    }

    val palette = remember(appearance) { AvatarPalette.from(appearance) }

    Canvas(modifier) {
        val vp = framing.viewport
        val k = minOf(size.width / vp.width, size.height / vp.height)
        val offsetX = (size.width - vp.width * k) / 2f - vp.left * k
        val offsetY = (size.height - vp.height * k) / 2f - vp.top * k
        val anim = Anim(
            breath = if (animate) sin(breath * 2 * PI).toFloat() else 0f,
            wave = if (animate) wave else 0f,
            sway = if (animate) sway else 0f,
            talk = if (speaking && animate) talk else 0f,
            drift = if (animate) drift else 0f,
            blink = blink,
            look = look,
            hop = hop.value,
        )
        withTransform({
            translate(offsetX, offsetY)
            scale(k, k, pivot = Offset.Zero)
        }) {
            drawCompanion(appearance, palette, outfit, activity, expression, anim, speaking, reaction, seatColor)
        }
    }
}

internal data class Anim(
    val breath: Float,
    val wave: Float,
    val sway: Float,
    val talk: Float,
    val drift: Float,
    val blink: Boolean,
    val look: Float,
    val hop: Float,
)

internal data class AvatarPalette(
    val skin: Color,
    val skinShade: Color,
    val hair: Color,
    val hairLight: Color,
    val eye: Color,
    val lips: Color,
    val blush: Color,
) {
    companion object {
        fun from(a: AppearanceDto): AvatarPalette {
            val skin = parseColor(a.skinTone, Color(0xFFC68E6A))
            val hair = parseColor(a.hairColor, Color(0xFF2B1B12))
            val feminine = a.presentation == "feminine"
            return AvatarPalette(
                skin = skin,
                skinShade = lerp(skin, Color.Black, 0.18f),
                hair = hair,
                hairLight = lerp(hair, Color.White, 0.22f),
                eye = parseColor(a.eyeColor, Color(0xFF3B2416)),
                lips = if (feminine) lerp(skin, Color(0xFFC2185B), 0.45f) else lerp(skin, Color(0xFF8D4A3A), 0.35f),
                blush = Color(0xFFFF7C93),
            )
        }
    }
}

fun parseColor(hex: String?, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(if (hex!!.startsWith("#")) hex else "#$hex"))
}.getOrDefault(fallback)

private data class ArmPose(val upper: Float, val elbow: Float)

private data class Rig(
    val dy: Float,
    val sit: Boolean,
    val sw: Float,
    val ww: Float,
    val hw: Float,
    val armW: Float,
    val legW: Float,
    val rx: Float,
    val ry: Float,
    val feminine: Boolean,
    val masculine: Boolean,
) {
    val headCenter get() = Offset(100f, 70f + dy)
    val shoulderY get() = 124f + dy
    val waistY get() = 190f + dy
    val hipY get() = 226f + dy
}

private fun DrawScope.drawCompanion(
    a: AppearanceDto,
    p: AvatarPalette,
    outfit: Outfit,
    activity: Activity,
    expression: Expression,
    anim: Anim,
    speaking: Boolean,
    reaction: ReactionKind?,
    seatColor: Color,
) {
    val sit = activity.pose == Pose.SIT
    val feminine = a.presentation == "feminine"
    val masculine = a.presentation == "masculine"
    val build = a.build.coerceIn(0f, 1f)
    val rig = Rig(
        dy = if (sit) 22f else 0f,
        sit = sit,
        sw = 30f + build * 8f + (if (masculine) 4f else 0f) - (if (feminine) 2f else 0f),
        ww = 21f + build * 11f + (if (masculine) 3f else 0f) - (if (feminine) 3f else 0f),
        hw = 26f + build * 10f + (if (feminine) 4f else 0f) - (if (masculine) 2f else 0f),
        armW = 9f + build * 3f,
        legW = 11f + build * 4f,
        rx = when (a.faceShape) { "round" -> 29f; "long" -> 25f; "square" -> 28f; "heart" -> 27.5f; else -> 27f },
        ry = when (a.faceShape) { "round" -> 29f; "long" -> 34f; "square" -> 31f; "heart" -> 31f; else -> 32f },
        feminine = feminine,
        masculine = masculine,
    )
    val heightScale = 0.94f + a.height.coerceIn(0f, 1f) * 0.12f
    val feet = Offset(100f, 384f)
    val danceTilt = if (activity == Activity.DANCING) anim.sway * 4f else 0f

    withTransform({
        translate(0f, -anim.hop * 14f)
        scale(heightScale, heightScale, pivot = feet)
        rotate(danceTilt, pivot = feet)
    }) {
        // Soft ground shadow.
        drawOval(Color.Black.copy(alpha = 0.12f), topLeft = Offset(56f, 376f), size = Size(88f, 14f))
        if (sit) drawSeat(seatColor)

        val clothes = Clothes.from(outfit, a)
        drawBackHair(a.hairStyle, rig, p, anim)
        drawLegs(rig, p, clothes, anim, activity)
        drawTorso(rig, p, clothes, anim)
        val poses = poseFor(activity, anim)
        drawArms(rig, p, clothes, poses, anim, activity)
        drawBag(rig, clothes)
        drawHead(a, rig, p, clothes, expression, anim, speaking, activity)
        drawProps(rig, p, activity, poses, anim)
    }
    drawReactionEffects(reaction, activity, anim, expression)
}

// ---------------------------------------------------------------------------------------------- clothes

private data class Clothes(
    val top: WardrobeItem?,
    val bottom: WardrobeItem?,
    val onePiece: WardrobeItem?,
    val outer: WardrobeItem?,
    val night: WardrobeItem?,
    val shoes: WardrobeItem?,
    val jewellery: WardrobeItem?,
    val accessory: WardrobeItem?,
    val bag: WardrobeItem?,
    val masculine: Boolean,
) {
    val torsoItem: WardrobeItem? get() = night ?: onePiece ?: top
    val sleeveItem: WardrobeItem? get() = outer ?: torsoItem
    val sleeve: SleeveLength
        get() {
            if (outer != null) return SleeveLength.LONG
            val item = torsoItem ?: return SleeveLength.SHORT
            return when (item.category) {
                Category.TSHIRT -> SleeveLength.SHORT
                Category.SHIRT, Category.NIGHTWEAR -> SleeveLength.LONG
                Category.DRESS -> when {
                    item.warmth >= 3 -> SleeveLength.LONG
                    StyleTag.PARTY in item.styles -> SleeveLength.NONE
                    else -> SleeveLength.SHORT
                }
                Category.TRADITIONAL -> if (masculine || item.warmth >= 3) SleeveLength.LONG else SleeveLength.ELBOW
                else -> if (item.warmth >= 3) SleeveLength.LONG else SleeveLength.SHORT
            }
        }

    companion object {
        fun from(outfit: Outfit, a: AppearanceDto) = Clothes(
            top = outfit.item(Slot.TOP),
            bottom = outfit.item(Slot.BOTTOM),
            onePiece = outfit.item(Slot.ONE_PIECE),
            outer = outfit.item(Slot.OUTER),
            night = outfit.item(Slot.NIGHT),
            shoes = outfit.item(Slot.SHOES),
            jewellery = outfit.item(Slot.JEWELLERY),
            accessory = outfit.item(Slot.ACCESSORY),
            bag = outfit.item(Slot.BAG),
            masculine = a.presentation == "masculine",
        )
    }
}

private enum class SleeveLength { NONE, SHORT, ELBOW, LONG }

private fun WardrobeItem.main(): Color = parseColor(primaryColor, Color(0xFFDDDDDD))
private fun WardrobeItem.accent(): Color = if (colors.size > 1) parseColor(colors[1], Color.White) else lerp(main(), if (luminance(main()) > 0.6f) Color.Black else Color.White, 0.35f)
private fun luminance(c: Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
private fun Color.shade(f: Float) = lerp(this, Color.Black, f)
private fun Color.light(f: Float) = lerp(this, Color.White, f)
private fun WardrobeItem.has(vararg words: String) = words.any { name.contains(it, ignoreCase = true) }

private enum class JewelleryKind { EARRINGS, NECKLACE, WRIST, SET }

/** Earrings first: "earring" contains "ring", so order matters. */
private fun WardrobeItem.jewelleryKind(): JewelleryKind = when {
    has("earring", "jhumka", "jhumki", "stud", "hoop", "drop") -> JewelleryKind.EARRINGS
    has("necklace", "chain", "pendant", "choker", "mangalsutra", "locket") -> JewelleryKind.NECKLACE
    has("bangle", "bracelet", "kada", "anklet", "ring") -> JewelleryKind.WRIST
    else -> JewelleryKind.SET
}

// ---------------------------------------------------------------------------------------------- body

private fun DrawScope.drawSeat(color: Color) {
    drawRoundRect(color.shade(0.25f), topLeft = Offset(50f, 262f), size = Size(100f, 44f), cornerRadius = CornerRadius(14f, 14f))
    drawRoundRect(color, topLeft = Offset(48f, 248f), size = Size(104f, 30f), cornerRadius = CornerRadius(16f, 16f))
    drawRoundRect(color.light(0.15f), topLeft = Offset(56f, 250f), size = Size(88f, 8f), cornerRadius = CornerRadius(6f, 6f))
}

private fun torsoPath(r: Rig, bottomY: Float, flare: Float = 0f): Path = Path().apply {
    val y0 = r.shoulderY - 4f
    moveTo(100f - r.sw + 7f, y0)
    quadraticBezierTo(100f - r.sw - 1f, y0 + 2f, 100f - r.sw, y0 + 12f)
    cubicTo(100f - r.sw + 1f, r.waistY - 30f, 100f - r.ww - 1f, r.waistY - 10f, 100f - r.ww, r.waistY)
    quadraticBezierTo(100f - r.hw - flare * 0.4f, r.waistY + 20f, 100f - r.hw - flare, bottomY)
    lineTo(100f + r.hw + flare, bottomY)
    quadraticBezierTo(100f + r.hw + flare * 0.4f, r.waistY + 20f, 100f + r.ww, r.waistY)
    cubicTo(100f + r.ww + 1f, r.waistY - 10f, 100f + r.sw - 1f, r.waistY - 30f, 100f + r.sw, y0 + 12f)
    quadraticBezierTo(100f + r.sw + 1f, y0 + 2f, 100f + r.sw - 7f, y0)
    close()
}

private class LegGeometry(val hip: Offset, val knee: Offset, val ankle: Offset)

private fun legs(r: Rig, anim: Anim, activity: Activity): Pair<LegGeometry, LegGeometry> {
    val dance = if (activity == Activity.DANCING) anim.sway else 0f
    fun leg(side: Float): LegGeometry {
        val lift = if (activity == Activity.DANCING && side * dance > 0.3f) -6f * (side * dance) else 0f
        return if (r.sit) {
            LegGeometry(
                hip = Offset(100f + side * r.hw * 0.5f, r.hipY),
                knee = Offset(100f + side * r.hw * 0.56f, SIT_KNEE_Y),
                ankle = Offset(100f + side * r.hw * 0.5f, 368f),
            )
        } else {
            LegGeometry(
                hip = Offset(100f + side * r.hw * 0.5f, r.hipY + 4f),
                knee = Offset(100f + side * (r.hw * 0.42f + dance * 3f), 300f + lift),
                ankle = Offset(100f + side * r.hw * 0.38f + dance * 4f, 368f + lift),
            )
        }
    }
    return leg(-1f) to leg(1f)
}

private const val SIT_KNEE_Y = 282f

private fun DrawScope.drawLegs(r: Rig, p: AvatarPalette, c: Clothes, anim: Anim, activity: Activity) {
    val (left, right) = legs(r, anim, activity)
    val legItem = c.night ?: c.bottom
    val longOnePiece = c.onePiece?.let { it.category == Category.TRADITIONAL && !c.masculine } == true
    val pantsColor = when {
        c.night != null -> c.night.main()
        c.onePiece?.category == Category.TRADITIONAL && c.masculine -> c.onePiece.accent().light(0.4f)
        legItem != null && legItem.category in setOf(Category.JEANS, Category.TROUSERS) -> legItem.main()
        c.onePiece == null && c.bottom == null -> Color(0xFF3E5C8A)
        else -> null
    }?.takeIf { !longOnePiece }
    val shorts = legItem?.category == Category.SHORTS
    val jeans = legItem?.category == Category.JEANS

    if (r.sit) {
        // Seen from the front: a lap across the seat, knees, then the shins going down.
        for (leg in listOf(left, right)) drawLine(p.skin, leg.knee, leg.ankle, r.legW * 0.82f, StrokeCap.Round)
        drawShoes(r, p, c, left, right)
        if (pantsColor != null) {
            for (leg in listOf(left, right)) drawLine(pantsColor, leg.knee, leg.ankle.copy(y = leg.ankle.y - 2f), (r.legW + 7f) * 0.86f, StrokeCap.Round)
        }
        val lapColor = pantsColor ?: if (shorts && legItem != null) legItem.main() else p.skin
        val lapTop = r.hipY - 12f
        drawRoundRect(lapColor, topLeft = Offset(100f - r.hw - 3f, lapTop), size = Size(r.hw * 2f + 6f, SIT_KNEE_Y + 6f - lapTop), cornerRadius = CornerRadius(16f, 16f))
        drawLine(lapColor.shade(0.18f), Offset(100f, lapTop + 10f), Offset(100f, SIT_KNEE_Y + 4f), 1.4f)
        for (leg in listOf(left, right)) {
            drawCircle(lapColor, r.legW * 0.78f, leg.knee)
            drawOval(lapColor.light(0.12f), topLeft = leg.knee - Offset(r.legW * 0.45f, r.legW * 0.55f), size = Size(r.legW * 0.9f, r.legW * 0.5f))
        }
        if (jeans && pantsColor != null) {
            drawLine(pantsColor.light(0.25f), Offset(100f - r.hw * 0.5f, lapTop + 8f), Offset(100f - r.hw * 0.5f, SIT_KNEE_Y), 0.8f)
            drawLine(pantsColor.light(0.25f), Offset(100f + r.hw * 0.5f, lapTop + 8f), Offset(100f + r.hw * 0.5f, SIT_KNEE_Y), 0.8f)
        }
        return
    }

    for (leg in listOf(left, right)) {
        drawLine(p.skin, leg.hip, leg.knee, r.legW, StrokeCap.Round)
        drawLine(p.skin, leg.knee, leg.ankle, r.legW * 0.82f, StrokeCap.Round)
    }
    // Shoes before trousers so the hem covers the top of the shoe.
    drawShoes(r, p, c, left, right)
    if (pantsColor != null) {
        val w = r.legW + 7f
        drawRoundRect(pantsColor, topLeft = Offset(100f - r.hw - 1f, r.hipY - 14f), size = Size(r.hw * 2f + 2f, 28f), cornerRadius = CornerRadius(10f, 10f))
        for (leg in listOf(left, right)) {
            drawLine(pantsColor, leg.hip, leg.knee, w, StrokeCap.Round)
            drawLine(pantsColor, leg.knee, leg.ankle.copy(y = leg.ankle.y - 2f), w * 0.86f, StrokeCap.Round)
            if (jeans) {
                drawLine(pantsColor.light(0.25f), leg.hip.copy(x = leg.hip.x + 2f), leg.ankle.copy(x = leg.ankle.x + 2f, y = leg.ankle.y - 4f), 0.8f)
            }
        }
        if (jeans) drawLine(pantsColor.shade(0.3f), Offset(100f, r.hipY - 12f), Offset(100f, r.hipY + 10f), 1f)
    } else if (shorts && legItem != null) {
        val color = legItem.main()
        drawRoundRect(color, topLeft = Offset(100f - r.hw - 1f, r.hipY - 14f), size = Size(r.hw * 2f + 2f, 30f), cornerRadius = CornerRadius(10f, 10f))
        for (leg in listOf(left, right)) {
            val mid = Offset((leg.hip.x + leg.knee.x) / 2f, (leg.hip.y + leg.knee.y) / 2f)
            drawLine(color, leg.hip, mid, r.legW + 9f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawShoes(r: Rig, p: AvatarPalette, c: Clothes, left: LegGeometry, right: LegGeometry) {
    val shoe = c.shoes
    val color = shoe?.main() ?: Color(0xFFF3F1EC)
    for ((i, leg) in listOf(left, right).withIndex()) {
        val side = if (i == 0) -1f else 1f
        val center = Offset(leg.ankle.x + side * 3f, leg.ankle.y + 9f)
        when {
            shoe?.has("boot") == true -> {
                drawLine(color, leg.ankle.copy(y = leg.ankle.y - 34f), leg.ankle, r.legW + 6f, StrokeCap.Round)
                drawOval(color, topLeft = center - Offset(11f, 6f), size = Size(22f, 12f))
            }
            shoe?.has("sandal", "chappal", "flip", "slipper", "kolhapuri") == true -> {
                drawOval(p.skin, topLeft = center - Offset(9f, 5f), size = Size(18f, 10f))
                drawLine(color, center - Offset(7f, 2f), center + Offset(7f, -2f), 2.5f, StrokeCap.Round)
                drawOval(color.shade(0.2f), topLeft = center + Offset(-10f, 3f), size = Size(20f, 4f))
            }
            shoe?.has("heel", "stiletto", "pump") == true -> {
                drawOval(color, topLeft = center - Offset(9f, 6f), size = Size(18f, 11f))
                drawLine(color.shade(0.2f), center + Offset(-side * 6f, 3f), center + Offset(-side * 7f, 10f), 2.5f, StrokeCap.Round)
            }
            else -> {
                drawOval(color, topLeft = center - Offset(11f, 6f), size = Size(22f, 12f))
                drawOval(color.shade(0.25f), topLeft = center + Offset(-11f, 3f), size = Size(22f, 4f))
            }
        }
    }
}

private fun DrawScope.drawTorso(r: Rig, p: AvatarPalette, c: Clothes, anim: Anim) {
    val bob = anim.breath * 0.7f
    // Neck.
    drawRoundRect(p.skin, topLeft = Offset(93f, 94f + r.dy), size = Size(14f, 34f), cornerRadius = CornerRadius(6f, 6f))
    drawRoundRect(p.skinShade.copy(alpha = 0.35f), topLeft = Offset(93f, 108f + r.dy), size = Size(14f, 6f), cornerRadius = CornerRadius(3f, 3f))

    val item = c.torsoItem
    withTransform({ translate(0f, bob) }) {
        when {
            c.onePiece != null && c.onePiece.category == Category.DRESS -> drawDress(r, p, c.onePiece)
            c.onePiece != null && c.onePiece.category == Category.TRADITIONAL -> drawTraditional(r, p, c.onePiece, c.masculine)
            else -> {
                if (c.bottom?.category == Category.SKIRT) drawSkirt(r, c.bottom)
                val color = item?.main() ?: Color(0xFFF4F1EC)
                val long = item?.let { it.category == Category.TOP && StyleTag.TRADITIONAL in it.styles } == true
                val path = torsoPath(r, if (long) r.hipY + 34f else r.hipY + 2f, if (long) 6f else 0f)
                drawPath(path, color)
                if (item != null) drawPattern(path, item.pattern, color, item.accent())
                drawNeckline(r, p, item)
                if (item?.category == Category.SHIRT) drawButtons(r, color)
            }
        }
        c.outer?.let { drawOuter(r, it) }
        c.jewellery?.let { drawNecklace(r, it) }
        c.accessory?.takeIf { it.has("scarf", "stole", "muffler", "dupatta") }?.let { drawScarf(r, it) }
    }
}

private fun DrawScope.drawNeckline(r: Rig, p: AvatarPalette, item: WardrobeItem?) {
    val y = r.shoulderY - 4f
    when (item?.category) {
        Category.SHIRT -> {
            val collar = (item.main()).light(0.15f)
            drawPath(Path().apply { moveTo(90f, y - 2f); lineTo(100f, y + 12f); lineTo(96f, y + 16f); lineTo(86f, y + 4f); close() }, collar)
            drawPath(Path().apply { moveTo(110f, y - 2f); lineTo(100f, y + 12f); lineTo(104f, y + 16f); lineTo(114f, y + 4f); close() }, collar)
            drawPath(Path().apply { moveTo(93f, y - 2f); lineTo(100f, y + 10f); lineTo(107f, y - 2f); close() }, p.skin)
        }
        Category.NIGHTWEAR, Category.TSHIRT -> drawArc(p.skin, 0f, 180f, true, topLeft = Offset(90f, y - 8f), size = Size(20f, 16f))
        else -> drawPath(Path().apply { moveTo(88f, y - 1f); quadraticBezierTo(100f, y + 18f, 112f, y - 1f); close() }, p.skin)
    }
}

private fun DrawScope.drawButtons(r: Rig, color: Color) {
    val c = color.shade(0.3f)
    for (i in 0..3) drawCircle(c, 1.2f, Offset(100f, r.shoulderY + 18f + i * 22f))
}

private fun DrawScope.drawSkirt(r: Rig, item: WardrobeItem) {
    val color = item.main()
    val hem = if (r.sit) 286f else if (item.has("maxi", "long")) 360f else 292f
    val flare = if (r.sit) 22f else 14f
    val path = Path().apply {
        moveTo(100f - r.ww - 2f, r.waistY + 2f)
        lineTo(100f + r.ww + 2f, r.waistY + 2f)
        quadraticBezierTo(100f + r.hw + flare * 0.6f, (r.waistY + hem) / 2f, 100f + r.hw + flare, hem)
        quadraticBezierTo(100f, hem + 6f, 100f - r.hw - flare, hem)
        quadraticBezierTo(100f - r.hw - flare * 0.6f, (r.waistY + hem) / 2f, 100f - r.ww - 2f, r.waistY + 2f)
        close()
    }
    drawPath(path, color)
    drawPattern(path, item.pattern, color, item.accent())
    drawLine(color.shade(0.15f), Offset(100f - r.ww - 2f, r.waistY + 3f), Offset(100f + r.ww + 2f, r.waistY + 3f), 2f)
}

private fun DrawScope.drawDress(r: Rig, p: AvatarPalette, item: WardrobeItem) {
    val color = item.main()
    val hem = when {
        item.has("maxi", "gown", "long") || item.warmth >= 4 -> 364f
        StyleTag.PARTY in item.styles -> 284f
        else -> 304f
    }.let { if (r.sit) minOf(it, 296f) else it }
    val flare = if (r.sit) 26f else 20f
    val path = Path().apply {
        addPath(torsoPath(r, r.waistY + 4f))
        moveTo(100f - r.ww, r.waistY)
        lineTo(100f + r.ww, r.waistY)
        quadraticBezierTo(100f + r.hw + flare * 0.5f, (r.waistY + hem) / 2f, 100f + r.hw + flare, hem)
        quadraticBezierTo(100f, hem + 8f, 100f - r.hw - flare, hem)
        quadraticBezierTo(100f - r.hw - flare * 0.5f, (r.waistY + hem) / 2f, 100f - r.ww, r.waistY)
        close()
    }
    drawPath(path, color)
    drawPattern(path, item.pattern, color, item.accent())
    drawLine(color.shade(0.2f), Offset(100f - r.ww, r.waistY), Offset(100f + r.ww, r.waistY), 2.5f)
    drawPath(Path().apply { moveTo(88f, r.shoulderY - 5f); quadraticBezierTo(100f, r.shoulderY + 16f, 112f, r.shoulderY - 5f); close() }, p.skin)
}

private fun DrawScope.drawTraditional(r: Rig, p: AvatarPalette, item: WardrobeItem, masculine: Boolean) {
    val color = item.main()
    val accent = item.accent()
    if (masculine) {
        // Long kurta over churidar.
        val path = torsoPath(r, if (r.sit) 280f else 296f, 6f)
        drawPath(path, color)
        drawPattern(path, item.pattern, color, accent)
        drawLine(accent, Offset(100f, r.shoulderY), Offset(100f, r.shoulderY + 44f), 2f)
        for (i in 0..2) drawCircle(accent, 1.4f, Offset(100f, r.shoulderY + 10f + i * 12f))
        return
    }
    // Saree / lehenga silhouette with a draped pallu and a border.
    val hem = if (r.sit) 300f else 372f
    val path = Path().apply {
        addPath(torsoPath(r, r.waistY + 4f))
        moveTo(100f - r.ww, r.waistY)
        lineTo(100f + r.ww, r.waistY)
        quadraticBezierTo(100f + r.hw + 14f, (r.waistY + hem) / 2f, 100f + r.hw + 30f, hem)
        lineTo(100f - r.hw - 30f, hem)
        quadraticBezierTo(100f - r.hw - 14f, (r.waistY + hem) / 2f, 100f - r.ww, r.waistY)
        close()
    }
    drawPath(path, color)
    drawPattern(path, item.pattern, color, accent)
    drawLine(accent, Offset(100f - r.hw - 30f, hem - 4f), Offset(100f + r.hw + 30f, hem - 4f), 7f)
    // Pallu across the body from the left shoulder.
    val pallu = Path().apply {
        moveTo(100f - r.sw + 2f, r.shoulderY - 4f)
        lineTo(100f - r.sw + 18f, r.shoulderY - 6f)
        lineTo(100f + r.hw + 4f, r.waistY + 24f)
        lineTo(100f + r.hw - 6f, r.waistY + 40f)
        close()
    }
    drawPath(pallu, color.light(0.12f))
    drawPath(pallu, accent, style = Stroke(2.5f))
    drawPath(Path().apply { moveTo(88f, r.shoulderY - 4f); quadraticBezierTo(100f, r.shoulderY + 12f, 112f, r.shoulderY - 4f); close() }, p.skin)
}

private fun DrawScope.drawOuter(r: Rig, item: WardrobeItem) {
    val color = item.main()
    val bottom = r.hipY + if (item.has("coat", "trench", "long")) 50f else 6f
    val left = Path().apply {
        moveTo(100f - r.sw + 6f, r.shoulderY - 5f)
        quadraticBezierTo(100f - r.sw - 2f, r.shoulderY, 100f - r.sw - 1f, r.shoulderY + 12f)
        lineTo(100f - r.hw - 3f, bottom)
        lineTo(100f - 7f, bottom)
        lineTo(100f - 9f, r.shoulderY + 22f)
        lineTo(100f - 12f, r.shoulderY - 4f)
        close()
    }
    val right = Path().apply {
        moveTo(100f + r.sw - 6f, r.shoulderY - 5f)
        quadraticBezierTo(100f + r.sw + 2f, r.shoulderY, 100f + r.sw + 1f, r.shoulderY + 12f)
        lineTo(100f + r.hw + 3f, bottom)
        lineTo(100f + 7f, bottom)
        lineTo(100f + 9f, r.shoulderY + 22f)
        lineTo(100f + 12f, r.shoulderY - 4f)
        close()
    }
    drawPath(left, color)
    drawPath(right, color)
    drawPattern(left, item.pattern, color, item.accent())
    drawPattern(right, item.pattern, color, item.accent())
    val lapel = color.shade(0.18f)
    drawLine(lapel, Offset(100f - 12f, r.shoulderY - 4f), Offset(100f - 9f, r.shoulderY + 22f), 2f)
    drawLine(lapel, Offset(100f + 12f, r.shoulderY - 4f), Offset(100f + 9f, r.shoulderY + 22f), 2f)
}

private fun DrawScope.drawNecklace(r: Rig, item: WardrobeItem) {
    if (item.jewelleryKind() != JewelleryKind.NECKLACE && item.jewelleryKind() != JewelleryKind.SET) return
    val color = item.main()
    drawArc(color, 20f, 140f, false, topLeft = Offset(88f, r.shoulderY - 20f), size = Size(24f, 28f), style = Stroke(1.6f))
    drawCircle(color, 2.4f, Offset(100f, r.shoulderY + 8f))
}

private fun DrawScope.drawScarf(r: Rig, item: WardrobeItem) {
    val color = item.main()
    drawRoundRect(color, topLeft = Offset(84f, r.shoulderY - 10f), size = Size(32f, 12f), cornerRadius = CornerRadius(6f, 6f))
    drawRoundRect(color.shade(0.08f), topLeft = Offset(102f, r.shoulderY - 2f), size = Size(9f, 40f), cornerRadius = CornerRadius(4f, 4f))
}

// ---------------------------------------------------------------------------------------------- arms

private fun poseFor(activity: Activity, anim: Anim): Pair<ArmPose, ArmPose> = when (activity) {
    Activity.SITTING_RELAXING, Activity.STARGAZING, Activity.YAWNING -> ArmPose(18f, -70f) to ArmPose(18f, -70f)
    Activity.LOOKING_AROUND -> ArmPose(35f, -100f) to ArmPose(35f, -100f)
    Activity.SMILING, Activity.LOOKING_AT_YOU -> ArmPose(10f, -35f) to ArmPose(10f, -35f)
    Activity.WAVING -> ArmPose(8f, -10f) to ArmPose(135f, 15f + 22f * anim.wave)
    Activity.DANCING -> ArmPose(140f + 15f * anim.sway, 20f) to ArmPose(140f - 15f * anim.sway, 20f)
    Activity.STRETCHING -> ArmPose(160f + 4f * anim.breath, 30f) to ArmPose(160f + 4f * anim.breath, 30f)
    Activity.READING -> ArmPose(20f, -95f) to ArmPose(20f, -95f)
    Activity.DRINKING_COFFEE -> ArmPose(18f, -70f) to ArmPose(22f, -150f + 8f * anim.breath)
    Activity.LISTENING_MUSIC -> ArmPose(18f, -70f) to ArmPose(113f, 115f)
    Activity.WATERING_PLANTS -> ArmPose(8f, -10f) to ArmPose(40f, -25f)
}

private class ArmGeometry(val shoulder: Offset, val elbow: Offset, val wrist: Offset)

private fun armGeometry(r: Rig, pose: ArmPose, side: Float): ArmGeometry {
    val upperLen = 48f
    val foreLen = 46f
    val shoulder = Offset(100f + side * (r.sw - 5f), r.shoulderY + 4f)
    val a1 = Math.toRadians(pose.upper.toDouble())
    val elbow = Offset(shoulder.x + side * sin(a1).toFloat() * upperLen, shoulder.y + cos(a1).toFloat() * upperLen)
    val a2 = Math.toRadians((pose.upper + pose.elbow).toDouble())
    val wrist = Offset(elbow.x + side * sin(a2).toFloat() * foreLen, elbow.y + cos(a2).toFloat() * foreLen)
    return ArmGeometry(shoulder, elbow, wrist)
}

private fun DrawScope.drawArms(r: Rig, p: AvatarPalette, c: Clothes, poses: Pair<ArmPose, ArmPose>, anim: Anim, activity: Activity) {
    val sleeveColor = c.sleeveItem?.main() ?: Color(0xFFF4F1EC)
    val sleeve = c.sleeve
    for ((side, pose) in listOf(-1f to poses.first, 1f to poses.second)) {
        val g = armGeometry(r, pose, side)
        drawLine(p.skin, g.shoulder, g.elbow, r.armW, StrokeCap.Round)
        drawLine(p.skin, g.elbow, g.wrist, r.armW * 0.86f, StrokeCap.Round)
        when (sleeve) {
            SleeveLength.NONE -> Unit
            SleeveLength.SHORT -> drawLine(sleeveColor, g.shoulder, lerpOffset(g.shoulder, g.elbow, 0.45f), r.armW + 4f, StrokeCap.Round)
            SleeveLength.ELBOW -> drawLine(sleeveColor, g.shoulder, lerpOffset(g.shoulder, g.elbow, 0.9f), r.armW + 4f, StrokeCap.Round)
            SleeveLength.LONG -> {
                drawLine(sleeveColor, g.shoulder, g.elbow, r.armW + 4f, StrokeCap.Round)
                drawLine(sleeveColor, g.elbow, lerpOffset(g.elbow, g.wrist, 0.86f), r.armW + 2f, StrokeCap.Round)
            }
        }
        // Hand.
        drawCircle(p.skin, r.armW * 0.62f, g.wrist)
        c.jewellery?.takeIf { it.jewelleryKind() == JewelleryKind.WRIST }?.let {
            drawCircle(it.main(), r.armW * 0.5f, lerpOffset(g.elbow, g.wrist, 0.82f), style = Stroke(1.6f))
        }
        if (side > 0) c.accessory?.takeIf { it.has("watch") }?.let {
            drawCircle(it.main(), r.armW * 0.5f, lerpOffset(g.elbow, g.wrist, 0.82f))
        }
    }
}

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun DrawScope.drawBag(r: Rig, c: Clothes) {
    val bag = c.bag ?: return
    val color = bag.main()
    val strapTop = Offset(100f - r.sw + 8f, r.shoulderY - 2f)
    val bagCenter = Offset(100f + r.hw + 6f, r.hipY + if (r.sit) -10f else 4f)
    drawLine(color.shade(0.25f), strapTop, bagCenter + Offset(-4f, -10f), 2.2f)
    drawRoundRect(color, topLeft = bagCenter - Offset(11f, 9f), size = Size(22f, 18f), cornerRadius = CornerRadius(4f, 4f))
    drawLine(color.shade(0.3f), bagCenter - Offset(11f, 3f), bagCenter + Offset(11f, -3f), 1.2f)
}

// ---------------------------------------------------------------------------------------------- head

private fun DrawScope.drawHead(
    a: AppearanceDto,
    r: Rig,
    p: AvatarPalette,
    c: Clothes,
    expression: Expression,
    anim: Anim,
    speaking: Boolean,
    activity: Activity,
) {
    val tilt = when (activity) {
        Activity.LISTENING_MUSIC -> anim.wave * 3f
        Activity.DANCING -> anim.sway * 5f
        Activity.READING -> 4f
        else -> anim.breath * 0.8f
    }
    val neck = Offset(100f, 100f + r.dy)
    rotate(tilt, pivot = neck) {
        val hc = r.headCenter
        // Ears.
        drawCircle(p.skin, 5.5f, Offset(hc.x - r.rx + 1f, hc.y + 4f))
        drawCircle(p.skin, 5.5f, Offset(hc.x + r.rx - 1f, hc.y + 4f))
        // Face.
        drawPath(facePath(a.faceShape, hc, r.rx, r.ry), p.skin)
        drawFeatures(a, r, p, expression, anim, speaking, activity)
        c.jewellery?.takeIf { it.jewelleryKind() == JewelleryKind.EARRINGS || it.jewelleryKind() == JewelleryKind.SET }?.let { drawEarrings(r, it) }
        drawFrontHair(a.hairStyle, r, p, anim)
        if (a.glasses) drawGlasses(r, Color(0xFF2B2B2B), tinted = false)
        c.accessory?.let { acc ->
            when {
                acc.has("sunglass", "shades") -> drawGlasses(r, acc.main(), tinted = true)
                acc.has("hat", "cap", "beanie") -> drawHat(r, acc)
                acc.has("bindi") -> drawCircle(acc.main(), 1.8f, Offset(hc.x, hc.y - r.ry * 0.42f))
                acc.has("clip", "band", "flower", "gajra", "scrunchie") -> drawHairFlower(r, acc)
            }
        }
        if (activity == Activity.LISTENING_MUSIC) drawHeadphones(r)
    }
}

private fun facePath(shape: String, c: Offset, rx: Float, ry: Float): Path = Path().apply {
    when (shape) {
        "square" -> addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - rx, c.y - ry, c.x + rx, c.y + ry, CornerRadius(rx * 0.55f, ry * 0.5f)))
        "heart" -> {
            moveTo(c.x, c.y + ry)
            cubicTo(c.x - rx * 0.5f, c.y + ry, c.x - rx, c.y + ry * 0.3f, c.x - rx, c.y - ry * 0.2f)
            cubicTo(c.x - rx, c.y - ry * 0.85f, c.x - rx * 0.5f, c.y - ry, c.x, c.y - ry)
            cubicTo(c.x + rx * 0.5f, c.y - ry, c.x + rx, c.y - ry * 0.85f, c.x + rx, c.y - ry * 0.2f)
            cubicTo(c.x + rx, c.y + ry * 0.3f, c.x + rx * 0.5f, c.y + ry, c.x, c.y + ry)
            close()
        }
        else -> {
            moveTo(c.x, c.y + ry)
            cubicTo(c.x - rx * 0.7f, c.y + ry, c.x - rx, c.y + ry * 0.45f, c.x - rx, c.y)
            cubicTo(c.x - rx, c.y - ry * 0.6f, c.x - rx * 0.6f, c.y - ry, c.x, c.y - ry)
            cubicTo(c.x + rx * 0.6f, c.y - ry, c.x + rx, c.y - ry * 0.6f, c.x + rx, c.y)
            cubicTo(c.x + rx, c.y + ry * 0.45f, c.x + rx * 0.7f, c.y + ry, c.x, c.y + ry)
            close()
        }
    }
}

private fun DrawScope.drawFeatures(a: AppearanceDto, r: Rig, p: AvatarPalette, expression: Expression, anim: Anim, speaking: Boolean, activity: Activity) {
    val hc = r.headCenter
    val eyeY = hc.y + 2f
    val eyeDx = 11f
    val lookX = anim.look * 1.8f + (if (expression == Expression.THINKING) -1.2f else 0f)
    val lookY = when {
        activity == Activity.STARGAZING -> -1.6f
        activity == Activity.READING || expression == Expression.BLUSH -> 1.4f
        expression == Expression.THINKING -> -1.2f
        else -> 0f
    }
    val browColor = p.hair.shade(0.1f)

    // Brows.
    val browLift = when (expression) { Expression.SURPRISED -> -3f; Expression.SLEEPY -> 1f; else -> 0f }
    for (side in listOf(-1f, 1f)) {
        val raise = if (expression == Expression.THINKING && side > 0) -2.5f else 0f
        val y = eyeY - 9f + browLift + raise
        drawLine(browColor, Offset(hc.x + side * (eyeDx - 5f), y + 0.6f), Offset(hc.x + side * (eyeDx + 5f), y - 0.6f), if (r.masculine) 2.4f else 1.7f, StrokeCap.Round)
    }

    // Eyes.
    for (side in listOf(-1f, 1f)) {
        val center = Offset(hc.x + side * eyeDx, eyeY)
        val happyClosed = expression == Expression.LAUGH || (expression == Expression.WINK && side > 0)
        val closed = anim.blink || happyClosed || (activity == Activity.YAWNING)
        when {
            happyClosed -> drawArc(Color(0xFF2A1A14), 200f, 140f, false, topLeft = center - Offset(5f, 2f), size = Size(10f, 7f), style = Stroke(1.8f, cap = StrokeCap.Round))
            closed -> drawArc(Color(0xFF2A1A14), 20f, 140f, false, topLeft = center - Offset(5f, 3f), size = Size(10f, 5f), style = Stroke(1.6f, cap = StrokeCap.Round))
            else -> {
                val ry = if (expression == Expression.SURPRISED) 4.8f else 3.9f
                drawOval(Color.White, topLeft = center - Offset(5.4f, ry), size = Size(10.8f, ry * 2f))
                val iris = center + Offset(lookX, lookY)
                drawCircle(p.eye, 3.1f, iris)
                drawCircle(Color(0xFF120A06), 1.5f, iris)
                drawCircle(Color.White, 0.8f, iris + Offset(-0.9f, -1f))
                if (expression == Expression.LOVE) drawHeart(iris, 2.6f, Color(0xFFE5435E))
                if (expression == Expression.SLEEPY) {
                    drawRect(p.skin, topLeft = center - Offset(6f, ry + 1f), size = Size(12f, ry + 0.5f))
                    drawLine(Color(0xFF2A1A14), center + Offset(-5.5f, -0.6f), center + Offset(5.5f, -0.6f), 1.3f)
                }
                if (r.feminine) {
                    drawLine(Color(0xFF2A1A14), center + Offset(side * 5f, -2f), center + Offset(side * 7.5f, -4f), 1.1f, StrokeCap.Round)
                }
            }
        }
    }

    // Nose.
    drawArc(p.skinShade, 60f, 80f, false, topLeft = Offset(hc.x - 3f, hc.y + 7f), size = Size(6f, 6f), style = Stroke(1.2f, cap = StrokeCap.Round))

    // Blush.
    val blushAlpha = when (expression) { Expression.BLUSH, Expression.LOVE -> 0.45f; Expression.LAUGH -> 0.3f; else -> 0.16f }
    drawCircle(p.blush.copy(alpha = blushAlpha), 4.6f, Offset(hc.x - 15f, hc.y + 11f))
    drawCircle(p.blush.copy(alpha = blushAlpha), 4.6f, Offset(hc.x + 15f, hc.y + 11f))

    // Facial hair (under the mouth so lips stay visible).
    when (a.facialHair) {
        "beard" -> drawPath(Path().apply {
            moveTo(hc.x - r.rx + 2f, hc.y + 4f)
            quadraticBezierTo(hc.x - r.rx + 4f, hc.y + r.ry + 6f, hc.x, hc.y + r.ry + 6f)
            quadraticBezierTo(hc.x + r.rx - 4f, hc.y + r.ry + 6f, hc.x + r.rx - 2f, hc.y + 4f)
            quadraticBezierTo(hc.x + r.rx * 0.5f, hc.y + 18f, hc.x, hc.y + 17f)
            quadraticBezierTo(hc.x - r.rx * 0.5f, hc.y + 18f, hc.x - r.rx + 2f, hc.y + 4f)
            close()
        }, p.hair)
        "stubble" -> drawPath(Path().apply {
            moveTo(hc.x - r.rx + 3f, hc.y + 8f)
            quadraticBezierTo(hc.x, hc.y + r.ry + 8f, hc.x + r.rx - 3f, hc.y + 8f)
            quadraticBezierTo(hc.x, hc.y + 20f, hc.x - r.rx + 3f, hc.y + 8f)
            close()
        }, p.hair.copy(alpha = 0.25f))
        else -> Unit
    }
    if (a.facialHair == "mustache" || a.facialHair == "beard") {
        drawRoundRect(p.hair, topLeft = Offset(hc.x - 8f, hc.y + 14.5f), size = Size(16f, 3.2f), cornerRadius = CornerRadius(2f, 2f))
    }

    // Mouth.
    val mouth = Offset(hc.x, hc.y + 19f)
    when {
        activity == Activity.YAWNING -> drawOval(Color(0xFF6B2A2A), topLeft = mouth - Offset(3.5f, 3f), size = Size(7f, 7f))
        speaking -> {
            val h = 1.5f + anim.talk * 4.5f
            drawOval(Color(0xFF6B2A2A), topLeft = mouth - Offset(4.5f, h / 2f - 1f), size = Size(9f, h))
        }
        expression == Expression.LAUGH -> {
            drawPath(Path().apply { moveTo(mouth.x - 6f, mouth.y - 1f); quadraticBezierTo(mouth.x, mouth.y + 9f, mouth.x + 6f, mouth.y - 1f); close() }, Color(0xFF6B2A2A))
            drawCircle(Color(0xFFE88B8B), 2f, mouth + Offset(0f, 3f))
        }
        expression == Expression.SURPRISED -> drawOval(Color(0xFF6B2A2A), topLeft = mouth - Offset(2.5f, 2f), size = Size(5f, 5.5f))
        expression == Expression.THINKING || expression == Expression.NEUTRAL ->
            drawArc(p.lips, 20f, 140f, false, topLeft = mouth - Offset(4.5f, 3f), size = Size(9f, 4f), style = Stroke(1.8f, cap = StrokeCap.Round))
        expression == Expression.SLEEPY -> drawLine(p.lips, mouth - Offset(3f, 0f), mouth + Offset(3f, 0f), 1.6f, StrokeCap.Round)
        expression == Expression.WINK ->
            drawArc(p.lips, 10f, 150f, false, topLeft = mouth - Offset(5f, 4f), size = Size(11f, 6f), style = Stroke(2f, cap = StrokeCap.Round))
        else -> drawArc(p.lips, 15f, 150f, false, topLeft = mouth - Offset(6f, 4.5f), size = Size(12f, 7f), style = Stroke(2f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawEarrings(r: Rig, item: WardrobeItem) {
    val hc = r.headCenter
    val color = item.main()
    for (side in listOf(-1f, 1f)) {
        val ear = Offset(hc.x + side * (r.rx - 1f), hc.y + 10f)
        if (item.has("jhumka", "jhumki", "drop", "dangle")) {
            drawLine(color, ear, ear + Offset(0f, 4f), 1f)
            drawArc(color, 180f, 180f, true, topLeft = ear + Offset(-3f, 3f), size = Size(6f, 6f))
            drawCircle(color, 0.9f, ear + Offset(0f, 8f))
        } else if (item.has("hoop")) {
            drawCircle(color, 3.4f, ear + Offset(0f, 3.4f), style = Stroke(1.1f))
        } else {
            drawCircle(color, 1.6f, ear + Offset(0f, 1f))
        }
    }
}

private fun DrawScope.drawGlasses(r: Rig, color: Color, tinted: Boolean) {
    val hc = r.headCenter
    for (side in listOf(-1f, 1f)) {
        val c = Offset(hc.x + side * 11f, hc.y + 2f)
        if (tinted) drawRoundRect(color.copy(alpha = 0.85f), topLeft = c - Offset(7f, 5f), size = Size(14f, 10f), cornerRadius = CornerRadius(4f, 4f))
        drawRoundRect(if (tinted) color.shade(0.4f) else color, topLeft = c - Offset(7f, 5f), size = Size(14f, 10f), cornerRadius = CornerRadius(4f, 4f), style = Stroke(1.3f))
    }
    drawLine(color, Offset(hc.x - 4f, hc.y + 1f), Offset(hc.x + 4f, hc.y + 1f), 1.2f)
}

private fun DrawScope.drawHat(r: Rig, item: WardrobeItem) {
    val hc = r.headCenter
    val color = item.main()
    if (item.has("cap")) {
        drawArc(color, 180f, 180f, true, topLeft = Offset(hc.x - r.rx - 2f, hc.y - r.ry - 8f), size = Size(r.rx * 2f + 4f, r.ry + 10f))
        drawRoundRect(color.shade(0.15f), topLeft = Offset(hc.x - 4f, hc.y - r.ry * 0.25f), size = Size(r.rx + 12f, 4f), cornerRadius = CornerRadius(2f, 2f))
    } else {
        drawOval(color.shade(0.1f), topLeft = Offset(hc.x - r.rx - 14f, hc.y - r.ry * 0.62f), size = Size(r.rx * 2f + 28f, 9f))
        drawRoundRect(color, topLeft = Offset(hc.x - r.rx * 0.8f, hc.y - r.ry - 12f), size = Size(r.rx * 1.6f, r.ry * 0.62f + 10f), cornerRadius = CornerRadius(8f, 8f))
        drawRect(item.accent(), topLeft = Offset(hc.x - r.rx * 0.8f, hc.y - r.ry * 0.62f - 5f), size = Size(r.rx * 1.6f, 3.5f))
    }
}

private fun DrawScope.drawHairFlower(r: Rig, item: WardrobeItem) {
    val hc = r.headCenter
    val center = Offset(hc.x + r.rx * 0.65f, hc.y - r.ry * 0.7f)
    val color = item.main()
    for (i in 0 until 5) {
        val ang = i * 72.0 * PI / 180.0
        drawCircle(color, 2.6f, center + Offset((cos(ang) * 3.2).toFloat(), (sin(ang) * 3.2).toFloat()))
    }
    drawCircle(Color(0xFFFFE08A), 1.6f, center)
}

private fun DrawScope.drawHeadphones(r: Rig) {
    val hc = r.headCenter
    val band = Color(0xFF2E2A33)
    drawArc(band, 180f, 180f, false, topLeft = Offset(hc.x - r.rx - 3f, hc.y - r.ry - 5f), size = Size(r.rx * 2f + 6f, r.ry * 2f), style = Stroke(3.2f))
    for (side in listOf(-1f, 1f)) {
        drawRoundRect(band, topLeft = Offset(hc.x + side * (r.rx + 1f) - 5f, hc.y - 4f), size = Size(10f, 15f), cornerRadius = CornerRadius(4f, 4f))
        drawRoundRect(Color(0xFFE77FA0), topLeft = Offset(hc.x + side * (r.rx + 1f) - 3f, hc.y - 1f), size = Size(6f, 9f), cornerRadius = CornerRadius(3f, 3f))
    }
}

// ---------------------------------------------------------------------------------------------- hair

private fun DrawScope.drawBackHair(style: String, r: Rig, p: AvatarPalette, anim: Anim) {
    val hc = r.headCenter
    val sway = anim.breath * 1.2f
    when (style) {
        "long_straight" -> drawRoundRect(p.hair, topLeft = Offset(hc.x - r.rx - 6f, hc.y - r.ry + 4f), size = Size(r.rx * 2f + 12f, r.ry + 100f), cornerRadius = CornerRadius(22f, 22f))
        "long_wavy" -> drawPath(Path().apply {
            moveTo(hc.x - r.rx - 7f, hc.y - 6f)
            var y = hc.y - 6f
            var flip = 1f
            while (y < hc.y + 92f) {
                quadraticBezierTo(hc.x - r.rx - 7f - 6f * flip + sway, y + 9f, hc.x - r.rx - 7f, y + 18f)
                y += 18f
                flip = -flip
            }
            quadraticBezierTo(hc.x, y + 14f, hc.x + r.rx + 7f, y)
            while (y > hc.y - 6f) {
                quadraticBezierTo(hc.x + r.rx + 7f + 6f * flip + sway, y - 9f, hc.x + r.rx + 7f, y - 18f)
                y -= 18f
                flip = -flip
            }
            close()
        }, p.hair)
        "curly" -> {
            val rnd = Random(7)
            for (i in 0 until 26) {
                val ang = (i / 26.0) * 2 * PI
                val rad = r.rx + 6f + rnd.nextFloat() * 4f
                val pt = hc + Offset((cos(ang) * rad).toFloat(), (sin(ang) * (r.ry + 4f)).toFloat() * 0.95f - 2f)
                if (pt.y < hc.y + r.ry + 18f) drawCircle(p.hair, 8f + rnd.nextFloat() * 3f, pt)
            }
        }
        "bob" -> drawRoundRect(p.hair, topLeft = Offset(hc.x - r.rx - 6f, hc.y - r.ry + 2f), size = Size(r.rx * 2f + 12f, r.ry * 2f + 6f), cornerRadius = CornerRadius(18f, 18f))
        "ponytail" -> {
            val base = Offset(hc.x + r.rx - 4f, hc.y - r.ry * 0.5f)
            drawPath(Path().apply {
                moveTo(base.x - 5f, base.y)
                cubicTo(base.x + 18f + sway * 3f, base.y + 10f, base.x + 14f + sway * 5f, base.y + 52f, base.x + 6f + sway * 6f, base.y + 74f)
                cubicTo(base.x + 2f + sway * 4f, base.y + 54f, base.x + 4f, base.y + 20f, base.x - 3f, base.y + 8f)
                close()
            }, p.hair)
        }
        "braids" -> drawRoundRect(p.hair, topLeft = Offset(hc.x - r.rx - 3f, hc.y - r.ry + 4f), size = Size(r.rx * 2f + 6f, r.ry + 12f), cornerRadius = CornerRadius(16f, 16f))
        else -> Unit
    }
}

private fun DrawScope.drawFrontHair(style: String, r: Rig, p: AvatarPalette, anim: Anim) {
    val hc = r.headCenter
    val rx = r.rx
    val ry = r.ry
    fun cap(hairline: Float, sidePart: Boolean, thickness: Float = 6f): Path = Path().apply {
        moveTo(hc.x - rx - 2f, hc.y + 6f)
        cubicTo(hc.x - rx - thickness, hc.y - ry * 0.9f, hc.x - rx * 0.5f, hc.y - ry - thickness * 1.4f, hc.x, hc.y - ry - thickness)
        cubicTo(hc.x + rx * 0.5f, hc.y - ry - thickness * 1.4f, hc.x + rx + thickness, hc.y - ry * 0.9f, hc.x + rx + 2f, hc.y + 6f)
        lineTo(hc.x + rx - 2f, hc.y - 2f)
        if (sidePart) {
            quadraticBezierTo(hc.x + rx * 0.4f, hc.y - ry * hairline, hc.x - rx * 0.15f, hc.y - ry * (hairline + 0.15f))
            quadraticBezierTo(hc.x - rx * 0.6f, hc.y - ry * (hairline - 0.15f), hc.x - rx + 2f, hc.y - 2f)
        } else {
            quadraticBezierTo(hc.x, hc.y - ry * (hairline + 0.2f), hc.x - rx + 2f, hc.y - 2f)
        }
        close()
    }
    when (style) {
        "long_straight", "long_wavy" -> {
            drawPath(cap(0.45f, sidePart = true), p.hair)
            drawRoundRect(p.hair, topLeft = Offset(hc.x - rx - 6f, hc.y - 4f), size = Size(9f, 62f), cornerRadius = CornerRadius(5f, 5f))
            drawRoundRect(p.hair, topLeft = Offset(hc.x + rx - 3f, hc.y - 4f), size = Size(9f, 62f), cornerRadius = CornerRadius(5f, 5f))
        }
        "curly" -> {
            drawPath(cap(0.5f, sidePart = false, thickness = 9f), p.hair)
            val rnd = Random(11)
            for (i in 0 until 9) {
                val x = hc.x - rx + i * (rx * 2f / 8f)
                drawCircle(p.hair, 5.5f + rnd.nextFloat() * 1.5f, Offset(x, hc.y - ry * 0.62f + rnd.nextFloat() * 3f))
            }
        }
        "bob" -> {
            drawPath(cap(0.4f, sidePart = false), p.hair)
            drawRoundRect(p.hair, topLeft = Offset(hc.x - rx - 6f, hc.y - 6f), size = Size(10f, 34f), cornerRadius = CornerRadius(5f, 5f))
            drawRoundRect(p.hair, topLeft = Offset(hc.x + rx - 4f, hc.y - 6f), size = Size(10f, 34f), cornerRadius = CornerRadius(5f, 5f))
        }
        "pixie" -> drawPath(cap(0.48f, sidePart = true, thickness = 5f), p.hair)
        "bun" -> {
            drawPath(cap(0.7f, sidePart = false, thickness = 4f), p.hair)
            drawCircle(p.hair, 12f, Offset(hc.x, hc.y - ry - 10f))
            drawCircle(p.hairLight.copy(alpha = 0.5f), 4f, Offset(hc.x - 4f, hc.y - ry - 14f))
        }
        "ponytail" -> drawPath(cap(0.7f, sidePart = false, thickness = 4f), p.hair)
        "braids" -> {
            drawPath(cap(0.62f, sidePart = false, thickness = 4f), p.hair)
            drawLine(p.hairLight.copy(alpha = 0.6f), Offset(hc.x, hc.y - ry - 3f), Offset(hc.x, hc.y - ry * 0.6f), 1.2f)
            for (side in listOf(-1f, 1f)) {
                var y = hc.y + 2f
                val x = hc.x + side * (rx + 1f)
                while (y < hc.y + 96f) {
                    drawOval(p.hair, topLeft = Offset(x - 5f, y), size = Size(10f, 12f))
                    drawLine(p.hairLight.copy(alpha = 0.4f), Offset(x - 3f, y + 6f), Offset(x + 3f, y + 6f), 0.8f)
                    y += 10f
                }
            }
        }
        "short" -> {
            drawPath(cap(0.62f, sidePart = true, thickness = 7f), p.hair)
            drawPath(Path().apply {
                moveTo(hc.x - rx * 0.6f, hc.y - ry - 3f)
                lineTo(hc.x - rx * 0.3f, hc.y - ry - 10f)
                lineTo(hc.x, hc.y - ry - 4f)
                lineTo(hc.x + rx * 0.35f, hc.y - ry - 11f)
                lineTo(hc.x + rx * 0.6f, hc.y - ry - 3f)
                close()
            }, p.hair)
        }
        "buzz" -> drawPath(cap(0.72f, sidePart = false, thickness = 2.5f), p.hair.copy(alpha = 0.85f))
        else -> drawPath(cap(0.5f, sidePart = true), p.hair)
    }
    // Soft sheen.
    if (style != "buzz") {
        drawArc(p.hairLight.copy(alpha = 0.35f), 200f, 50f, false, topLeft = Offset(hc.x - rx + 2f, hc.y - ry - 4f), size = Size(rx * 1.4f, ry), style = Stroke(2.2f, cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------------------------- props & effects

private fun DrawScope.drawProps(r: Rig, p: AvatarPalette, activity: Activity, poses: Pair<ArmPose, ArmPose>, anim: Anim) {
    val left = armGeometry(r, poses.first, -1f)
    val right = armGeometry(r, poses.second, 1f)
    when (activity) {
        Activity.READING -> {
            val c = lerpOffset(left.wrist, right.wrist, 0.5f) + Offset(0f, -4f)
            drawRoundRect(Color(0xFF8E4B5E), topLeft = c - Offset(22f, 15f), size = Size(44f, 28f), cornerRadius = CornerRadius(3f, 3f))
            drawRoundRect(Color(0xFFFFFBF2), topLeft = c - Offset(20f, 13f), size = Size(19f, 24f), cornerRadius = CornerRadius(2f, 2f))
            drawRoundRect(Color(0xFFFFFBF2), topLeft = c - Offset(-1f, 13f), size = Size(19f, 24f), cornerRadius = CornerRadius(2f, 2f))
            for (i in 0..4) {
                drawLine(Color(0xFFCFC6B8), c + Offset(-17f, -9f + i * 4.5f), c + Offset(-4f, -9f + i * 4.5f), 0.8f)
                drawLine(Color(0xFFCFC6B8), c + Offset(4f, -9f + i * 4.5f), c + Offset(17f, -9f + i * 4.5f), 0.8f)
            }
            drawCircle(p.skin, r.armW * 0.62f, left.wrist)
            drawCircle(p.skin, r.armW * 0.62f, right.wrist)
        }
        Activity.DRINKING_COFFEE -> {
            val c = right.wrist + Offset(-3f, -8f)
            drawRoundRect(Color(0xFFF6EFE6), topLeft = c - Offset(7f, 9f), size = Size(14f, 17f), cornerRadius = CornerRadius(3f, 3f))
            drawArc(Color(0xFFF6EFE6), -90f, 180f, false, topLeft = c + Offset(5f, -5f), size = Size(8f, 9f), style = Stroke(2.2f))
            drawRect(Color(0xFFB57A5A), topLeft = c - Offset(7f, 5f), size = Size(14f, 3f))
            for (i in 0..1) {
                val t = (anim.drift + i * 0.5f) % 1f
                val base = c + Offset(-2f + i * 4f, -12f - t * 18f)
                drawLine(Color.White.copy(alpha = 0.7f * (1f - t)), base, base + Offset(2f * sin(t * 6f), -6f), 1.4f, StrokeCap.Round)
            }
        }
        Activity.WATERING_PLANTS -> {
            val c = right.wrist + Offset(6f, 2f)
            drawRoundRect(Color(0xFF7FB7A4), topLeft = c - Offset(8f, 8f), size = Size(18f, 14f), cornerRadius = CornerRadius(4f, 4f))
            drawLine(Color(0xFF7FB7A4), c + Offset(9f, -2f), c + Offset(22f, -10f), 3f, StrokeCap.Round)
            for (i in 0..2) {
                val t = (anim.drift + i / 3f) % 1f
                drawCircle(Color(0xFF6EC1E4).copy(alpha = 1f - t), 1.4f, c + Offset(23f + i * 1.5f, -8f + t * 26f))
            }
        }
        Activity.LISTENING_MUSIC, Activity.DANCING -> {
            for (i in 0..1) {
                val t = (anim.drift + i * 0.5f) % 1f
                drawNote(Offset(150f + i * 14f, 70f + r.dy - t * 40f), Color(0xFFB0557F).copy(alpha = 1f - t))
            }
        }
        Activity.YAWNING -> {
            for (i in 0..2) {
                val t = (anim.drift + i / 3f) % 1f
                drawZ(Offset(136f + i * 8f + t * 6f, 54f + r.dy - t * 30f), 5f + i * 1.5f, Color(0xFF7A6FA8).copy(alpha = 1f - t))
            }
        }
        Activity.STARGAZING -> {
            val t = anim.drift
            drawSparkle(Offset(60f, 30f + r.dy), 3f + 1.5f * sin(t * 6.28f), Color(0xFFFFE9A8))
            drawSparkle(Offset(145f, 22f + r.dy), 2.5f + 1.5f * cos(t * 6.28f), Color(0xFFFFE9A8))
        }
        else -> Unit
    }
}

private fun DrawScope.drawReactionEffects(reaction: ReactionKind?, activity: Activity, anim: Anim, expression: Expression) {
    val hearts = reaction == ReactionKind.KISS || (reaction == null && expression == Expression.LOVE && activity == Activity.LOOKING_AT_YOU)
    if (hearts) {
        for (i in 0..2) {
            val t = (anim.drift + i / 3f) % 1f
            drawHeart(Offset(132f + i * 10f, 70f - t * 50f), 4f + i, Color(0xFFE5435E).copy(alpha = 1f - t))
        }
    }
    if (reaction == ReactionKind.PLAYFUL || reaction == ReactionKind.LAUGH) {
        drawSparkle(Offset(62f, 40f), 4f + 2f * anim.wave, Color(0xFFFFC857))
        drawSparkle(Offset(140f, 32f), 3f - 1.5f * anim.wave, Color(0xFFFFC857))
    }
    if (reaction == ReactionKind.NOTIFY || reaction == ReactionKind.SURPRISE) {
        drawSparkle(Offset(140f, 30f), 5f, Color(0xFF7DD3FC))
    }
}

private fun DrawScope.drawHeart(center: Offset, size: Float, color: Color) {
    drawPath(Path().apply {
        moveTo(center.x, center.y + size)
        cubicTo(center.x - size * 1.6f, center.y - size * 0.2f, center.x - size * 0.7f, center.y - size * 1.4f, center.x, center.y - size * 0.45f)
        cubicTo(center.x + size * 0.7f, center.y - size * 1.4f, center.x + size * 1.6f, center.y - size * 0.2f, center.x, center.y + size)
        close()
    }, color)
}

private fun DrawScope.drawSparkle(center: Offset, size: Float, color: Color) {
    drawPath(Path().apply {
        moveTo(center.x, center.y - size * 2f)
        quadraticBezierTo(center.x, center.y, center.x + size * 2f, center.y)
        quadraticBezierTo(center.x, center.y, center.x, center.y + size * 2f)
        quadraticBezierTo(center.x, center.y, center.x - size * 2f, center.y)
        quadraticBezierTo(center.x, center.y, center.x, center.y - size * 2f)
        close()
    }, color)
}

private fun DrawScope.drawNote(at: Offset, color: Color) {
    drawOval(color, topLeft = at - Offset(4f, 2.5f), size = Size(7f, 5f))
    drawLine(color, at + Offset(2.6f, 0f), at + Offset(2.6f, -12f), 1.4f)
    drawLine(color, at + Offset(2.6f, -12f), at + Offset(7f, -9f), 1.4f, StrokeCap.Round)
}

private fun DrawScope.drawZ(at: Offset, size: Float, color: Color) {
    drawPath(Path().apply {
        moveTo(at.x, at.y)
        lineTo(at.x + size, at.y)
        lineTo(at.x, at.y + size)
        lineTo(at.x + size, at.y + size)
    }, color, style = Stroke(1.5f, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------------------------------------- patterns

private fun DrawScope.drawPattern(path: Path, pattern: Pattern, base: Color, accent: Color) {
    if (pattern == Pattern.SOLID) return
    val ink = if (accent == base) base.shade(0.25f) else accent
    clipPath(path) {
        val bounds = path.getBounds()
        when (pattern) {
            Pattern.STRIPED -> {
                var y = bounds.top
                while (y < bounds.bottom) {
                    drawLine(ink.copy(alpha = 0.75f), Offset(bounds.left, y), Offset(bounds.right, y), 2.2f)
                    y += 7f
                }
            }
            Pattern.CHECKED -> {
                var y = bounds.top
                while (y < bounds.bottom) { drawLine(ink.copy(alpha = 0.45f), Offset(bounds.left, y), Offset(bounds.right, y), 2f); y += 9f }
                var x = bounds.left
                while (x < bounds.right) { drawLine(ink.copy(alpha = 0.45f), Offset(x, bounds.top), Offset(x, bounds.bottom), 2f); x += 9f }
            }
            Pattern.DOTTED -> {
                var y = bounds.top + 4f
                var row = 0
                while (y < bounds.bottom) {
                    var x = bounds.left + if (row % 2 == 0) 3f else 8f
                    while (x < bounds.right) { drawCircle(ink.copy(alpha = 0.8f), 1.5f, Offset(x, y)); x += 10f }
                    y += 8f
                    row++
                }
            }
            Pattern.FLORAL -> {
                val rnd = Random(3)
                var y = bounds.top + 6f
                while (y < bounds.bottom) {
                    var x = bounds.left + rnd.nextFloat() * 8f
                    while (x < bounds.right) {
                        val c = Offset(x, y + rnd.nextFloat() * 6f)
                        for (i in 0 until 5) {
                            val ang = i * 72.0 * PI / 180.0
                            drawCircle(ink.copy(alpha = 0.8f), 1.7f, c + Offset((cos(ang) * 2.2).toFloat(), (sin(ang) * 2.2).toFloat()))
                        }
                        drawCircle(Color(0xFFFFE08A), 1f, c)
                        x += 16f + rnd.nextFloat() * 6f
                    }
                    y += 15f
                }
            }
            Pattern.PRINTED -> {
                val rnd = Random(5)
                repeat(40) {
                    val c = Offset(bounds.left + rnd.nextFloat() * bounds.width, bounds.top + rnd.nextFloat() * bounds.height)
                    drawRoundRect(ink.copy(alpha = 0.55f), topLeft = c, size = Size(4f, 2.5f), cornerRadius = CornerRadius(1f, 1f))
                }
            }
            Pattern.SOLID -> Unit
        }
    }
}
