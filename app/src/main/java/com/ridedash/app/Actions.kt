package com.ridedash.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * What the dashboard buttons do. Decides whether to act on this phone
 * or forward the command to the main phone over Bluetooth.
 */
object Actions {

    private fun useLink(c: Context) = !Prefs.isCompanion(c) && LinkService.isConnected

    fun media(c: Context, cmd: String) {
        val m = RideHub.media
        if ((m == null || m.remote) && useLink(c)) {
            LinkService.send(Proto.cmd("media_cmd", cmd))
        } else {
            Local.media(cmd)
        }
    }

    fun answer(c: Context): Boolean =
        if (RideHub.callRemote) { LinkService.send(Proto.cmd("call_cmd", "answer")); true }
        else Local.answer(c)

    fun reject(c: Context): Boolean =
        if (RideHub.callRemote) { LinkService.send(Proto.cmd("call_cmd", "reject")); true }
        else Local.reject(c)

    fun reply(c: Context, m: RideHub.Msg, text: String): Boolean =
        if (m.remote) {
            LinkService.send(JSONObject().put("t", "reply").put("key", m.key).put("text", text))
            LinkService.isConnected
        } else Local.reply(c, m.key, text)

    fun dial(c: Context, number: String): Boolean =
        if (useLink(c)) {
            LinkService.send(JSONObject().put("t", "dial").put("number", number))
            true
        } else Local.dial(c, number)
}

/** Actions executed on THIS phone. */
object Local {
    @Volatile
    var controller: MediaController? = null
    val replyActions = ConcurrentHashMap<String, Notification.Action>()

    fun media(cmd: String) {
        val c = controller ?: return
        val tc = c.transportControls
        when (cmd) {
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            "play" -> tc.play()
            "pause" -> tc.pause()
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
        }
    }

    private fun telecom(c: Context) = c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !Perms.has(c, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            telecom(c).acceptRingingCall(); true
        } catch (e: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun reject(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !Perms.has(c, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            telecom(c).endCall()
        } catch (e: Exception) { false }
    }

    fun reply(c: Context, key: String, text: String): Boolean {
        val action = replyActions[key] ?: return false
        val inputs = action.remoteInputs ?: return false
        if (inputs.isEmpty()) return false
        val intent = Intent()
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(c, 0, intent); true
        } catch (e: PendingIntent.CanceledException) { false }
    }

    @SuppressLint("MissingPermission")
    fun dial(c: Context, number: String): Boolean {
        val uri = Uri.fromParts("tel", number, null)
        if (Build.VERSION.SDK_INT >= 23 && Perms.has(c, Manifest.permission.CALL_PHONE)) {
            try {
                telecom(c).placeCall(uri, Bundle()); return true
            } catch (e: Exception) { /* fall through */ }
        }
        return try {
            c.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { false }
    }
}

/** Newline-delimited JSON messages sent over Bluetooth. */
object Proto {
    fun msg(m: RideHub.Msg): JSONObject = JSONObject()
        .put("t", "msg").put("key", m.key).put("app", m.app).put("from", m.from)
        .put("text", m.text).put("time", m.time).put("reply", m.canReply)

    fun media(m: RideHub.Media): JSONObject = JSONObject()
        .put("t", "media").put("title", m.title).put("artist", m.artist).put("playing", m.playing)

    fun call(state: RideHub.Call, name: String): JSONObject = JSONObject()
        .put("t", "call").put("state", state.name).put("name", name)

    fun cmd(type: String, cmd: String): JSONObject = JSONObject().put("t", type).put("cmd", cmd)
}
