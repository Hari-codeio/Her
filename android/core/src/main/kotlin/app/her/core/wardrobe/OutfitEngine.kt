package app.her.core.wardrobe

import app.her.core.model.Category
import app.her.core.model.DayPart
import app.her.core.model.Environment
import app.her.core.model.Mood
import app.her.core.model.OccasionKind
import app.her.core.model.Outfit
import app.her.core.model.OutfitRecord
import app.her.core.model.Pattern
import app.her.core.model.Season
import app.her.core.model.Slot
import app.her.core.model.StyleTag
import app.her.core.model.WardrobeItem
import app.her.core.util.ColorTools
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

data class OutfitContext(
    val date: LocalDate,
    val season: Season = Season.of(date),
    val dayPart: DayPart = DayPart.MORNING,
    val temperatureC: Double? = null,
    val occasion: OccasionKind? = null,
    val environment: Environment = Environment.LIVING_ROOM,
    val mood: Mood = Mood.HAPPY,
    val history: List<OutfitRecord> = emptyList(),
    val seed: Long = 0L,
    val allowSurprise: Boolean = true,
    val surpriseChance: Double = 0.12,
)

/**
 * Picks what the companion wears. The same context and seed always give the same outfit, so the
 * choice stays stable all day; a "shuffle" just changes the seed.
 */
class OutfitEngine {

    fun dailyOutfit(all: List<WardrobeItem>, ctx: OutfitContext): Outfit {
        val items = all.filter { it.approved }
        val random = Random(ctx.seed)
        val surprise = ctx.allowSurprise && ctx.occasion == null && random.nextDouble() < ctx.surpriseChance
        val targetStyles = if (surprise) emptySet() else targetStyles(ctx)
        val warmth = targetWarmth(ctx)
        val stats = HistoryStats(ctx.history, ctx.date)

        val bases = baseCandidates(items)
        if (bases.isEmpty()) return defaultOutfit(items)

        val jitterRange = if (surprise) 3.0 else 0.75
        val best = bases
            .map { base -> base to scoreBase(base, targetStyles, warmth, ctx, stats) + random.nextDouble() * jitterRange }
            .maxBy { it.second }
            .first

        val chosen = best.toMutableList()
        val baseColors = best.map { it.primaryColor }

        if (needsOuter(ctx, warmth)) {
            pickExtra(items, Slot.OUTER, baseColors, ctx, stats, random)?.let { chosen += it }
        }
        pickExtra(items, Slot.SHOES, baseColors, ctx, stats, random)?.let { chosen += it }

        val dressy = best.any { item ->
            item.styles.any { it in DRESSY_STYLES } || item.category == Category.TRADITIONAL
        } || ctx.occasion != null
        if (dressy || random.nextDouble() < 0.55) {
            pickExtra(items, Slot.JEWELLERY, baseColors, ctx, stats, random)?.let { chosen += it }
        }
        if (random.nextDouble() < 0.45) {
            pickExtra(items, Slot.ACCESSORY, baseColors, ctx, stats, random)?.let { chosen += it }
        }
        val goingOut = !ctx.environment.indoor || ctx.environment == Environment.CAFE
        if (goingOut && random.nextDouble() < 0.8) {
            pickExtra(items, Slot.BAG, baseColors, ctx, stats, random)?.let { chosen += it }
        }

        val note = when {
            ctx.occasion == OccasionKind.BIRTHDAY -> "Dressed up for the birthday"
            ctx.occasion == OccasionKind.ANNIVERSARY -> "Anniversary look"
            ctx.occasion == OccasionKind.CUSTOM -> "Dressed for the occasion"
            surprise -> "Surprise combination"
            ctx.temperatureC != null && ctx.temperatureC < 12 -> "Bundled up for the cold"
            ctx.temperatureC != null && ctx.temperatureC > 30 -> "Light and breezy for the heat"
            else -> null
        }
        return Outfit(chosen, isSurprise = surprise, note = note)
    }

    /** Nightwear for bedtime, or null when the wardrobe has none. */
    fun bedtimeOutfit(all: List<WardrobeItem>, ctx: OutfitContext): Outfit? {
        val night = all.filter { it.approved && it.slot == Slot.NIGHT }
        if (night.isEmpty()) return null
        val random = Random(ctx.seed xor 0x5EEDL)
        val stats = HistoryStats(ctx.history, ctx.date)
        val pick = night.maxBy { stats.itemFeedback(it.id) + seasonScore(it, ctx.season) + random.nextDouble() }
        return Outfit(listOf(pick), note = "Bedtime look")
    }

