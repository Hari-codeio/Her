package app.her.core

import app.her.core.model.Category
import app.her.core.model.OccasionKind
import app.her.core.model.OutfitRecord
import app.her.core.model.Season
import app.her.core.model.Slot
import app.her.core.model.StyleTag
import app.her.core.model.WardrobeItem
import app.her.core.wardrobe.OutfitContext
import app.her.core.wardrobe.OutfitEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OutfitEngineTest {
    private val engine = OutfitEngine()
    private val today = LocalDate.of(2026, 11, 12)

    private fun item(id: String, cat: Category, color: String, vararg styles: StyleTag, warmth: Int = 2, approved: Boolean = true, seasons: Set<Season> = emptySet()) =
        WardrobeItem(id, id, cat, listOf(color), styles = styles.toSet(), warmth = warmth, approved = approved, seasons = seasons)

    private val wardrobe = listOf(
        item("white-tee", Category.TSHIRT, "#F4F1EC", StyleTag.CASUAL),
        item("lavender-kurti", Category.TOP, "#B9A6E0", StyleTag.CASUAL, StyleTag.TRADITIONAL),
        item("black-shirt", Category.SHIRT, "#1E1E1E", StyleTag.FORMAL),
        item("jeans", Category.JEANS, "#3E5C8A", StyleTag.CASUAL, warmth = 3),
        item("white-trousers", Category.TROUSERS, "#F2EFE8", StyleTag.FORMAL, StyleTag.CASUAL),
        item("red-dress", Category.DRESS, "#C0263A", StyleTag.PARTY, StyleTag.ROMANTIC),
        item("saree", Category.TRADITIONAL, "#D4A017", StyleTag.FESTIVE, StyleTag.TRADITIONAL),
        item("sneakers", Category.SHOES, "#FAFAFA", StyleTag.CASUAL),
        item("heels", Category.SHOES, "#2B2B2B", StyleTag.PARTY),
        item("denim-jacket", Category.OUTERWEAR, "#5A7BA8", StyleTag.CASUAL, warmth = 4),
        item("silver-jhumkas", Category.JEWELLERY, "#C0C0C0", StyleTag.TRADITIONAL),
        item("pj-set", Category.NIGHTWEAR, "#F7C6D0", StyleTag.NIGHT, StyleTag.LOUNGE),
        item("pending-top", Category.TOP, "#00FF00", StyleTag.CASUAL, approved = false),
    )

    @Test
    fun `outfit has a valid structure`() {
        repeat(30) { i ->
            val outfit = engine.dailyOutfit(wardrobe, OutfitContext(date = today.plusDays(i.toLong()), seed = i.toLong()))
            val slots = outfit.items.map { it.slot }
            val onePiece = Slot.ONE_PIECE in slots
            val topAndBottom = Slot.TOP in slots && Slot.BOTTOM in slots
            assertTrue("needs a dress/traditional or top+bottom, got $slots", onePiece xor topAndBottom)
            assertTrue("one shoe pair", slots.count { it == Slot.SHOES } == 1)
            assertTrue("no duplicate slots", slots.size == slots.toSet().size)
            assertFalse("nightwear never in a day outfit", Slot.NIGHT in slots)
        }
    }

    @Test
    fun `same seed gives the same outfit, different seeds vary`() {
        val ctx = OutfitContext(date = today, seed = 42)
        assertEquals(engine.dailyOutfit(wardrobe, ctx).key, engine.dailyOutfit(wardrobe, ctx).key)
        val keys = (0L until 40L).map { engine.dailyOutfit(wardrobe, ctx.copy(seed = it)).key }.toSet()
        assertTrue("expected variety across seeds, got ${keys.size}", keys.size >= 3)
    }

    @Test
    fun `never uses items the owner has not approved`() {
        repeat(40) { i ->
            val outfit = engine.dailyOutfit(wardrobe, OutfitContext(date = today, seed = i.toLong(), surpriseChance = 1.0))
            assertTrue(outfit.items.none { it.id == "pending-top" })
        }
    }

    @Test
    fun `avoids repeating yesterday's base`() {
        val ctx = OutfitContext(date = today, seed = 7, allowSurprise = false)
        val first = engine.dailyOutfit(wardrobe, ctx)
        val history = listOf(OutfitRecord(today.minusDays(1), first.items.map { it.id }))
        val next = engine.dailyOutfit(wardrobe, ctx.copy(history = history))
        assertTrue("base should change after being worn yesterday", next.baseIds != first.baseIds)
    }

    @Test
    fun `disliked combination is not chosen again`() {
        val ctx = OutfitContext(date = today, seed = 3, allowSurprise = false)
        val first = engine.dailyOutfit(wardrobe, ctx)
        val history = listOf(OutfitRecord(today.minusDays(40), first.items.map { it.id }, liked = false))
        repeat(10) { i ->
            val again = engine.dailyOutfit(wardrobe, ctx.copy(seed = 3L + i, history = history))
            assertTrue(again.baseIds != first.baseIds)
        }
    }

    @Test
    fun `cold weather adds outerwear, heat does not`() {
        val cold = engine.dailyOutfit(wardrobe, OutfitContext(date = today, temperatureC = 6.0, seed = 1))
        assertNotNull(cold.item(Slot.OUTER))
        val hot = engine.dailyOutfit(wardrobe, OutfitContext(date = today, temperatureC = 33.0, seed = 1))
        assertNull(hot.item(Slot.OUTER))
    }

    @Test
    fun `anniversary prefers romantic or party pieces`() {
        var dressy = 0
        repeat(20) { i ->
            val outfit = engine.dailyOutfit(wardrobe, OutfitContext(date = today, occasion = OccasionKind.ANNIVERSARY, seed = i.toLong()))
            if (outfit.items.any { StyleTag.ROMANTIC in it.styles || StyleTag.PARTY in it.styles || StyleTag.FORMAL in it.styles }) dressy++
            assertFalse("no surprises on special days", outfit.isSurprise)
        }
        assertTrue("expected mostly dressy outfits, got $dressy/20", dressy >= 16)
    }

    @Test
    fun `bedtime outfit uses nightwear`() {
        val night = engine.bedtimeOutfit(wardrobe, OutfitContext(date = today, seed = 9))
        assertEquals("pj-set", night?.items?.single()?.id)
        assertNull(engine.bedtimeOutfit(wardrobe.filter { it.slot != Slot.NIGHT }, OutfitContext(date = today)))
    }

    @Test
    fun `empty wardrobe falls back to a default look`() {
        val outfit = engine.dailyOutfit(emptyList(), OutfitContext(date = today))
        assertTrue(outfit.isDefault)
        assertTrue(outfit.items.isNotEmpty())
    }
}
