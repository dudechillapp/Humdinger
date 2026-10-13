package com.kowalski.humdinger.ui.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kowalski.humdinger.data.ClipLifetime
import com.kowalski.humdinger.data.Limits
import com.kowalski.humdinger.data.MediaType
import com.kowalski.humdinger.data.RecordingLimits
import com.kowalski.humdinger.data.RecordingMode
import com.kowalski.humdinger.data.RequestRepository
import com.kowalski.humdinger.data.ThankYouTarget
import com.kowalski.humdinger.data.ThanksRepository
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.ui.components.ClipPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class RecordViewModel : ViewModel() {
    val uploading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun showError(message: String?) {
        error.value = message
    }

    fun post(
        me: UserProfile,
        clip: File,
        type: MediaType,
        hint: String,
        thankYou: ThankYouTarget?,
        lifetime: ClipLifetime,
        onPosted: (String) -> Unit,
    ) {
        if (uploading.value) return
        uploading.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val id = if (thankYou != null) {
                    ThanksRepository.sendThanks(me, thankYou, clip, type, hint, lifetime.days)
                    thankYou.requestId
                } else {
                    RequestRepository.createRequest(me, clip, type, hint, lifetime.days)
                }
                clip.delete()
                onPosted(id)
            } catch (e: Exception) {
                error.value = "Upload failed: ${e.message}"
            } finally {
                uploading.value = false
            }
        }
    }
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    me: UserProfile,
    onBack: () -> Unit,
    onPosted: (String) -> Unit,
    /** Set when recording a thank-you for the person who named your song, instead of a new request. */
    thankYou: ThankYouTarget? = null,
    vm: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mode by rememberSaveable { mutableStateOf(MediaType.AUDIO) }
    var withSound by rememberSaveable { mutableStateOf(true) }
    var hint by rememberSaveable { mutableStateOf("") }
    // How long this clip stays up. Starts at their usual setting from the Me tab.
    var lifetime by rememberSaveable { mutableStateOf(ClipLifetime.fromDays(me.clipLifetimeDays)) }

    var hasMic by remember { mutableStateOf(context.isGranted(Manifest.permission.RECORD_AUDIO)) }
    var hasCam by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasMic = result[Manifest.permission.RECORD_AUDIO] ?: hasMic
        hasCam = result[Manifest.permission.CAMERA] ?: hasCam
    }
    val askPermissions = {
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
    }
    LaunchedEffect(Unit) {
        if (!hasMic || !hasCam) askPermissions()
    }

    var recording by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(Limits.MAX_CLIP_SECONDS) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var clip by remember { mutableStateOf<File?>(null) }

    val uploading by vm.uploading.collectAsStateWithLifecycle()
    // Near this month's free storage allowance: hums only, or no new recordings for now.
    val limitFlow = remember { RecordingLimits.mode() }
    val limit by limitFlow.collectAsStateWithLifecycle(initialValue = RecordingMode.NORMAL)
    LaunchedEffect(limit) {
        if (limit == RecordingMode.AUDIO_ONLY && mode == MediaType.VIDEO && !recording && clip == null) {
            mode = MediaType.AUDIO
        }
    }
    val error by vm.error.collectAsStateWithLifecycle()

    val audioRecorder = remember { AudioRecorder(context) }
    val videoRecorder = remember { VideoRecorder(context) }

    // Camera only runs while it's needed: video mode, permission granted, nothing recorded yet.
    val cameraActive = mode == MediaType.VIDEO && hasCam && clip == null
    DisposableEffect(cameraActive) {
        if (cameraActive) videoRecorder.bind(lifecycleOwner) { vm.showError(it) }
        onDispose { if (cameraActive) videoRecorder.unbind() }
    }
    DisposableEffect(Unit) {
        onDispose {
            audioRecorder.stop()
            videoRecorder.unbind()
        }
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        if (mode == MediaType.AUDIO) {
            val ok = audioRecorder.stop()
            clip = if (ok) pendingFile else null
            if (!ok) vm.showError("That was a bit quick! Hold on for at least a second.")
        } else {
            videoRecorder.stop() // the clip arrives in the callback below
        }
    }

    fun startRecording() {
        vm.showError(null)
        val ext = if (mode == MediaType.AUDIO) "m4a" else "mp4"
        val file = File(context.cacheDir, "clip_${System.currentTimeMillis()}.$ext")
        pendingFile = file
        try {
            if (mode == MediaType.AUDIO) {
                audioRecorder.start(file)
            } else {
                videoRecorder.start(file, withSound && hasMic) { result ->
                    clip = result
                    if (result == null) vm.showError("That recording didn't work — give it another go.")
                }
            }
            secondsLeft = Limits.MAX_CLIP_SECONDS
            recording = true
        } catch (e: Exception) {
            vm.showError("Couldn't start recording: ${e.message}")
        }
    }

    // Countdown; stops automatically at zero.
    LaunchedEffect(recording) {
        if (recording) {
            while (secondsLeft > 0) {
                delay(1_000)
                secondsLeft--
            }
            stopRecording()
        }
    }

    val canRecord = if (mode == MediaType.AUDIO) hasMic else hasCam

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (thankYou != null) "Thank ${thankYou.solverName}" else "New request") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (clip == null && !recording) {
                if (thankYou != null) ThankYouIntroCard(thankYou) else IntroCard()
            }

            val locked = recording || clip != null
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = mode == MediaType.AUDIO,
                    onClick = { if (!locked) mode = MediaType.AUDIO },
                    label = { Text("🎤  Hum / sing") },
                    enabled = !locked || mode == MediaType.AUDIO,
                )
                FilterChip(
                    selected = mode == MediaType.VIDEO,
                    onClick = { if (!locked && limit.allows(MediaType.VIDEO)) mode = MediaType.VIDEO },
                    label = { Text("🎭  Mime / video") },
                    enabled = (!locked && limit.allows(MediaType.VIDEO)) || mode == MediaType.VIDEO,
                )
            }
            if (limit != RecordingMode.NORMAL) {
                RecordingLimitCard(limit, isThankYou = thankYou != null)
            }

            val recordedClip = clip
            if (recordedClip == null) {
                // ---------- Recording ----------
                if (!canRecord) {
                    PermissionCard(
                        text = if (mode == MediaType.AUDIO) {
                            "Humdinger needs your microphone to record your hum."
                        } else {
                            "Humdinger needs your camera to record your mime."
                        },
                        onAllow = askPermissions,
                    )
                } else if (mode == MediaType.AUDIO) {
                    MicBubble(recording, secondsLeft)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black),
                    ) {
                        AndroidView(
                            factory = {
                                val view = videoRecorder.previewView
                                (view.parent as? ViewGroup)?.removeView(view)
                                view
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (recording) {
                            Text(
                                "● ${secondsLeft}s",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xCCFF3B30))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Include sound", modifier = Modifier.weight(1f))
                        Switch(
                            checked = withSound && hasMic,
                            onCheckedChange = { withSound = it },
                            enabled = !recording && hasMic,
                        )
                    }
                }

                Button(
                    onClick = { if (recording) stopRecording() else startRecording() },
                    enabled = canRecord && (recording || limit.allows(mode)),
                    colors = if (recording) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(if (recording) "■  Stop  (${secondsLeft}s left)" else "●  Start recording")
                }
                Text(
                    if (mode == MediaType.AUDIO) {
                        "Up to ${Limits.MAX_CLIP_SECONDS} seconds. Hum, whistle, la-la-la — whatever you've got!"
                    } else {
                        "Up to ${Limits.MAX_CLIP_SECONDS} seconds. Mouth the words, do the moves, act out the video. Go wild."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                // ---------- Review & post ----------
                Text("How'd that sound? 👀", style = MaterialTheme.typography.titleLarge)
                ClipPlayer(Uri.fromFile(recordedClip).toString(), isVideo = mode == MediaType.VIDEO)

                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it.take(Limits.MAX_HINT_CHARS) },
                    label = { Text(if (thankYou != null) "Add a note (optional)" else "Any clues? (optional)") },
                    placeholder = {
                        Text(
                            if (thankYou != null) "e.g. You legend! That song's been stuck in my head for weeks"
                            else "e.g. 90s, heard it on a car advert, female singer"
                        )
                    },
                    supportingText = { Text("${hint.length}/${Limits.MAX_HINT_CHARS}") },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth(),
                )

                LifetimePicker(
                    selected = lifetime,
                    usual = ClipLifetime.fromDays(me.clipLifetimeDays),
                    isThankYou = thankYou != null,
                    enabled = !uploading,
                    onSelect = { lifetime = it },
                )

                if (uploading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Sending it out into the world…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                recordedClip.delete()
                                clip = null
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Text("↺  Retake") }
                        Button(
                            onClick = { vm.post(me, recordedClip, mode, hint, thankYou, lifetime, onPosted) },
                            enabled = limit.allows(mode),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Text(if (thankYou != null) "Send thanks 🎁" else "Post it 🚀") }
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun MicBubble(recording: Boolean, secondsLeft: Int) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (recording) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "micScale",
    )
    Spacer(Modifier.height(12.dp))
    Box(
        modifier = Modifier
            .size(200.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Text(
                "$secondsLeft",
                fontSize = 64.sp,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("🎤", fontSize = 80.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PermissionCard(text: String, onAllow: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🙈", fontSize = 48.sp)
        Text(text, textAlign = TextAlign.Center)
        Button(onClick = onAllow) { Text("Allow access") }
        Text(
            "If you said no before, you may need to allow it in your phone's Settings → Apps → Humdinger.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Keep this clip up for": 1 day to 3 months, starting at their usual setting from the Me tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LifetimePicker(
    selected: ClipLifetime,
    usual: ClipLifetime,
    isThankYou: Boolean,
    enabled: Boolean,
    onSelect: (ClipLifetime) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (isThankYou) "🗑️ Keep this thank-you for" else "🗑️ Keep this clip up for",
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ClipLifetime.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
        Text(
            (if (isThankYou) "After that, the recording deletes itself. Your chat stays. "
            else "The longer it's up, the more time people have to name it! After that, it deletes itself. ") +
                (if (selected == usual) "Your usual time is set on the Me tab."
                else "Just for this one. Your usual time (${usual.label}) is set on the Me tab."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Explains why videos (or all new recordings) are switched off for now. */
@Composable
private fun RecordingLimitCard(limit: RecordingMode, isThankYou: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (limit == RecordingMode.PAUSED) {
            Text("⏸️ Recording's on a short break", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "So many hums this month that we've nearly filled our free storage! New recordings are back " +
                    "at the start of next month. " +
                    (if (isThankYou) "You can still send a written thank-you." else "You can still guess, react and chat."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            Text("🎤 Hums only for now", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Videos take up lots of space and we're getting close to this month's free storage, " +
                    "so it's hums and singing only until next month.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun IntroCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Got a song stuck in your head? 🎶", style = MaterialTheme.typography.titleMedium)
        Text(
            "Beatbox it, sing your heart out, sing only the words you think it might be. " +
                "Try anything, and our community of music boffins will get on it!",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ThankYouIntroCard(target: ThankYouTarget) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Say thanks to ${target.solverName}! 🎁", style = MaterialTheme.typography.titleMedium)
        Text(
            "They named \"${target.songTitle}\". Record a quick thank-you: sing a bit of it, do a victory dance, " +
                "whatever you like. It lands in their Messages. You can only send one, so make it count!",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
