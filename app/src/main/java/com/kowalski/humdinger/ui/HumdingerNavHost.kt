package com.kowalski.humdinger.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.Friends
import com.kowalski.humdinger.data.HouseRules
import com.kowalski.humdinger.data.Legal
import com.kowalski.humdinger.data.ThankYouTarget
import com.kowalski.humdinger.data.ThanksRepository
import com.kowalski.humdinger.data.Tier
import com.kowalski.humdinger.data.Tiers
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.data.UserRepository
import com.kowalski.humdinger.push.DeepLinks
import com.kowalski.humdinger.push.Notifications
import com.kowalski.humdinger.push.PushTokens
import com.kowalski.humdinger.ui.components.LevelUpDialog
import com.kowalski.humdinger.ui.components.TierAvatar
import com.kowalski.humdinger.ui.components.rememberOpenLink
import com.kowalski.humdinger.ui.detail.RequestDetailScreen
import com.kowalski.humdinger.ui.feed.FeedScreen
import com.kowalski.humdinger.ui.friends.FriendsScreen
import com.kowalski.humdinger.ui.friends.LocalOpenPerson
import com.kowalski.humdinger.ui.friends.PersonDialog
import com.kowalski.humdinger.ui.settings.SettingsScreen
import com.kowalski.humdinger.ui.inbox.ChatScreen
import com.kowalski.humdinger.ui.inbox.InboxScreen
import com.kowalski.humdinger.ui.leaderboard.LeaderboardScreen
import com.kowalski.humdinger.ui.profile.ProfileScreen
import com.kowalski.humdinger.ui.profile.RenameDialog
import com.kowalski.humdinger.ui.profile.WelcomeDialog
import com.kowalski.humdinger.ui.record.RecordScreen
import com.kowalski.humdinger.ui.safety.HouseRulesDialog

private data class Tab(val route: String, val emoji: String, val label: String)

