package com.ridedash.app

import android.os.Handler
import android.os.Looper

/**
 * Single in-memory source of truth for what the dashboard shows.
 * Fed by NotificationService (this phone) and LinkService (the other phone).
 * All changes are applied on the main thread.
 */
object RideHub {

    data class Msg(
        val key: String,
        val app: String,
        val from: String,
        val text: String,
        val time: Long,
        val canReply: Boolean,
        val remote: Boolean
    )

    data class Media(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val remote: Boolean
    )

    enum class Call { IDLE, RINGING, OFFHOOK }
    enum class Change { MESSAGES, MEDIA, CALL, LINK, PAIRING, BADGES }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Change) -> Unit>()

    val messages = ArrayList<Msg>()
    var lastNewMessage: Msg? = null
        private set
    var media: Media? = null
        private set
    var call = Call.IDLE
        private set
    var callerName = ""
        private set
    var callRemote = false
        private set
    var linkStatus = "Link off"
        private set

    fun addListener(l: (Change) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Change) -> Unit) { listeners.remove(l) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private fun fire(c: Change) {
        listeners.toList().forEach { it(c) }
    }

    fun addMessage(m: Msg) = onMain {
        if (messages.none { it.key == m.key && it.text == m.text }) {
            messages.add(0, m)
            while (messages.size > 40) messages.removeAt(messages.size - 1)
            lastNewMessage = m
            fire(Change.MESSAGES)
        }
    }

    fun clearMessages() = onMain {
        messages.clear()
        lastNewMessage = null
        fire(Change.MESSAGES)
    }

    /** Local "nothing playing" must not wipe music coming from the main phone. */
    fun setMedia(m: Media?) = onMain {
        if (m == null && media?.remote == true) return@onMain
        if (m != null && !m.remote && media?.remote == true && !m.playing) return@onMain
        media = m
        fire(Change.MEDIA)
    }

    fun setCall(state: Call, name: String, remote: Boolean) = onMain {
        if (!remote && callRemote && call != Call.IDLE && state == Call.IDLE) return@onMain
        call = state
        callerName = name
        callRemote = remote
        fire(Change.CALL)
    }

    /** Current GPS speed in km/h, updated by the dashboard. */
    @Volatile
    var speedKmh = 0

    /** Unread message count per app package, from notifications. */
    var badges: Map<String, Int> = emptyMap()
        private set

    fun setBadges(m: Map<String, Int>) = onMain {
        if (m != badges) {
            badges = m
            fire(Change.BADGES)
        }
    }

    /** 6-digit code waiting for the user to confirm, or null. */
    var pairingCode: String? = null
        private set

    fun setPairing(code: String?) = onMain {
        pairingCode = code
        fire(Change.PAIRING)
    }

    fun setLink(status: String) = onMain {
        linkStatus = status
        fire(Change.LINK)
    }

    /** Called when the Bluetooth link drops. */
    fun clearRemote() = onMain {
        if (media?.remote == true) {
            media = null
            fire(Change.MEDIA)
        }
        if (callRemote && call != Call.IDLE) {
            call = Call.IDLE
            fire(Change.CALL)
        }
    }
}
