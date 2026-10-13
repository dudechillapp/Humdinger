package com.kowalski.humdinger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.kowalski.humdinger.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kowalski.humdinger.ui.theme.Coral
import com.kowalski.humdinger.ui.theme.Grape
import com.kowalski.humdinger.ui.theme.Mint
import com.kowalski.humdinger.ui.theme.Sunny
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kowalski.humdinger.data.Tier
import com.kowalski.humdinger.data.Tiers
import com.kowalski.humdinger.data.UserRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val avatarDrawables = listOf(
    R.drawable.avatar_tier_0, R.drawable.avatar_tier_1, R.drawable.avatar_tier_2, R.drawable.avatar_tier_3,
    R.drawable.avatar_tier_4, R.drawable.avatar_tier_5, R.drawable.avatar_tier_6, R.drawable.avatar_tier_7,
)

/**
 * The player's character: a smiley with headphones that gets cooler kit at every tier —
 * sunglasses, a backwards cap, a radio mic, a top hat, a wizard hat and finally a crown.
 */
@Composable
fun TierAvatar(tierIndex: Int, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(avatarDrawables[tierIndex.coerceIn(0, avatarDrawables.lastIndex)]),
        contentDescription = Tiers.all.getOrNull(tierIndex)?.name,
        modifier = modifier.size(size),
    )
}

/** Someone's character, looked up live from their id (shown next to their posts and guesses). */
@Composable
fun TierAvatarFor(uid: String?, size: Dp, modifier: Modifier = Modifier) {
    if (uid.isNullOrEmpty()) return
    val points by remember(uid) { UserRepository.points(uid) }.collectAsStateWithLifecycle(initialValue = null)
    TierAvatar(tierIndex = Tiers.indexFor(points ?: 0L), size = size, modifier = modifier)
}

/** Small "🎸 Air Guitarist" label. */
@Composable
fun TierLabel(points: Long, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.tertiary) {
    val tier = Tiers.forPoints(points)
    Text(
        "${tier.emoji} ${tier.name}",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
    )
}

/** Someone's current tier, looked up live from their id (shown next to their posts and guesses). */
@Composable
fun TierLabelFor(uid: String?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.tertiary) {
    if (uid.isNullOrEmpty()) return
    val points by remember(uid) { UserRepository.points(uid) }.collectAsStateWithLifecycle(initialValue = null)
    points?.let { TierLabel(it, modifier, color) }
}

/** Current tier, progress to the next one, and the full ladder. */
@Composable
fun TierCard(points: Long, modifier: Modifier = Modifier) {
    val current = Tiers.forPoints(points)
    val next = Tiers.next(points)
    var showLadder by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.emoji, fontSize = 40.sp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Your tier", style = MaterialTheme.typography.labelMedium)
                    Text(current.name, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (next != null) {
                val span = (next.minPoints - current.minPoints).toFloat()
                val progress = ((points - current.minPoints) / span).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${next.minPoints - points} more points to become ${next.emoji} ${next.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("You've reached the top. Absolute legend! 🎉", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { showLadder = !showLadder }, modifier = Modifier.align(Alignment.End)) {
                Text(if (showLadder) "Hide tiers" else "See all tiers")
            }
            if (showLadder) {
                Tiers.all.forEach { tier ->
                    val unlocked = points >= tier.minPoints
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (unlocked) tier.emoji else "🔒", fontSize = 20.sp)
                        Text(
                            tier.name,
                            modifier = Modifier.padding(start = 10.dp).weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (unlocked) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("${tier.minPoints} pts", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * The celebration when someone reaches a new tier. The higher the tier, the bigger the
 * smiley, the louder the shout and the more confetti.
 */
@Composable
fun LevelUpDialog(tier: Tier, onDismiss: () -> Unit) {
    val index = Tiers.all.indexOf(tier).coerceAtLeast(1)
    val pop = remember { Animatable(0.2f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val article = if (tier.name.first().lowercaseChar() in "aeiou") "an" else "a"

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Confetti(pieces = 20 + index * 22, modifier = Modifier.fillMaxSize())
            Card(
                modifier = Modifier.padding(28.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(tier.shout, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    TierAvatar(tierIndex = index, size = (120 + index * 8).dp, modifier = Modifier.scale(pop.value))
                    if (tier.celebration.isNotEmpty()) Text(tier.celebration, fontSize = 28.sp)
                    Text(
                        "You're now $article ${tier.emoji} ${tier.name}",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(tier.levelUpMessage, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = onDismiss) { Text("Let's go! 🎶") }
                }
            }
        }
    }
}

private class ConfettiPiece(
    val x: Float,
    val speed: Float,
    val phase: Float,
    val size: Float,
    val color: Color,
    val spin: Float,
)

/** Falling confetti in the brand colours. */
@Composable
private fun Confetti(pieces: Int, modifier: Modifier = Modifier) {
    val palette = listOf(Coral, Sunny, Grape, Mint, Color.White)
    val items = remember(pieces) {
        List(pieces) {
            ConfettiPiece(
                x = Random.nextFloat(),
                speed = 0.6f + Random.nextFloat() * 0.8f,
                phase = Random.nextFloat(),
                size = 14f + Random.nextFloat() * 16f,
                color = palette.random(),
                spin = Random.nextFloat() * 360f,
            )
        }
    }
    val time by rememberInfiniteTransition(label = "confetti").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_000, easing = LinearEasing)),
        label = "confettiTime",
    )
    Canvas(modifier) {
        items.forEach { p ->
            val progress = (time * p.speed + p.phase) % 1f
            val y = progress * (size.height + 60f) - 30f
            val x = p.x * size.width + sin((progress + p.phase) * 6.28f) * 24f
            rotate(p.spin + progress * 720f, pivot = Offset(x, y)) {
                drawRect(
                    color = p.color,
                    topLeft = Offset(x - p.size / 2, y - p.size / 4),
                    size = Size(p.size, p.size / 2),
                )
            }
        }
    }
}