    // ------------------------------------------------------------------ scoring

    private fun baseCandidates(items: List<WardrobeItem>): List<List<WardrobeItem>> {
        val onePieces = items.filter { it.slot == Slot.ONE_PIECE }.map { listOf(it) }
        val tops = items.filter { it.slot == Slot.TOP }
        val bottoms = items.filter { it.slot == Slot.BOTTOM }
        val pairs = if (tops.isNotEmpty() && bottoms.isNotEmpty()) {
            tops.flatMap { top -> bottoms.map { bottom -> listOf(top, bottom) } }
        } else {
            (tops + bottoms).map { listOf(it) }
        }
        return onePieces + pairs
    }

    private fun scoreBase(
        base: List<WardrobeItem>,
        target: Set<StyleTag>,
        warmth: Int,
        ctx: OutfitContext,
        stats: HistoryStats,
    ): Double {
        val perItem = base.map { item ->
            styleScore(item, target) +
                seasonScore(item, ctx.season) -
                abs(item.warmth - warmth) * 0.7 +
                stats.itemPenalty(item.id) +
                stats.itemFeedback(item.id)
        }.average()
        var score = perItem
        if (base.size == 2) {
            score += ColorTools.pairScore(base[0].primaryColor, base[1].primaryColor) * 1.5
            if (base.count { it.pattern != Pattern.SOLID } > 1) score -= 1.5
        }
        score += stats.comboPenalty(base.map { it.id }.toSet())
        return score
    }

    private fun styleScore(item: WardrobeItem, target: Set<StyleTag>): Double {
        if (target.isEmpty() || item.styles.isEmpty()) return 0.0
        var score = 1.5 * min(item.styles.count { it in target }, 2)
        val relaxedTarget = StyleTag.LOUNGE in target
        if (!relaxedTarget && item.styles.any { it == StyleTag.LOUNGE || it == StyleTag.NIGHT }) score -= 2.0
        if (relaxedTarget && item.styles.any { it in DRESSY_STYLES } && StyleTag.CASUAL !in item.styles) score -= 1.0
        if (item.category == Category.TRADITIONAL && (StyleTag.FESTIVE in target || StyleTag.TRADITIONAL in target)) score += 1.0
        return score
    }

    private fun seasonScore(item: WardrobeItem, season: Season): Double =
        if (item.seasons.isEmpty() || season in item.seasons) 1.0 else -2.0

    private fun pickExtra(
        items: List<WardrobeItem>,
        slot: Slot,
        baseColors: List<String>,
        ctx: OutfitContext,
        stats: HistoryStats,
        random: Random,
    ): WardrobeItem? {
        val options = items.filter { it.slot == slot }
        if (options.isEmpty()) return null
        return options.maxBy { item ->
            val harmony = baseColors.map { ColorTools.pairScore(it, item.primaryColor) }.average()
            harmony + seasonScore(item, ctx.season) + stats.itemPenalty(item.id) * 0.3 +
                stats.itemFeedback(item.id) + random.nextDouble() * 0.8
        }
    }

    private fun needsOuter(ctx: OutfitContext, warmth: Int): Boolean = when {
        ctx.temperatureC != null -> ctx.temperatureC < 16
        else -> warmth >= 4 || (ctx.season == Season.AUTUMN && ctx.dayPart != DayPart.AFTERNOON)
    }

    private fun defaultOutfit(items: List<WardrobeItem>): Outfit {
        val extras = listOfNotNull(
            items.firstOrNull { it.slot == Slot.SHOES },
            items.firstOrNull { it.slot == Slot.JEWELLERY },
        )
        val base = DEFAULT_ITEMS.filter { default -> extras.none { it.slot == default.slot } }
        return Outfit(base + extras, isDefault = true)
    }

