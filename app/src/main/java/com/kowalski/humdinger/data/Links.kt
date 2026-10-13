package com.kowalski.humdinger.data

/**
 * Guesses must include a link to the song on a music platform, so people can check the answer
 * (and so nobody farms participation points with empty guesses).
 *
 * Supported: YouTube / YouTube Music, Spotify, Apple Music, Amazon Music, Deezer, SoundCloud,
 * TIDAL, Shazam, and song.link / album.link (links that open in whatever app you use).
 *
 * Keep this pattern in sync with isMusicLink() in firebase/firestore.rules.
 */
private val musicLink = Regex(
    "^https://(" +
        "(www[.]|m[.]|music[.])?youtube[.]com|youtu[.]be" +
        "|open[.]spotify[.]com|spotify[.]link" +
        "|music[.]apple[.]com" +
        "|music[.]amazon[.](com|co[.]uk|de|fr|it|es|ca|in|co[.]jp|com[.]au|com[.]br|com[.]mx)" +
        "|(www[.]|link[.])?deezer[.]com|deezer[.]page[.]link" +
        "|(www[.]|m[.]|on[.])?soundcloud[.]com" +
        "|(www[.]|listen[.])?tidal[.]com" +
        "|(www[.])?shazam[.]com" +
        "|song[.]link|album[.]link" +
        ")/.+$"
)

private val anyUrl = Regex("https://\\S+")

object Links {
    /** Shown in the guess box so people know which apps work. */
    const val SUPPORTED = "YouTube, Spotify, Apple Music, Amazon Music, Deezer, SoundCloud, TIDAL or Shazam"

    /** Pulls the first https link out of pasted text (share sheets often add extra words). */
    fun extract(text: String): String = anyUrl.find(text.trim())?.value ?: text.trim()

    fun isMusicLink(url: String): Boolean = url.length < 300 && musicLink.matches(url)

    /** The platform's name, for "▶ Listen on …" buttons. */
    fun label(url: String): String = when {
        "music.youtube" in url -> "YouTube Music"
        "youtube" in url || "youtu.be" in url -> "YouTube"
        "spotify" in url -> "Spotify"
        "music.apple" in url -> "Apple Music"
        "music.amazon" in url -> "Amazon Music"
        "deezer" in url -> "Deezer"
        "soundcloud" in url -> "SoundCloud"
        "tidal" in url -> "TIDAL"
        "shazam" in url -> "Shazam"
        else -> "song.link" // song.link / album.link open in whatever app you use
    }
}
