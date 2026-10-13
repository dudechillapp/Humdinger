package com.kowalski.humdinger.ui.friends

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.Friend
import com.kowalski.humdinger.data.Friends
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.ui.components.TierAvatarFor
import com.kowalski.humdinger.ui.components.TierLabelFor
import com.kowalski.humdinger.ui.components.timeAgo
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch

/** Friend requests, your friends (tap to message), and requests you've sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(me: UserProfile, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blocked by Blocks.ids.collectAsStateWithLifecycle()
    val friends by Friends.friends.collectAsStateWithLifecycle()
    val incoming by Friends.incoming.collectAsStateWithLifecycle()
    val outgoing by Friends.outgoing.collectAsStateWithLifecycle()
    val myFriends = friends.filter { it.uid !in blocked }
    val requests = incoming.filter { it.fromUid !in blocked }
    val sent = outgoing.filter { it.toUid !in blocked }

    fun act(success: String?, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                success?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e("Humdinger", "Friends action failed", e)
                val denied = (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                Toast.makeText(
                    context,
                    if (denied) "Their privacy settings don't allow that right now." else e.message ?: "Something went wrong. Try again?",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("👥 Friends") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 24.sp) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (requests.isNotEmpty()) {
                item { SectionTitle("👋 Friend requests") }
                items(requests, key = { "in-" + it.id }) { req ->
                    PersonRow(uid = req.fromUid, name = req.fromName, subtitle = "Asked ${timeAgo(req.createdAt)}", highlight = true) {
                        Button(onClick = {
                            act("You and ${req.fromName} are friends now! 🎉") { Friends.accept(me, req.fromUid, req.fromName) }
                        }) { Text("Accept") }
                        TextButton(onClick = { act(null) { Friends.deleteRequest(req.fromUid, me.uid) } }) { Text("Decline") }
                    }
                }
            }

            item { SectionTitle("👥 Your friends (${myFriends.size})") }
            if (myFriends.isEmpty()) {
                item {
                    Text(
                        "No friends yet. Tap anyone's picture or name (on a hum, a guess, a comment or the " +
                            "leaderboard) and choose 👋 Add friend. Friends can message each other and get a " +
                            "notification when the other posts a hum.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(myFriends, key = { "f-" + it.uid }) { friend ->
                PersonRow(uid = friend.uid, name = friend.name, subtitle = "Friends since ${timeAgo(friend.since)}") {
                    FilledTonalButton(onClick = {
                        act(null) { onOpenChat(Friends.openChat(me, Friend(friend.uid, friend.name, null))) }
                    }) { Text("💬 Message") }
                }
            }

            if (sent.isNotEmpty()) {
                item { SectionTitle("📤 Requests you've sent") }
                items(sent, key = { "out-" + it.id }) { req ->
                    PersonRow(uid = req.toUid, name = req.toName, subtitle = "Waiting for them to accept") {
                        OutlinedButton(onClick = { act("Request cancelled.") { Friends.deleteRequest(me.uid, req.toUid) } }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

/** A person with their character and tier. Tapping the picture or name opens their card. */
@Composable
private fun PersonRow(
    uid: String,
    name: String,
    subtitle: String,
    highlight: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.openPersonOnClick(uid, name),
            ) {
                TierAvatarFor(uid, size = 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TierLabelFor(uid)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}
