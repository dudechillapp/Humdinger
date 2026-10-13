package com.kowalski.humdinger.data

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Date

class AlreadySolvedException : Exception("This one's already been solved!")

object RequestRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private val storage get() = FirebaseStorage.getInstance()
    private val requests get() = db.collection("requests")

    // ---------- Reading ----------

    /**
     * Open requests newest first; solved ones most recently solved first.
     * Needs the composite indexes (status, createdAt) and (status, solvedAt) — firestore.indexes.json.
     */
    fun feed(solved: Boolean): Flow<List<SongRequest>> =
        requests
            .whereEqualTo("status", if (solved) "solved" else "open")
            .orderBy(if (solved) "solvedAt" else "createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toSongRequest() }.filterNot { it.isExpired } }

    /** Needs the composite index (ownerId ASC, createdAt DESC) — see SETUP.md. */
    fun byOwner(uid: String): Flow<List<SongRequest>> =
        requests
            .whereEqualTo("ownerId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toSongRequest() }.filterNot { it.isExpired } }

    fun request(id: String): Flow<SongRequest?> =
        requests.document(id).liveSnapshots()
            .map { if (it.exists()) it.toSongRequest().takeUnless { r -> r.isExpired } else null }

    fun guesses(requestId: String): Flow<List<Guess>> =
        requests.document(requestId).collection("guesses")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toGuess() } }

    /** All replies on a request's guesses, oldest first (the screen groups them under each guess). */
    fun replies(requestId: String): Flow<List<GuessReply>> =
        requests.document(requestId).collection("replies")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(500)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toGuessReply() } }

    /** Comments on a hum, oldest first. */
    fun comments(requestId: String): Flow<List<HumComment>> =
        requests.document(requestId).collection("comments")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(300)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toHumComment() } }

    /** Anyone can comment on a hum (the database rules stop it if the hummer has blocked you). */
    suspend fun addComment(requestId: String, me: UserProfile, text: String) {
        requests.document(requestId).collection("comments").add(
            mapOf(
                "authorId" to me.uid,
                "authorName" to me.username,
                "text" to text.trim().take(Limits.MAX_HUM_COMMENT_CHARS),
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /** Your own comments, or any comment on your own hum. */
    suspend fun deleteComment(requestId: String, commentId: String) {
        requests.document(requestId).collection("comments").document(commentId).delete().await()
    }

    fun myReaction(requestId: String, uid: String): Flow<Reaction?> =
        requests.document(requestId).collection("reactions").document(uid).liveSnapshots()
            .map { Reaction.fromKey(it.getString("reaction")) }

    // ---------- Writing ----------

    /** Uploads the clip, then creates the request. Returns the new request id. */
    suspend fun createRequest(
        me: UserProfile,
        clip: File,
        mediaType: MediaType,
        hint: String,
        /** How long this clip stays up. Defaults to their "Keep my recordings for" setting. */
        lifetimeDays: Int = me.clipLifetimeDays,
    ): String {
        val ref = requests.document()
        val (ext, contentType) = when (mediaType) {
            MediaType.AUDIO -> "m4a" to "audio/mp4"
            MediaType.VIDEO -> "mp4" to "video/mp4"
        }
        // Each clip gets its own folder, so the server can tidy up everything for a request at once.
        val clipRef = storage.reference.child("clips/${me.uid}/${ref.id}/clip.$ext")
        val metadata = StorageMetadata.Builder().setContentType(contentType).build()
        clipRef.putFile(Uri.fromFile(clip), metadata).await()
        val url = clipRef.downloadUrl.await().toString()

        ref.set(
            mapOf(
                "ownerId" to me.uid,
                "ownerName" to me.username,
                "mediaType" to mediaType.key,
                "mediaUrl" to url,
                "hint" to hint.trim().take(Limits.MAX_HINT_CHARS),
                "status" to "open",
                "guessCount" to 0L,
                "reactionCounts" to emptyMap<String, Long>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "expireAt" to expiryFrom(Timestamp.now(), lifetimeDays),
                "lifetimeDays" to lifetimeDays,
                // Picked a different time from their usual setting for this one clip, so a later
                // change on the Me tab leaves it alone.
                "customLifetime" to (lifetimeDays != me.clipLifetimeDays),
            )
        ).await()
        return ref.id
    }

    suspend fun addGuess(
        requestId: String,
        me: UserProfile,
        title: String,
        artist: String,
        link: String,
        comment: String,
    ) {
        val reqRef = requests.document(requestId)
        val guessRef = reqRef.collection("guesses").document()
        db.batch()
            .set(
                guessRef,
                mapOf(
                    "authorId" to me.uid,
                    "authorName" to me.username,
                    "title" to title.trim().take(100),
                    "artist" to artist.trim().take(100),
                    "link" to link.trim(),
                    "comment" to comment.trim().take(Limits.MAX_COMMENT_CHARS),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            )
            .update(reqRef, "guessCount", FieldValue.increment(1))
            .commit()
            .await()
    }

    /**
     * Replies under a guess. The guesser (and the requester) get a notification.
     * The database rules stop it if either of them has blocked you.
     */
    suspend fun addReply(requestId: String, guess: Guess, me: UserProfile, text: String) {
        requests.document(requestId).collection("replies").add(
            mapOf(
                "guessId" to guess.id,
                "guessAuthorId" to guess.authorId,
                "authorId" to me.uid,
                "authorName" to me.username,
                "text" to text.trim().take(Limits.MAX_REPLY_CHARS),
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /** You can take back your own replies. */
    suspend fun deleteReply(requestId: String, replyId: String) {
        requests.document(requestId).collection("replies").document(replyId).delete().await()
    }

    /** Tapping your current reaction removes it; tapping another one switches to it. */
    suspend fun toggleReaction(requestId: String, uid: String, tapped: Reaction) {
        val reqRef = requests.document(requestId)
        val reactionRef = reqRef.collection("reactions").document(uid)
        db.runTransaction { tx ->
            val old = Reaction.fromKey(tx.get(reactionRef).getString("reaction"))
            val new = if (old == tapped) null else tapped

            val updates = mutableMapOf<String, Any>()
            if (old != null) updates["reactionCounts.${old.key}"] = FieldValue.increment(-1)
            if (new != null) updates["reactionCounts.${new.key}"] = FieldValue.increment(1)
            if (updates.isNotEmpty()) tx.update(reqRef, updates)

            if (new == null) tx.delete(reactionRef) else tx.set(reactionRef, mapOf("reaction" to new.key, "uid" to uid))
            null
        }.await()
    }

    /**
     * The requester crowns a guess. In one atomic transaction:
     *  - the request is marked solved,
     *  - the winning guesser gets [Points.SOLVER] points,
     *  - everyone else who guessed before now gets [Points.PARTICIPANT] points.
     * The database rules check every part of this, so points can't be faked from the app.
     */
    suspend fun confirmGuess(request: SongRequest, winner: Guess, myUid: String) {
        val reqRef = requests.document(request.id)
        val allGuesses = reqRef.collection("guesses").get().await().documents.map { it.toGuess() }
        val others = allGuesses
            .map { it.authorId }
            .filter { it != winner.authorId && it != myUid && it.isNotEmpty() }
            .distinct()
            .take(Points.MAX_PARTICIPANTS)

        try {
            db.runTransaction { tx ->
                val snap = tx.get(reqRef)
                if (snap.getString("status") != "open") throw AlreadySolvedException()

                tx.update(
                    reqRef,
                    mapOf(
                        "status" to "solved",
                        "solvedGuessId" to winner.id,
                        "solverId" to winner.authorId,
                        "solverName" to winner.authorName,
                        "solvedTitle" to winner.title,
                        "solvedArtist" to winner.artist,
                        "solvedLink" to winner.link,
                        "solvedAt" to FieldValue.serverTimestamp(),
                        "rewardedIds" to others,
                    )
                )
                // A permanent record on the solver's account: what they named, their answer and comment.
                tx.set(
                    db.collection("users").document(winner.authorId).collection("solved").document(request.id),
                    mapOf(
                        "requesterId" to myUid,
                        "requesterName" to request.ownerName,
                        "songTitle" to winner.title,
                        "songArtist" to winner.artist,
                        "link" to winner.link,
                        "comment" to winner.comment,
                        "hint" to request.hint,
                        "solvedAt" to FieldValue.serverTimestamp(),
                    )
                )
                tx.update(
                    db.collection("users").document(winner.authorId),
                    mapOf(
                        "points" to FieldValue.increment(Points.SOLVER),
                        "solvedCount" to FieldValue.increment(1),
                        "lastAwardFrom" to request.id,
                    )
                )
                others.forEach { uid ->
                    tx.update(
                        db.collection("users").document(uid),
                        mapOf(
                            "points" to FieldValue.increment(Points.PARTICIPANT),
                            "lastAwardFrom" to request.id,
                        )
                    )
                }
                null
            }.await()
        } catch (e: Exception) {
            if (e is AlreadySolvedException || e.cause is AlreadySolvedException) throw AlreadySolvedException()
            throw e
        }
    }

    /**
     * One report per person per request. When enough different people report the same
     * request, the server deletes it automatically (see functions/index.js).
     * Returns false if this person had already reported it.
     */
    suspend fun report(requestId: String, reporterUid: String, reason: String): Boolean {
        val reportRef = requests.document(requestId).collection("reports").document(reporterUid)
        return try {
            reportRef.set(mapOf("reason" to reason, "uid" to reporterUid, "createdAt" to FieldValue.serverTimestamp())).await()
            true
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else throw e
        }
    }

    /** The owner deletes their request now: it disappears straight away and the server clears it up. */
    suspend fun deleteNow(requestId: String) {
        requests.document(requestId).update("expireAt", FieldValue.serverTimestamp()).await()
    }

    /**
     * Applies a new "keep my recordings for" setting to everything the user has already
     * posted. Anything now past its time disappears and is deleted by the server.
     */
    suspend fun applyLifetimeToMyRequests(uid: String, days: Int) {
        val mine = requests.whereEqualTo("ownerId", uid).get().await().documents
            .filter { it.getBoolean("customLifetime") != true } // clips given their own time keep it
        mine.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { doc ->
                val createdAt = doc.getTimestamp("createdAt") ?: Timestamp.now()
                batch.update(doc.reference, "expireAt", expiryFrom(createdAt, days))
            }
            batch.commit().await()
        }
    }

    private fun expiryFrom(start: Timestamp, days: Int): Timestamp =
        Timestamp(Date(start.toDate().time + days * 24L * 60 * 60 * 1000))
}
