package com.ridedash.app

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Reads messages (WhatsApp, SMS, Telegram...), music sessions and call state
 * on this phone. Requires the user to grant "Notification access".
 */
class NotificationService : NotificationListenerService() {

    companion object {
        private val MSG_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WA Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages",
            "com.android.mms" to "SMS",
            "com.facebook.orca" to "Messenger",
            "com.instagram.android" to "Instagram",
            "com.Slack" to "Slack",
            "com.microsoft.teams" to "Teams",
            "com.truecaller" to "Truecaller"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var current: MediaController? = null
    private var lastCallerName = ""
    private var lastCallState = RideHub.Call.IDLE
    private var receiverOn = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushMedia()
        override fun onSessionDestroyed() {
            pickController(activeSessions())
        }
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> RideHub.Call.RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> RideHub.Call.OFFHOOK
                else -> RideHub.Call.IDLE
            }
            reportCall(state)
            // The dialer's notification (with the caller's name) often arrives a moment later.
            if (state == RideHub.Call.RINGING) {
                handler.postDelayed({
                    if (lastCallState == RideHub.Call.RINGING) reportCall(RideHub.Call.RINGING)
                }, 1200)
            }
            if (state == RideHub.Call.IDLE) lastCallerName = ""
        }
    }

    // ---------- lifecycle ----------

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            m.addOnActiveSessionsChangedListener(sessionsListener, componentName())
            pickController(activeSessions())
        } catch (e: Exception) { /* access not granted yet */ }

        updateBadges()
        if (!receiverOn) {
            try {
                ContextCompat.registerReceiver(
                    this, phoneReceiver,
                    IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED
                )
                receiverOn = true
            } catch (e: Exception) { }
        }
    }

    override fun onListenerDisconnected() {
        cleanup()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        try { current?.unregisterCallback(mediaCallback) } catch (e: Exception) { }
        current = null
        Local.controller = null
        if (receiverOn) {
            try { unregisterReceiver(phoneReceiver) } catch (e: Exception) { }
            receiverOn = false
        }
    }

    // ---------- music ----------

    private fun componentName() = ComponentName(this, NotificationService::class.java)

    private fun activeSessions(): List<MediaController> = try {
        msm?.getActiveSessions(componentName()) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun pickController(list: List<MediaController>?) {
        try { current?.unregisterCallback(mediaCallback) } catch (e: Exception) { }
        val chosen = Prefs.musicApp(this)
        val playing = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val preferred = list?.firstOrNull { it.packageName == chosen }
        current = playing ?: preferred ?: list?.firstOrNull()
        Local.controller = current
        current?.registerCallback(mediaCallback, handler)
        pushMedia()
    }

    private fun pushMedia() {
        val c = current
        if (c == null) {
            RideHub.setMedia(null)
            return
        }
        val md = c.metadata
        val media = RideHub.Media(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            remote = false
        )
        RideHub.setMedia(media)
        if (Prefs.isCompanion(this)) LinkService.send(Proto.media(media))
    }

    // ---------- calls ----------

    private fun reportCall(state: RideHub.Call) {
        lastCallState = state
        val name = lastCallerName.ifBlank { if (state == RideHub.Call.RINGING) "Incoming call" else "Call" }
        RideHub.setCall(state, name, false)
        if (Prefs.isCompanion(this)) LinkService.send(Proto.call(state, name))
    }

    // ---------- messages ----------

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        val n = s.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""

        if (n.category == Notification.CATEGORY_CALL) {
            if (title.isNotBlank()) lastCallerName = title
            return
        }
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (s.isOngoing) return

        val isMessage = n.category == Notification.CATEGORY_MESSAGE || MSG_APPS.containsKey(s.packageName)
        if (!isMessage) return

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: return
        if (text.isBlank() || title.isBlank()) return

        val replyAction = n.actions?.firstOrNull { a -> !a.remoteInputs.isNullOrEmpty() }
        if (replyAction != null) Local.replyActions[s.key] = replyAction

        val msg = RideHub.Msg(
            key = s.key,
            app = MSG_APPS[s.packageName] ?: s.packageName.substringAfterLast('.'),
            from = title,
            text = text,
            time = s.postTime,
            canReply = replyAction != null,
            remote = false
        )
        RideHub.addMessage(msg)
        if (Prefs.isCompanion(this)) LinkService.send(Proto.msg(msg))
        updateBadges()
        maybeAutoReply(msg)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { Local.replyActions.remove(it) }
        updateBadges()
    }

    /** Counts unread messages per app from the notifications still showing. */
    private fun updateBadges() {
        val counts = HashMap<String, Int>()
        try {
            for (s in activeNotifications ?: emptyArray()) {
                if (s.packageName == packageName || s.isOngoing) continue
                val n = s.notification ?: continue
                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
                if (n.category == Notification.CATEGORY_CALL) continue
                val isMsg = n.category == Notification.CATEGORY_MESSAGE || MSG_APPS.containsKey(s.packageName)
                if (!isMsg) continue
                val msgs = n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0
                counts[s.packageName] = (counts[s.packageName] ?: 0) + maxOf(1, msgs)
            }
        } catch (e: Exception) { }
        RideHub.setBadges(counts)
    }

    private val lastAutoReply = HashMap<String, Long>()

    /** Opt-in: tells the sender you are riding. Only while moving, at most once per 30 min per chat. */
    private fun maybeAutoReply(msg: RideHub.Msg) {
        if (!Prefs.autoReply(this) || !msg.canReply || RideHub.speedKmh < 15) return
        if (Prefs.isCompanion(this)) return
        val who = msg.app + "|" + msg.from
        val now = System.currentTimeMillis()
        if (now - (lastAutoReply[who] ?: 0L) < 30 * 60_000L) return
        lastAutoReply[who] = now
        Local.reply(this, msg.key, Prefs.autoReplyText(this))
    }
}
