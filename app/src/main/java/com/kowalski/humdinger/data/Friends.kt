package com.kowalski.humdinger.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class Friend(val uid: String, val name: String, val since: Timestamp?)

data class FriendRequest(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val toUid: String,
    val toName: String,
    val createdAt: Timestamp?,
)

enum class FriendState { NONE, REQUEST_SENT, REQUEST_RECEIVED, FRIENDS }

/**
 * Friends: one person sends a request (friendRequests/<from>_<to>), the other accepts.
 * Accepting writes users/<me>/friends/<them> and users/<them>/friends/<me> in one go and
 * deletes the request. Friends can message each other, and get a notification when the
 * other posts a hum (if they want: Settings). Blocking someone ends the friendship.
 */
object Friends {
    private val db get() = FirebaseFirestore.getInstance()
    private val requests get() = db.collection("friendRequests")
    private fun friendsRef(uid: String) = db.collection("users").document(uid).collection("friends")

    private fun requestId(from: String, to: String) = "${from}_$to"

    /** Friend chats live with the other conversations, at thanks/f_<uid>_<uid> (ids in order). */
    fun chatId(a: String, b: String) = "f_" + listOf(a, b).sorted().joinToString("_")

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    private val _incoming = MutableStateFlow<List<FriendRequest>>(emptyList())
    private val _outgoing = MutableStateFlow<List<FriendRequest>>(emptyList())

    /** The signed-in person's friends, and requests to and from them (kept up to date by [keepUpdated]). */
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()
    val incoming: StateFlow<List<FriendRequest>> = _incoming.asStateFlow()
    val outgoing: StateFlow<List<FriendRequest>> = _outgoing.asStateFlow()

    fun stateFor(otherUid: String): FriendState = when {
        _friends.value.any { it.uid == otherUid } -> FriendState.FRIENDS
        _incoming.value.any { it.fromUid == otherUid } -> FriendState.REQUEST_RECEIVED
        _outgoing.value.any { it.toUid == otherUid } -> FriendState.REQUEST_SENT
        else -> FriendState.NONE
    }

    private fun friendsList(uid: String): Flow<List<Friend>> =
        friendsRef(uid).liveSnapshots().map { snap ->
            snap.documents
                .map { Friend(it.id, it.getString("name") ?: "someone", it.getTimestamp("since")) }
                .sortedBy { it.name.lowercase() }
        }

    private fun requestList(field: String, uid: String): Flow<List<FriendRequest>> =
        requests.whereEqualTo(field, uid).liveSnapshots().map { snap ->
            snap.documents.map {
                FriendRequest(
                    id = it.id,
                    fromUid = it.getString("fromUid") ?: "",
                    fromName = it.getString("fromName") ?: "someone",
                    toUid = it.getString("toUid") ?: "",
                    toName = it.getString("toName") ?: "someone",
                    createdAt = it.getTimestamp("createdAt"),
                )
            }.sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
        }

    /** Keeps [friends], [incoming] and [outgoing] up to date. Runs for as long as the caller is active. */
    suspend fun keepUpdated(myUid: String) {
        _friends.value = emptyList()
        _incoming.value = emptyList()
        _outgoing.value = emptyList()
        coroutineScope {
            launch { friendsList(myUid).catch { emit(emptyList()) }.collect { _friends.value = it } }
            launch { requestList("toUid", myUid).catch { emit(emptyList()) }.collect { _incoming.value = it } }
            launch { requestList("fromUid", myUid).catch { emit(emptyList()) }.collect { _outgoing.value = it } }
        }
    }

    /** Sends a friend request. If they'd already asked you, this accepts theirs instead. */
    suspend fun sendRequest(me: UserProfile, otherUid: String, otherName: String) {
        if (otherUid.isBlank() || otherUid == me.uid) return
        val theirs = requests.document(requestId(otherUid, me.uid)).get().await()
        if (theirs.exists()) {
            accept(me, otherUid, theirs.getString("fromName") ?: otherName)
            return
        }
        requests.document(requestId(me.uid, otherUid)).set(
            mapOf(
                "fromUid" to me.uid,
                "fromName" to me.username,
                "toUid" to otherUid,
                "toName" to otherName.take(30),
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /** Accepts [fromUid]'s request: you're now on each other's friends lists. */
    suspend fun accept(me: UserProfile, fromUid: String, fromName: String) {
        db.batch()
            .set(
                friendsRef(me.uid).document(fromUid),
                mapOf("uid" to fromUid, "name" to fromName.take(30), "since" to FieldValue.serverTimestamp(), "addedBy" to me.uid),
            )
            .set(
                friendsRef(fromUid).document(me.uid),
                mapOf("uid" to me.uid, "name" to me.username, "since" to FieldValue.serverTimestamp(), "addedBy" to me.uid),
            )
            .delete(requests.document(requestId(fromUid, me.uid)))
            .commit()
            .await()
    }

    /** Declining or cancelling: the request just disappears (nobody is told). */
    suspend fun deleteRequest(fromUid: String, toUid: String) {
        requests.document(requestId(fromUid, toUid)).delete().await()
    }

    /** Takes you off each other's friends lists. Your chat stays in Messages. */
    suspend fun unfriend(myUid: String, otherUid: String) {
        db.batch()
            .delete(friendsRef(myUid).document(otherUid))
            .delete(friendsRef(otherUid).document(myUid))
            .commit()
            .await()
    }

    /** Used when blocking: ends the friendship and any requests between you. Best effort. */
    suspend fun removeEverything(myUid: String, otherUid: String) {
        runCatching { unfriend(myUid, otherUid) }
        runCatching { deleteRequest(myUid, otherUid) }
        runCatching { deleteRequest(otherUid, myUid) }
    }

    /** Opens your chat with a friend, starting it if it doesn't exist yet. Returns its id. */
    suspend fun openChat(me: UserProfile, friend: Friend): String {
        val id = chatId(me.uid, friend.uid)
        val ref = db.collection("thanks").document(id)
        if (!ref.get().await().exists()) {
            ref.set(
                mapOf(
                    "members" to listOf(me.uid, friend.uid),
                    "fromUid" to me.uid,
                    "fromName" to me.username,
                    "toUid" to friend.uid,
                    "toName" to friend.name,
                    "mediaType" to "friend",
                    "mediaUrl" to "",
                    "note" to "",
                    "songTitle" to "",
                    "songArtist" to "",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "lastMessage" to "👋 Started a chat",
                    "lastAt" to FieldValue.serverTimestamp(),
                    "lastSenderId" to me.uid,
                    "readAt" to mapOf(me.uid to FieldValue.serverTimestamp()),
                )
            ).await()
        }
        return id
    }
}
