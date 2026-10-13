package com.kowalski.humdinger.ui.profile

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import com.kowalski.humdinger.data.NameCheck
import com.kowalski.humdinger.data.NameFilter
import com.kowalski.humdinger.data.NameTakenException
import com.kowalski.humdinger.data.UserProfile
import com.kowalski.humdinger.data.UserRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lets someone type a new name, or roll a new random fun one. */
@Composable
fun RenameDialog(me: UserProfile, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(me.username) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val validShape = UserRepository.isValidName(name)
    val friendly = NameFilter.isAllowed(name)
    val valid = validShape && friendly
    val changed = UserRepository.cleanName(name) != me.username

    // Check as they type whether someone else already has the name (null = not checked yet).
    var free by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(name) {
        free = null
        if (!valid || !changed) return@LaunchedEffect
        delay(400)
        free = runCatching { NameCheck.isNameFree(name, me.uid) }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change your name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(24)
                        error = null
                    },
                    label = { Text("Your name") },
                    singleLine = true,
                    isError = (name.isNotBlank() && !valid) || free == false,
                    supportingText = {
                        Text(
                            when {
                                name.isNotBlank() && !friendly -> "Let's keep names friendly. Try another!"
                                free == false -> "Someone's already called that. Try another!"
                                free == true -> "✓ That name's free"
                                else -> "3–24 letters, numbers or spaces"
                            }
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            name = NameCheck.randomFreeName(me.uid)
                            error = null
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🎲  Surprise me") }
                Text(
                    "Your points stay with you, and your old posts switch to the new name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = valid && changed && !busy && free != false,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            UserRepository.rename(me.uid, name)
                            onDismiss()
                        } catch (e: NameTakenException) {
                            error = e.message
                        } catch (e: Exception) {
                            Log.e("Humdinger", "Rename failed", e)
                            error = e.message ?: "Couldn't change your name. Try again?"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

/** Shown once, right after a new guest gets their random name. */
@Composable
fun WelcomeDialog(me: UserProfile, onKeep: () -> Unit, onChange: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Hi, ${me.username}! 👋") },
        text = {
            Text(
                "That's your Humdinger name. Keep it, or pick something else — " +
                    "you can change it any time on the Me tab."
            )
        },
        confirmButton = { Button(onClick = onKeep) { Text("Love it!") } },
        dismissButton = { TextButton(onClick = onChange) { Text("Change it") } },
    )
}
