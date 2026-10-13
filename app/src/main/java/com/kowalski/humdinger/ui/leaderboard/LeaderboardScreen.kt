package com.kowalski.humdinger.ui.leaderboard

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.data.UserRepository
import com.kowalski.humdinger.ui.components.CenteredLoading
import com.kowalski.humdinger.ui.components.EmptyState
import com.kowalski.humdinger.ui.components.TierAvatar
import com.kowalski.humdinger.ui.components.TierLabel
import com.kowalski.humdinger.data.Tiers
import com.kowalski.humdinger.ui.friends.openPersonOnClick
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LeaderboardViewModel : ViewModel() {
    /** null while loading. */
    val top: StateFlow<List<UserProfile>?> = UserRepository.leaderboard()
        // People can choose not to be listed (Settings → Privacy).
        .map { list -> list.filter { it.onLeaderboard } }
        .catch { e ->
            Log.e("Humdinger", "Leaderboard failed", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(me: UserProfile, vm: LeaderboardViewModel = viewModel()) {
    val top by vm.top.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("🏆 Top Namers") }) },
    ) { padding ->
        val list = top
        Box(Modifier.padding(padding).fillMaxSize()) {
        when {
            list == null -> CenteredLoading()
            list.isEmpty() -> EmptyState("🏆", "No champions yet", "Name someone's hum to get on the board.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(list, key = { _, u -> u.uid }) { index, user ->
                    val isMe = user.uid == me.uid
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMe) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .openPersonOnClick(user.uid, user.username)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (index) {
                                    0 -> "🥇"
                                    1 -> "🥈"
                                    2 -> "🥉"
                                    else -> "#${index + 1}"
                                },
                                fontSize = if (index < 3) 28.sp else 18.sp,
                                modifier = Modifier.width(48.dp),
                            )
                            TierAvatar(tierIndex = Tiers.indexFor(user.points), size = 44.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${user.username}" + if (isMe) " (you)" else "",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TierLabel(user.points)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("${user.points} pts", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        }
    }
}
