package com.kowalski.humdinger.data

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
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

/** Who a thank-you is for: the person who named the requester's song. */
data class ThankYouTarget(
    val requestId: String,
    val solverId: String,
    val solverName: String,
    val songTitle: String,
    val songArtist: String,
)

/**
 * A conversation in the Messages tab. Most start with the requester's thank-you (written,
 * or recorded as audio/video) to the person who named their song, and then the two of them
 * can keep chatting. Stored at thanks/<requestId>, with the chat in thanks/<requestId>/messages.
 * Friends can also just chat: those are stored at thanks/f_<uid>_<uid> with mediaType "friend".
 */
data class Conversation(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val toUid: String,
    val toName: String,
    val songTitle: String,
    val songArtist: String,
    /** "text" for a written thank-you, otherwise "audio" / "video". */
    val thanksKind: String,
    val mediaUrl: String,
    /** The recording was deleted after its time was up (the chat stays). */
    val mediaExpired: Boolean,
    val note: String,
    val createdAt: Timestamp?,
    val lastMessage: String,
    val lastAt: Timestamp?,
    val lastSenderId: String,
    val readAt: Map<String, Timestamp>,
    val blockedBy: String?,
) {
    val isTextThanks: Boolean get() = thanksKind == "text"
    val isVideo: Boolean get() = thanksKind == MediaType.VIDEO.key

    /** A chat between friends (not started by a thank-you). */
    val isFriendChat: Boolean get() = thanksKind == "friend"

    fun otherUid(me: String) = if (me == fromUid) toUid else fromUid
    fun otherName(me: String) = if (me == fromUid) toName else fromName

    fun isUnreadFor(me: String): Boolean {
        if (lastSenderId == me || lastAt == null) return false
        val seen = readAt[me] ?: return true
        return lastAt > seen
    }
}

data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Timestamp?,
)

/** A permanent record on someone's account of a song they named. */
data class SolvedRecord(
    val requestId: String,
    val songTitle: String,
    val songArtist: String,
    val link: String,
    val comment: String,
    val requesterName: String,
    val hint: String,
    val solvedAt: Timestamp?,
)

/** A message from Humdinger itself, e.g. "your request was removed after reports". */
data class Notice(
    val id: String,
    val kind: String,
    val mediaType: MediaType,
    val hint: String,
    val postedAt: Timestamp?,
    val reportCount: Long,
    val reasonSummary: String,
    val read: Boolean,
    val createdAt: Timestamp?,
)

internal fun DocumentSnapshot.toConversation(): Conversation {
    @Suppress("UNCHECKED_CAST")
    val rawRead = get("readAt") as? Map<String, Any?> ?: emptyMap()
    return Conversation(
        id = id,
        fromUid = getString("fromUid") ?: "",
        fromName = getString("fromName") ?: "someone",
        toUid = getString("toUid") ?: "",
        toName = getString("toName") ?: "someone",
        songTitle = getString("songTitle") ?: "",
        songArtist = getString("songArtist") ?: "",
        thanksKind = getString("mediaType") ?: "text",
        mediaUrl = getString("mediaUrl") ?: "",
        mediaExpired = getBoolean("mediaExpired") ?: false,
        note = getString("note") ?: "",
        createdAt = getTimestamp("createdAt"),
        lastMessage = getString("lastMessage") ?: "",
        lastAt = getTimestamp("lastAt") ?: getTimestamp("createdAt"),
        lastSenderId = getString("lastSenderId") ?: (getString("fromUid") ?: ""),
        readAt = rawRead.mapNotNull { (k, v) -> (v as? Timestamp)?.let { k to it } }.toMap(),
        blockedBy = getString("blockedBy"),
    )
}

internal fun DocumentSnapshot.toChatMessage() = ChatMessage(
    id = id,
    senderId = getString("senderId") ?: "",
    text = getString("text") ?: "",
    createdAt = getTimestamp("createdAt"),
)

internal fun DocumentSnapshot.toSolvedRecord() = SolvedRecord(
    requestId = id,
    songTitle = getString("songTitle") ?: "",
    songArtist = getString("songArtist") ?: "",
    link = getString("link") ?: "",
    comment = getString("comment") ?: "",
    requesterName = getString("requesterName") ?: "someone",
    hint = getString("hint") ?: "",
    solvedAt = getTimestamp("solvedAt"),
)

internal fun DocumentSnapshot.toNotice() = Notice(
    id = id,
    kind = getString("kind") ?: "",
    mediaType = MediaType.fromKey(getString("mediaType")),
    hint = getString("hint") ?: "",
    postedAt = getTimestamp("postedAt"),
    reportCount = getLong("reportCount") ?: 0L,
    reasonSummary = getString("reasonSummary") ?: "",
    read = getBoolean("read") ?: false,
    createdAt = getTimestamp("createdAt"),
)

object Chat {
    const val MAX_MESSAGE_CHARS = 1000
    const val MAX_THANKS_CHARS = 500
}

object ThanksRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private val storage get() = FirebaseStorage.getInstance()
    private val thanks get() = db.collection("thanks")

    /** Everyone this person is chatting with (their Messages tab). Needs the (members, lastAt) index. */
    fun conversations(uid: String): Flow<List<Conversation>> =
        thanks.whereArrayContains("members", uid)
            .orderBy("lastAt", Query.Direction.DESCENDING)
            .limit(100)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toConversation() } }

    fun conversation(id: String): Flow<Conversation?> =
        thanks.document(id).liveSnapshots().map { if (it.exists()) it.toConversation() else null }

    fun messages(conversationId: String): Flow<List<ChatMessage>> =
        thanks.document(conversationId).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(300)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toChatMessage() } }

    /** True once the requester has sent their one thank-you for this request. */
    fun alreadyThanked(requestId: String): Flow<Boolean> =
        thanks.document(requestId).liveSnapshots().map { it.exists() }

    /** The songs this person has named — kept on their account even after the clips are gone. */
    fun solvedHistory(uid: String): Flow<List<SolvedRecord>> =
        db.collection("users").document(uid).collection("solved")
            .orderBy("solvedAt", Query.Direction.DESCENDING)
            .limit(100)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toSolvedRecord() } }

    private fun baseThanks(me: UserProfile, target: ThankYouTarget, preview: String) = mutableMapOf<String, Any>(
        "members" to listOf(me.uid, target.solverId),
        "fromUid" to me.uid,
        "fromName" to me.username,
        "toUid" to target.solverId,
        "toName" to target.solverName,
        "songTitle" to target.songTitle,
        "songArtist" to target.songArtist,
        "createdAt" to FieldValue.serverTimestamp(),
        "lastMessage" to preview,
        "lastAt" to FieldValue.serverTimestamp(),
        "lastSenderId" to me.uid,
        "readAt" to mapOf(me.uid to FieldValue.serverTimestamp()),
    )

    /** A written thank-you. Starts a conversation in both people's Messages. One per request. */
    suspend fun sendTextThanks(me: UserProfile, target: ThankYouTarget, text: String) {
        val message = text.trim().take(Chat.MAX_THANKS_CHARS)
        val data = baseThanks(me, target, "🎁 $message")
        data["mediaType"] = "text"
        data["mediaUrl"] = ""
        data["note"] = message
        thanks.document(target.requestId).set(data).await()
    }

    /** A recorded thank-you (audio or video). The recording expires; the conversation stays. */
    suspend fun sendThanks(
        me: UserProfile,
        target: ThankYouTarget,
        clip: File,
        mediaType: MediaType,
        note: String,
        lifetimeDays: Int = me.clipLifetimeDays,
    ) {
        val (ext, contentType) = when (mediaType) {
            MediaType.AUDIO -> "m4a" to "audio/mp4"
            MediaType.VIDEO -> "mp4" to "video/mp4"
        }
        val clipRef = storage.reference.child("thanks/${me.uid}/${target.requestId}/clip.$ext")
        clipRef.putFile(Uri.fromFile(clip), StorageMetadata.Builder().setContentType(contentType).build()).await()
        val url = clipRef.downloadUrl.await().toString()
        val expireAt = Timestamp(Date(System.currentTimeMillis() + lifetimeDays * 24L * 60 * 60 * 1000))

        val data = baseThanks(me, target, "🎁 Sent you a recorded thank-you")
        data["mediaType"] = mediaType.key
        data["mediaUrl"] = url
        data["note"] = note.trim().take(Chat.MAX_THANKS_CHARS)
        data["expireAt"] = expireAt
        thanks.document(target.requestId).set(data).await()
    }

    suspend fun sendMessage(conversationId: String, myUid: String, text: String) {
        val clean = text.trim().take(Chat.MAX_MESSAGE_CHARS)
        if (clean.isEmpty()) return
        val convo = thanks.document(conversationId)
        db.batch()
            .set(
                convo.collection("messages").document(),
                mapOf("senderId" to myUid, "text" to clean, "createdAt" to FieldValue.serverTimestamp()),
            )
            .update(
                convo,
                mapOf(
                    "lastMessage" to clean.take(120),
                    "lastAt" to FieldValue.serverTimestamp(),
                    "lastSenderId" to myUid,
                    "readAt.$myUid" to FieldValue.serverTimestamp(),
                ),
            )
            .commit()
            .await()
    }

    suspend fun markConversationRead(conversationId: String, myUid: String) {
        thanks.document(conversationId).update("readAt.$myUid", FieldValue.serverTimestamp()).await()
    }

    /** Blocking stops new messages in this conversation (either way) until the blocker unblocks. */
    suspend fun setBlocked(conversationId: String, myUid: String, blocked: Boolean) {
        thanks.document(conversationId)
            .update("blockedBy", if (blocked) myUid else FieldValue.delete())
            .await()
    }

    suspend fun reportConversation(conversationId: String, myUid: String, reason: String) {
        db.collection("chatReports").add(
            mapOf(
                "conversationId" to conversationId,
                "reporterId" to myUid,
                "reason" to reason,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /** Notices for this person (written by the server). Needs the (toUid, createdAt) index. */
    fun notices(uid: String): Flow<List<Notice>> =
        db.collection("notices")
            .whereEqualTo("toUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .liveSnapshots()
            .map { snap -> snap.documents.map { it.toNotice() } }

    suspend fun markNoticeRead(noticeId: String) {
        db.collection("notices").document(noticeId).update("read", true).await()
    }
}
