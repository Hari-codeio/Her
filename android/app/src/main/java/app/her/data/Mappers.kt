package app.her.data

import app.her.core.model.Category
import app.her.core.model.Memory
import app.her.core.model.MemoryKind
import app.her.core.model.Occasion
import app.her.core.model.OccasionKind
import app.her.core.model.OccasionPerson
import app.her.core.model.Pattern
import app.her.core.model.Season
import app.her.core.model.StyleTag
import app.her.core.model.WardrobeItem
import app.her.data.remote.MemoryDto
import app.her.data.remote.OccasionDto
import app.her.data.remote.WardrobeItemDto
import app.her.data.remote.WardrobeItemInput
import java.time.LocalDate

fun WardrobeItemDto.toCore(): WardrobeItem = WardrobeItem(
    id = id,
    name = name,
    category = Category.fromApi(category),
    colors = colors.ifEmpty { listOf("#CCCCCC") },
    pattern = Pattern.fromApi(pattern),
    styles = styles.mapNotNull { StyleTag.fromApi(it) }.toSet(),
    seasons = seasons.mapNotNull { Season.fromApi(it) }.toSet(),
    warmth = warmth.coerceIn(1, 5),
    approved = approved,
)

fun WardrobeItem.toInput(): WardrobeItemInput = WardrobeItemInput(
    name = name,
    category = category.apiName,
    colors = colors,
    pattern = pattern.apiName,
    styles = styles.map { it.apiName },
    seasons = seasons.map { it.apiName },
    warmth = warmth,
)

fun MemoryDto.toCore(): Memory = Memory(
    id = id,
    kind = MemoryKind.fromApi(kind),
    title = title,
    body = body,
    happenedOn = happenedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    place = place,
    isFictional = isFictional,
    recallAllowed = recallAllowed,
)

fun OccasionDto.toCore(viewerId: String?, subjectId: String?): Occasion? {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
    return Occasion(
        id = id,
        kind = OccasionKind.fromApi(kind),
        title = title,
        date = parsed,
        recursYearly = recursYearly,
        companionMayInitiate = companionMayInitiate,
        person = when (personUserId) {
            null -> OccasionPerson.BOTH
            viewerId -> OccasionPerson.VIEWER
            subjectId -> OccasionPerson.SUBJECT
            else -> OccasionPerson.BOTH
        },
    )
}
