package com.kowalski.humdinger.ui.profile

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.auth.AuthCredential
import com.kowalski.humdinger.data.AccountDeletion
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.ClipLifetime
import com.kowalski.humdinger.data.Legal
import com.kowalski.humdinger.data.GoogleLinkResult
import com.kowalski.humdinger.data.GoogleSignIn
import com.kowalski.humdinger.data.Points
import com.kowalski.humdinger.data.Tiers
import com.kowalski.humdinger.data.Links
import com.kowalski.humdinger.data.RequestRepository
import com.kowalski.humdinger.data.SolvedRecord
import com.kowalski.humdinger.data.ThanksRepository
import com.kowalski.humdinger.data.SignInCancelledException
import com.kowalski.humdinger.data.SongRequest
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.data.UserRepository
import com.kowalski.humdinger.push.PushTokens
import com.kowalski.humdinger.ui.components.RequestCard
import com.kowalski.humdinger.ui.components.rememberOpenLink
import com.kowalski.humdinger.ui.components.timeAgo
import com.kowalski.humdinger.ui.components.TierAvatar
import com.kowalski.humdinger.ui.components.TierCard
import com.kowalski.humdinger.ui.safety.BlockedPeopleCard
import com.kowalski.humdinger.ui.safety.DeleteAccountDialog
import com.kowalski.humdinger.ui.safety.HouseRulesDialog
import com.kowalski.humdinger.ui.safety.SafetyCard
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(me: UserProfile, onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val myRequestsFlow = remember(me.uid) {
        RequestRepository.byOwner(me.uid).catch { e ->
            Log.e("Humdinger", "My requests failed", e)
            emit(emptyList<SongRequest>())
        }
    }
    val myRequests by myRequestsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val solvedFlow = remember(me.uid) {
        ThanksRepository.solvedHistory(me.uid).catch { e ->
            Log.e("Humdinger", "Solved history failed", e)
            emit(emptyList<SolvedRecord>())
        }
    }
    val solved by solvedFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val openLink = rememberOpenLink()

    val isGuest = UserRepository.isGuest

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Me") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TierAvatar(tierIndex = Tiers.indexFor(me.points), size = 120.dp)
                    Text(me.username, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (isGuest) "Playing as a guest" else "Saved to your Google account ✓",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("⭐", "${me.points}", "points", Modifier.weight(1f))
                    StatCard("👑", "${me.solvedCount}", "songs named", Modifier.weight(1f))
                }
            }
            item { TierCard(me.points) }
            if (isGuest) {
                item {
                    Card(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("💾 Save your progress", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Guests lose their points if they delete the app or change phones. " +
                                    "Tap to sign in with Google in Settings.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("How points work", style = MaterialTheme.typography.titleMedium)
                        Text("👑  Name the song: +${Points.SOLVER}")
                        Text("🙋  Have a go before it's solved: +${Points.PARTICIPANT}")
                        Text(
                            "Guesses need a link to the song (YouTube, Spotify, Apple Music and more) to count.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Text(
                    "👑 Songs I've named",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (solved.isEmpty()) {
                item {
                    Text(
                        "None yet. Your first one will be kept here for good, with your answer and comment.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(solved, key = { "solved-" + it.requestId }) { record ->
                SolvedRecordCard(record, onOpenLink = openLink)
            }
            item {
                Text(
                    "Your requests",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (myRequests.isEmpty()) {
                item {
                    Text(
                        "Nothing yet — got a tune stuck in your head?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(myRequests, key = { it.id }) { r ->
                RequestCard(r, onClick = { onOpen(r.id) })
            }
        }
    }
}

@Composable
private fun StatCard(emoji: String, value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = 28.sp)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One entry in "Songs I've named": kept on the account even after the clip itself is deleted. */
@Composable
private fun SolvedRecordCard(record: SolvedRecord, onOpenLink: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                record.songTitle + if (record.songArtist.isNotBlank()) " — ${record.songArtist}" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Named for ${record.requesterName} · ${timeAgo(record.solvedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.hint.isNotBlank()) {
                Text("Their clue: “${record.hint}”", style = MaterialTheme.typography.bodySmall)
            }
            if (record.comment.isNotBlank()) {
                Text("💬 ${record.comment}", style = MaterialTheme.typography.bodyMedium)
            }
            if (record.link.isNotBlank()) {
                TextButton(onClick = { onOpenLink(record.link) }) { Text("▶  ${Links.label(record.link)}") }
            }
        }
    }
}
