package com.signalless.app.features.call

import android.content.Context
import com.signalless.app.R
import com.signalless.app.ui.NotificationManager

internal object MeshCallNotifications {
    fun onQueued(
        context: Context,
        notifications: NotificationManager,
        peerID: String,
        nickname: String?,
        action: CallAction?
    ) {
        when (action) {
            CallAction.INVITE -> notifications.showIncomingCallNotification(
                peerID,
                displayName(context, peerID, nickname)
            )
            CallAction.DECLINE, CallAction.HANGUP, CallAction.BUSY ->
                notifications.cancelIncomingCallNotification(peerID)
            else -> Unit
        }
    }

    fun onFlushed(notifications: NotificationManager, peerIDs: List<String>) {
        peerIDs.forEach { notifications.cancelIncomingCallNotification(it) }
    }

    private fun displayName(context: Context, peerID: String, nickname: String?): String {
        val trimmed = nickname?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == peerID) {
            return context.getString(R.string.call_mesh_peer)
        }
        return trimmed
    }
}