    companion object {
        private val DRESSY_STYLES = setOf(StyleTag.FORMAL, StyleTag.PARTY, StyleTag.FESTIVE, StyleTag.ROMANTIC, StyleTag.TRADITIONAL)

        val DEFAULT_ITEMS = listOf(
            WardrobeItem("builtin-tee", "White tee", Category.TSHIRT, listOf("#F4F1EC"), styles = setOf(StyleTag.CASUAL)),
            WardrobeItem("builtin-jeans", "Blue jeans", Category.JEANS, listOf("#3E5C8A"), styles = setOf(StyleTag.CASUAL), warmth = 3),
            WardrobeItem("builtin-sneakers", "White sneakers", Category.SHOES, listOf("#FAFAFA"), styles = setOf(StyleTag.CASUAL)),
        )

        fun targetStyles(ctx: OutfitContext): Set<StyleTag> {
            val styles = mutableSetOf<StyleTag>()
            when (ctx.occasion) {
                OccasionKind.BIRTHDAY -> styles += setOf(StyleTag.PARTY, StyleTag.FESTIVE, StyleTag.ROMANTIC)
                OccasionKind.ANNIVERSARY -> styles += setOf(StyleTag.ROMANTIC, StyleTag.FORMAL, StyleTag.PARTY)
                OccasionKind.CUSTOM -> styles += setOf(StyleTag.FESTIVE, StyleTag.PARTY, StyleTag.TRADITIONAL)
                null -> {
                    styles += when (ctx.environment) {
                        Environment.BEDROOM, Environment.LIVING_ROOM -> setOf(StyleTag.CASUAL, StyleTag.LOUNGE)
                        Environment.CAFE -> setOf(StyleTag.CASUAL, StyleTag.ROMANTIC)
                        Environment.GARDEN, Environment.PARK -> setOf(StyleTag.CASUAL, StyleTag.SPORTY)
                        Environment.BEACH -> setOf(StyleTag.CASUAL)
                        Environment.APARTMENT -> setOf(StyleTag.CASUAL, StyleTag.FORMAL)
                    }
                    when (ctx.mood) {
                        Mood.ROMANTIC -> styles += StyleTag.ROMANTIC
                        Mood.PLAYFUL, Mood.EXCITED -> styles += StyleTag.PARTY
                        Mood.SLEEPY, Mood.RELAXED, Mood.CALM -> styles += StyleTag.LOUNGE
                        Mood.HAPPY, Mood.CURIOUS -> styles += StyleTag.CASUAL
                    }
                    if (ctx.date.dayOfWeek == DayOfWeek.SATURDAY || ctx.date.dayOfWeek == DayOfWeek.SUNDAY) {
                        styles += StyleTag.CASUAL
                    }
                }
            }
            return styles
        }

        fun targetWarmth(ctx: OutfitContext): Int {
            val t = ctx.temperatureC
            if (t != null) {
                return when {
                    t < 5 -> 5
                    t < 12 -> 4
                    t < 18 -> 3
                    t < 25 -> 2
                    else -> 1
                }
            }
            return when (ctx.season) {
                Season.WINTER -> 4
                Season.AUTUMN -> 3
                Season.SPRING -> 2
                Season.SUMMER -> 1
            }
        }
    }
}

/** What the companion wore recently and how the user reacted. Only records before today count. */
class HistoryStats(history: List<OutfitRecord>, private val today: LocalDate) {
    private val past = history.filter { it.date.isBefore(today) }
    private val lastWornDaysAgo: Map<String, Long> = buildMap {
        for (record in past) {
            val days = ChronoUnit.DAYS.between(record.date, today)
            for (id in record.itemIds) {
                val existing = get(id)
                if (existing == null || days < existing) put(id, days)
            }
        }
    }
    private val likes: Map<String, Int> = past.filter { it.liked == true }.flatMap { it.itemIds }.groupingBy { it }.eachCount()
    private val dislikes: Map<String, Int> = past.filter { it.liked == false }.flatMap { it.itemIds }.groupingBy { it }.eachCount()

    fun itemPenalty(id: String): Double = when (lastWornDaysAgo[id] ?: Long.MAX_VALUE) {
        1L -> -3.0
        2L, 3L -> -1.5
        in 4L..7L -> -0.5
        else -> 0.0
    }

    fun itemFeedback(id: String): Double =
        0.6 * min(likes[id] ?: 0, 3) - 0.8 * min(dislikes[id] ?: 0, 3)

    fun comboPenalty(baseIds: Set<String>): Double {
        var penalty = 0.0
        for (record in past) {
            if (!record.itemIds.containsAll(baseIds)) continue
            if (record.liked == false) penalty -= 8.0
            val days = ChronoUnit.DAYS.between(record.date, today)
            if (days <= 21) penalty -= 5.0
        }
        return penalty
    }
}
