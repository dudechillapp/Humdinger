package com.kowalski.humdinger.ui.inbox

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.Chat
import com.kowalski.humdinger.data.ChatMessage
import com.kowalski.humdinger.data.Conversation
import com.kowalski.humdinger.data.ThanksRepository
import com.kowalski.humdinger.data.Privacy
import com.kowalski.humdinger.data.UserRepository
import kotlinx.coroutines.flow.flowOf
import com.kowalski.humdinger.ui.components.CenteredLoading
import com.kowalski.humdinger.ui.components.ClipPlayer
import com.kowalski.humdinger.ui.components.EmptyState
import com.kowalski.humdinger.ui.components.TierAvatarFor
import com.kowalski.humdinger.ui.components.TierLabelFor
import com.kowalski.humdinger.ui.components.timeAgo
import com.kowalski.humdinger.ui.safety.BlockDialog
import com.kowalski.humdinger.ui.friends.openPersonOnClick
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    val conversationId: String = checkNotNull(savedStateHandle["id"])
    val myUid: String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts = _messages.asSharedFlow()

    /** null while loading. */
    val conversation: StateFlow<Conversation?> = ThanksRepository.conversation(conversationId)
        .catch { e -> Log.e("Humdinger", "Conversation failed", e); emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<ChatMessage>> = ThanksRepository.messages(conversationId)
        .catch { e -> Log.e("Humdinger", "Messages failed", e); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun launchWithToast(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            success?.let { _messages.emit(it) }
        } catch (e: Exception) {
            Log.e("Humdinger", "Chat action failed", e)
            _messages.emit(e.message ?: "Something went wrong.")
        }
    }

    fun send(text: String) = launchWithToast(null) { ThanksRepository.sendMessage(conversationId, myUid, text) }

    fun markRead() = viewModelScope.launch {
        runCatching { ThanksRepository.markConversationRead(conversationId, myUid) }
    }

    /** Blocks (or unblocks) the person everywhere, and closes (or reopens) this conversation. */
    fun setBlocked(blocked: Boolean, otherUid: String, otherName: String) =
        launchWithToast(if (blocked) "Blocked. They can't message you any more. 🚫" else "Unblocked.") {
            if (blocked) Blocks.block(myUid, otherUid, otherName) else Blocks.unblock(myUid, otherUid)
            ThanksRepository.setBlocked(conversationId, myUid, blocked)
        }

    fun report(reason: String) = launchWithToast("Thanks for reporting. We'll take a look.") {
        ThanksRepository.reportConversation(conversationId, myUid, reason)
    }
}

/** A conversation that started with a thank-you. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit, vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val convo by vm.conversation.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val me = vm.myUid

    var draft by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        vm.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Mark read when opened and whenever a new message arrives; keep the newest message in view.
    LaunchedEffect(messages.size, convo?.lastAt) {
        if (convo != null) vm.markRead()
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size) // +1 for the thank-you card
    }

    val c = convo
    val otherName = c?.otherName(me) ?: ""
    val blockedByMe = c?.blockedBy == me
    // Their "Who can message me" setting.
    val otherFlow = remember(c?.otherUid(me)) {
        c?.otherUid(me)?.let { UserRepository.profile(it).catch { emit(null) } } ?: flowOf(null)
    }
    val other by otherFlow.collectAsStateWithLifecycle(initialValue = null)
    val messagesOff = other?.messagesFrom == Privacy.NOBODY

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (c != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.openPersonOnClick(c.otherUid(me), otherName),
                        ) {
                            TierAvatarFor(c.otherUid(me), size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(otherName, style = MaterialTheme.typography.titleMedium)
                                TierLabelFor(c.otherUid(me))
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 24.sp) } },
                actions = {
                    if (c != null) {
                        IconButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 22.sp) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (blockedByMe) "Unblock $otherName" else "Block $otherName") },
                                onClick = {
                                    menuOpen = false
                                    if (blockedByMe) vm.setBlocked(false, c.otherUid(me), otherName) else confirmBlock = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Report this conversation") },
                                onClick = {
                                    menuOpen = false
                                    showReport = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (c != null) {
                Surface(tonalElevation = 3.dp) {
                    if (c.blockedBy != null || messagesOff) {
                        Text(
                            when {
                                blockedByMe -> "You blocked $otherName. Unblock them from the ⋮ menu to chat again."
                                c.blockedBy != null -> "You can't reply to this conversation."
                                else -> "$otherName has turned messages off."
                            },
                            modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it.take(Chat.MAX_MESSAGE_CHARS) },
                                placeholder = { Text("Message $otherName…") },
                                maxLines = 4,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    vm.send(draft)
                                    draft = ""
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text("Send") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                c == null -> CenteredLoading()
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "thanks") { if (c.isFriendChat) FriendChatHeader(c, me) else ThanksHeader(c, me) }
                    items(messages, key = { it.id }) { msg -> MessageBubble(msg, mine = msg.senderId == me) }
                    if (messages.isEmpty()) {
                        item(key = "hint") {
                            Text(
                                "Say hi! 👋 You can chat about the song, swap recommendations, whatever you like.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmBlock && c != null) {
        BlockDialog(
            name = otherName,
            onConfirm = {
                confirmBlock = false
                vm.setBlocked(true, c.otherUid(me), otherName)
            },
            onDismiss = { confirmBlock = false },
        )
    }

    if (showReport) {
        val reasons = listOf("Harassment or bullying", "Offensive or hateful", "Sexual content", "Spam or scam", "Rude or offensive name", "Seems to be under 16", "Something else")
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("Report this conversation") },
            text = {
                Column {
                    reasons.forEach { reason ->
                        TextButton(
                            onClick = {
                                showReport = false
                                vm.report(reason)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(reason, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showReport = false }) { Text("Cancel") } },
        )
    }
}

/** The top of a chat between friends. */
@Composable
private fun FriendChatHeader(c: Conversation, me: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "👥 You and ${c.otherName(me)} are friends. Swap hums, songs, whatever you like!",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** The thank-you that started the conversation. */
@Composable
private fun ThanksHeader(c: Conversation, me: String) {
    val fromMe = c.fromUid == me
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (fromMe) "🎁 You thanked ${c.toName}" else "🎁 ${c.fromName} says thanks!",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "For naming “${c.songTitle}”" +
                    (if (c.songArtist.isNotBlank()) " by ${c.songArtist}" else "") +
                    " · ${timeAgo(c.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!c.isTextThanks) {
                if (c.mediaExpired || c.mediaUrl.isBlank()) {
                    Text("🗑️ The recording has been deleted (its time was up).", style = MaterialTheme.typography.bodySmall)
                } else {
                    ClipPlayer(c.mediaUrl, isVideo = c.isVideo)
                }
            }
            if (c.note.isNotBlank()) {
                Text("“${c.note}”", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, mine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    timeAgo(msg.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
