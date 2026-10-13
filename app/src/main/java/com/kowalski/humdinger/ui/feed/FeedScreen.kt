package com.kowalski.humdinger.ui.feed

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.kowalski.humdinger.R
import com.kowalski.humdinger.data.AppPrefs
import com.kowalski.humdinger.data.Blocks
import com.kowalski.humdinger.data.Friends
import com.kowalski.humdinger.data.RequestRepository
import com.kowalski.humdinger.data.SongRequest
import com.kowalski.humdinger.ui.components.CenteredLoading
import com.kowalski.humdinger.ui.components.EmptyState
import com.kowalski.humdinger.ui.components.LogoLockup
import com.kowalski.humdinger.ui.components.RequestCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface FeedState {
    data object Loading : FeedState
    data class Loaded(val items: List<SongRequest>) : FeedState
    data class Failed(val message: String) : FeedState
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    val showSolved = MutableStateFlow(false)

    private val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private val prefs = app.getSharedPreferences("humdinger", Context.MODE_PRIVATE)
    private val seenKey = "seenSolved_$myUid"

    /** When this person last looked at the Solved tab (first time: now, so it doesn't start at 50). */
    private val lastSeenSolved = MutableStateFlow(
        prefs.getLong(seenKey, -1L).takeIf { it > 0 } ?: System.currentTimeMillis().also { saveSeen(it) }
    )

    /** Cards solved after this get a "New" pill while you're looking at the Solved tab. */
    val newCutoff = MutableStateFlow(lastSeenSolved.value)

    private fun saveSeen(time: Long) {
        prefs.edit().putLong(seenKey, time).apply()
    }

    private fun markSolvedSeen() {
        val now = System.currentTimeMillis()
        lastSeenSolved.value = now
        saveSeen(now)
    }

    /** How many songs were solved since you last looked (other people's, not anyone you've blocked). */
    val newSolvedCount: StateFlow<Int> =
        combine(
            RequestRepository.feed(solved = true).catch { emit(emptyList()) },
            lastSeenSolved,
            Blocks.ids,
            AppPrefs.feedFilter,
        ) { solved, seen, blocked, filter ->
            solved.count { r ->
                val at = r.solvedAt?.toDate()?.time ?: 0L
                at > seen && r.ownerId != myUid && r.ownerId !in blocked && filter.matches(r.mediaType)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun selectTab(solved: Boolean) {
        if (solved == showSolved.value) return
        if (solved) newCutoff.value = lastSeenSolved.value
        // Opening the Solved tab clears the badge; so does leaving it (you've seen what arrived meanwhile).
        markSolvedSeen()
        showSolved.value = solved
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<FeedState> = showSolved
        .flatMapLatest { solved ->
            // People you've blocked are hidden from your feed.
            combine(RequestRepository.feed(solved), Blocks.ids, AppPrefs.feedFilter) { items, blocked, filter ->
                items.filter { it.ownerId !in blocked && filter.matches(it.mediaType) }
            }
                .map<List<SongRequest>, FeedState> { FeedState.Loaded(it) }
                .onStart { emit(FeedState.Loading) }
                .catch { e ->
                    // A missing Firestore index shows up here; the log message contains a link to create it.
                    Log.e("Humdinger", "Feed failed", e)
                    emit(FeedState.Failed(e.message ?: "Couldn't load the feed."))
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedState.Loading)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    /** Used to highlight your own requests so you can spot them. */
    myUid: String,
    onOpen: (String) -> Unit,
    onRecord: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: FeedViewModel = viewModel(),
) {
    // Friend requests waiting for you (shown as a badge on the friends button).
    val friendRequests by Friends.incoming.collectAsStateWithLifecycle()
    val blocked by Blocks.ids.collectAsStateWithLifecycle()
    val waiting = friendRequests.count { it.fromUid !in blocked }

    val showSolved by vm.showSolved.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val newSolved by vm.newSolvedCount.collectAsStateWithLifecycle()
    val newCutoff by vm.newCutoff.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { LogoLockup() },
                navigationIcon = {
                    IconButton(onClick = onOpenFriends) {
                        BadgedBox(badge = { if (waiting > 0) Badge { Text("$waiting") } }) {
                            Icon(
                                painterResource(R.drawable.ic_friends),
                                contentDescription = "Friends",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Text("🎤", fontSize = 20.sp) },
                text = { Text("Hum it") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = if (showSolved) 1 else 0) {
                Tab(
                    selected = !showSolved,
                    onClick = { vm.selectTab(false) },
                    text = { Text("🤔 Who knows this?") },
                )
                Tab(
                    selected = showSolved,
                    onClick = { vm.selectTab(true) },
                    text = {
                        // How many songs were named while you were away.
                        if (!showSolved && newSolved > 0) {
                            BadgedBox(badge = { Badge { Text(if (newSolved > 9) "9+" else "$newSolved") } }) {
                                Text("🎉 Solved")
                            }
                        } else {
                            Text("🎉 Solved")
                        }
                    },
                )
            }

            when (val s = state) {
                FeedState.Loading -> CenteredLoading()
                is FeedState.Failed -> EmptyState("😵", "Feed's gone quiet", s.message)
                is FeedState.Loaded -> {
                    if (s.items.isEmpty()) {
                        if (showSolved) {
                            EmptyState("🕵️", "Nothing solved yet", "Crack a few hums and they'll show up here.")
                        } else {
                            EmptyState("🦗", "Crickets…", "No one's humming yet. Be the first — tap Hum it!")
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(s.items, key = { it.id }) { request ->
                                RequestCard(
                                    request,
                                    onClick = { onOpen(request.id) },
                                    isMine = request.ownerId == myUid,
                                    isNew = showSolved && request.ownerId != myUid &&
                                        (request.solvedAt?.toDate()?.time ?: 0L) > newCutoff,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
