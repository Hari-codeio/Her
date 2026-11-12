package app.her.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors docs/API.md. Keep field names in sync with the server.

@Serializable
data class UserDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class RegisterRequest(@SerialName("display_name") val displayName: String)

@Serializable
data class RegisterResponse(val user: UserDto, val token: String)

@Serializable
data class MeResponse(val user: UserDto, val couple: CoupleDto? = null, val partner: UserDto? = null)

@Serializable
data class DisplayNameUpdate(@SerialName("display_name") val displayName: String)

@Serializable
data class CoupleDto(
    val id: String,
    val members: List<UserDto> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("anniversary_date") val anniversaryDate: String? = null,
)

@Serializable
data class AnniversaryUpdate(@SerialName("anniversary_date") val anniversaryDate: String?)

@Serializable
data class InviteDto(val code: String, @SerialName("expires_at") val expiresAt: String)

@Serializable
data class JoinRequest(val code: String)

@Serializable
data class ConsentsDto(
    val likeness: Boolean = false,
    val voice: Boolean = false,
    val personality: Boolean = false,
    @SerialName("ai_training") val aiTraining: Boolean = false,
)

@Serializable
data class ConsentUpdate(
    val likeness: Boolean,
    val voice: Boolean,
    val personality: Boolean,
    @SerialName("ai_training") val aiTraining: Boolean,
    @SerialName("statement_version") val statementVersion: String,
)

