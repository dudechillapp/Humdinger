package com.kowalski.humdinger.ui.detail

import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.Chat
import com.kowalski.humdinger.data.GuessReports
import com.kowalski.humdinger.data.UserReports
import com.kowalski.humdinger.data.Guess
import com.kowalski.humdinger.data.GuessReply
import com.kowalski.humdinger.data.HumComment
import com.kowalski.humdinger.data.AppPrefs
import com.kowalski.humdinger.data.Friends
import com.kowalski.humdinger.data.Privacy
import com.kowalski.humdinger.data.UserRepository
import com.kowalski.humdinger.data.CommentFilter
import com.kowalski.humdinger.data.CommentReports
import com.kowalski.humdinger.data.ReplyReports
import com.kowalski.humdinger.data.Limits
import com.kowalski.humdinger.data.Links
import com.kowalski.humdinger.data.ThankYouTarget
import com.kowalski.humdinger.data.ThanksRepository
import com.kowalski.humdinger.data.Points
import com.kowalski.humdinger.data.Reaction
import com.kowalski.humdinger.data.RequestRepository
import com.kowalski.humdinger.data.SongRequest
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.ui.components.CenteredLoading
import com.kowalski.humdinger.ui.components.ClipPlayer
import com.kowalski.humdinger.ui.components.EmptyState
import com.kowalski.humdinger.ui.components.modeEmoji
import com.kowalski.humdinger.ui.components.modeVerb
import com.kowalski.humdinger.ui.components.rememberOpenLink
import com.kowalski.humdinger.ui.components.timeAgo
import com.kowalski.humdinger.ui.components.TierLabelFor
import com.kowalski.humdinger.ui.components.TierAvatarFor
import com.kowalski.humdinger.ui.safety.BlockDialog
import com.kowalski.humdinger.ui.friends.openPersonOnClick
import com.kowalski.humdinger.ui.safety.ReasonDialog
import com.kowalski.humdinger.data.MediaType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Guesses from deleted accounts on solved songs are kept with this author id (see functions/index.js). */
private const val DELETED_USER = "deleted"

/** Missing = deleted, never existed, or couldn't be loaded. */
sealed interface DetailState {
    data object Loading : DetailState
    data object Missing : DetailState
    data class Loaded(val request: SongRequest) : DetailState
}

class RequestDetailViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val requestId: String = checkNotNull(savedStateHandle["id"])
    private val myUid: String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<DetailState> = RequestRepository.request(requestId)
        .map { if (it == null) DetailState.Missing else DetailState.Loaded(it) }
        .catch { e ->
            Log.e("Humdinger", "Request failed", e)
            emit(DetailState.Missing)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    val guesses: StateFlow<List<Guess>> = RequestRepository.guesses(requestId)
        .catch { e ->
            Log.e("Humdinger", "Guesses failed", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Replies, grouped by the guess they're under. */
    val replies: StateFlow<Map<String, List<GuessReply>>> = RequestRepository.replies(requestId)
        .map { list -> list.groupBy { it.guessId } }
        .catch { e ->
            Log.e("Humdinger", "Replies failed", e)
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Comments on the hum ("Love the effort 😂"). */
    val comments: StateFlow<List<HumComment>> = RequestRepository.comments(requestId)
        .catch { e ->
            Log.e("Humdinger", "Comments failed", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val myReaction: StateFlow<Reaction?> =
        (if (myUid.isEmpty()) emptyFlow<Reaction?>() else RequestRepository.myReaction(requestId, myUid))
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the requester has already sent their one thank-you. */
    val alreadyThanked: StateFlow<Boolean> = ThanksRepository.alreadyThanked(requestId)
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private fun launchWithToast(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { _messages.emit(it) }
            } catch (e: Exception) {
                Log.e("Humdinger", "Action failed", e)
                _messages.emit(e.message ?: "Something went wrong.")
            }
        }
    }

    fun react(reaction: Reaction) = launchWithToast(null) {
        RequestRepository.toggleReaction(requestId, myUid, reaction)
    }

    fun guess(me: UserProfile, title: String, artist: String, link: String, comment: String) =
        launchWithToast("Guess posted! 🎯") {
            RequestRepository.addGuess(requestId, me, title, artist, link, comment)
        }

    fun reply(me: UserProfile, guess: Guess, text: String) = viewModelScope.launch {
        try {
            RequestRepository.addReply(requestId, guess, me, text)
            _messages.emit("Reply posted 💬")
        } catch (e: Exception) {
            Log.e("Humdinger", "Reply failed", e)
            val denied = (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            _messages.emit(if (denied) "You can't reply to this one." else e.message ?: "Couldn't post your reply.")
        }
    }

    fun comment(me: UserProfile, text: String) = viewModelScope.launch {
        try {
            RequestRepository.addComment(requestId, me, text)
            _messages.emit("Comment posted 💬")
        } catch (e: Exception) {
            Log.e("Humdinger", "Comment failed", e)
            val denied = (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            _messages.emit(if (denied) "You can't comment on this one." else e.message ?: "Couldn't post your comment.")
        }
    }

    fun deleteComment(comment: HumComment) =
        launchWithToast("Comment deleted.") { RequestRepository.deleteComment(requestId, comment.id) }

    fun reportComment(comment: HumComment, reason: String) =
        launchWithToast("Thanks for reporting. We'll take a look.") {
            CommentReports.report(requestId, comment, myUid, reason)
        }

    fun deleteReply(reply: GuessReply) =
        launchWithToast("Reply deleted.") { RequestRepository.deleteReply(requestId, reply.id) }

    fun reportReply(reply: GuessReply, reason: String) =
        launchWithToast("Thanks for reporting. We'll take a look.") {
            ReplyReports.report(requestId, reply, myUid, reason)
        }

    fun confirm(request: SongRequest, winner: Guess) =
        launchWithToast("Solved! 🎉 Points handed out.") {
            RequestRepository.confirmGuess(request, winner, myUid)
        }

    fun report(reason: String) = viewModelScope.launch {
        try {
            val firstTime = RequestRepository.report(requestId, myUid, reason)
            _messages.emit(
                if (firstTime) "Thanks for reporting. If a few people report it, it's removed automatically."
                else "You've already reported this one. Thanks!"
            )
        } catch (e: Exception) {
            Log.e("Humdinger", "Report failed", e)
            _messages.emit(e.message ?: "Couldn't send the report.")
        }
    }

    fun sendTextThanks(me: UserProfile, target: ThankYouTarget, text: String) =
        launchWithToast("Thanks sent! 🎁 Carry on the chat in ✉️ Messages.") {
            ThanksRepository.sendTextThanks(me, target, text)
        }

    /** People the signed-in person has blocked: their guesses are hidden. */
    val blocked: StateFlow<Set<String>> = Blocks.ids

    fun block(uid: String, name: String) =
        launchWithToast("Blocked $name. You won't see them any more. 🚫") { Blocks.block(myUid, uid, name) }

    fun unblock(uid: String, name: String) =
        launchWithToast("Unblocked $name.") { Blocks.unblock(myUid, uid) }

    fun reportPerson(uid: String, name: String, reason: String) =
        launchWithToast("Thanks for reporting $name. We'll take a look.") {
            UserReports.report(myUid, uid, name, reason)
        }

    fun reportGuess(guess: Guess, reason: String) =
        launchWithToast("Thanks for reporting. We'll take a look.") {
            GuessReports.report(requestId, guess, myUid, reason)
        }

    fun deleteNow(onDone: () -> Unit) = viewModelScope.launch {
        try {
            RequestRepository.deleteNow(requestId)
            _messages.emit("Deleted. It's gone from the feed. 🗑️")
            onDone()
        } catch (e: Exception) {
            Log.e("Humdinger", "Delete failed", e)
            _messages.emit(e.message ?: "Couldn't delete it. Try again?")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailScreen(
    me: UserProfile,
    onBack: () -> Unit,
    onThank: (ThankYouTarget) -> Unit,
    vm: RequestDetailViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val guesses by vm.guesses.collectAsStateWithLifecycle()
    val myReaction by vm.myReaction.collectAsStateWithLifecycle()
    val alreadyThanked by vm.alreadyThanked.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val replies by vm.replies.collectAsStateWithLifecycle()
    val comments by vm.comments.collectAsStateWithLifecycle()
    val autoplay = remember { AppPrefs.autoplay.value } // read once, so changing it later doesn't restart the clip
    val friends by Friends.friends.collectAsStateWithLifecycle()
    val openLink = rememberOpenLink()

    var showGuessDialog by rememberSaveable { mutableStateOf(false) }
    var toCrown by remember { mutableStateOf<Guess?>(null) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var thankChoice by remember { mutableStateOf<ThankYouTarget?>(null) }
    var writingThanks by remember { mutableStateOf<ThankYouTarget?>(null) }
    // Blocking and reporting: (their id, their name), and the guess being reported.
    var toBlock by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reportingGuess by remember { mutableStateOf<Guess?>(null) }
    var reportingPerson by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Replies: the guess being replied to, and a reply being reported or deleted.
    var replyingTo by remember { mutableStateOf<Guess?>(null) }
    var reportingReply by remember { mutableStateOf<GuessReply?>(null) }
    var deletingReply by remember { mutableStateOf<GuessReply?>(null) }
    var commenting by rememberSaveable { mutableStateOf(false) }
    var deletingComment by remember { mutableStateOf<HumComment?>(null) }
    var reportingComment by remember { mutableStateOf<HumComment?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val loaded = state as? DetailState.Loaded
    val request = loaded?.request
    val isOwner = request?.ownerId == me.uid
    val ownerBlocked = request != null && !isOwner && request.ownerId in blocked

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (request != null) "${request.ownerName}" else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
                },
                actions = {
                    if (request != null) {
                        IconButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 22.sp) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (isOwner) {
                                DropdownMenuItem(
                                    text = { Text("🗑️  Delete now") },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("🚩  Report this clip") },
                                    onClick = {
                                        menuOpen = false
                                        showReport = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("🙋  Report ${request.ownerName}") },
                                    onClick = {
                                        menuOpen = false
                                        reportingPerson = request.ownerId to request.ownerName
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (ownerBlocked) "Unblock ${request.ownerName}" else "🚫  Block ${request.ownerName}")
                                    },
                                    onClick = {
                                        menuOpen = false
                                        if (ownerBlocked) vm.unblock(request.ownerId, request.ownerName)
                                        else toBlock = request.ownerId to request.ownerName
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (request != null && !request.isSolved && !isOwner && !ownerBlocked) {
                Button(
                    onClick = { showGuessDialog = true },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) { Text("🎯  I know this!") }
            }
        },
    ) { padding ->
        when (state) {
            DetailState.Loading -> Box(Modifier.padding(padding)) { CenteredLoading() }
            DetailState.Missing -> Box(Modifier.padding(padding)) {
                EmptyState("👻", "Gone!", "This request doesn't exist any more.")
            }
            is DetailState.Loaded -> if (ownerBlocked) {
                Column(
                    Modifier.padding(padding).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState("🚫", "You blocked ${request!!.ownerName}", "Their requests are hidden from you.")
                    TextButton(onClick = { vm.unblock(request.ownerId, request.ownerName) }) { Text("Unblock") }
                }
            } else {
                val r = request!!
                val visibleGuesses = guesses.filter { it.authorId !in blocked }
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.openPersonOnClick(r.ownerId, r.ownerName),
                        ) {
                            TierAvatarFor(r.ownerId, size = 56.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "${modeEmoji(r.mediaType)} ${r.ownerName} ${modeVerb(r.mediaType)} · ${timeAgo(r.createdAt)}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                TierLabelFor(r.ownerId)
                            }
                        }
                    }
                    if (r.hint.isNotBlank()) {
                        item { Text("“${r.hint}”", style = MaterialTheme.typography.bodyLarge) }
                    }
                    item {
                        ClipPlayer(r.mediaUrl, isVideo = r.mediaType == MediaType.VIDEO, autoPlay = autoplay)
                    }
                    item {
                        ReactionRow(r, myReaction, onReact = vm::react)
                    }
                    item(key = "comments") {
                        // The hummer's "Who can comment on my hums" setting.
                        val ownerFlow = remember(r.ownerId) { UserRepository.profile(r.ownerId).catch { emit(null) } }
                        val owner by ownerFlow.collectAsStateWithLifecycle(initialValue = null)
                        val canComment = Privacy.canComment(owner, me.uid, friends.any { it.uid == r.ownerId })
                        CommentsSection(
                            canAdd = canComment,
                            closedNote = when (owner?.commentsFrom) {
                                Privacy.FRIENDS -> "Only ${r.ownerName}'s friends can comment."
                                else -> "${r.ownerName} has turned comments off."
                            },
                            comments = comments.filter { it.authorId !in blocked },
                            myUid = me.uid,
                            ownerId = r.ownerId,
                            onAdd = { commenting = true },
                            onDelete = { deletingComment = it },
                            onReport = { reportingComment = it },
                            onBlock = { toBlock = it.authorId to it.authorName },
                        )
                    }
                    r.expireAt?.let { expiry ->
                        item {
                            Text(
                                "🗑️ Disappears ${DateUtils.getRelativeTimeSpanString(expiry.toDate().time)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (r.isSolved) {
                        item { SolvedBanner(r, onOpenLink = openLink) }
                        if (isOwner && r.solverId != null) {
                            item {
                                if (alreadyThanked) {
                                    Text(
                                        "🎁 You sent ${r.solverName ?: "them"} a thank-you. Keep chatting in ✉️ Messages!",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            thankChoice = ThankYouTarget(
                                                requestId = r.id,
                                                solverId = r.solverId,
                                                solverName = r.solverName ?: "them",
                                                songTitle = r.solvedTitle ?: "",
                                                songArtist = r.solvedArtist ?: "",
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                    ) { Text("🎁  Say thanks to ${r.solverName ?: "them"}") }
                                }
                            }
                        }
                    } else if (isOwner) {
                        item {
                            Text(
                                "When someone gets it, tap 👑 on their guess. They get ${Points.SOLVER} points " +
                                    "and everyone else who had a go gets ${Points.PARTICIPANT}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        Text(
                            "Guesses (${visibleGuesses.size})",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (visibleGuesses.isEmpty()) {
                        item {
                            Text(
                                if (isOwner) "No guesses yet — hang tight, the crowd's listening."
                                else "No guesses yet. Be the hero!",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(visibleGuesses, key = { it.id }) { g ->
                        GuessCard(
                            guess = g,
                            isWinner = r.solvedGuessId == g.id,
                            canCrown = isOwner && !r.isSolved,
                            isMine = g.authorId == me.uid,
                            myUid = me.uid,
                            requestOwnerId = r.ownerId,
                            replies = replies[g.id].orEmpty().filter { it.authorId !in blocked },
                            onOpenLink = openLink,
                            onCrown = { toCrown = g },
                            onReport = { reportingGuess = g },
                            onReportPerson = { reportingPerson = g.authorId to g.authorName },
                            onBlock = { toBlock = g.authorId to g.authorName },
                            onReply = { replyingTo = g },
                            onDeleteReply = { deletingReply = it },
                            onReportReply = { reportingReply = it },
                            onBlockReplier = { toBlock = it.authorId to it.authorName },
                        )
                    }
                }
            }
        }
    }

    if (showGuessDialog) {
        GuessDialog(
            onDismiss = { showGuessDialog = false },
            onSubmit = { title, artist, link, comment ->
                showGuessDialog = false
                vm.guess(me, title, artist, link, comment)
            },
        )
    }

    toCrown?.let { g ->
        if (request != null) {
            AlertDialog(
                onDismissRequest = { toCrown = null },
                title = { Text("👑 That's the one?") },
                text = {
                    Text(
                        "“${g.title}”${if (g.artist.isNotBlank()) " by ${g.artist}" else ""}, " +
                            "guessed by ${g.authorName}. This closes your request and hands out the points."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        toCrown = null
                        vm.confirm(request, g)
                    }) { Text("Yes, crown it!") }
                },
                dismissButton = { TextButton(onClick = { toCrown = null }) { Text("Not yet") } },
            )
        }
    }

    // Say thanks: write a message, or record one.
    thankChoice?.let { target ->
        AlertDialog(
            onDismissRequest = { thankChoice = null },
            title = { Text("🎁 Say thanks to ${target.solverName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("It lands in their ✉️ Messages, and then you can chat. You can send one thank-you per song.")
                    Button(
                        onClick = {
                            thankChoice = null
                            writingThanks = target
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("✍️  Write a message") }
                    FilledTonalButton(
                        onClick = {
                            thankChoice = null
                            onThank(target)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🎤  Record a hum, song or video") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { thankChoice = null }) { Text("Not now") } },
        )
    }
    writingThanks?.let { target ->
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writingThanks = null },
            title = { Text("✍️ Thanks, ${target.solverName}!") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Chat.MAX_THANKS_CHARS) },
                    placeholder = { Text("e.g. You legend! That tune's been stuck in my head for weeks 🙌") },
                    minLines = 3,
                    maxLines = 6,
                    supportingText = { Text("${text.length}/${Chat.MAX_THANKS_CHARS}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = text.isNotBlank(),
                    onClick = {
                        writingThanks = null
                        vm.sendTextThanks(me, target, text)
                    },
                ) { Text("Send thanks 🎁") }
            },
            dismissButton = { TextButton(onClick = { writingThanks = null }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this request?") },
            text = { Text("Your clip, its guesses and reactions will be deleted for good. Points already handed out stay.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    vm.deleteNow(onDone = onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }

    toBlock?.let { (uid, name) ->
        BlockDialog(
            name = name,
            onConfirm = {
                toBlock = null
                vm.block(uid, name)
            },
            onDismiss = { toBlock = null },
        )
    }

    reportingPerson?.let { (uid, name) ->
        ReasonDialog(
            title = "Report $name",
            reasons = UserReports.REASONS,
            onPick = { reason ->
                reportingPerson = null
                vm.reportPerson(uid, name, reason)
            },
            onDismiss = { reportingPerson = null },
        )
    }

    reportingGuess?.let { g ->
        ReasonDialog(
            title = "Report ${g.authorName}'s guess",
            reasons = listOf("Offensive or hateful", "Harassment or bullying", "Sexual content", "Spam or advertising", "Copyright problem", "Something else"),
            onPick = { reason ->
                reportingGuess = null
                vm.reportGuess(g, reason)
            },
            onDismiss = { reportingGuess = null },
        )
    }

    replyingTo?.let { g ->
        ReplyDialog(
            guess = g,
            isRequester = isOwner,
            requestSolved = request?.isSolved == true,
            onDismiss = { replyingTo = null },
            onSend = { text ->
                replyingTo = null
                vm.reply(me, g, text)
            },
        )
    }

    if (commenting && request != null) {
        CommentDialog(
            request = request,
            isOwner = isOwner,
            onDismiss = { commenting = false },
            onSend = { text ->
                commenting = false
                vm.comment(me, text)
            },
        )
    }

    deletingComment?.let { comment ->
        val mine = comment.authorId == me.uid
        AlertDialog(
            onDismissRequest = { deletingComment = null },
            title = { Text(if (mine) "Delete your comment?" else "Remove ${comment.authorName}'s comment?") },
            text = { Text("“${comment.text}”") },
            confirmButton = {
                Button(onClick = {
                    deletingComment = null
                    vm.deleteComment(comment)
                }) { Text(if (mine) "Delete" else "Remove") }
            },
            dismissButton = { TextButton(onClick = { deletingComment = null }) { Text("Keep it") } },
        )
    }

    reportingComment?.let { comment ->
        ReasonDialog(
            title = "Report ${comment.authorName}'s comment",
            reasons = listOf("Offensive or hateful", "Harassment or bullying", "Sexual content", "Spam or advertising", "Something else"),
            onPick = { reason ->
                reportingComment = null
                vm.reportComment(comment, reason)
            },
            onDismiss = { reportingComment = null },
        )
    }

    deletingReply?.let { reply ->
        AlertDialog(
            onDismissRequest = { deletingReply = null },
            title = { Text("Delete your reply?") },
            text = { Text("“${reply.text}”") },
            confirmButton = {
                Button(onClick = {
                    deletingReply = null
                    vm.deleteReply(reply)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingReply = null }) { Text("Keep it") } },
        )
    }

    reportingReply?.let { reply ->
        ReasonDialog(
            title = "Report ${reply.authorName}'s reply",
            reasons = listOf("Offensive or hateful", "Harassment or bullying", "Sexual content", "Spam or advertising", "Something else"),
            onPick = { reason ->
                reportingReply = null
                vm.reportReply(reply, reason)
            },
            onDismiss = { reportingReply = null },
        )
    }

    if (showReport) {
        ReportDialog(
            onDismiss = { showReport = false },
            onReport = { reason ->
                showReport = false
                vm.report(reason)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionRow(request: SongRequest, mine: Reaction?, onReact: (Reaction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Reaction.entries.forEach { r ->
            val selected = mine == r
            val count = request.reactionCounts[r.key] ?: 0L
            Surface(
                onClick = { onReact(r) },
                shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(r.emoji, fontSize = 26.sp)
                    Text(
                        if (count > 0) "$count" else r.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SolvedBanner(request: SongRequest, onOpenLink: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
        ),
    ) {
        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🎉 SOLVED!", style = MaterialTheme.typography.labelLarge)
            Text(request.solvedTitle ?: "", style = MaterialTheme.typography.headlineSmall)
            request.solvedArtist?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
            Text("Named by ${request.solverName ?: "someone"}", style = MaterialTheme.typography.bodyMedium)
            TierLabelFor(request.solverId, color = MaterialTheme.colorScheme.onTertiary)
            request.solvedLink?.let { link ->
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = { onOpenLink(link) }) {
                    Text("▶  Listen on ${Links.label(link)}")
                }
            }
        }
    }
}

@Composable
private fun GuessCard(
    guess: Guess,
    isWinner: Boolean,
    canCrown: Boolean,
    isMine: Boolean,
    myUid: String,
    requestOwnerId: String,
    replies: List<GuessReply>,
    onOpenLink: (String) -> Unit,
    onCrown: () -> Unit,
    onReport: () -> Unit,
    onReportPerson: () -> Unit,
    onBlock: () -> Unit,
    onReply: () -> Unit,
    onDeleteReply: (GuessReply) -> Unit,
    onReportReply: (GuessReply) -> Unit,
    onBlockReplier: (GuessReply) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWinner) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (isWinner) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierAvatarFor(guess.authorId, size = 44.dp, modifier = Modifier.openPersonOnClick(guess.authorId, guess.authorName))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).openPersonOnClick(guess.authorId, guess.authorName)) {
                    Text("${guess.authorName}", style = MaterialTheme.typography.labelLarge)
                    TierLabelFor(guess.authorId)
                }
                if (isWinner) Text("👑 Got it!", style = MaterialTheme.typography.labelLarge)
                else Text(timeAgo(guess.createdAt), style = MaterialTheme.typography.bodySmall)
                if (!isMine && guess.authorId != DELETED_USER) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 20.sp) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("🚩  Report guess") },
                                onClick = {
                                    menuOpen = false
                                    onReport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("🙋  Report ${guess.authorName}") },
                                onClick = {
                                    menuOpen = false
                                    onReportPerson()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("🚫  Block ${guess.authorName}") },
                                onClick = {
                                    menuOpen = false
                                    onBlock()
                                },
                            )
                        }
                    }
                }
            }
            Text(
                guess.title + if (guess.artist.isNotBlank()) " — ${guess.artist}" else "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (guess.comment.isNotBlank()) {
                Text("💬 ${guess.comment}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onOpenLink(guess.link) }) {
                    Text("▶  ${Links.label(guess.link)}")
                }
                if (canCrown) {
                    Button(onClick = onCrown) { Text("👑  That's it!") }
                }
            }

            // Replies: "Same thought!", "Not it, but so close!"… Long threads show the latest 3.
            if (replies.isNotEmpty()) {
                var showAll by rememberSaveable(guess.id) { mutableStateOf(false) }
                val shown = if (showAll || replies.size <= 3) replies else replies.takeLast(3)
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (shown.size < replies.size) {
                        TextButton(onClick = { showAll = true }) {
                            Text("Show all ${replies.size} replies")
                        }
                    }
                    shown.forEach { reply ->
                        ReplyRow(
                            reply = reply,
                            isMine = reply.authorId == myUid,
                            isRequester = reply.authorId == requestOwnerId,
                            isGuesser = reply.authorId == guess.authorId,
                            onDelete = { onDeleteReply(reply) },
                            onReport = { onReportReply(reply) },
                            onBlock = { onBlockReplier(reply) },
                        )
                    }
                }
            }
            TextButton(onClick = onReply) {
                Text("💬  Reply")
            }
        }
    }
}

@Composable
private fun ReplyRow(
    reply: GuessReply,
    isMine: Boolean,
    isRequester: Boolean,
    isGuesser: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp)) {
            TierAvatarFor(reply.authorId, size = 28.dp, modifier = Modifier.openPersonOnClick(reply.authorId, reply.authorName))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val who = when {
                    isRequester -> " · 🎤 asked"
                    isGuesser -> " · 🎯 guessed"
                    else -> ""
                }
                Text(
                    reply.authorName + who + "  ·  " + timeAgo(reply.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(reply.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (reply.authorId != DELETED_USER) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Text("⋮", fontSize = 18.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (isMine) {
                            DropdownMenuItem(
                                text = { Text("🗑️  Delete reply") },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("🚩  Report reply") },
                                onClick = {
                                    menuOpen = false
                                    onReport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("🚫  Block ${reply.authorName}") },
                                onClick = {
                                    menuOpen = false
                                    onBlock()
                                },
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

/** Write a short reply. A few ready-made ideas keep things friendly and quick. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyDialog(
    guess: Guess,
    isRequester: Boolean,
    requestSolved: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val friendly = CommentFilter.isAllowed(text)
    val ideas = when {
        requestSolved -> listOf("Great shout! 🙌", "Such a tune 🎶", "Knew it! 🎯")
        isRequester -> listOf("Not it, but so close! 🤏", "Getting warm! 🔥", "Nope, but thanks for trying 🙏")
        else -> listOf("Same thought! 🙌", "Ooh, good shout 👀", "Sounds so similar!")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💬 Reply to ${guess.authorName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "“${guess.title}”" + if (guess.artist.isNotBlank()) " — ${guess.artist}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ideas.forEach { idea ->
                        SuggestionChip(onClick = { text = idea }, label = { Text(idea) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Limits.MAX_REPLY_CHARS) },
                    placeholder = { Text("Say something nice…") },
                    minLines = 2,
                    maxLines = 4,
                    isError = !friendly,
                    supportingText = {
                        Text(if (friendly) "${text.length}/${Limits.MAX_REPLY_CHARS}" else "Let's keep it friendly 🙂 Try different words.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank() && friendly, onClick = { onSend(text) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GuessDialog(onDismiss: () -> Unit, onSubmit: (String, String, String, String) -> Unit) {
    var comment by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    val cleanLink = Links.extract(link)
    val linkOk = Links.isMusicLink(cleanLink)
    val canSubmit = title.isNotBlank() && linkOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎯 What's the song?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    label = { Text("Song title") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it.take(100) },
                    label = { Text("Artist (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Link to the song") },
                    placeholder = { Text("Paste from Share → Copy link") },
                    singleLine = true,
                    isError = link.isNotBlank() && !linkOk,
                    supportingText = {
                        Text(
                            if (link.isNotBlank() && !linkOk) "Paste a link from ${Links.SUPPORTED}"
                            else "From ${Links.SUPPORTED}. It lets everyone hear it, and earns you points."
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(Limits.MAX_COMMENT_CHARS) },
                    label = { Text("Comment (optional)") },
                    placeholder = { Text("e.g. It's the 2004 remix, I'd know that hum anywhere!") },
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            Button(enabled = canSubmit, onClick = { onSubmit(title, artist, cleanLink, comment) }) { Text("Post guess") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onReport: (String) -> Unit) {
    val reasons = listOf("Offensive or hateful", "Nudity or sexual content", "Spam", "Not a song request", "Copyright problem", "Something else")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this clip") },
        text = {
            Column {
                reasons.forEach { reason ->
                    TextButton(onClick = { onReport(reason) }, modifier = Modifier.fillMaxWidth()) {
                        Text(reason, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Comments on the hum: the latest few, with "Show all", and a button to add one. */
@Composable
private fun CommentsSection(
    canAdd: Boolean,
    closedNote: String,
    comments: List<HumComment>,
    myUid: String,
    ownerId: String,
    onAdd: () -> Unit,
    onDelete: (HumComment) -> Unit,
    onReport: (HumComment) -> Unit,
    onBlock: (HumComment) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll || comments.size <= 3) comments else comments.takeLast(3)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (comments.isEmpty()) "💬 Comments" else "💬 Comments (${comments.size})",
            style = MaterialTheme.typography.titleMedium,
        )
        if (comments.isEmpty() && canAdd) {
            Text(
                "No comments yet. Say something nice (or funny)!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (shown.size < comments.size) {
            TextButton(onClick = { showAll = true }) { Text("Show all ${comments.size} comments") }
        }
        shown.forEach { comment ->
            CommentRow(
                comment = comment,
                isMine = comment.authorId == myUid,
                isHummer = comment.authorId == ownerId,
                canRemove = myUid == ownerId && comment.authorId != myUid,
                onDelete = { onDelete(comment) },
                onReport = { onReport(comment) },
                onBlock = { onBlock(comment) },
            )
        }
        if (canAdd) {
            OutlinedButton(onClick = onAdd) { Text("💬  Add a comment") }
        } else {
            Text(closedNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommentRow(
    comment: HumComment,
    isMine: Boolean,
    isHummer: Boolean,
    canRemove: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp)) {
            TierAvatarFor(comment.authorId, size = 28.dp, modifier = Modifier.openPersonOnClick(comment.authorId, comment.authorName))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    comment.authorName + (if (isHummer) " · 🎤 hummer" else "") + "  ·  " + timeAgo(comment.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (comment.authorId != DELETED_USER) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Text("⋮", fontSize = 18.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (isMine || canRemove) {
                            DropdownMenuItem(
                                text = { Text(if (isMine) "🗑️  Delete comment" else "🗑️  Remove from my hum") },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                        if (!isMine) {
                            DropdownMenuItem(
                                text = { Text("🚩  Report comment") },
                                onClick = {
                                    menuOpen = false
                                    onReport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("🚫  Block ${comment.authorName}") },
                                onClick = {
                                    menuOpen = false
                                    onBlock()
                                },
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

/** Write a comment on a hum. Ready-made ideas keep it friendly (and quick). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentDialog(
    request: SongRequest,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val friendly = CommentFilter.isAllowed(text)
    val ideas = when {
        isOwner -> listOf("Thanks for the guesses! 🙏", "Here's a clue: it's from the 90s 📼", "It's driving me mad 😩")
        request.isSolved -> listOf("What a tune! 🎶", "Knew it! 🎯", "Now it's stuck in MY head 😅")
        else -> listOf(
            "Love the effort 😂",
            "That's a banger already 🔥",
            "I can hear it… just can't name it 😩",
            "Main character energy 🎤",
            "Brave! 👏",
            "Now it's stuck in MY head 😅",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💬 Comment on ${if (isOwner) "your" else request.ownerName + "'s"} ${if (request.mediaType == MediaType.VIDEO) "mime" else "hum"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ideas.forEach { idea ->
                        SuggestionChip(onClick = { text = idea }, label = { Text(idea) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Limits.MAX_HUM_COMMENT_CHARS) },
                    placeholder = { Text("Say something nice or funny…") },
                    minLines = 2,
                    maxLines = 4,
                    isError = !friendly,
                    supportingText = {
                        Text(if (friendly) "${text.length}/${Limits.MAX_HUM_COMMENT_CHARS}" else "Let's keep it friendly 🙂 Try different words.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank() && friendly, onClick = { onSend(text) }) { Text("Post") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
