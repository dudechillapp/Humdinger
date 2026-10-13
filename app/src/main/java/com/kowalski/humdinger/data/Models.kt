package com.kowalski.humdinger.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Points rules. If you change these, change the matching numbers in firebase/firestore.rules
 * (function isAward) too, or the database will reject the award.
 */
object Points {
    /** The guesser the requester crowns as correct. */
    const val SOLVER = 20L

    /** Everyone else who posted a guess (with a link) before the song was confirmed. */
    const val PARTICIPANT = 5L

    /** Max number of other guessers rewarded per request (keeps writes small). */
    const val MAX_PARTICIPANTS = 30
}

object Limits {
    const val MAX_CLIP_SECONDS = 30
    const val MAX_HINT_CHARS = 140
    const val MAX_COMMENT_CHARS = 200
    const val MAX_REPLY_CHARS = 200
    const val MAX_HUM_COMMENT_CHARS = 200
}

/** Reaction keys stored in the database, mapped to the emoji shown on screen. */
enum class Reaction(val key: String, val emoji: String, val label: String) {
    LAUGH("laugh", "😂", "Crying"),
    FIRE("fire", "🔥", "Banger"),
    CLAP("clap", "👏", "Brave"),
    CRINGE("cringe", "😬", "Oof"),
    SPOT_ON("spot_on", "🎯", "Nailed it");

    companion object {
        fun fromKey(key: String?): Reaction? = entries.firstOrNull { it.key == key }
    }
}

data class UserProfile(
    val uid: String,
    val username: String,
    val points: Long,
    val solvedCount: Long,
    /** How long their recordings stay up before being deleted automatically. */
    val clipLifetimeDays: Int,
    /** Which version of the house rules they've agreed to (0 = not yet). */
    val rulesVersion: Int = 0,
    /** Notification on/off settings they've changed (see NotifyType). Missing = the default. */
    val notify: Map<String, Boolean> = emptyMap(),
    /** Quiet hours: notifications arrive silently between these hours (their phone's time). */
    val quietStart: Int = 22,
    val quietEnd: Int = 8,
    /** Daily summary: the hour (0-23) it's sent. */
    val digestHour: Int = 18,
    /** Privacy (see Privacy): who can send friend requests, message them and comment on their hums. */
    val friendRequestsFrom: String = Privacy.EVERYONE,
    val messagesFrom: String = Privacy.FRIENDS,
    val commentsFrom: String = Privacy.EVERYONE,
    val onLeaderboard: Boolean = true,
)

/** The choices for "Keep my recordings for". Keep in sync with firebase/firestore.rules. */
enum class ClipLifetime(val days: Int, val label: String) {
    ONE_DAY(1, "1 day"),
    THREE_DAYS(3, "3 days"),
    ONE_WEEK(7, "1 week"),
    ONE_MONTH(30, "1 month"),
    THREE_MONTHS(90, "3 months");

    companion object {
        val DEFAULT = ONE_WEEK
        fun fromDays(days: Int?) = entries.firstOrNull { it.days == days } ?: DEFAULT
    }
}

enum class MediaType(val key: String) {
    AUDIO("audio"), VIDEO("video");

    companion object {
        fun fromKey(key: String?) = if (key == VIDEO.key) VIDEO else AUDIO
    }
}

data class SongRequest(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val mediaType: MediaType,
    val mediaUrl: String,
    val hint: String,
    val isSolved: Boolean,
    val createdAt: Timestamp?,
    val guessCount: Long,
    val reactionCounts: Map<String, Long>,
    val solvedGuessId: String?,
    val solverId: String?,
    val solverName: String?,
    val solvedTitle: String?,
    val solvedArtist: String?,
    val solvedLink: String?,
    /** When the clip and everything with it is deleted automatically. */
    val expireAt: Timestamp?,
    /** When the song was named (solved requests only). */
    val solvedAt: Timestamp? = null,
) {
    val isExpired: Boolean get() = expireAt != null && expireAt <= Timestamp.now()
}

data class Guess(
    val id: String,
    val authorId: String,
    val authorName: String,
    val title: String,
    val artist: String,
    val link: String,
    /** Optional note from the guesser, e.g. "Pretty sure it's the 2004 remix!" */
    val comment: String,
    val createdAt: Timestamp?,
)

