package com.kowalski.humdinger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kowalski.humdinger.data.AppPrefs
import com.kowalski.humdinger.push.DeepLinks
import com.kowalski.humdinger.push.Notifications
import com.kowalski.humdinger.ui.HumdingerNavHost
import com.kowalski.humdinger.ui.components.LogoMark
import com.kowalski.humdinger.ui.components.Wordmark
import com.kowalski.humdinger.ui.theme.HumdingerTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    /** A notification was tapped while the app was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    private fun handleNotificationTap(intent: Intent?) {
        intent?.getStringExtra(DeepLinks.EXTRA)?.let { DeepLinks.pending.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppPrefs.init(this)
        Notifications.ensureChannel(this)
        handleNotificationTap(intent)
        setContent {
            HumdingerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val session: SessionViewModel = viewModel()
                    val state by session.state.collectAsStateWithLifecycle()
                    val justJoined by session.justJoined.collectAsStateWithLifecycle()

                    // Show the loading screen for at least a moment, so it never just flickers past.
                    var splashTimeDone by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        delay(1_500)
                        splashTimeDone = true
                    }

                    when (val s = state) {
                        is SessionState.Ready -> if (splashTimeDone) {
                            HumdingerNavHost(
                                me = s.me,
                                showWelcome = justJoined,
                                onWelcomeSeen = { session.justJoined.value = false },
                            )
                        } else {
                            SplashScreen(message = null, onRetry = null)
                        }
                        SessionState.Loading -> SplashScreen(message = null, onRetry = null)
                        is SessionState.Failed -> SplashScreen(message = s.message, onRetry = session::retry)
                    }
                }
            }
        }
    }
}

/** Shown for a moment while the guest account is set up, or if there's no connection. */
@Composable
private fun SplashScreen(message: String?, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        LogoMark(size = 132.dp)
        Wordmark(fontSize = 48.sp)
        Text(
            "Hum it. Sing it. Mime it.\nSomeone out there knows that song.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message == null) {
            CircularProgressIndicator()
        } else {
            Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            if (onRetry != null) Button(onClick = onRetry) { Text("Try again") }
        }
    }
}
