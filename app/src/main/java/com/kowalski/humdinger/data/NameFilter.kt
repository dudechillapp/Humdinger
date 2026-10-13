package com.kowalski.humdinger.data

/**
 * Keeps names friendly. Checks typed names against a ban list, and sees through the usual
 * tricks: capitals, spaces, repeated letters ("fuuuck") and number swaps ("sh1t", "@ss").
 *
 * Add words to either list whenever you spot something that slips through.
 * A few of the worst words are also blocked in firebase/firestore.rules as a backstop.
 */
object NameFilter {

    /** Blocked anywhere inside a name, even glued to other words. */
    private val bannedAnywhere = listOf(
        // English
        "fuck", "fuk", "fck", "shit", "cunt", "nigg", "faggot", "fagot", "bitch", "whore",
        "slut", "porn", "penis", "vagina", "pussy", "wank", "twat", "bollock", "bellend",
        "jizz", "retard", "paedo", "nonce", "hitler", "nazi", "kkk", "dildo", "boner",
        "blowjob", "handjob", "bastard", "motherf", "dickhead",
        // German
        "scheiss", "scheiß", "fotze", "hurensohn", "wichser", "arschloch", "schlampe",
        "nutte", "ficken", "fickt", "schwuchtel", "missgeburt", "neger", "spasti", "kanake",
        // Pretending to be staff
        "humdinger", "moderator", "official",
    )

    /** Blocked only as a whole word (so "Grape", "Cocktail" or "Classic" are still fine). */
    private val bannedWords = setOf(
        "ass", "arse", "dick", "cock", "cocks", "cum", "tit", "tits", "piss", "sex", "sexy",
        "rape", "rapist", "pedo", "fag", "fags", "homo", "spic", "chink", "kike", "coon",
        "paki", "hure", "spast", "anal", "shag", "fick", "admin", "mod", "staff", "support", "nude", "nudes", "hoe",
    )

    private val numberSwaps = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's',
        '7' to 't', '8' to 'b', '@' to 'a', '$' to 's', '!' to 'i',
    )

    private fun normalise(text: String): String =
        text.lowercase().map { numberSwaps[it] ?: it }.joinToString("")

    private fun squashRepeats(text: String): String = text.replace(Regex("(.)\\1+"), "$1")

    /** True if the name is OK to use. */
    fun isAllowed(name: String): Boolean {
        val normal = normalise(name)
        val lettersOnly = normal.filter { it.isLetter() }
        val squashed = squashRepeats(lettersOnly)

        val anywhereHit = bannedAnywhere.any { bad ->
            val badSquashed = squashRepeats(bad)
            // The "fuuuck" check only for longer words, so "kkk" doesn't end up banning every "k".
            lettersOnly.contains(bad) || (badSquashed.length >= 4 && squashed.contains(badSquashed))
        }
        if (anywhereHit) return false

        val words = normal.split(Regex("[^a-zß]+")).filter { it.isNotEmpty() }
        val wordHit = words.any { it in bannedWords || squashRepeats(it) in bannedWords } ||
            lettersOnly in bannedWords // e.g. "a s s"
        return !wordHit
    }
}