/** A comment on a hum, e.g. "Love the effort 😂". Anyone can leave one. */
data class HumComment(
    val id: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Timestamp?,
)

/** A short reply under a guess, e.g. "Same thought!" or "Not it, but so close!" */
data class GuessReply(
    val id: String,
    val guessId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Timestamp?,
)

// --- Mapping from Firestore documents (done by hand so nothing depends on reflection) ---

internal fun DocumentSnapshot.toUserProfile() = UserProfile(
    uid = id,
    username = getString("username") ?: "",
    points = getLong("points") ?: 0L,
    solvedCount = getLong("solvedCount") ?: 0L,
    clipLifetimeDays = ClipLifetime.fromDays(getLong("clipLifetimeDays")?.toInt()).days,
    rulesVersion = getLong("rulesVersion")?.toInt() ?: 0,
    notify = (get("notify") as? Map<*, *>)
        ?.mapNotNull { (k, v) -> if (k is String && v is Boolean) k to v else null }
        ?.toMap() ?: emptyMap(),
    quietStart = ((get("notify") as? Map<*, *>)?.get("quietStart") as? Number)?.toInt()?.coerceIn(0, 23) ?: 22,
    quietEnd = ((get("notify") as? Map<*, *>)?.get("quietEnd") as? Number)?.toInt()?.coerceIn(0, 23) ?: 8,
    digestHour = ((get("notify") as? Map<*, *>)?.get("digestHour") as? Number)?.toInt()?.coerceIn(0, 23) ?: 18,
    friendRequestsFrom = (get("privacy") as? Map<*, *>)?.get(Privacy.FRIEND_REQUESTS) as? String ?: Privacy.EVERYONE,
    messagesFrom = (get("privacy") as? Map<*, *>)?.get(Privacy.MESSAGES) as? String ?: Privacy.FRIENDS,
    commentsFrom = (get("privacy") as? Map<*, *>)?.get(Privacy.COMMENTS) as? String ?: Privacy.EVERYONE,
    onLeaderboard = (get("privacy") as? Map<*, *>)?.get(Privacy.LEADERBOARD) as? Boolean ?: true,
)

internal fun DocumentSnapshot.toSongRequest(): SongRequest {
    @Suppress("UNCHECKED_CAST")
    val rawCounts = get("reactionCounts") as? Map<String, Any?> ?: emptyMap()
    return SongRequest(
        id = id,
        ownerId = getString("ownerId") ?: "",
        ownerName = getString("ownerName") ?: "someone",
        mediaType = MediaType.fromKey(getString("mediaType")),
        mediaUrl = getString("mediaUrl") ?: "",
        hint = getString("hint") ?: "",
        isSolved = getString("status") == "solved",
        createdAt = getTimestamp("createdAt"),
        guessCount = getLong("guessCount") ?: 0L,
        reactionCounts = rawCounts.mapValues { (it.value as? Number)?.toLong() ?: 0L },
        solvedGuessId = getString("solvedGuessId"),
        solverId = getString("solverId"),
        solverName = getString("solverName"),
        solvedTitle = getString("solvedTitle"),
        solvedArtist = getString("solvedArtist"),
        solvedLink = getString("solvedLink"),
        expireAt = getTimestamp("expireAt"),
        solvedAt = getTimestamp("solvedAt"),
    )
}

internal fun DocumentSnapshot.toGuess() = Guess(
    id = id,
    authorId = getString("authorId") ?: "",
    authorName = getString("authorName") ?: "someone",
    title = getString("title") ?: "",
    artist = getString("artist") ?: "",
    link = getString("link") ?: "",
    comment = getString("comment") ?: "",
    createdAt = getTimestamp("createdAt"),
)

internal fun DocumentSnapshot.toGuessReply() = GuessReply(
    id = id,
    guessId = getString("guessId") ?: "",
    authorId = getString("authorId") ?: "",
    authorName = getString("authorName") ?: "someone",
    text = getString("text") ?: "",
    createdAt = getTimestamp("createdAt"),
)

internal fun DocumentSnapshot.toHumComment() = HumComment(
    id = id,
    authorId = getString("authorId") ?: "",
    authorName = getString("authorName") ?: "someone",
    text = getString("text") ?: "",
    createdAt = getTimestamp("createdAt"),
)
