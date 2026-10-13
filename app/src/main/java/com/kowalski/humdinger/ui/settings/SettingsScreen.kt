package com.kowalski.humdinger.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.kowalski.humdinger.data.AppPrefs
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.ClipLifetime
import com.kowalski.humdinger.data.FeedFilter
import com.kowalski.humdinger.data.GoogleLinkResult
import com.kowalski.humdinger.data.GoogleSignIn
import com.kowalski.humdinger.data.Legal
import com.kowalski.humdinger.data.NotificationSettings
import com.kowalski.humdinger.data.NotifyType
import com.kowalski.humdinger.data.Privacy
import com.kowalski.humdinger.data.SignInCancelledException
import com.kowalski.humdinger.data.ThemeMode
import com.kowalski.humdinger.data.Tiers
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.data.UserRepository
import com.kowalski.humdinger.push.Notifications
import com.kowalski.humdinger.push.PushTokens
import com.kowalski.humdinger.ui.components.TierAvatar
import com.kowalski.humdinger.ui.components.rememberOpenLink
import com.kowalski.humdinger.ui.profile.RenameDialog
import com.kowalski.humdinger.ui.safety.BlockedPeopleCard
import com.kowalski.humdinger.ui.safety.DeleteAccountDialog
import com.kowalski.humdinger.ui.safety.HouseRulesDialog
import kotlinx.coroutines.launch

