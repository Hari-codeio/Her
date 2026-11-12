package app.her.core.games

import kotlin.random.Random

enum class Game(val apiName: String, val title: String, val emoji: String, val description: String, val opener: String) {
    TRUTH_OR_DARE("truth_or_dare", "Truth or dare", "🎲", "Wholesome truths and dares you can do from your phone.", "Let's play truth or dare!"),
    GUESSING("guessing", "Guess what", "🔮", "Your companion thinks of something; you guess with yes/no questions.", "Let's play a guessing game. You think of something first?"),
    QUIZ("quiz", "How well do you know us?", "💞", "A quiz built from your saved memories.", "Quiz me about us!"),
    RANDOM_QUESTIONS("random_questions", "Random questions", "❓", "Deep, silly and everything in between.", "Ask me a random question."),
    WOULD_YOU_RATHER("would_you_rather", "Would you rather", "⚖️", "Impossible choices, playful debates.", "Let's play would you rather!"),
    STORY("story", "Story time", "📖", "Co-write a clearly fictional story together.", "Let's write a story together."),
    VIRTUAL_DATE("virtual_date", "Virtual date", "🕯️", "A cozy, imagined date in your companion's scene.", "Take me on a virtual date."),
}

/** Offline cards so games work even without a connection. */
object GameDecks {
    val truths = listOf(
        "What's a small habit of mine you secretly love?",
        "What was your first impression of us?",
        "What's a song that reminds you of us?",
        "What's something you've never told anyone you're proud of?",
        "Which memory of ours would you relive tomorrow?",
        "What's the silliest thing that made you smile this week?",
        "What's one dream trip you want us to take?",
        "What do you do when nobody's watching?",
        "What's a food you pretend to like?",
        "What's the most romantic thing you've ever imagined doing?",
        "When did you last laugh so hard you cried?",
        "What's a goal you're quietly working towards?",
    )
    val dares = listOf(
        "Send your partner a voice note singing one line of your favourite song.",
        "Change your wallpaper to a photo of you two for a day.",
        "Text your partner three emojis that describe your day.",
        "Do 10 jumping jacks right now 😄",
        "Write a two-line poem about your partner.",
        "Send your partner the oldest photo of you two in your gallery.",
        "Talk in an accent for the next three messages.",
        "Plan a 5-minute surprise for your partner this week.",
        "Take a selfie with your goofiest face (you don't have to share it!).",
        "Tell your partner one thing you appreciate about them today.",
    )
    val questions = listOf(
        "If we could live anywhere for a year, where would it be?",
        "What's your comfort movie?",
        "Mountains or beaches?",
        "What's a skill you'd learn instantly if you could?",
        "What's the best meal you've ever had?",
        "What does a perfect lazy Sunday look like?",
        "Which fictional world would you live in?",
        "What's a small thing that instantly improves your mood?",
        "What's your earliest happy memory?",
        "If you could have dinner with anyone, who?",
        "What's something you want to do together this year?",
        "Morning person or night owl — and why?",
    )
    val wouldYouRather = listOf(
        "Would you rather have breakfast in bed or a midnight picnic?",
        "Would you rather travel to the past or the future?",
        "Would you rather only whisper or only shout for a day?",
        "Would you rather have a house by the sea or in the mountains?",
        "Would you rather be able to fly or be invisible?",
        "Would you rather cook together or dance together?",
        "Would you rather know every language or play every instrument?",
        "Would you rather live without music or without movies?",
    )

    fun draw(deck: List<String>, random: Random): String = deck[random.nextInt(deck.size)]
}
