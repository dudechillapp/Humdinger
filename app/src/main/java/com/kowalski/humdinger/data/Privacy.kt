package com.kowalski.humdinger.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Privacy settings, saved on the profile as privacy.<key>. The database rules enforce them
 * (firestore.rules: friendRequests, comments, thanks/messages), so nobody can get round them.
 */
object Privacy {
    const val FRIEND_REQUESTS = "friendRequests"
    const val MESSAGES = "messages"
    const val COMMENTS = "comments"
    const val LEADERBOARD = "leaderboard"

    const val EVERYONE = "everyone"
    const val FRIENDS = "friends"
    const val NOBODY = "nobody"

    /** Choices shown in Settings for each setting: (value, label). */
    val friendRequestChoices = listOf(EVERYONE to "Everyone", NOBODY to "Nobody")
    val messageChoices = listOf(FRIENDS to "Friends", NOBODY to "Nobody")
    val commentChoices = listOf(EVERYONE to "Everyone", FRIENDS to "Friends", NOBODY to "Nobody")

    suspend fun set(uid: String, key: String, value: Any) {
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .update("privacy.$key", value)
            .await()
    }

    /** Whether [me] may comment on a hum by [owner]. */
    fun canComment(owner: UserProfile?, me: String, areFriends: Boolean): Boolean = when {
        owner == null || owner.uid == me -> true
        owner.commentsFrom == NOBODY -> false
        owner.commentsFrom == FRIENDS -> areFriends
        else -> true
    }
}
