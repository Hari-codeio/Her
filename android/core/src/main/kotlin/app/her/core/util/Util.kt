package app.her.core.util

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object Seeds {
    /** Stable per companion and day, so the app, the overlay and the background worker agree. */
    fun daily(companionId: String, date: LocalDate, salt: Long = 0): Long =
        stableHash(companionId) * 1_000_003L + date.toEpochDay() * 7_919L + salt

    fun stableHash(text: String): Long {
        // FNV-1a, stable across JVMs (String.hashCode is too, but this spreads bits better).
        var hash = -0x340d631b7bdddcdbL
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }
}

fun <T> Map<T, Double>.weightedPick(random: Random): T {
    val positive = filterValues { it > 0.0 }
    require(positive.isNotEmpty()) { "weightedPick needs at least one positive weight" }
    val total = positive.values.sum()
    var roll = random.nextDouble() * total
    for ((key, weight) in positive) {
        roll -= weight
        if (roll <= 0.0) return key
    }
    return positive.keys.last()
}

object ColorTools {
    data class Hsv(val h: Float, val s: Float, val v: Float)

    fun parse(hex: String): Int? {
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6) return null
        return clean.toLongOrNull(16)?.toInt()
    }

    fun hsv(hex: String): Hsv {
        val rgb = parse(hex) ?: 0xCCCCCC
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0) it + 360f else it }
        val s = if (mx == 0f) 0f else d / mx
        return Hsv(h, s, mx)
    }

    /** Whites, blacks, greys, creams and beiges go with everything. */
    fun isNeutral(hex: String): Boolean {
        val c = hsv(hex)
        return c.s < 0.15f || c.v < 0.18f || (c.v > 0.7f && c.s < 0.28f)
    }

    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * How well two colours sit together, roughly -1.5 (clash) .. 1.2 (lovely).
     * Neutrals pair with anything; analogous and complementary hues score well.
     */
    fun pairScore(a: String, b: String): Double {
        if (isNeutral(a) || isNeutral(b)) return 1.0
        val ca = hsv(a)
        val cb = hsv(b)
        val d = hueDistance(ca.h, cb.h)
        val bothLoud = ca.s > 0.7f && cb.s > 0.7f && ca.v > 0.6f && cb.v > 0.6f
        return when {
            d <= 30f -> 1.2
            d >= 150f -> 1.0
            bothLoud -> -1.5
            d in 105f..135f -> 0.5
            else -> -0.8
        }
    }

    fun harmony(colors: List<String>): Double {
        if (colors.size < 2) return 0.5
        var total = 0.0
        var pairs = 0
        for (i in colors.indices) for (j in i + 1 until colors.size) {
            total += pairScore(colors[i], colors[j])
            pairs++
        }
        return total / pairs
    }
}
