package com.kowalski.humdinger.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Every kind of notification Humdinger sends, each with its own on/off switch in Settings.
 * Saved on the profile as notify.<key> = true/false; the server checks them before sending
 * (notifyUser in functions/index.js). Keep the keys and defaults in sync with NOTIFY_DEFAULTS there,
 * and with isNotifyUpdate in firestore.rules.
 */
enum class NotifyType(
    val key: String,
    val group: String,
    val title: String,
    val description: String,
    val defaultOn: Boolean = true,
) {
    GUESSES("guesses", "Your hums", "🎯 New guesses", "Someone thinks they know your song"),
    COMMENTS("comments", "Your hums", "💬 Comments", "Someone comments on your hum"),
    REPLIES("replies", "Your hums", "↩️ Replies", "Replies to your guesses, and on your hums"),
    CROWNED("crowned", "Points", "👑 You named it", "Your guess was crowned"),
    THANKS("thanks", "Messages", "🎁 Thank-yous", "Someone thanks you for naming their song"),
    CHAT("chat", "Messages", "✉️ Chat messages", "New messages in your conversations"),
    FRIEND_REQUESTS("friendRequests", "Friends", "👋 Friend requests", "Requests, and when someone accepts yours"),
    FRIEND_HUMS("friendHums", "Friends", "🎤 Friends' new hums", "A friend posts a new hum"),
    ALL_HUMS("allHums", "Everyone", "📣 Every new hum", "Anyone posts a new hum (can get busy!)", defaultOn = false),
    REMOVED("removed", "Safety", "⚠️ Removed clips", "One of your clips was removed after reports"),
    ;

    companion object {
        /** The master switch: off = no notifications at all. */
        const val ALL_KEY = "all"

        /** Quiet hours on/off, and the hours (0-23, their phone's time). */
        const val QUIET_ON = "quietOn"
        const val QUIET_START = "quietStart"
        const val QUIET_END = "quietEnd"

        /** Daily summary instead of instant alerts (chat messages and thank-yous still come straight away). */
        const val DAILY = "daily"
        const val DIGEST_HOUR = "digestHour"

        /** Their phone's time zone, so quiet hours and the summary happen at their local time. */
        const val TIME_ZONE = "tz"
    }
}

object NotificationSettings {
    fun isOn(prefs: Map<String, Boolean>, type: NotifyType): Boolean = prefs[type.key] ?: type.defaultOn

    fun allOn(prefs: Map<String, Boolean>): Boolean = prefs[NotifyType.ALL_KEY] ?: true

    /** Saves one setting (true/false, an hour, ...), plus the phone's time zone. */
    suspend fun set(uid: String, key: String, value: Any) {
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .update(
                mapOf(
                    "notify.$key" to value,
                    "notify.${NotifyType.TIME_ZONE}" to java.util.TimeZone.getDefault().id,
                )
            )
            .await()
    }
}
