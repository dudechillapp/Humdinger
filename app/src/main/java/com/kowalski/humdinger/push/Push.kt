package com.kowalski.humdinger.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kowalski.humdinger.MainActivity
import com.kowalski.humdinger.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Where a tapped notification should take the user: "request:<id>" or "messages".
 * MainActivity sets it; the navigation reads it and clears it.
 */
object DeepLinks {
    const val EXTRA = "open"
    val pending = MutableStateFlow<String?>(null)
}

object Notifications {
    const val CHANNEL_ID = "activity"

    /** Used during someone's quiet hours (set in Settings): no sound, no pop-up. */
    const val QUIET_CHANNEL_ID = "quiet"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Guesses and messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Guesses, comments, friends, thank-yous, messages and notices"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(QUIET_CHANNEL_ID, "Quiet hours", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Notifications that arrive during your quiet hours, without a sound"
                }
            )
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Shows a notification while the app is open (when it's closed, Android shows it for us). */
    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, body: String, open: String?, quiet: Boolean = false) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            open?.let { putExtra(DeepLinks.EXTRA, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            (open ?: title).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, if (quiet) QUIET_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.coral))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
    }
}

/** Keeps this phone's push address ("token") on the signed-in account, so the server can reach it. */
object PushTokens {
    private val db get() = FirebaseFirestore.getInstance()

    suspend fun register(uid: String) {
        val token = FirebaseMessaging.getInstance().token.await()
        db.collection("users").document(uid).update("fcmTokens", FieldValue.arrayUnion(token)).await()
    }

    /** Called before signing out or switching accounts, so the old account stops notifying this phone. */
    suspend fun unregister(uid: String) {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            db.collection("users").document(uid).update("fcmTokens", FieldValue.arrayRemove(token)).await()
        }
    }
}

class HumdingerMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .update("fcmTokens", FieldValue.arrayUnion(token))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        Notifications.show(this, title, body, message.data[DeepLinks.EXTRA], quiet = message.data["quiet"] == "1")
    }
}
