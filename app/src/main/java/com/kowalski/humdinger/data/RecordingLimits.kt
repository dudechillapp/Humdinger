package com.kowalski.humdinger.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Keeps clip storage within Google's free allowance. The server checks usage every hour
 * (checkStorageBudget in functions/index.js) and sets the mode in config/storage:
 *  - NORMAL: hums and videos
 *  - AUDIO_ONLY: getting close to the free allowance, so hums only (they're much smaller)
 *  - PAUSED: nearly at the limit, so no new recordings until the allowance resets next month
 * Written thank-yous, guesses and chat always work. The database and storage rules
 * enforce the same thing, so an old version of the app can't get round it.
 */
enum class RecordingMode {
    NORMAL, AUDIO_ONLY, PAUSED;

    fun allows(type: MediaType) = when (this) {
        NORMAL -> true
        AUDIO_ONLY -> type == MediaType.AUDIO
        PAUSED -> false
    }

    companion object {
        fun fromKey(key: String?) = when (key) {
            "audioOnly" -> AUDIO_ONLY
            "paused" -> PAUSED
            else -> NORMAL
        }
    }
}

object RecordingLimits {
    fun mode(): Flow<RecordingMode> =
        FirebaseFirestore.getInstance().collection("config").document("storage").liveSnapshots()
            .map { RecordingMode.fromKey(it.getString("mode")) }
            .catch { emit(RecordingMode.NORMAL) }
}
