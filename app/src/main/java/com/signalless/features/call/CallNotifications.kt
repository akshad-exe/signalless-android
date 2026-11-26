package com.signalless.app.features.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.signalless.app.R
import com.signalless.app.service.CallService
import com.signalless.app.ui.IncomingCallActivity

/** The call service's notification: ringing, or an ongoing call. */
internal object CallNotifications {
    const val NOTIFICATION_ID = 0x5CA11
    private const val RINGING_CHANNEL = "SignalLess_call_ringing"
    private const val ONGOING_CHANNEL = "SignalLess_call_ongoing"

    fun build(context: Context, name: String, ringing: Boolean, connectedAtMs: Long?): Notification {
        createChannels(context)
        val caller = Person.Builder()
            .setName(name.ifBlank { context.getString(R.string.call_mesh_peer) })
            .setImportant(true)
            .build()
        val screen = activity(context, 1, answer = false)
        val hangup = PendingIntent.getService(
            context, 2, CallService.intent(context, CallService.ACTION_HANGUP), FLAGS
        )
        val style = if (ringing) {
            // Answering goes through the activity: the microphone needs a visible app.
            NotificationCompat.CallStyle.forIncomingCall(caller, hangup, activity(context, 3, answer = true))
        } else {
            NotificationCompat.CallStyle.forOngoingCall(caller, hangup)
        }
        return NotificationCompat.Builder(context, if (ringing) RINGING_CHANNEL else ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .addPerson(caller)
            .setContentIntent(screen)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (ringing) setFullScreenIntent(screen, true)
                if (connectedAtMs != null) setWhen(connectedAtMs).setUsesChronometer(true)
            }
            .build()
            .apply { if (ringing) flags = flags or Notification.FLAG_INSISTENT }
    }

    private fun activity(context: Context, request: Int, answer: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, IncomingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(IncomingCallActivity.EXTRA_ANSWER, answer),
            FLAGS
        )

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringing = NotificationChannel(
            RINGING_CHANNEL,
            context.getString(R.string.notification_incoming_call),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // A self-managed call rings itself.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            )
            enableVibration(true)
        }
        val ongoing = NotificationChannel(
            ONGOING_CHANNEL,
            context.getString(R.string.call_ongoing_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannels(listOf(ringing, ongoing))
    }

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
