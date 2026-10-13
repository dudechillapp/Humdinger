package com.kowalski.humdinger.ui.friends

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.Friend
import com.kowalski.humdinger.data.FriendState
import com.kowalski.humdinger.data.Friends
import com.kowalski.humdinger.data.Privacy
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.data.UserRepository
import kotlinx.coroutines.flow.catch
import com.kowalski.humdinger.data.UserReports
import com.kowalski.humdinger.ui.components.TierAvatarFor
import com.kowalski.humdinger.ui.components.TierLabelFor
import com.kowalski.humdinger.ui.safety.BlockDialog
import com.kowalski.humdinger.ui.safety.ReasonDialog
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch

/** Accounts deleted from Humdinger leave this id on things that stay (see functions/index.js). */
private const val DELETED_USER = "deleted"

/**
 * Opens someone's card (add friend, message, block, report). Provided once in the
 * navigation, so any screen can make a picture or name tappable with [Modifier.openPersonOnClick].
 */
val LocalOpenPerson = compositionLocalOf<(uid: String, name: String) -> Unit> { { _, _ -> } }

/** Tapping this opens that person's card. Does nothing for deleted accounts. */
@Composable
fun Modifier.openPersonOnClick(uid: String, name: String): Modifier {
    val open = LocalOpenPerson.current
    return if (uid.isBlank() || uid == DELETED_USER) this else clickable { open(uid, name) }
}

/** Someone's card: their character and tier, and what you can do (friends, message, block, report). */
@Composable
fun PersonDialog(
    me: UserProfile,
    uid: String,
    name: String,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val friends by Friends.friends.collectAsStateWithLifecycle()
    val incoming by Friends.incoming.collectAsStateWithLifecycle()
    val outgoing by Friends.outgoing.collectAsStateWithLifecycle()
    val blocked by Blocks.ids.collectAsStateWithLifecycle()
    val state = remember(friends, incoming, outgoing, uid) { Friends.stateFor(uid) }
    // Their privacy settings (who can send friend requests / message them).
    val theirFlow = remember(uid) { UserRepository.profile(uid).catch { emit(null) } }
    val them by theirFlow.collectAsStateWithLifecycle(initialValue = null)
    val takesRequests = them?.friendRequestsFrom != Privacy.NOBODY
    val takesMessages = them?.messagesFrom != Privacy.NOBODY

    var busy by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var confirmUnfriend by remember { mutableStateOf(false) }

    fun act(success: String?, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                success?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e("Humdinger", "Friend action failed", e)
                val denied = (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                Toast.makeText(
                    context,
                    if (denied) "Their privacy settings don't allow that right now." else e.message ?: "Something went wrong. Try again?",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierAvatarFor(uid, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    TierLabelFor(uid)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    uid == me.uid -> Text("That's you! 😎")
                    uid in blocked -> {
                        Text("You've blocked $name.")
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { act("Unblocked $name.") { Blocks.unblock(me.uid, uid) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Unblock") }
                    }
                    state == FriendState.FRIENDS -> {
                        Text("👥 You're friends")
                        if (!takesMessages) {
                            Text(
                                "$name has turned messages off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (takesMessages) Button(
                            enabled = !busy,
                            onClick = {
                                act(null) {
                                    val id = Friends.openChat(me, Friend(uid, name, null))
                                    onDismiss()
                                    onOpenChat(id)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("💬  Message $name") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { confirmUnfriend = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Unfriend") }
                    }
                    state == FriendState.REQUEST_SENT -> {
                        Text("✓ Friend request sent. They'll get a notification.")
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { act("Request cancelled.") { Friends.deleteRequest(me.uid, uid) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel request") }
                    }
                    state == FriendState.REQUEST_RECEIVED -> {
                        Text("👋 $name wants to be friends!")
                        Button(
                            enabled = !busy,
                            onClick = { act("You and $name are friends now! 🎉") { Friends.accept(me, uid, name) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Accept") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { act(null) { Friends.deleteRequest(uid, me.uid) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Decline") }
                    }
                    !takesRequests -> {
                        Text("$name isn't taking friend requests right now.")
                    }
                    else -> {
                        Button(
                            enabled = !busy,
                            onClick = { act("Friend request sent to $name 👋") { Friends.sendRequest(me, uid, name) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("👋  Add friend") }
                        Text(
                            "Friends can message each other and get a notification when the other posts a hum.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (uid != me.uid && uid !in blocked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { reporting = true }) { Text("🙋 Report") }
                        TextButton(onClick = { confirmBlock = true }) { Text("🚫 Block") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    if (confirmUnfriend) {
        AlertDialog(
            onDismissRequest = { confirmUnfriend = false },
            title = { Text("Unfriend $name?") },
            text = { Text("You'll come off each other's friends lists. They aren't told, and your chat stays in Messages.") },
            confirmButton = {
                Button(onClick = {
                    confirmUnfriend = false
                    act("Unfriended $name.") { Friends.unfriend(me.uid, uid) }
                }) { Text("Unfriend") }
            },
            dismissButton = { TextButton(onClick = { confirmUnfriend = false }) { Text("Cancel") } },
        )
    }
    if (confirmBlock) {
        BlockDialog(
            name = name,
            onConfirm = {
                confirmBlock = false
                act("Blocked $name. You won't see them any more. 🚫") { Blocks.block(me.uid, uid, name) }
            },
            onDismiss = { confirmBlock = false },
        )
    }
    if (reporting) {
        ReasonDialog(
            title = "Report $name",
            reasons = UserReports.REASONS,
            onPick = { reason ->
                reporting = false
                act("Thanks for reporting $name. We'll take a look.") { UserReports.report(me.uid, uid, name, reason) }
            },
            onDismiss = { reporting = false },
        )
    }
}
