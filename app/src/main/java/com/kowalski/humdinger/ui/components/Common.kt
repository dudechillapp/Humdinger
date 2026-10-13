package com.kowalski.humdinger.ui.components

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.kowalski.humdinger.data.MediaType
import com.kowalski.humdinger.data.Reaction
import com.kowalski.humdinger.data.SongRequest

fun timeAgo(ts: Timestamp?): String =
    ts?.let { DateUtils.getRelativeTimeSpanString(it.toDate().time).toString() } ?: "just now"

fun modeEmoji(type: MediaType) = if (type == MediaType.VIDEO) "🎭" else "🎤"

fun modeVerb(type: MediaType) = if (type == MediaType.VIDEO) "is miming" else "is humming"

/** Opens a YouTube/Spotify link in its app (or the browser). */
@Composable
fun rememberOpenLink(): (String) -> Unit {
    val context = LocalContext.current
    return { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 56.sp)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestCard(
    request: SongRequest,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** True on the front page for your own requests: coral tint, outline and a "Your request" badge. */
    isMine: Boolean = false,
    /** Solved since you last looked at the Solved tab. */
    isNew: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMine) colors.primaryContainer else colors.surfaceVariant,
            contentColor = if (isMine) colors.onPrimaryContainer else colors.onSurfaceVariant,
        ),
        border = if (isMine) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isMine) {
                Pill("⭐ Your request", colors.primary, colors.onPrimary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierAvatarFor(request.ownerId, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isMine) {
                            "${modeEmoji(request.mediaType)} You're ${if (request.mediaType == MediaType.VIDEO) "miming" else "humming"}"
                        } else {
                            "${modeEmoji(request.mediaType)} ${request.ownerName} ${modeVerb(request.mediaType)}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TierLabelFor(request.ownerId)
                        Text(
                            "  ·  ${timeAgo(request.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (request.isSolved) {
                    if (isNew) {
                        Pill("✨ New", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Pill("Solved", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
                } else {
                    Pill("Help!", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
            }

            if (request.hint.isNotBlank()) {
                Text(
                    "“${request.hint}”",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (request.isSolved && request.solvedTitle != null) {
                Text(
                    "🎉 ${request.solvedTitle}" +
                        (request.solvedArtist?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Reaction.entries.forEach { r ->
                    val n = request.reactionCounts[r.key] ?: 0L
                    if (n > 0) Text("${r.emoji} $n", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "💬 ${request.guessCount} ${if (request.guessCount == 1L) "guess" else "guesses"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