/**
 * Everything you can change: account, privacy, notifications, playing, colours, and safety.
 * Account-wide settings are saved on the profile; colours, autoplay and the feed filter are
 * saved on this phone (AppPrefs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(me: UserProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openLink = rememberOpenLink()

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // ---------- Notifications ----------
    // Show changes straight away; the saved settings arrive back through `me` a moment later.
    var notify by remember { mutableStateOf(me.notify) }
    var quietStart by remember { mutableStateOf(me.quietStart) }
    var quietEnd by remember { mutableStateOf(me.quietEnd) }
    var digestHour by remember { mutableStateOf(me.digestHour) }
    LaunchedEffect(me.notify, me.quietStart, me.quietEnd, me.digestHour) {
        notify = me.notify
        quietStart = me.quietStart
        quietEnd = me.quietEnd
        digestHour = me.digestHour
    }

    var phoneAllows by remember { mutableStateOf(Notifications.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phoneAllows = granted || Notifications.canNotify(context)
    }

    fun openPhoneSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun saveNotify(key: String, value: Any, undo: () -> Unit) {
        scope.launch {
            try {
                NotificationSettings.set(me.uid, key, value)
            } catch (e: Exception) {
                Log.e("Humdinger", "Couldn't save notification setting", e)
                undo()
                toast(e.message ?: "Couldn't save that. Try again?")
            }
        }
    }

    fun switchNotify(key: String, on: Boolean) {
        val before = notify
        notify = notify + (key to on)
        saveNotify(key, on) { notify = before }
    }

    val allOn = NotificationSettings.allOn(notify)
    val quietOn = notify[NotifyType.QUIET_ON] ?: false
    val dailyOn = notify[NotifyType.DAILY] ?: false

    // ---------- Privacy ----------
    var friendRequestsFrom by remember { mutableStateOf(me.friendRequestsFrom) }
    var messagesFrom by remember { mutableStateOf(me.messagesFrom) }
    var commentsFrom by remember { mutableStateOf(me.commentsFrom) }
    var onLeaderboard by remember { mutableStateOf(me.onLeaderboard) }
    LaunchedEffect(me.friendRequestsFrom, me.messagesFrom, me.commentsFrom, me.onLeaderboard) {
        friendRequestsFrom = me.friendRequestsFrom
        messagesFrom = me.messagesFrom
        commentsFrom = me.commentsFrom
        onLeaderboard = me.onLeaderboard
    }

    fun savePrivacy(key: String, value: Any, undo: () -> Unit) {
        scope.launch {
            try {
                Privacy.set(me.uid, key, value)
            } catch (e: Exception) {
                Log.e("Humdinger", "Couldn't save privacy setting", e)
                undo()
                toast(e.message ?: "Couldn't save that. Try again?")
            }
        }
    }

    // ---------- Phone settings ----------
    val theme by AppPrefs.theme.collectAsStateWithLifecycle()
    val autoplay by AppPrefs.autoplay.collectAsStateWithLifecycle()
    val feedFilter by AppPrefs.feedFilter.collectAsStateWithLifecycle()

    // ---------- Account ----------
    var isGuest by remember(me.uid) { mutableStateOf(UserRepository.isGuest) }
    var signingIn by remember { mutableStateOf(false) }
    var existingAccount by remember { mutableStateOf<AuthCredential?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deleting by AccountDeletion.inProgress.collectAsStateWithLifecycle()

    fun saveWithGoogle() {
        signingIn = true
        scope.launch {
            try {
                val token = GoogleSignIn.getIdToken(context)
                when (val result = UserRepository.linkGoogle(token)) {
                    GoogleLinkResult.Linked -> {
                        isGuest = false
                        toast("Saved! Your points are safe with your Google account. 🎉")
                    }
                    is GoogleLinkResult.AlreadyHasProfile -> existingAccount = result.credential
                }
            } catch (e: SignInCancelledException) {
                // They closed the picker — nothing to do.
            } catch (e: Exception) {
                Log.e("Humdinger", "Google sign-in failed", e)
                toast(e.message ?: "Google sign-in didn't work. Try again?")
            } finally {
                signingIn = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙️ Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 24.sp) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- Account ----------
            item(key = "account") {
                Section("👤 Account") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TierAvatar(tierIndex = Tiers.indexFor(me.points), size = 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(me.username, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (isGuest) "Playing as a guest" else "Saved to your Google account ✓",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { renaming = true }) { Text("✏️  Change name") }
                    if (isGuest) {
                        Text(
                            "💾 Guests lose their points if they delete the app or change phones. Sign in with " +
                                "Google to keep everything. Your name and points stay the same.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (signingIn) {
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        } else {
                            Button(
                                onClick = { saveWithGoogle() },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) { Text("Sign in with Google") }
                        }
                    } else {
                        OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Sign out")
                        }
                    }
                }
            }

            // ---------- Privacy ----------
            item(key = "privacy") {
                Section("🔒 Privacy") {
                    ChoiceRow(
                        title = "Who can send me friend requests",
                        choices = Privacy.friendRequestChoices,
                        selected = friendRequestsFrom,
                        onSelect = { value ->
                            val before = friendRequestsFrom
                            friendRequestsFrom = value
                            savePrivacy(Privacy.FRIEND_REQUESTS, value) { friendRequestsFrom = before }
                        },
                    )
                    HorizontalDivider()
                    ChoiceRow(
                        title = "Who can message me",
                        description = "Nobody: people can't send you messages (you can still message them, and thank-yous still arrive).",
                        choices = Privacy.messageChoices,
                        selected = messagesFrom,
                        onSelect = { value ->
                            val before = messagesFrom
                            messagesFrom = value
                            savePrivacy(Privacy.MESSAGES, value) { messagesFrom = before }
                        },
                    )
                    HorizontalDivider()
                    ChoiceRow(
                        title = "Who can comment on my hums",
                        choices = Privacy.commentChoices,
                        selected = commentsFrom,
                        onSelect = { value ->
                            val before = commentsFrom
                            commentsFrom = value
                            savePrivacy(Privacy.COMMENTS, value) { commentsFrom = before }
                        },
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "🏆 Show me on the leaderboard",
                        description = "Off: you still earn points, but you're not listed in Top Namers.",
                        checked = onLeaderboard,
                        enabled = true,
                        onChange = { on ->
                            val before = onLeaderboard
                            onLeaderboard = on
                            savePrivacy(Privacy.LEADERBOARD, on) { onLeaderboard = before }
                        },
                    )
                }
            }

            // ---------- Notifications ----------
            item(key = "phone") {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (phoneAllows) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (phoneAllows) {
                            Text("📱 Notifications are allowed on this phone", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Sounds, vibration and pop-ups are set in your phone's settings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { openPhoneSettings() }) { Text("Open phone settings") }
                        } else {
                            Text("📵 Notifications are off on this phone", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Humdinger can't show you anything until you allow notifications for it.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else openPhoneSettings()
                            }) { Text("Allow notifications") }
                            TextButton(onClick = { openPhoneSettings() }) { Text("Open phone settings") }
                        }
                    }
                }
            }

            item(key = "notify-main") {
                Section("🔔 Notifications") {
                    SwitchRow(
                        title = "All notifications",
                        description = if (allOn) "Choose exactly which ones below" else "Paused: you won't get any",
                        checked = allOn,
                        enabled = true,
                        onChange = { switchNotify(NotifyType.ALL_KEY, it) },
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "🌙 Quiet hours",
                        description = "Notifications still arrive, but without a sound or pop-up.",
                        checked = quietOn,
                        enabled = allOn,
                        onChange = { switchNotify(NotifyType.QUIET_ON, it) },
                    )
                    if (quietOn) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            Text("From", style = MaterialTheme.typography.bodyMedium)
                            HourPicker(quietStart) { hour ->
                                val before = quietStart
                                quietStart = hour
                                saveNotify(NotifyType.QUIET_START, hour) { quietStart = before }
                            }
                            Text("until", style = MaterialTheme.typography.bodyMedium)
                            HourPicker(quietEnd) { hour ->
                                val before = quietEnd
                                quietEnd = hour
                                saveNotify(NotifyType.QUIET_END, hour) { quietEnd = before }
                            }
                        }
                    }
                    HorizontalDivider()
                    SwitchRow(
                        title = "📬 One daily summary instead",
                        description = "Get one notification a day, like \"3 new guesses · 2 comments\". " +
                            "Messages and thank-yous still come straight away.",
                        checked = dailyOn,
                        enabled = allOn,
                        onChange = { switchNotify(NotifyType.DAILY, it) },
                    )
                    if (dailyOn) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            Text("Send it at", style = MaterialTheme.typography.bodyMedium)
                            HourPicker(digestHour) { hour ->
                                val before = digestHour
                                digestHour = hour
                                saveNotify(NotifyType.DIGEST_HOUR, hour) { digestHour = before }
                            }
                        }
                    }
                }
            }

            NotifyType.entries.groupBy { it.group }.forEach { (group, types) ->
                item(key = "notify-$group") {
                    Section(group) {
                        types.forEachIndexed { index, type ->
                            if (index > 0) HorizontalDivider()
                            SwitchRow(
                                title = type.title,
                                description = type.description,
                                checked = NotificationSettings.isOn(notify, type),
                                enabled = allOn,
                                onChange = { switchNotify(type.key, it) },
                            )
                        }
                    }
                }
            }

            // ---------- Playing ----------
            item(key = "playing") {
                Section("🎤 Playing") {
                    Text("🗑️ Keep my recordings for", style = MaterialTheme.typography.bodyLarge)
                    ChipChoices(
                        choices = ClipLifetime.entries.map { it.days.toString() to it.label },
                        selected = ClipLifetime.fromDays(me.clipLifetimeDays).days.toString(),
                        onSelect = { value ->
                            val lifetime = ClipLifetime.fromDays(value.toInt())
                            scope.launch {
                                try {
                                    UserRepository.setClipLifetime(me.uid, lifetime)
                                    toast("Your recordings now stay up for ${lifetime.label}.")
                                } catch (e: Exception) {
                                    Log.e("Humdinger", "Couldn't change clip lifetime", e)
                                    toast(e.message ?: "Couldn't change that. Try again?")
                                }
                            }
                        },
                    )
                    Text(
                        "💡 The longer your clip stays up, the more time people have to name that tune! After that, " +
                            "your clip, its guesses, comments and reactions are deleted for good. Your points always stay. " +
                            "You can also pick a different time for a single clip when you post it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "▶️ Play clips straight away",
                        description = "When you open a hum or mime. Off: tap play yourself (handy in public).",
                        checked = autoplay,
                        enabled = true,
                        onChange = { AppPrefs.setAutoplay(it) },
                    )
                    HorizontalDivider()
                    Text("🎶 Show in my feed", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    ChipChoices(
                        choices = FeedFilter.entries.map { it.key to it.label },
                        selected = feedFilter.key,
                        onSelect = { AppPrefs.setFeedFilter(FeedFilter.fromKey(it)) },
                    )
                }
            }

            // ---------- Looks ----------
            item(key = "looks") {
                Section("🎨 Colours") {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(selected = theme == mode, onClick = { AppPrefs.setTheme(mode) })
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(
                        "Saved on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------- Safety ----------
            item(key = "blocked") {
                BlockedPeopleCard(
                    myUid = me.uid,
                    onUnblock = { person ->
                        scope.launch {
                            runCatching { Blocks.unblock(me.uid, person.uid) }
                                .onSuccess { toast("Unblocked ${person.name}.") }
                                .onFailure { toast(it.message ?: "Couldn't unblock. Try again?") }
                        }
                    },
                )
            }
            item(key = "safety") {
                Section("🛡️ Safety and help") {
                    TextButton(onClick = { showRules = true }) { Text("📜  House rules") }
                    TextButton(onClick = { openLink(Legal.PRIVACY_URL) }) { Text("🔒  Privacy policy") }
                    TextButton(onClick = { openLink("mailto:${Legal.CONTACT_EMAIL}") }) { Text("✉️  Contact us") }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("🗑️  Delete my account", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (renaming) RenameDialog(me = me, onDismiss = { renaming = false })

    if (showRules) {
        HouseRulesDialog(onAgree = null, onClose = { showRules = false }, onOpenFullRules = { openLink(Legal.RULES_URL) })
    }

    if (confirmDelete || deleting) {
        DeleteAccountDialog(
            me = me,
            busy = deleting,
            onConfirm = { AccountDeletion.start(context) },
            onDismiss = { confirmDelete = false },
        )
    }

    // The Google account already belongs to another Humdinger profile (e.g. from an old phone).
    existingAccount?.let { credential ->
        AlertDialog(
            onDismissRequest = { existingAccount = null },
            title = { Text("Welcome back! 👋") },
            text = {
                Text(
                    "That Google account already has a Humdinger profile. Switch to it? " +
                        "This guest (${me.username}, ${me.points} points) will be left behind."
                )
            },
            confirmButton = {
                Button(onClick = {
                    existingAccount = null
                    scope.launch {
                        try {
                            PushTokens.unregister(me.uid)
                            UserRepository.switchTo(credential)
                        } catch (e: Exception) {
                            toast(e.message ?: "Couldn't switch accounts.")
                        }
                    }
                }) { Text("Switch account") }
            },
            dismissButton = { TextButton(onClick = { existingAccount = null }) { Text("Stay as guest") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You'll carry on as a new guest. Sign in with Google again any time to get your profile back.") },
            confirmButton = {
                Button(onClick = {
                    confirmSignOut = false
                    scope.launch {
                        PushTokens.unregister(me.uid)
                        GoogleSignIn.clear(context)
                        UserRepository.signOut()
                    }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/** A titled card holding a group of settings. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A question with a few answers as chips, e.g. "Who can message me: Friends / Nobody". */
@Composable
private fun ChoiceRow(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    description: String? = null,
) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChipChoices(choices, selected, onSelect)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipChoices(choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { if (value != selected) onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** Shows an hour like "22:00"; tap to pick another. */
@Composable
private fun HourPicker(hour: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("%02d:00".format(hour)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { h ->
                DropdownMenuItem(
                    text = { Text("%02d:00".format(h)) },
                    onClick = {
                        open = false
                        if (h != hour) onPick(h)
                    },
                )
            }
        }
    }
}