@Serializable
data class ConsentEventDto(
    val id: String,
    @SerialName("companion_id") val companionId: String,
    val scope: String,
    val granted: Boolean,
    val statement: String = "",
    @SerialName("statement_version") val statementVersion: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AppearanceDto(
    val presentation: String = "feminine",
    @SerialName("skin_tone") val skinTone: String = "#C68E6A",
    @SerialName("hair_style") val hairStyle: String = "long_wavy",
    @SerialName("hair_color") val hairColor: String = "#2B1B12",
    @SerialName("eye_color") val eyeColor: String = "#3B2416",
    @SerialName("face_shape") val faceShape: String = "oval",
    val height: Float = 0.5f,
    val build: Float = 0.4f,
    @SerialName("facial_hair") val facialHair: String = "none",
    val glasses: Boolean = false,
    @SerialName("style_notes") val styleNotes: String = "",
)

@Serializable
data class AppearanceVersionDto(
    val id: String,
    val appearance: AppearanceDto,
    val status: String,
    @SerialName("authored_by") val authoredBy: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
)

@Serializable
data class PersonaDto(
    @SerialName("nickname_for_viewer") val nicknameForViewer: String? = null,
    @SerialName("speaking_style") val speakingStyle: String? = null,
    val languages: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    @SerialName("favorite_things") val favoriteThings: List<String> = emptyList(),
    @SerialName("pet_phrases") val petPhrases: List<String> = emptyList(),
    val boundaries: List<String> = emptyList(),
    val about: String? = null,
)

@Serializable
data class VoiceDto(val status: String = "none", val samples: Int = 0)

@Serializable
data class CompanionDto(
    val id: String,
    val subject: UserDto,
    val viewer: UserDto,
    val name: String,
    val status: String,
    val consents: ConsentsDto = ConsentsDto(),
    val appearance: AppearanceDto? = null,
    @SerialName("pending_appearance") val pendingAppearance: AppearanceVersionDto? = null,
    val persona: PersonaDto? = null,
    @SerialName("pending_persona") val pendingPersona: PersonaDto? = null,
    val voice: VoiceDto = VoiceDto(),
    @SerialName("photo_count") val photoCount: Int = 0,
) {
    val isActive: Boolean get() = status == "active"
}

@Serializable
data class CompanionNameUpdate(val name: String)

@Serializable
data class RejectRequest(val reason: String? = null)

@Serializable
data class PhotoDto(
    val id: String,
    @SerialName("uploaded_by") val uploadedBy: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("content_type") val contentType: String? = null,
)

@Serializable
data class StatusDto(val status: String = "none", val samples: Int = 0)

@Serializable
data class WardrobeItemDto(
    val id: String,
    @SerialName("companion_id") val companionId: String = "",
    val name: String,
    val category: String,
    val colors: List<String> = emptyList(),
    val pattern: String = "solid",
    val styles: List<String> = emptyList(),
    val seasons: List<String> = emptyList(),
    val warmth: Int = 2,
    val approved: Boolean = true,
    @SerialName("added_by") val addedBy: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class WardrobeItemInput(
    val name: String,
    val category: String,
    val colors: List<String>,
    val pattern: String = "solid",
    val styles: List<String> = emptyList(),
    val seasons: List<String> = emptyList(),
    val warmth: Int = 2,
)

@Serializable
data class ApproveRequest(val approved: Boolean)

@Serializable
data class MemoryDto(
    val id: String,
    @SerialName("couple_id") val coupleId: String = "",
    val kind: String = "moment",
    val title: String,
    val body: String = "",
    @SerialName("happened_on") val happenedOn: String? = null,
    val place: String? = null,
    @SerialName("has_photo") val hasPhoto: Boolean = false,
    @SerialName("is_fictional") val isFictional: Boolean = false,
    @SerialName("recall_allowed") val recallAllowed: Boolean = true,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class MemoryInput(
    val kind: String,
    val title: String,
    val body: String = "",
    @SerialName("happened_on") val happenedOn: String? = null,
    val place: String? = null,
    @SerialName("is_fictional") val isFictional: Boolean = false,
    @SerialName("recall_allowed") val recallAllowed: Boolean = true,
)

@Serializable
data class MemorySuggestionDto(
    val id: String,
    @SerialName("companion_id") val companionId: String = "",
    val text: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class OccasionDto(
    val id: String,
    @SerialName("couple_id") val coupleId: String = "",
    val kind: String = "custom",
    val title: String,
    val date: String,
    @SerialName("recurs_yearly") val recursYearly: Boolean = true,
    @SerialName("person_user_id") val personUserId: String? = null,
    @SerialName("companion_may_initiate") val companionMayInitiate: Boolean = true,
    @SerialName("created_by") val createdBy: String? = null,
)

@Serializable
data class OccasionInput(
    val kind: String,
    val title: String,
    val date: String,
    @SerialName("recurs_yearly") val recursYearly: Boolean = true,
    @SerialName("person_user_id") val personUserId: String? = null,
    @SerialName("companion_may_initiate") val companionMayInitiate: Boolean = true,
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val role: String,
    val text: String,
    val kind: String = "chat",
    val game: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ChatContextDto(
    @SerialName("local_time") val localTime: String? = null,
    @SerialName("day_part") val dayPart: String? = null,
    val mood: String? = null,
    val activity: String? = null,
    val environment: String? = null,
    @SerialName("outfit_summary") val outfitSummary: String? = null,
    val weather: String? = null,
    @SerialName("occasion_today") val occasionToday: String? = null,
    @SerialName("notification_hint") val notificationHint: String? = null,
)

@Serializable
data class ChatRequest(
    val text: String,
    val mode: String = "chat",
    val game: String? = null,
    val context: ChatContextDto = ChatContextDto(),
)

@Serializable
data class GreetingRequest(val context: ChatContextDto)

@Serializable
data class TextDto(val text: String)

@Serializable
data class SpeakRequest(val text: String)

@Serializable
data class DoneDto(
    @SerialName("user_message_id") val userMessageId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
)

@Serializable
data class ConfigDto(
    @SerialName("chat_available") val chatAvailable: Boolean = false,
    @SerialName("likeness_provider") val likenessProvider: Boolean = false,
    @SerialName("voice_provider") val voiceProvider: Boolean = false,
    @SerialName("consent_statement_version") val consentStatementVersion: String = "",
    @SerialName("consent_statements") val consentStatements: Map<String, String> = emptyMap(),
)

@Serializable
data class LikenessDto(val status: String = "none")
