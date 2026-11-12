package com.ridedash.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bluetooth link between the two phones.
 *  - Bike phone (dashboard) waits for a connection.
 *  - Main phone (companion) connects to the bike phone and keeps retrying.
 */
class LinkService : Service() {

    companion object {
        private val APP_UUID: UUID = UUID.fromString("6f1d2a3e-8b7c-4e2a-9c51-2b7a0d3e4f10")
        private const val CHANNEL = "ridedash_link"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: LinkService? = null
            private set

        val isConnected: Boolean get() = instance?.connected == true

        fun start(c: Context) {
            if (!Perms.bluetooth(c)) return
            try {
                ContextCompat.startForegroundService(c, Intent(c, LinkService::class.java))
            } catch (e: Exception) { }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, LinkService::class.java))
        }

        fun send(o: JSONObject) {
            instance?.write(o)
        }
    }

    @Volatile private var running = false
    @Volatile var connected = false
        private set
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    private var writer: ExecutorService = Executors.newSingleThreadExecutor()
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground(RideHub.linkStatus)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            worker = Thread({ loop() }, "ridedash-link").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        closeQuietly()
        writer.shutdownNow()
        instance = null
        RideHub.clearRemote()
        RideHub.setLink("Link off")
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Phone link", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("RideDash")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun goForeground(text: String): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        true
    } catch (e: Exception) {
        RideHub.setLink("Bluetooth permission needed")
        false
    }

    @SuppressLint("MissingPermission")
    private fun status(text: String) {
        RideHub.setLink(text)
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) { }
    }

    // ---------- connection loop ----------

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    private fun loop() {
        val adapter = adapter()
        if (adapter == null) {
            status("No Bluetooth on this phone")
            return
        }
        while (running) {
            var s: BluetoothSocket? = null
            try {
                if (!adapter.isEnabled) {
                    status("Turn on Bluetooth")
                    sleep(3000)
                    continue
                }
                if (Prefs.isCompanion(this)) {
                    val addr = Prefs.peerAddress(this)
                    if (addr == null) {
                        status("Choose the bike phone in Setup")
                        sleep(3000)
                        continue
                    }
                    status("Connecting to ${Prefs.peerName(this).ifBlank { "bike phone" }}…")
                    adapter.cancelDiscovery()
                    val sk = adapter.getRemoteDevice(addr).createInsecureRfcommSocketToServiceRecord(APP_UUID)
                    sk.connect()
                    s = sk
                } else {
                    status("Waiting for main phone…")
                    val srv = adapter.listenUsingInsecureRfcommWithServiceRecord("RideDash", APP_UUID)
                    server = srv
                    try {
                        s = srv.accept()
                    } finally {
                        try { srv.close() } catch (e: IOException) { }
                        server = null
                    }
                }
                val sock = s ?: continue
                socket = sock
                connected = true
                status(if (Prefs.isCompanion(this)) "Connected to bike phone" else "Main phone connected")
                onConnected()
                readLoop(sock)
            } catch (e: SecurityException) {
                status("Bluetooth permission needed")
                sleep(5000)
            } catch (e: IOException) {
                // connection failed or dropped
            } catch (e: IllegalArgumentException) {
                status("Bike phone address invalid – choose again")
                sleep(5000)
            } finally {
                val wasConnected = connected
                connected = false
                try { s?.close() } catch (e: IOException) { }
                socket = null
                if (wasConnected) RideHub.clearRemote()
            }
            if (running) {
                status("Not connected – retrying")
                sleep(3000)
            }
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        while (running) {
            val line = reader.readLine() ?: break
            try {
                val o = JSONObject(line)
                main.post { handle(o) }
            } catch (e: JSONException) { }
        }
    }

    private fun closeQuietly() {
        try { server?.close() } catch (e: IOException) { }
        try { socket?.close() } catch (e: IOException) { }
    }

    fun write(o: JSONObject) {
        val s = socket ?: return
        if (!connected || writer.isShutdown) return
        val bytes = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
        try {
            writer.execute {
                try {
                    s.outputStream.write(bytes)
                    s.outputStream.flush()
                } catch (e: IOException) {
                    try { s.close() } catch (x: IOException) { }
                }
            }
        } catch (e: Exception) { }
    }

    /** Main phone sends its current state as soon as the bike phone connects. */
    private fun onConnected() {
        if (!Prefs.isCompanion(this)) return
        RideHub.media?.let { if (!it.remote) write(Proto.media(it)) }
        if (!RideHub.callRemote && RideHub.call != RideHub.Call.IDLE) {
            write(Proto.call(RideHub.call, RideHub.callerName))
        }
    }

    // ---------- incoming messages ----------

    private fun handle(o: JSONObject) {
        when (o.optString("t")) {
            // Bike phone receives these
            "msg" -> RideHub.addMessage(
                RideHub.Msg(
                    key = o.optString("key"),
                    app = o.optString("app"),
                    from = o.optString("from"),
                    text = o.optString("text"),
                    time = o.optLong("time", System.currentTimeMillis()),
                    canReply = o.optBoolean("reply"),
                    remote = true
                )
            )
            "media" -> RideHub.setMedia(
                RideHub.Media(
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    playing = o.optBoolean("playing"),
                    remote = true
                )
            )
            "call" -> {
                val state = try {
                    RideHub.Call.valueOf(o.optString("state"))
                } catch (e: IllegalArgumentException) { RideHub.Call.IDLE }
                RideHub.setCall(state, o.optString("name"), true)
            }
            // Main phone receives these
            "media_cmd" -> Local.media(o.optString("cmd"))
            "call_cmd" -> when (o.optString("cmd")) {
                "answer" -> Local.answer(this)
                "reject" -> Local.reject(this)
            }
            "reply" -> Local.reply(this, o.optString("key"), o.optString("text"))
            "dial" -> Local.dial(this, o.optString("number"))
        }
    }
}