private val tabs = listOf(
    Tab("feed", "🎶", "Feed"),
    Tab("leaderboard", "🏆", "Leaderboard"),
    Tab("messages", "✉️", "Messages"),
    Tab("profile", "😎", "Me"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HumdingerNavHost(me: UserProfile, showWelcome: Boolean, onWelcomeSeen: () -> Unit) {
    val nav = rememberNavController()
    var renaming by remember { mutableStateOf(false) }

    // First launch: say hi and offer to change the random name.
    if (showWelcome) {
        WelcomeDialog(
            me = me,
            onKeep = onWelcomeSeen,
            onChange = {
                onWelcomeSeen()
                renaming = true
            },
        )
    }
    if (renaming) {
        RenameDialog(me = me, onDismiss = { renaming = false })
    }

    // Everyone agrees to the house rules once (and again if they change), after the welcome.
    val openLink = rememberOpenLink()
    val scope = rememberCoroutineScope()
    if (!showWelcome && !renaming && me.rulesVersion < HouseRules.VERSION) {
        HouseRulesDialog(
            onAgree = { scope.launch { runCatching { UserRepository.acceptRules(me.uid) } } },
            onClose = {},
            onOpenFullRules = { openLink(Legal.RULES_URL) },
        )
    }

    // Level-up celebration. The last tier we celebrated is remembered on the phone, so points
    // earned while the app was closed still get a party next time it opens.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("humdinger", Context.MODE_PRIVATE) }
    val tierIndex = Tiers.indexFor(me.points)
    var celebrate by remember { mutableStateOf<Tier?>(null) }
    LaunchedEffect(me.uid, tierIndex) {
        val key = "seenTier_${me.uid}"
        val seen = prefs.getInt(key, -1)
        if (seen >= 0 && tierIndex > seen) celebrate = Tiers.all[tierIndex]
        if (tierIndex != seen) prefs.edit().putInt(key, tierIndex).apply()
    }
    celebrate?.let { tier -> LevelUpDialog(tier, onDismiss = { celebrate = null }) }

    // Push notifications: keep this phone's push address on the account, and ask for
    // permission once (Android 13+), after the welcome message is out of the way.
    LaunchedEffect(me.uid) {
        runCatching { PushTokens.register(me.uid) }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(showWelcome) {
        if (!showWelcome && !Notifications.canNotify(context) && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A notification was tapped: go to the request, or to Messages.
    val deepLink by DeepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        DeepLinks.pending.value = null
        when {
            link.startsWith("request:") -> nav.navigate("request/${link.removePrefix("request:")}")
            link.startsWith("chat:") -> nav.navigate("chat/${link.removePrefix("chat:")}")
            link == "friends" -> nav.navigate("friends")
            link == "messages" -> nav.navigate("messages") {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    val openRequest: (String) -> Unit = { id -> nav.navigate("request/$id") }

    // Who this person has blocked: their content is hidden all over the app.
    LaunchedEffect(me.uid) { Blocks.keepUpdated(me.uid) }
    val blocked by Blocks.ids.collectAsStateWithLifecycle()

    // Friends and friend requests (the feed's friends button shows how many requests are waiting).
    LaunchedEffect(me.uid) { Friends.keepUpdated(me.uid) }

    // Tapping anyone's picture or name opens their card (add friend, message, block, report).
    var person by remember { mutableStateOf<Pair<String, String>?>(null) }
    val openPerson: (String, String) -> Unit = { uid, name -> person = uid to name }

    // Conversations (for the Messages tab and its unread badge), minus anyone they've blocked.
    val inboxFlow = remember(me.uid) { ThanksRepository.conversations(me.uid).catch { emit(emptyList()) } }
    val allInbox by inboxFlow.collectAsStateWithLifecycle(initialValue = null)
    val inbox = allInbox?.filterNot { it.otherUid(me.uid) in blocked }
    val noticesFlow = remember(me.uid) { ThanksRepository.notices(me.uid).catch { emit(emptyList()) } }
    val notices by noticesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val unread = (inbox?.count { it.isUnreadFor(me.uid) } ?: 0) + notices.count { !it.read }

    // Who we're recording a thank-you for (set just before opening the "thank" screen).
    var thankTarget by remember { mutableStateOf<ThankYouTarget?>(null) }

    person?.let { (uid, name) ->
        PersonDialog(
            me = me,
            uid = uid,
            name = name,
            onDismiss = { person = null },
            onOpenChat = { id -> nav.navigate("chat/$id") },
        )
    }

    CompositionLocalProvider(LocalOpenPerson provides openPerson) {
    Scaffold(
        // Each screen handles its own top insets; this outer scaffold only adds the bottom bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                // The Me tab shows their character, which gets cooler kit as they level up.
                                when {
                                    tab.route == "profile" ->
                                        TierAvatar(tierIndex = Tiers.indexFor(me.points), size = 30.dp)
                                    tab.route == "messages" && unread > 0 ->
                                        BadgedBox(badge = { Badge { Text("$unread") } }) {
                                            Text(tab.emoji, fontSize = 22.sp)
                                        }
                                    else -> Text(tab.emoji, fontSize = 22.sp)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "feed", modifier = Modifier.padding(padding)) {
            composable("feed") {
                FeedScreen(
                    myUid = me.uid,
                    onOpen = openRequest,
                    onRecord = { nav.navigate("record") },
                    onOpenFriends = { nav.navigate("friends") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("leaderboard") {
                LeaderboardScreen(me)
            }
            composable("messages") {
                InboxScreen(
                    myUid = me.uid,
                    conversations = inbox,
                    notices = notices,
                    onOpenConversation = { id -> nav.navigate("chat/$id") },
                )
            }
            composable(
                "chat/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                ChatScreen(onBack = { nav.popBackStack() })
            }
            composable("thank") {
                val target = thankTarget
                if (target == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    RecordScreen(
                        me = me,
                        onBack = { nav.popBackStack() },
                        onPosted = { nav.popBackStack() },
                        thankYou = target,
                    )
                }
            }
            composable("profile") {
                ProfileScreen(me, onOpen = openRequest, onOpenSettings = { nav.navigate("settings") })
            }
            composable("friends") {
                FriendsScreen(me, onBack = { nav.popBackStack() }, onOpenChat = { id -> nav.navigate("chat/$id") })
            }
            composable("settings") {
                SettingsScreen(me, onBack = { nav.popBackStack() })
            }
            composable("record") {
                RecordScreen(
                    me = me,
                    onBack = { nav.popBackStack() },
                    onPosted = { id ->
                        nav.navigate("request/$id") {
                            popUpTo("record") { inclusive = true }
                        }
                    },
                )
            }
            composable(
                "request/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                RequestDetailScreen(
                    me,
                    onBack = { nav.popBackStack() },
                    onThank = { target ->
                        thankTarget = target
                        nav.navigate("thank")
                    },
                )
            }
        }
    }
    }
}
