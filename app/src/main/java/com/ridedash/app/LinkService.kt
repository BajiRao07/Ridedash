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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Secure Bluetooth link between the two phones.
 *
 *  - Uses encrypted, bonded Bluetooth RFCOMM (Android's "secure" sockets).
 *  - First time: the phones pair inside the app; both show a 6-digit code and the
 *    user confirms it matches on both. Nothing is trusted before that.
 *  - Afterwards the bike phone only accepts the paired main phone, and every
 *    message is additionally encrypted and authenticated with AES-256-GCM.
 *  - Each phone only obeys the commands meant for its role.
 */
class LinkService : Service() {

    companion object {
        private val APP_UUID: UUID = UUID.fromString("6f1d2a3e-8b7c-4e2a-9c51-2b7a0d3e4f10")
        private const val CHANNEL = "ridedash_link"
        private const val NOTIF_ID = 42
        private const val MAX_FRAME = 64 * 1024
        private const val TYPE_PLAIN = 0
        private const val TYPE_SEALED = 1
        private const val MAX_TEXT = 2000

        @Volatile
        var instance: LinkService? = null
            private set

        val isConnected: Boolean get() = instance?.connected == true

        fun start(c: Context) {
            if (Prefs.isStandalone(c) || !Perms.bluetooth(c)) return
            try {
                ContextCompat.startForegroundService(c, Intent(c, LinkService::class.java))
            } catch (e: Exception) { }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, LinkService::class.java))
        }

        fun restart(c: Context) {
            stop(c)
            start(c)
        }

        fun send(o: JSONObject) {
            instance?.write(o)
        }

        /** Called by the pairing dialog when the user taps MATCH / DOESN'T MATCH. */
        fun confirmPairing(ok: Boolean) {
            instance?.pairingDecision?.offer(ok)
        }
    }

    /** Length-prefixed frames over the Bluetooth socket. */
    private class Wire(socket: BluetoothSocket) {
        private val input = DataInputStream(BufferedInputStream(socket.inputStream))
        private val output = DataOutputStream(BufferedOutputStream(socket.outputStream))

        fun send(type: Int, body: ByteArray) = synchronized(this) {
            output.writeByte(type)
            output.writeInt(body.size)
            output.write(body)
            output.flush()
        }

        fun recv(): Pair<Int, ByteArray> {
            val type = input.readUnsignedByte()
            val len = input.readInt()
            if (len < 0 || len > MAX_FRAME) throw IOException("bad frame size")
            val body = ByteArray(len)
            input.readFully(body)
            return Pair(type, body)
        }

        fun sendJson(o: JSONObject) = send(TYPE_PLAIN, o.toString().toByteArray(Charsets.UTF_8))

        fun recvJson(): JSONObject {
            val (type, body) = recv()
            if (type != TYPE_PLAIN) throw GeneralSecurityException("unexpected frame")
            return JSONObject(String(body, Charsets.UTF_8))
        }
    }

    @Volatile private var running = false
    @Volatile var connected = false
        private set
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var wire: Wire? = null
    @Volatile private var channel: Crypto.Channel? = null
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    internal val pairingDecision = LinkedBlockingQueue<Boolean>()

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
            Thread({ loop() }, "ridedash-link").start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        pairingDecision.offer(false)
        try { server?.close() } catch (e: IOException) { }
        try { socket?.close() } catch (e: IOException) { }
        writer.shutdownNow()
        instance = null
        RideHub.setPairing(null)
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

    private fun check(ok: Boolean) {
        if (!ok) throw GeneralSecurityException("protocol check failed")
    }

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
                    // ---- MAIN PHONE: connects out to the chosen bike phone ----
                    val addr = Prefs.peerAddress(this)
                    if (addr == null) {
                        status("Choose the bike phone in Setup")
                        sleep(3000)
                        continue
                    }
                    val pairing = Prefs.pairingRequested(this)
                    if (!pairing && SecureStore.loadKey(this) == null) {
                        status("Not paired – pair with the bike phone in Setup")
                        sleep(3000)
                        continue
                    }
                    status(if (pairing) "Pairing with bike phone…" else "Connecting securely…")
                    adapter.cancelDiscovery()
                    val sk = adapter.getRemoteDevice(addr).createRfcommSocketToServiceRecord(APP_UUID)
                    s = sk
                    socket = sk
                    sk.connect()
                    val w = Wire(sk)
                    if (pairing) {
                        pairAsInitiator(w, addr, Prefs.peerName(this))
                        continue
                    }
                    channel = handshake(w, initiator = true)
                    wire = w
                } else {
                    // ---- BIKE PHONE: waits for the paired main phone only ----
                    val key = SecureStore.loadKey(this)
                    if (!Prefs.pairingOpen(this) && key == null) {
                        status("Not paired – start pairing in Setup")
                        sleep(3000)
                        continue
                    }
                    status(if (Prefs.pairingOpen(this)) "Pairing open – waiting for main phone" else "Waiting for main phone…")
                    val srv = adapter.listenUsingRfcommWithServiceRecord("RideDash", APP_UUID)
                    server = srv
                    val accepted = try {
                        srv.accept(30_000)
                    } catch (e: IOException) {
                        null // timeout: re-check pairing window
                    } finally {
                        try { srv.close() } catch (e: IOException) { }
                        server = null
                    }
                    if (accepted == null) continue
                    s = accepted
                    socket = accepted
                    val remoteAddr = accepted.remoteDevice.address
                    val w = Wire(accepted)
                    if (Prefs.pairingOpen(this)) {
                        val name = try { accepted.remoteDevice.name ?: "" } catch (e: SecurityException) { "" }
                        pairAsResponder(w, remoteAddr, name)
                        continue
                    }
                    if (!remoteAddr.equals(Prefs.peerAddress(this), ignoreCase = true)) {
                        status("Blocked an unknown device")
                        sleep(2000)
                        continue
                    }
                    channel = handshake(w, initiator = false)
                    wire = w
                }

                connected = true
                status(if (Prefs.isCompanion(this)) "Securely connected to bike phone" else "Main phone securely connected")
                onConnected()
                readLoop(wire!!, channel!!)
            } catch (e: SecurityException) {
                status("Bluetooth permission needed")
                sleep(5000)
            } catch (e: IOException) {
                // connection failed or dropped – retry below
            } catch (e: Exception) {
                // Authentication / protocol failure: refuse and slow down retries
                status("Security check failed – connection refused")
                sleep(5000)
            } finally {
                val wasConnected = connected
                connected = false
                wire = null
                channel = null
                try { s?.close() } catch (e: IOException) { }
                socket = null
                if (wasConnected) RideHub.clearRemote()
            }
            if (running) sleep(3000)
        }
    }

    // ---------- session handshake (every connection) ----------

    private fun readNonce(w: Wire): ByteArray {
        val o = w.recvJson()
        check(o.optString("t") == "hello")
        val n = Crypto.unb64(o.getString("n"))
        check(n.size == 16)
        return n
    }

    private fun handshake(w: Wire, initiator: Boolean): Crypto.Channel {
        val key = SecureStore.loadKey(this) ?: throw GeneralSecurityException("not paired")
        val mine = Crypto.random(16)
        val theirs: ByteArray
        if (initiator) {
            w.sendJson(JSONObject().put("t", "hello").put("n", Crypto.b64(mine)))
            theirs = readNonce(w)
        } else {
            theirs = readNonce(w)
            w.sendJson(JSONObject().put("t", "hello").put("n", Crypto.b64(mine)))
        }
        val nA = if (initiator) mine else theirs
        val nB = if (initiator) theirs else mine
        val a2b = "a2b".toByteArray()
        val b2a = "b2a".toByteArray()
        val kAB = Crypto.hmac(key, a2b, nA, nB)
        val kBA = Crypto.hmac(key, b2a, nA, nB)
        val ch = if (initiator) Crypto.Channel(kAB, kBA, a2b, b2a) else Crypto.Channel(kBA, kAB, b2a, a2b)

        // Prove both sides hold the paired key before trusting anything.
        w.send(TYPE_SEALED, ch.seal(JSONObject().put("t", "auth").toString().toByteArray(Charsets.UTF_8)))
        val (type, body) = w.recv()
        check(type == TYPE_SEALED)
        val o = JSONObject(String(ch.open(body), Charsets.UTF_8))
        check(o.optString("t") == "auth")
        return ch
    }

    // ---------- one-time pairing ----------

    private fun pairAsInitiator(w: Wire, addr: String, name: String) {
        val kp = Crypto.newKeyPair()
        val pk = kp.public.encoded
        val n = Crypto.random(16)
        // Commit to our key first so the other side can't choose its key based on ours.
        w.sendJson(JSONObject().put("t", "pair1").put("c", Crypto.b64(Crypto.sha256(pk, n))))
        val p2 = w.recvJson()
        if (p2.optString("t") == "pair_closed") {
            status("Bike phone is not in pairing mode")
            endPairingMode()
            return
        }
        check(p2.optString("t") == "pair2")
        val pkB = Crypto.unb64(p2.getString("pk"))
        val nB = Crypto.unb64(p2.getString("n"))
        check(nB.size == 16)
        w.sendJson(JSONObject().put("t", "pair3").put("pk", Crypto.b64(pk)).put("n", Crypto.b64(n)))
        finishPairing(w, kp, pk, pkB, n, nB, pkB, initiator = true, addr = addr, name = name)
    }

    private fun pairAsResponder(w: Wire, addr: String, name: String) {
        val p1 = w.recvJson()
        check(p1.optString("t") == "pair1")
        val commit = Crypto.unb64(p1.getString("c"))
        val kp = Crypto.newKeyPair()
        val pk = kp.public.encoded
        val n = Crypto.random(16)
        w.sendJson(JSONObject().put("t", "pair2").put("pk", Crypto.b64(pk)).put("n", Crypto.b64(n)))
        val p3 = w.recvJson()
        check(p3.optString("t") == "pair3")
        val pkA = Crypto.unb64(p3.getString("pk"))
        val nA = Crypto.unb64(p3.getString("n"))
        check(nA.size == 16 && Crypto.same(Crypto.sha256(pkA, nA), commit))
        finishPairing(w, kp, pkA, pk, nA, n, pkA, initiator = false, addr = addr, name = name)
    }

    private fun finishPairing(
        w: Wire, kp: java.security.KeyPair,
        pkA: ByteArray, pkB: ByteArray, nA: ByteArray, nB: ByteArray,
        peerPub: ByteArray, initiator: Boolean, addr: String, name: String
    ) {
        val shared = Crypto.ecdh(kp.private, Crypto.publicFrom(peerPub))
        val transcript = pkA + pkB + nA + nB
        val code = Crypto.sas(transcript)
        val linkKey = Crypto.hmac(shared, "ridedash-link-key".toByteArray(), transcript)

        pairingDecision.clear()
        RideHub.setPairing(code)
        status("Check the pairing code on both phones")
        val ok = pairingDecision.poll(120, TimeUnit.SECONDS) ?: false
        RideHub.setPairing(null)

        val mine = if (initiator) "ok-a" else "ok-b"
        val theirs = if (initiator) "ok-b" else "ok-a"
        if (!ok) {
            try { w.sendJson(JSONObject().put("t", "pair_no")) } catch (e: IOException) { }
            status("Pairing cancelled")
            endPairingMode()
            return
        }
        w.sendJson(JSONObject().put("t", "pair_ok").put("m", Crypto.b64(Crypto.hmac(linkKey, mine.toByteArray()))))
        val r = w.recvJson()
        val confirmed = r.optString("t") == "pair_ok" &&
            Crypto.same(Crypto.unb64(r.optString("m")), Crypto.hmac(linkKey, theirs.toByteArray()))
        if (!confirmed) {
            status("Pairing failed – codes were not confirmed on both phones")
            endPairingMode()
            return
        }
        SecureStore.saveKey(this, linkKey)
        Prefs.setPeer(this, addr, name.ifBlank { addr })
        endPairingMode()
        status("Paired securely with ${name.ifBlank { "phone" }}")
        sleep(1500)
    }

    private fun endPairingMode() {
        Prefs.setPairingRequested(this, false)
        Prefs.closePairing(this)
    }

    // ---------- encrypted traffic ----------

    private fun readLoop(w: Wire, ch: Crypto.Channel) {
        while (running) {
            val (type, body) = w.recv()
            if (type != TYPE_SEALED) throw GeneralSecurityException("plain frame after handshake")
            val o = JSONObject(String(ch.open(body), Charsets.UTF_8))
            main.post { handle(o) }
        }
    }

    fun write(o: JSONObject) {
        val w = wire ?: return
        val ch = channel ?: return
        if (!connected || writer.isShutdown) return
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        try {
            writer.execute {
                try {
                    w.send(TYPE_SEALED, ch.seal(bytes))
                } catch (e: Exception) {
                    try { socket?.close() } catch (x: IOException) { }
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

    private fun txt(o: JSONObject, k: String) = o.optString(k).take(MAX_TEXT)

    /** Each phone only accepts the messages meant for its role. */
    private fun handle(o: JSONObject) {
        val t = o.optString("t")
        if (!Prefs.isCompanion(this)) {
            // Bike phone: display-only data from the main phone
            when (t) {
                "msg" -> RideHub.addMessage(
                    RideHub.Msg(
                        key = txt(o, "key"),
                        app = txt(o, "app").take(40),
                        from = txt(o, "from").take(100),
                        text = txt(o, "text"),
                        time = o.optLong("time", System.currentTimeMillis()),
                        canReply = o.optBoolean("reply"),
                        remote = true
                    )
                )
                "media" -> RideHub.setMedia(
                    RideHub.Media(
                        title = txt(o, "title").take(200),
                        artist = txt(o, "artist").take(200),
                        playing = o.optBoolean("playing"),
                        remote = true
                    )
                )
                "call" -> {
                    val state = try {
                        RideHub.Call.valueOf(o.optString("state"))
                    } catch (e: IllegalArgumentException) { RideHub.Call.IDLE }
                    RideHub.setCall(state, txt(o, "name").take(100), true)
                }
            }
        } else {
            // Main phone: commands from the paired bike phone
            when (t) {
                "media_cmd" -> {
                    val cmd = o.optString("cmd")
                    if (cmd in setOf("toggle", "play", "pause", "next", "prev")) Local.media(cmd)
                }
                "call_cmd" -> when (o.optString("cmd")) {
                    "answer" -> Local.answer(this)
                    "reject" -> Local.reject(this)
                }
                "reply" -> {
                    val text = txt(o, "text").take(500)
                    if (text.isNotBlank()) Local.reply(this, o.optString("key"), text)
                }
                "dial" -> {
                    // Only phone-number characters are accepted.
                    val number = o.optString("number").trim()
                    if (number.length in 3..20 && number.all { it.isDigit() || it in "+*# " }) {
                        Local.dial(this, number)
                    }
                }
            }
        }
    }
}
