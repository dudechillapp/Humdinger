package com.kowalski.humdinger.data

import android.util.Log
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class NameTakenException : Exception("That name's already taken. Try another!")

/** Result of trying to attach a Google account to the current guest. */
sealed interface GoogleLinkResult {
    /** Saved! Same account, same points — now backed up to Google. */
    data object Linked : GoogleLinkResult

    /** That Google account already has a Humdinger profile (e.g. from an old phone). */
    data class AlreadyHasProfile(val credential: AuthCredential) : GoogleLinkResult
}

object UserRepository {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    /** Emits the signed-in user's id, or null when nobody is signed in. */
    fun authUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    val isGuest: Boolean get() = auth.currentUser?.isAnonymous ?: true

    // One shared live listener per person, however many places show their tier.
    private val cacheScope = CoroutineScope(SupervisorJob())
    private val pointsCache = ConcurrentHashMap<String, Flow<Long>>()

    /** Live points for any user (used to show their tier next to their posts). */
    fun points(uid: String): Flow<Long> = pointsCache.getOrPut(uid) {
        db.collection("users").document(uid).liveSnapshots()
            .map { it.getLong("points") ?: 0L }
            .catch { emit(0L) }
            .shareIn(cacheScope, SharingStarted.WhileSubscribed(10_000), replay = 1)
    }

    /** Emits the user's profile, or null if it hasn't been created yet. */
    fun profile(uid: String): Flow<UserProfile?> =
        db.collection("users").document(uid).liveSnapshots()
            // Ignore an empty cached result, so returning users aren't treated as brand new.
            .filter { it.exists() || !it.metadata.isFromCache }
            .map { if (it.exists()) it.toUserProfile() else null }

    fun leaderboard(): Flow<List<UserProfile>> =
        db.collection("users")
            .orderBy("points", Query.Direction.DESCENDING)
            .limit(50)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toUserProfile() } }

    /** No sign-up needed: everyone starts as a guest. */
    suspend fun signInAsGuest() {
        auth.signInAnonymously().await()
    }

    /**
     * Gives a new user a random fun name like "Springy Toast". Names are unique, so it
     * reserves the name and creates the profile in one transaction, retrying on a clash.
     */
    suspend fun createProfileWithFunName(uid: String) {
        val userRef = db.collection("users").document(uid)
        repeat(15) { attempt ->
            val name = FunNames.random(attempt)
            val key = FunNames.key(name)
            val nameRef = db.collection("usernames").document(key)
            try {
                db.runTransaction { tx ->
                    if (tx.get(userRef).exists()) return@runTransaction null // already done
                    if (tx.get(nameRef).exists()) throw NameTakenException()
                    tx.set(nameRef, mapOf("uid" to uid))
                    tx.set(
                        userRef,
                        mapOf(
                            "username" to name,
                            "usernameLower" to key,
                            "points" to 0L,
                            "solvedCount" to 0L,
                            "createdAt" to FieldValue.serverTimestamp(),
                        )
                    )
                    null
                }.await()
                return
            } catch (e: Exception) {
                if (e !is NameTakenException && e.cause !is NameTakenException) throw e
            }
        }
        throw IllegalStateException("Couldn't find a free name. Try again!")
    }

    /**
     * Attaches a Google account to the current guest. The account id stays the same,
     * so points, requests and guesses all carry over.
     */
    suspend fun linkGoogle(idToken: String): GoogleLinkResult {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in")
        return try {
            user.linkWithCredential(credential).await()
            GoogleLinkResult.Linked
        } catch (e: FirebaseAuthUserCollisionException) {
            GoogleLinkResult.AlreadyHasProfile(e.updatedCredential ?: credential)
        }
    }

    /** Switches to an existing Google-backed profile (the current guest is left behind). */
    suspend fun switchTo(credential: AuthCredential) {
        auth.signInWithCredential(credential).await()
    }

    /** Names people can pick themselves: 3–24 letters, numbers or spaces. */
    private val namePattern = Regex("^[A-Za-z0-9 ]{3,24}$")

    /** Tidies up a typed name: trims it and squashes double spaces. */
    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun isValidName(raw: String): Boolean = namePattern.matches(cleanName(raw))

    /**
     * Changes the user's name. Names stay unique: the new one is reserved and the old one
     * released in a single transaction. Afterwards, the name shown on their own requests
     * is updated too.
     */
    suspend fun rename(uid: String, rawName: String) {
        val name = cleanName(rawName)
        if (!namePattern.matches(name)) {
            throw IllegalArgumentException("Names need 3–24 letters, numbers or spaces.")
        }
        if (!NameFilter.isAllowed(name)) {
            throw IllegalArgumentException("Let's keep names friendly. Try another!")
        }
        val key = FunNames.key(name)
        val userRef = db.collection("users").document(uid)
        val newRef = db.collection("usernames").document(key)

        try {
            db.runTransaction { tx ->
                val oldKey = tx.get(userRef).getString("usernameLower") ?: ""
                if (key != oldKey) {
                    val existing = tx.get(newRef)
                    if (existing.exists() && existing.getString("uid") != uid) throw NameTakenException()
                    if (!existing.exists()) tx.set(newRef, mapOf("uid" to uid))
                    if (oldKey.isNotEmpty()) tx.delete(db.collection("usernames").document(oldKey))
                }
                tx.update(userRef, mapOf("username" to name, "usernameLower" to key))
                null
            }.await()
        } catch (e: Exception) {
            if (e is NameTakenException || e.cause is NameTakenException) throw NameTakenException()
            throw e
        }

        // Points never change. Now put the new name on everything they've posted before.
        refreshNameEverywhere(uid, name)
    }

    /**
     * Updates the name shown on the user's past posts: their requests, songs they
     * named, their guesses on other people's requests and their replies. Each part is best effort,
     * so one hiccup doesn't stop the rest.
     */
    private suspend fun refreshNameEverywhere(uid: String, name: String) {
        val requests = db.collection("requests")
        updateAll(requests.whereEqualTo("ownerId", uid), "ownerName", name)
        updateAll(requests.whereEqualTo("solverId", uid), "solverName", name)
        // Needs the "guesses / authorId" collection-group index — see SETUP.md.
        updateAll(db.collectionGroup("guesses").whereEqualTo("authorId", uid), "authorName", name)
        // Needs the "replies / authorId" collection-group index (firestore.indexes.json).
        updateAll(db.collectionGroup("replies").whereEqualTo("authorId", uid), "authorName", name)
        updateAll(db.collectionGroup("comments").whereEqualTo("authorId", uid), "authorName", name)
        // Your name in your friends' friends lists.
        updateAll(db.collectionGroup("friends").whereEqualTo("uid", uid), "name", name)
    }

    private suspend fun updateAll(query: Query, field: String, value: String) {
        try {
            val docs = query.get().await().documents
            docs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.update(it.reference, field, value) }
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.e("Humdinger", "Couldn't update $field to the new name", e)
        }
    }

    /**
     * Sets how long this person's recordings stay up, and applies it to everything they've
     * already posted. The server deletes anything past its time.
     */
    suspend fun setClipLifetime(uid: String, lifetime: ClipLifetime) {
        db.collection("users").document(uid).update("clipLifetimeDays", lifetime.days).await()
        RequestRepository.applyLifetimeToMyRequests(uid, lifetime.days)
    }

    /** They've read and agreed to the house rules. */
    suspend fun acceptRules(uid: String) {
        db.collection("users").document(uid).update("rulesVersion", HouseRules.VERSION).await()
    }

    /** Signing out starts a fresh guest automatically. */
    fun signOut() = auth.signOut()
}
