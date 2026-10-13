package com.kowalski.humdinger.ui.inbox

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kowalski.humdinger.data.Conversation
import com.kowalski.humdinger.data.MediaType
import com.kowalski.humdinger.data.Notice
import com.kowalski.humdinger.data.ThanksRepository
import com.kowalski.humdinger.ui.components.CenteredLoading
import com.kowalski.humdinger.ui.components.EmptyState
import com.kowalski.humdinger.ui.components.Pill
import com.kowalski.humdinger.ui.components.TierAvatarFor
import com.kowalski.humdinger.ui.components.timeAgo

/** Messages tab: conversations that started with a thank-you, plus notices from Humdinger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    myUid: String,
    conversations: List<Conversation>?,
    notices: List<Notice>,
    onOpenConversation: (String) -> Unit,
) {
    // Opening the tab marks notices as read. (Conversations are marked read when opened.)
    LaunchedEffect(notices) {
        notices.filterNot { it.read }.forEach { notice ->
            runCatching { ThanksRepository.markNoticeRead(notice.id) }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("✉️ Messages") }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                conversations == null -> CenteredLoading()
                conversations.isEmpty() && notices.isEmpty() -> EmptyState(
                    "✉️",
                    "No messages yet",
                    "Name someone's song and they can send you a thank-you, or message a friend. Your chats appear here.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(notices, key = { "notice-" + it.id }) { notice -> NoticeCard(notice) }
                    items(conversations, key = { it.id }) { convo ->
                        ConversationRow(convo, myUid, onClick = { onOpenConversation(convo.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(convo: Conversation, myUid: String, onClick: () -> Unit) {
    val unread = convo.isUnreadFor(myUid)
    val iSentThanks = convo.fromUid == myUid
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unread) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TierAvatarFor(convo.otherUid(myUid), size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        convo.otherName(myUid),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(timeAgo(convo.lastAt), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (convo.isFriendChat) "👥 Friends"
                    else (if (iSentThanks) "You thanked them" else "Thanked you") + " for “${convo.songTitle}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (convo.lastSenderId == myUid) "You: " else "") + convo.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (unread) {
                Spacer(Modifier.width(8.dp))
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(12.dp)) {}
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⚠️ Your ${if (notice.mediaType == MediaType.VIDEO) "mime" else "hum"} was removed",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!notice.read) {
                    Pill("New", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
                }
            }
            Text(
                "Your request from ${timeAgo(notice.postedAt)}" +
                    (if (notice.hint.isNotBlank()) " (“${notice.hint}”)" else "") +
                    " was reported by ${notice.reportCount} people, so it was taken down automatically.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (notice.reasonSummary.isNotBlank()) {
                Text("What people reported: ${notice.reasonSummary}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "No hard feelings! Just keep clips friendly and about the song, and you're good to hum again. 🎶",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
