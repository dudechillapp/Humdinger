package com.kowalski.humdinger.data

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** The web pages for the privacy policy, house rules and account deletion (the `website` folder). */
object Legal {
    const val SITE = "https://humdinger-61b7b.web.app"
    const val PRIVACY_URL = "$SITE/privacy"
    const val RULES_URL = "$SITE/rules"
    const val DELETE_URL = "$SITE/delete-account"
    const val CONTACT_EMAIL = "humdinger.application@gmail.com"
}

/**
 * The house rules everyone agrees to before joining in. If you change them in a way people
 * should agree to again, raise [VERSION] and everyone sees them once more.
 * Keep in sync with website/rules.html.
 */
object HouseRules {
    const val VERSION = 1
    const val MIN_AGE = 16

    data class Rule(val emoji: String, val title: String, val body: String)

    val rules = listOf(
        Rule("😂", "Laugh with people, not at them", "Everyone's brave for humming in public. Cheer them on. No bullying, mocking people's looks or voices, or harassment."),
        Rule("🚫", "No hate", "Nothing that attacks people for who they are: race, religion, gender, sexuality, disability, where they're from, or anything else."),
        Rule("🧼", "Keep it clean", "No nudity, sexual content, violence, drugs or anything illegal, in clips, guesses, comments, replies or messages. Comments are for cheering people on or a bit of fun, never for putting someone down."),
        Rule("🙋", "Only film yourself", "Don't record other people without their permission, and never share anyone's personal details, like phone numbers or addresses. That includes your own."),
        Rule("🎯", "Real guesses only", "Guesses are honest attempts with a link to the song. No spam, ads, self-promotion or links to anything else."),
        Rule("🎭", "Be yourself", "Don't pretend to be someone else, including the Humdinger team."),
        Rule("🎂", "$MIN_AGE and over", "You need to be at least $MIN_AGE to use Humdinger."),
    )

    const val CONSEQUENCES =
        "Anything that breaks these rules can be reported. Clips reported by several people are removed " +
            "automatically, and we can remove anything, or anyone, that breaks the rules."
}

/** Someone this person has blocked. They're stored at users/<me>/blocked/<their id>. */
data class BlockedUser(val uid: String, val name: String, val blockedAt: Timestamp?)

/**
 * Blocking: you stop seeing that person's requests, guesses and messages, and they can't
 * guess on your requests or message you (the database rules enforce that part).
 */
object Blocks {
    private val db get() = FirebaseFirestore.getInstance()
    private fun blockedRef(myUid: String) = db.collection("users").document(myUid).collection("blocked")

    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    /** Who the signed-in person has blocked, for hiding their content everywhere. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun list(myUid: String): Flow<List<BlockedUser>> =
        blockedRef(myUid)
            .orderBy("blockedAt", Query.Direction.DESCENDING)
            .liveSnapshots()
            .map { snap ->
                snap.documents.map { BlockedUser(it.id, it.getString("name") ?: "someone", it.getTimestamp("blockedAt")) }
            }

    /** Keeps [ids] up to date for the signed-in person. Runs for as long as the caller is active. */
    suspend fun keepUpdated(myUid: String) {
        _ids.value = emptySet()
        list(myUid).catch { emit(emptyList()) }.collect { list -> _ids.value = list.map { it.uid }.toSet() }
    }

    suspend fun block(myUid: String, otherUid: String, otherName: String) {
        if (otherUid.isBlank() || otherUid == myUid) return
        _ids.value = _ids.value + otherUid // hide them straight away
        blockedRef(myUid).document(otherUid).set(
            mapOf(
                "uid" to otherUid,
                "name" to otherName.take(30),
                "blockedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        // Blocking someone also ends any friendship or friend request between you.
        runCatching { Friends.removeEverything(myUid, otherUid) }
    }

    suspend fun unblock(myUid: String, otherUid: String) {
        _ids.value = _ids.value - otherUid
        blockedRef(myUid).document(otherUid).delete().await()
    }
}

/** Reports about a person (for example a rude name), for you to review in the Firebase console (userReports). */
object UserReports {
    val REASONS = listOf(
        "Rude or offensive name",
        "Pretending to be someone else",
        "Harassment or bullying",
        "Seems to be under ${HouseRules.MIN_AGE}",
        "Something else",
    )

    suspend fun report(reporterUid: String, reportedUid: String, reportedName: String, reason: String) {
        FirebaseFirestore.getInstance().collection("userReports").add(
            mapOf(
                "reportedUid" to reportedUid,
                "reportedName" to reportedName,
                "reporterId" to reporterUid,
                "reason" to reason,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }
}

/** Reports about a guess or comment, for you to review in the Firebase console (guessReports). */
object GuessReports {
    suspend fun report(requestId: String, guess: Guess, reporterUid: String, reason: String) {
        FirebaseFirestore.getInstance().collection("guessReports").add(
            mapOf(
                "requestId" to requestId,
                "guessId" to guess.id,
                "authorId" to guess.authorId,
                "authorName" to guess.authorName,
                "title" to guess.title,
                "comment" to guess.comment,
                "reporterId" to reporterUid,
                "reason" to reason,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }
}

/** Reports about a reply under a guess (also go to guessReports, with a replyId). */
object ReplyReports {
    suspend fun report(requestId: String, reply: GuessReply, reporterUid: String, reason: String) {
        FirebaseFirestore.getInstance().collection("guessReports").add(
            mapOf(
                "requestId" to requestId,
                "guessId" to reply.guessId,
                "replyId" to reply.id,
                "authorId" to reply.authorId,
                "authorName" to reply.authorName,
                "title" to "(reply)",
                "comment" to reply.text,
                "reporterId" to reporterUid,
                "reason" to reason,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }
}

/** Reports about a comment on a hum (also go to guessReports, with a commentId). */
object CommentReports {
    suspend fun report(requestId: String, comment: HumComment, reporterUid: String, reason: String) {
        FirebaseFirestore.getInstance().collection("guessReports").add(
            mapOf(
                "requestId" to requestId,
                "commentId" to comment.id,
                "authorId" to comment.authorId,
                "authorName" to comment.authorName,
                "title" to "(comment)",
                "comment" to comment.text,
                "reporterId" to reporterUid,
                "reason" to reason,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }
}

/** Deleting an account for good. The work happens on the server (deleteAccount in functions/index.js). */
object AccountDeletion {
    /**
     * True while an account is being deleted. The app shows a spinner, and doesn't give the
     * vanishing profile a new fun name in the meantime.
     */
    val inProgress = MutableStateFlow(false)

    // Not tied to a screen: the screens disappear while the account is deleted.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Deletes everything on the server, then starts afresh as a new guest. */
    fun start(context: Context) {
        if (inProgress.value) return
        val app = context.applicationContext
        scope.launch {
            inProgress.value = true
            try {
                FirebaseFunctions.getInstance("europe-west1")
                    .getHttpsCallable("deleteAccount")
                    .apply { setTimeout(5, TimeUnit.MINUTES) }
                    .call()
                    .await()
                runCatching { GoogleSignIn.clear(app) }
                FirebaseAuth.getInstance().signOut()
                Toast.makeText(app, "Your account has been deleted. Thanks for humming with us! 👋", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("Humdinger", "Account deletion failed", e)
                Toast.makeText(
                    app,
                    "Couldn't delete your account. Check your internet and try again, or email ${Legal.CONTACT_EMAIL}.",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                inProgress.value = false
            }
        }
    }
}
