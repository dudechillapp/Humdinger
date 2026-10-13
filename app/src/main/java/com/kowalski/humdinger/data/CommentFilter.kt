package com.kowalski.humdinger.data

/**
 * A light touch for comments: everyday swearing ("that's a f***ing banger") is fine,
 * but slurs, hate, really explicit sexual words and "kill yourself"-type bullying are stopped.
 * Anything else that slips through can be reported.
 */
object CommentFilter {

    /** Blocked inside any single word, even glued to other words ("ultrawordhere"). */
    private val bannedInsideWord = listOf(
        // Slurs and hate
        "nigg", "faggot", "fagot", "tranny", "retard", "paedo", "pedophile",
        "neger", "kanake", "schwuchtel", "missgeburt", "untermensch",
        // Very explicit
        "porn", "blowjob", "handjob", "dildo", "cumshot", "pussy",
    )

    /** Blocked as phrases, even with spaces or dots in between ("kill  your.self"). */
    private val bannedPhrases = listOf(
        "killyourself", "killurself", "hangyourself", "neckyourself", "bringdichum",
        "heilhitler", "siegheil", "whitepower",
    )

    /** Blocked only as a whole word (so "Scunthorpe" or "cocktail" are fine). */
    private val bannedWords = setOf(
        "cunt", "cunts", "kys", "fag", "fags", "spic", "chink", "kike", "coon", "paki",
        "rape", "rapist", "nonce", "pedo", "nudes", "fotze", "hure", "nutte",
    )

    private val numberSwaps = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's',
        '7' to 't', '8' to 'b', '@' to 'a', '$' to 's', '!' to 'i',
    )

    private fun normalise(text: String): String =
        text.lowercase().map { numberSwaps[it] ?: it }.joinToString("")

    /** Squashes "k i l l   y o u r s e l f" and "fuuuck"-style stretching. */
    private fun squash(text: String): String =
        normalise(text).filter { it.isLetter() }.replace(Regex("(.)\\1{2,}"), "$1")

    fun isAllowed(text: String): Boolean {
        if (bannedPhrases.any { squash(text).contains(it) }) return false
        val words = normalise(text).split(Regex("[^\\p{L}]+"))
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("(.)\\1{2,}"), "$1") }
        if (words.any { word -> bannedInsideWord.any { word.contains(it) } }) return false
        return words.none { it in bannedWords }
    }
}
