package app.her.core.model

import java.time.LocalDate
import java.time.Month

enum class DayPart(val label: String) {
    MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening"), NIGHT("Night");

    val apiName: String get() = name.lowercase()

    companion object {
        fun ofHour(hour: Int): DayPart = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** A fictional character state for the day. Never a claim about the real partner's feelings. */
enum class Mood(val label: String, val emoji: String) {
    HAPPY("Happy", "😊"),
    PLAYFUL("Playful", "😜"),
    CALM("Calm", "😌"),
    SLEEPY("Sleepy", "😴"),
    EXCITED("Excited", "🤩"),
    RELAXED("Relaxed", "🙂"),
    ROMANTIC("Romantic", "🥰"),
    CURIOUS("Curious", "🤔");

    val apiName: String get() = name.lowercase()
}

enum class Environment(val label: String, val indoor: Boolean) {
    BEDROOM("Bedroom", true),
    LIVING_ROOM("Living room", true),
    GARDEN("Garden", false),
    CAFE("Café", true),
    BEACH("Beach", false),
    PARK("Park", false),
    APARTMENT("Modern apartment", true);

    val apiName: String get() = name.lowercase()
}

enum class Pose { STAND, SIT }

enum class Activity(val label: String, val pose: Pose, val verb: String) {
    SITTING_RELAXING("Relaxing", Pose.SIT, "relaxing"),
    LOOKING_AROUND("Looking around", Pose.STAND, "looking around"),
    SMILING("Smiling", Pose.STAND, "smiling at you"),
    WAVING("Waving", Pose.STAND, "waving at you"),
    READING("Reading", Pose.SIT, "reading a book"),
    DANCING("Dancing", Pose.STAND, "dancing"),
    LISTENING_MUSIC("Listening to music", Pose.SIT, "listening to music"),
    DRINKING_COFFEE("Coffee break", Pose.SIT, "sipping coffee"),
    STRETCHING("Stretching", Pose.STAND, "stretching"),
    LOOKING_AT_YOU("Looking at you", Pose.STAND, "looking at you"),
    WATERING_PLANTS("Watering plants", Pose.STAND, "watering the plants"),
    STARGAZING("Stargazing", Pose.SIT, "stargazing"),
    YAWNING("Getting sleepy", Pose.SIT, "getting sleepy");

    val apiName: String get() = name.lowercase()
}

enum class Expression { NEUTRAL, SMILE, LAUGH, WINK, SURPRISED, SLEEPY, BLUSH, THINKING, LOVE }

// ---------------------------------------------------------------- Wardrobe

enum class Slot { TOP, BOTTOM, ONE_PIECE, OUTER, NIGHT, SHOES, ACCESSORY, JEWELLERY, BAG }

val BASE_SLOTS = setOf(Slot.TOP, Slot.BOTTOM, Slot.ONE_PIECE, Slot.NIGHT)

enum class Category(val apiName: String, val label: String, val slot: Slot) {
    TOP("top", "Tops", Slot.TOP),
    TSHIRT("tshirt", "T-shirts", Slot.TOP),
    SHIRT("shirt", "Shirts", Slot.TOP),
    DRESS("dress", "Dresses", Slot.ONE_PIECE),
    JEANS("jeans", "Jeans", Slot.BOTTOM),
    TROUSERS("trousers", "Trousers", Slot.BOTTOM),
    SKIRT("skirt", "Skirts", Slot.BOTTOM),
    SHORTS("shorts", "Shorts", Slot.BOTTOM),
    TRADITIONAL("traditional", "Traditional", Slot.ONE_PIECE),
    OUTERWEAR("outerwear", "Outerwear", Slot.OUTER),
    NIGHTWEAR("nightwear", "Nightwear", Slot.NIGHT),
    SHOES("shoes", "Shoes", Slot.SHOES),
    ACCESSORY("accessory", "Accessories", Slot.ACCESSORY),
    JEWELLERY("jewellery", "Jewellery", Slot.JEWELLERY),
    BAG("bag", "Bags", Slot.BAG);

    companion object {
        fun fromApi(value: String): Category = entries.firstOrNull { it.apiName == value } ?: TOP
    }
}

enum class Pattern {
    SOLID, STRIPED, FLORAL, CHECKED, DOTTED, PRINTED;

    val apiName: String get() = name.lowercase()

    companion object {
        fun fromApi(value: String?): Pattern = entries.firstOrNull { it.apiName == value } ?: SOLID
    }
}

enum class StyleTag {
    CASUAL, FORMAL, TRADITIONAL, PARTY, SPORTY, LOUNGE, NIGHT, FESTIVE, ROMANTIC;

    val apiName: String get() = name.lowercase()
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromApi(value: String): StyleTag? = entries.firstOrNull { it.apiName == value }
    }
}

enum class Season {
    SPRING, SUMMER, AUTUMN, WINTER;

