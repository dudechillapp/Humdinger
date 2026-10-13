package com.kowalski.humdinger.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kowalski.humdinger.data.BlockedUser
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.HouseRules
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.ui.components.TierAvatarFor
import kotlinx.coroutines.flow.catch

/**
 * The house rules. With [onAgree], it's the one-time "please agree" version that can't be
 * dismissed; without it, it's just for reading.
 */
@Composable
fun HouseRulesDialog(onAgree: (() -> Unit)?, onClose: () -> Unit, onOpenFullRules: () -> Unit) {
    val mustAgree = onAgree != null
    AlertDialog(
        onDismissRequest = { if (!mustAgree) onClose() },
        properties = DialogProperties(dismissOnBackPress = !mustAgree, dismissOnClickOutside = !mustAgree),
        title = { Text(if (mustAgree) "🎶 Before you join in" else "🎶 House rules") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (mustAgree) {
                    Text("Humdinger is all about having a laugh together. Keep it fun for everyone:")
                }
                HouseRules.rules.forEach { rule ->
                    Row {
                        Text(rule.emoji, fontSize = 22.sp, modifier = Modifier.width(36.dp))
                        Column {
                            Text(rule.title, style = MaterialTheme.typography.titleSmall)
                            Text(rule.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    HouseRules.CONSEQUENCES,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenFullRules, modifier = Modifier.padding(0.dp)) {
                    Text("Read the full rules and privacy policy")
                }
            }
        },
        confirmButton = {
            if (onAgree != null) {
                Button(onClick = onAgree) { Text("I agree, let's go! 🎤") }
            } else {
                TextButton(onClick = onClose) { Text("Got it") }
            }
        },
    )
}

/** Asks before blocking someone. */
@Composable
fun BlockDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🚫 Block $name?") },
        text = {
            Text(
                "You won't see their requests, guesses or messages any more, and they can't guess on " +
                    "your requests or message you. They won't be told. You can unblock them on the Me tab."
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Block") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a reason from a list (used for reporting). */
@Composable
fun ReasonDialog(title: String, reasons: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                reasons.forEach { reason ->
                    TextButton(onClick = { onPick(reason) }, modifier = Modifier.fillMaxWidth()) {
                        Text(reason, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Me tab: the people you've blocked, with an unblock button each. */
@Composable
fun BlockedPeopleCard(myUid: String, onUnblock: (BlockedUser) -> Unit) {
    val flow = remember(myUid) { Blocks.list(myUid).catch { emit(emptyList()) } }
    val blocked by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    if (blocked.isEmpty()) return

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🚫 People you've blocked", style = MaterialTheme.typography.titleMedium)
            blocked.forEach { person ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TierAvatarFor(person.uid, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(person.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onUnblock(person) }) { Text("Unblock") }
                }
            }
        }
    }
}

/** Me tab: house rules, privacy policy and deleting your account. */
@Composable
fun SafetyCard(onRules: () -> Unit, onPrivacy: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🛡️ Safety and privacy", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRules) { Text("📜  House rules") }
            TextButton(onClick = onPrivacy) { Text("🔒  Privacy policy") }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("🗑️  Delete my account") }
        }
    }
}

/** Double-checks before deleting an account for good. */
@Composable
fun DeleteAccountDialog(me: UserProfile, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This deletes ${me.username} for good: your ${me.points} points, your recordings, " +
                        "guesses, comments, replies, songs you've named, thank-yous and chats. It can't be undone."
                )
                Text(
                    "Guesses on songs that were already solved stay, but as \"Deleted user\", so other " +
                        "people's solved songs still make sense.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Deleting everything…")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Delete for good") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Keep my account") }
        },
    )
}
