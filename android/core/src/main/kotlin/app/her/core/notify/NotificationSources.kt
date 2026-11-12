package app.her.core.notify

/** Messaging apps whose notifications the user can choose to surface under the floating companion. */
enum class NotificationSource(val label: String, val packages: Set<String>) {
    WHATSAPP("WhatsApp", setOf("com.whatsapp", "com.whatsapp.w4b")),
    INSTAGRAM("Instagram", setOf("com.instagram.android")),
    MESSENGER("Messenger", setOf("com.facebook.orca")),
    TELEGRAM("Telegram", setOf("org.telegram.messenger")),
    SIGNAL("Signal", setOf("org.thoughtcrime.securesms")),
    SNAPCHAT("Snapchat", setOf("com.snapchat.android")),
    MESSAGES("Messages", setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"));

    companion object {
        fun forPackage(pkg: String): NotificationSource? = entries.firstOrNull { pkg in it.packages }
        val DEFAULT_ENABLED = setOf(WHATSAPP, INSTAGRAM)
    }
}

object PartnerMatcher {
    /**
     * True when a notification title looks like it came from the partner. Titles are usually the
     * sender or chat name ("Maya", "Maya ❤️", "Maya (2 messages)"), so match whole words.
     */
    fun isPartner(title: String?, partnerNames: Collection<String>): Boolean {
        if (title.isNullOrBlank()) return false
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.toSet()
        val titleDigits = title.filter { it.isDigit() }
        return partnerNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { name ->
            // Phone numbers: "+91 98450 12345" should match "98450-12345" however either is formatted.
            val nameDigits = name.filter { it.isDigit() }
            if (nameDigits.length >= 6 && name.none { it.isLetter() }) {
                return@any titleDigits.length >= 6 && (titleDigits.endsWith(nameDigits) || nameDigits.endsWith(titleDigits))
            }
            val parts = name.split(Regex("\\s+"))
            parts.all { it in words }
        }
    }
}