    val apiName: String get() = name.lowercase()
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromApi(value: String): Season? = entries.firstOrNull { it.apiName == value }

        fun of(date: LocalDate, southernHemisphere: Boolean = false): Season {
            val north = when (date.month) {
                Month.MARCH, Month.APRIL, Month.MAY -> SPRING
                Month.JUNE, Month.JULY, Month.AUGUST -> SUMMER
                Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER -> AUTUMN
                else -> WINTER
            }
            if (!southernHemisphere) return north
            return when (north) {
                SPRING -> AUTUMN
                SUMMER -> WINTER
                AUTUMN -> SPRING
                WINTER -> SUMMER
            }
        }
    }
}

data class WardrobeItem(
    val id: String,
    val name: String,
    val category: Category,
    val colors: List<String>,
    val pattern: Pattern = Pattern.SOLID,
    val styles: Set<StyleTag> = emptySet(),
    val seasons: Set<Season> = emptySet(),
    val warmth: Int = 2,
    val approved: Boolean = true,
) {
    val slot: Slot get() = category.slot
    val primaryColor: String get() = colors.firstOrNull() ?: "#CCCCCC"
    val secondaryColor: String get() = colors.getOrNull(1) ?: primaryColor
}

data class Outfit(
    val items: List<WardrobeItem>,
    val isSurprise: Boolean = false,
    val isDefault: Boolean = false,
    val note: String? = null,
) {
    val key: String get() = items.map { it.id }.sorted().joinToString(",")
    val baseIds: Set<String> get() = items.filter { it.slot in BASE_SLOTS }.map { it.id }.toSet()

    fun item(slot: Slot): WardrobeItem? = items.firstOrNull { it.slot == slot }

    fun summary(): String =
        items.sortedBy { it.slot.ordinal }.joinToString(", ") { it.name }
}

/** One day's worn outfit, used to avoid repetition and learn from likes. */
data class OutfitRecord(
    val date: LocalDate,
    val itemIds: List<String>,
    val liked: Boolean? = null,
)

// ---------------------------------------------------------------- Couple data

enum class OccasionKind {
    BIRTHDAY, ANNIVERSARY, CUSTOM;

    val apiName: String get() = name.lowercase()

    companion object {
        fun fromApi(value: String): OccasionKind = entries.firstOrNull { it.apiName == value } ?: CUSTOM
    }
}

enum class OccasionPerson { VIEWER, SUBJECT, BOTH }

data class Occasion(
    val id: String,
    val kind: OccasionKind,
    val title: String,
    val date: LocalDate,
    val recursYearly: Boolean = true,
    val companionMayInitiate: Boolean = true,
    val person: OccasionPerson = OccasionPerson.BOTH,
)

enum class MemoryKind {
    MOMENT, DATE, PLACE, MESSAGE, PREFERENCE;

    val apiName: String get() = name.lowercase()
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromApi(value: String): MemoryKind = entries.firstOrNull { it.apiName == value } ?: MOMENT
    }
}

data class Memory(
    val id: String,
    val kind: MemoryKind,
    val title: String,
    val body: String = "",
    val happenedOn: LocalDate? = null,
    val place: String? = null,
    val isFictional: Boolean = false,
    val recallAllowed: Boolean = true,
)
