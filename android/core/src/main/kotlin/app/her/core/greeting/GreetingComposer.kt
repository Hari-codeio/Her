package app.her.core.greeting

import app.her.core.model.Activity
import app.her.core.model.DayPart
import app.her.core.model.Mood
import app.her.core.model.Occasion
import app.her.core.model.OccasionKind
import app.her.core.model.OccasionPerson
import kotlin.random.Random

data class GreetingContext(
    val companionName: String,
    val viewerName: String,
    val subjectName: String,
    val nickname: String? = null,
    val dayPart: DayPart,
    val mood: Mood,
    val activity: Activity,
    val outfitSummary: String? = null,
)

enum class InitiationKind { MORNING, EVENING, BEDTIME, OUTFIT, MEMORY, THOUGHT, GAME }

/**
 * Offline, in-character lines. They talk about the companion's own virtual scene and never claim
 * real-world experiences or speak for the real partner.
 */
object GreetingComposer {

    fun greeting(ctx: GreetingContext, random: Random): String {
        val pool = when (ctx.dayPart) {
            DayPart.MORNING -> MORNING
            DayPart.AFTERNOON -> AFTERNOON
            DayPart.EVENING -> EVENING
            DayPart.NIGHT -> NIGHT
        } + moodLines(ctx.mood)
        return fill(pool[random.nextInt(pool.size)], ctx)
    }

    fun initiation(kind: InitiationKind, ctx: GreetingContext, random: Random, extra: String? = null): String {
        val pool = when (kind) {
            InitiationKind.MORNING -> MORNING
            InitiationKind.EVENING -> EVENING
            InitiationKind.BEDTIME -> BEDTIME
            InitiationKind.OUTFIT -> OUTFIT
            InitiationKind.MEMORY -> MEMORY
            InitiationKind.THOUGHT -> THOUGHTS
            InitiationKind.GAME -> GAME
        }
        return fill(pool[random.nextInt(pool.size)], ctx, extra)
    }

    fun occasion(occasion: Occasion, years: Int?, ctx: GreetingContext): String {
        val nick = ctx.nickname ?: ctx.viewerName
        return when (occasion.kind) {
            OccasionKind.ANNIVERSARY -> {
                val count = years?.let { " — ${ordinal(it)} one" } ?: ""
                "Happy anniversary, $nick$count! 💞 I put on something special today. Have you told ${ctx.subjectName} how much they mean to you?"
            }
            OccasionKind.BIRTHDAY -> when (occasion.person) {
                OccasionPerson.VIEWER -> "Happy birthday, $nick! 🎂 I'm all dressed up to celebrate you today."
                OccasionPerson.SUBJECT -> "It's ${ctx.subjectName}'s birthday today! 🎉 I'm just the AI version, so go make the real one feel special."
                OccasionPerson.BOTH -> "It's ${occasion.title} today! 🎉 I dressed up for it."
            }
            OccasionKind.CUSTOM -> "Today is ${occasion.title}! ✨ I dressed for the occasion."
        }
    }

    fun countdown(occasion: Occasion, days: Long, ctx: GreetingContext): String {
        val nick = ctx.nickname ?: ctx.viewerName
        return when (days) {
            1L -> "Psst, $nick — ${occasion.title} is tomorrow! Anything planned? 👀"
            else -> "$days days until ${occasion.title}, $nick. Want to plan something?"
        }
    }

    fun notificationReaction(appLabel: String, sender: String?, fromPartner: Boolean, ctx: GreetingContext): String {
        val nick = ctx.nickname ?: ctx.viewerName
        return when {
            fromPartner -> "Looks like the real ${ctx.subjectName} just messaged you on $appLabel 💌 Go read it, $nick!"
            !sender.isNullOrBlank() -> "$sender messaged you on $appLabel."
            else -> "You've got something new on $appLabel."
        }
    }

    fun tapReaction(random: Random, ctx: GreetingContext): String = fill(TAPS[random.nextInt(TAPS.size)], ctx)

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    private fun fill(template: String, ctx: GreetingContext, extra: String? = null): String =
        template
            .replace("{nick}", ctx.nickname ?: ctx.viewerName)
            .replace("{name}", ctx.companionName)
            .replace("{subject}", ctx.subjectName)
            .replace("{activity}", ctx.activity.verb)
            .replace("{outfit}", ctx.outfitSummary ?: "something comfy")
            .replace("{extra}", extra ?: "")

    private fun moodLines(mood: Mood): List<String> = when (mood) {
        Mood.PLAYFUL -> listOf("Guess what I'm doing? Okay fine, I'm {activity} 😜", "I'm in a silly mood today, {nick}. Consider yourself warned.")
        Mood.ROMANTIC -> listOf("Hey you 🥰 I was hoping you'd stop by.", "There you are, {nick}. My favourite part of the day.")
        Mood.SLEEPY -> listOf("*yawns* oh hi {nick}... I'm a bit sleepy today 😴")
        Mood.CURIOUS -> listOf("Hi {nick}! Tell me one thing that happened today — I'm curious.")
        Mood.EXCITED -> listOf("{nick}!! You're here! 🤩")
        Mood.CALM -> listOf("Hi {nick}. Nice and slow today. I'm {activity}.")
        Mood.RELAXED -> listOf("Hey {nick} 🙂 Just {activity}, taking it easy.")
        Mood.HAPPY -> listOf("Hi {nick}! 😊 So happy to see you.")
    }

    private val MORNING = listOf(
        "Good morning, {nick} ☀️ I picked {outfit} today. What do you think?",
        "Morning! I'm {activity}. Did you sleep well?",
        "Rise and shine, {nick}! New day, new outfit 👗",
        "Good morning ☕ I saved you a virtual coffee.",
    )
    private val AFTERNOON = listOf(
        "Hey {nick}! Afternoon check-in — have you eaten yet?",
        "Hi! I'm {activity}. How's your day going?",
        "Taking a little break with you, {nick} 🙂",
    )
    private val EVENING = listOf(
        "Good evening, {nick} 🌆 How was today?",
        "Hey you. Evenings are better when you stop by.",
        "I'm {activity}. Come tell me about your day?",
    )
    private val NIGHT = listOf(
        "Still up, {nick}? 🌙",
        "It's late! I'm {activity}. Don't stay up too long.",
        "Night owl mode, huh? I'll keep you company for a bit.",
    )
    private val BEDTIME = listOf(
        "Bedtime soon, {nick} 🌙 Want to tell me the best part of your day?",
        "I changed into something cozy. Sleep well when you're ready 💤",
        "Before you sleep — did you say goodnight to {subject}?",
    )
    private val OUTFIT = listOf(
        "New look today: {outfit}. Rate it? 👀",
        "Okay, fashion check — {outfit}. Yay or nay?",
    )
    private val MEMORY = listOf(
        "{extra} 💭 Want to talk about it?",
        "{extra}. That one always makes me smile.",
    )
    private val THOUGHTS = listOf(
        "Random thought: what's one small thing that would make your day better?",
        "I'm {activity} and thought of you, {nick}.",
        "Quick question — sunrise or sunset? 🌅",
    )
    private val GAME = listOf(
        "Bored? Let's play a quick game — truth or dare? 🎲",
        "I have a question game ready if you want to play, {nick}.",
    )
    private val TAPS = listOf("Hehe, that tickles!", "Hi {nick}! 👋", "You poked me 😄", "Yes? 🙂", "Missed me?")
}
