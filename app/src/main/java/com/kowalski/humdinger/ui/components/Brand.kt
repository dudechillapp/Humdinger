package com.kowalski.humdinger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kowalski.humdinger.R
import com.kowalski.humdinger.ui.theme.Fredoka

/** The logo mark: a speech bubble singing a note. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_logo),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/** "Humdinger" in the brand font. */
@Composable
fun Wordmark(fontSize: TextUnit, modifier: Modifier = Modifier) {
    Text(
        "Humdinger",
        modifier = modifier,
        style = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = fontSize),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Logo mark + wordmark side by side, for the feed's top bar. */
@Composable
fun LogoLockup(modifier: Modifier = Modifier, markSize: Dp = 30.dp, fontSize: TextUnit = 26.sp) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogoMark(markSize)
        Wordmark(fontSize)
    }
}
