package com.kowalski.humdinger.data

/**
 * Recognition tiers, earned with points (naming songs and having a go).
 * Tweak names, emoji, thresholds or messages freely — they only live in the app.
 * Each tier's avatar (a smiley with headphones that gets cooler kit as you climb) is
 * res/drawable/avatar_tier_N.xml, drawn by tools/avatar_generator.py.
 */
data class Tier(
    val minPoints: Long,
    /** Badge shown next to names. */
    val emoji: String,
    val name: String,
    /** What the level-up celebration shouts. */
    val shout: String,
    /** Emoji burst in the level-up celebration. */
    val celebration: String,
    /** Shown in the level-up celebration. */
    val levelUpMessage: String,
)

object Tiers {
    val all = listOf(
        Tier(0, "🎧", "Fresh Ears", "WELCOME!", "",
            "Welcome to the crowd. Every music boffin started right here."),
        Tier(20, "🎵", "Toe Tapper", "LEVEL UP!", "🎵👏",
            "You've started naming tunes! The crowd's already tapping along."),
        Tier(60, "🎸", "Air Guitarist", "LEVEL UP!", "🎸🤘🔥",
            "You're getting seriously good at this. Keep shredding!"),
        Tier(150, "🎤", "Karaoke Hero", "HUGE LEVEL UP!", "🎤🔥🙌🔥",
            "You really know your stuff! People are lucky to have you around."),
        Tier(300, "📻", "Radio Buff", "HUGE LEVEL UP!", "📻⚡🎉⚡📻",
            "You've heard it all. The feed is in safe hands with you."),
        Tier(600, "💿", "Record Collector", "EPIC LEVEL UP!!", "💿✨🏆✨💿",
            "A walking music library. Nothing gets past those ears."),
        Tier(1000, "🎼", "Music Boffin", "EPIC LEVEL UP!!", "🧙🎼🔥🚀🔥",
            "An actual music boffin! Hummers everywhere salute you."),
        Tier(2000, "👑", "Humdinger Legend", "LEGENDARY!!!", "👑🌟🐐🌟👑",
            "The GOAT. Legend status. They'll be humming about you for years."),
    )

    fun indexFor(points: Long): Int = all.indexOfLast { points >= it.minPoints }.coerceAtLeast(0)

    fun forPoints(points: Long): Tier = all[indexFor(points)]

    /** The next tier to aim for, or null at the very top. */
    fun next(points: Long): Tier? = all.getOrNull(indexFor(points) + 1)
}
