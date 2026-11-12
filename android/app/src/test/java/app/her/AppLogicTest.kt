package app.her

import app.her.core.model.Category
import app.her.core.model.OccasionKind
import app.her.core.model.OccasionPerson
import app.her.data.remote.CompanionDto
import app.her.data.remote.HerApi
import app.her.data.remote.HerJson
import app.her.data.remote.OccasionDto
import app.her.data.remote.WardrobeItemDto
import app.her.data.toCore
import app.her.domain.SpeechController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {

    @Test
    fun `error details are parsed from the server shape`() {
        assertEquals("likeness_consent_required", HerApi.parseDetail("""{"detail":"likeness_consent_required"}"""))
        assertEquals("invalid_input", HerApi.parseDetail("""{"detail":[{"loc":["body","code"],"msg":"field required"}]}"""))
        assertEquals("unknown_error", HerApi.parseDetail(""))
        assertEquals("Internal Server Error", HerApi.parseDetail("Internal Server Error"))
    }

    @Test
    fun `speech text drops emoji and markdown`() {
        assertEquals("Good morning, jaan! Coffee?", SpeechController.cleanForSpeech("Good morning, jaan! ☀️😊 *Coffee?* 💕"))
    }

    @Test
    fun `companion json from the server decodes, unknown fields ignored`() {
        val json = """
            {"id":"c1","subject":{"id":"u2","display_name":"Maya"},"viewer":{"id":"u1","display_name":"Arjun"},
             "name":"Maya","status":"active","consents":{"likeness":true,"voice":false,"personality":true,"ai_training":false},
             "appearance":{"presentation":"feminine","skin_tone":"#B57A55","hair_style":"braids","hair_color":"#0F0B09",
               "eye_color":"#3B2416","face_shape":"heart","height":0.4,"build":0.3,"facial_hair":"none","glasses":true,"style_notes":""},
             "pending_appearance":null,"persona":null,"pending_persona":null,"voice":{"status":"none","samples":0},
             "photo_count":3,"created_at":"2026-11-12T07:21:00Z","updated_at":"2026-11-12T07:21:00Z","something_new":1}
        """.trimIndent()
        val c = HerJson.decodeFromString<CompanionDto>(json)
        assertTrue(c.isActive)
        assertEquals("braids", c.appearance?.hairStyle)
        assertTrue(c.appearance!!.glasses)
        assertFalse(c.consents.voice)
    }

    @Test
    fun `wardrobe and occasion mapping`() {
        val item = WardrobeItemDto(id = "w1", name = "Saree", category = "traditional", colors = listOf("#D4A017", "#8E3B46"), styles = listOf("festive", "unknown"), seasons = listOf("monsoon", "winter"), warmth = 9)
        val core = item.toCore()
        assertEquals(Category.TRADITIONAL, core.category)
        assertEquals(1, core.styles.size)
        assertEquals(1, core.seasons.size)
        assertEquals(5, core.warmth)

        val bday = OccasionDto(id = "o1", kind = "birthday", title = "Maya's birthday", date = "1996-03-09", personUserId = "u2")
        val mapped = bday.toCore(viewerId = "u1", subjectId = "u2")!!
        assertEquals(OccasionKind.BIRTHDAY, mapped.kind)
        assertEquals(OccasionPerson.SUBJECT, mapped.person)
        assertNull(OccasionDto(id = "x", title = "bad", date = "not-a-date").toCore("u1", "u2"))
    }
}
