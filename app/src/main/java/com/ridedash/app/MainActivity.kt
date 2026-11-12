package com.ridedash.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var screenDash: View
    private lateinit var screenMap: View
    private lateinit var screenMessages: View
    private lateinit var screenCalls: View
    private lateinit var callOverlay: View
    private var current: View? = null

    private lateinit var speedText: TextView
    private lateinit var mapSpeed: TextView
    private lateinit var batteryText: TextView
    private lateinit var linkText: TextView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtist: TextView
    private lateinit var btnPlay: Button
    private lateinit var lastMsgFrom: TextView
    private lateinit var lastMsgText: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var favList: LinearLayout
    private lateinit var mapContainer: FrameLayout
    private lateinit var callStatus: TextView
    private lateinit var callName: TextView
    private lateinit var btnAnswer: Button
    private lateinit var btnReject: Button

    private var map: MapView? = null
    private var myLoc: MyLocationNewOverlay? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpoken = ""
    private var lastRingAnnounced = ""
    private var gpsOn = false

    private val hubListener: (RideHub.Change) -> Unit = { change ->
        when (change) {
            RideHub.Change.MESSAGES -> updateMessages()
            RideHub.Change.MEDIA -> updateMedia()
            RideHub.Change.CALL -> updateCall()
            RideHub.Change.LINK -> updateLink()
            RideHub.Change.PAIRING -> PairingDialog.update(this)
        }
    }

    // Implements every method so it never crashes on Android < 11.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
            val txt = if (kmh < 2) "0" else kmh.toString()
            speedText.text = txt
            mapSpeed.text = getString(R.string.kmh_value, txt)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            speedText.text = "–"
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) batteryText.text = "${level * 100 / scale}%"
        }
    }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            val base = File(getExternalFilesDir(null) ?: filesDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()
        wireButtons()
        show(screenDash)

        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) try { tts?.language = Locale.getDefault() } catch (e: Exception) { }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Acts as a home screen: back only returns to the dashboard.
                if (current !== screenDash) show(screenDash)
            }
        })

        if (!Prefs.setupDone(this)) startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        RideHub.addListener(hubListener)
        updateLink(); updateMedia(); updateMessages(); updateCall()
        PairingDialog.update(this)
        startGps()
        if (current === screenMap) map?.onResume()
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED
        )
        if (Prefs.setupDone(this)) LinkService.start(this)
    }

    override fun onPause() {
        RideHub.removeListener(hubListener)
        PairingDialog.detach()
        stopGps()
        map?.onPause()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        map?.onDetach()
        super.onDestroy()
    }

    // ---------------- setup ----------------

    private fun bindViews() {
        screenDash = findViewById(R.id.screenDash)
        screenMap = findViewById(R.id.screenMap)
        screenMessages = findViewById(R.id.screenMessages)
        screenCalls = findViewById(R.id.screenCalls)
        callOverlay = findViewById(R.id.callOverlay)
        speedText = findViewById(R.id.speedText)
        mapSpeed = findViewById(R.id.mapSpeed)
        batteryText = findViewById(R.id.batteryText)
        linkText = findViewById(R.id.linkText)
        mediaTitle = findViewById(R.id.mediaTitle)
        mediaArtist = findViewById(R.id.mediaArtist)
        btnPlay = findViewById(R.id.btnPlay)
        lastMsgFrom = findViewById(R.id.lastMsgFrom)
        lastMsgText = findViewById(R.id.lastMsgText)
        messageList = findViewById(R.id.messageList)
        favList = findViewById(R.id.favList)
        mapContainer = findViewById(R.id.mapContainer)
        callStatus = findViewById(R.id.callStatus)
        callName = findViewById(R.id.callName)
        btnAnswer = findViewById(R.id.btnAnswer)
        btnReject = findViewById(R.id.btnReject)
    }

    private fun click(id: Int, f: () -> Unit) {
        findViewById<View>(id).setOnClickListener { f() }
    }

    private fun wireButtons() {
        click(R.id.btnMap) { show(screenMap) }
        click(R.id.btnMessages) { show(screenMessages) }
        click(R.id.msgCard) { show(screenMessages) }
        click(R.id.btnCalls) { show(screenCalls) }
        click(R.id.btnSetup) { startActivity(Intent(this, SetupActivity::class.java)) }

        click(R.id.btnPrev) { Actions.media(this, "prev") }
        click(R.id.btnPlay) { Actions.media(this, "toggle") }
        click(R.id.btnNext) { Actions.media(this, "next") }

        click(R.id.btnMapBack) { show(screenDash) }
        click(R.id.btnMsgBack) { show(screenDash) }
        click(R.id.btnCallsBack) { show(screenDash) }
        click(R.id.btnClearMsgs) { RideHub.clearMessages() }

        click(R.id.btnZoomIn) { map?.controller?.zoomIn() }
        click(R.id.btnZoomOut) { map?.controller?.zoomOut() }
        click(R.id.btnRecenter) { recenter() }
        click(R.id.btnNavigate) { askDestination() }

        btnAnswer.setOnClickListener {
            if (!Actions.answer(this)) toast(getString(R.string.cant_answer))
        }
        btnReject.setOnClickListener {
            if (!Actions.reject(this)) toast(getString(R.string.cant_reject))
        }
    }

    private fun show(v: View) {
        listOf(screenDash, screenMap, screenMessages, screenCalls).forEach {
            it.visibility = if (it === v) View.VISIBLE else View.GONE
        }
        current = v
        when (v) {
            screenMap -> { ensureMap(); map?.onResume() }
            screenMessages -> renderMessages()
            screenCalls -> renderFavourites()
        }
        if (v !== screenMap) map?.onPause()
    }

    // ---------------- GPS speed ----------------

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn || !Perms.location(this)) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            gpsOn = true
        } catch (e: Exception) {
            speedText.text = "–"
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        } catch (e: Exception) { }
        gpsOn = false
    }

    // ---------------- Map ----------------

    private fun ensureMap() {
        if (map != null) return
        val mv = MapView(this)
        mv.setTileSource(TileSourceFactory.MAPNIK)
        mv.setMultiTouchControls(true)
        mv.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mv.controller.setZoom(16.0)
        mv.isTilesScaledToDpi = true
        if (Perms.location(this)) {
            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mv)
            overlay.enableMyLocation()
            overlay.enableFollowLocation()
            overlay.runOnFirstFix {
                runOnUiThread {
                    overlay.myLocation?.let { mv.controller.animateTo(it) }
                    mv.controller.setZoom(17.0)
                }
            }
            mv.overlays.add(overlay)
            myLoc = overlay
        } else {
            toast(getString(R.string.need_location))
        }
        mapContainer.addView(
            mv, 0, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        map = mv
    }

    private fun recenter() {
        val o = myLoc ?: return
        o.enableFollowLocation()
        o.myLocation?.let { map?.controller?.animateTo(it) }
    }

    private fun askDestination() {
        val input = EditText(this).apply {
            hint = getString(R.string.where_to)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.navigate)
            .setView(input)
            .setPositiveButton(R.string.go) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) startNavigation(q)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Turn-by-turn via Google Maps in two-wheeler mode, falling back to any maps app. */
    private fun startNavigation(query: String) {
        val q = Uri.encode(query)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=l"))
                    .setPackage("com.google.android.apps.maps")
            )
            return
        } catch (e: ActivityNotFoundException) { }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_nav_app))
        }
    }

    // ---------------- UI updates ----------------

    private fun updateLink() {
        linkText.text = RideHub.linkStatus
    }

    private fun updateMedia() {
        val m = RideHub.media
        if (m == null) {
            mediaTitle.text = getString(R.string.nothing_playing)
            mediaArtist.text = getString(R.string.start_music_hint)
        } else {
            mediaTitle.text = m.title.ifBlank { getString(R.string.unknown_track) }
            mediaArtist.text = m.artist
        }
        btnPlay.text = if (m?.playing == true) "II" else "▶"
    }

    private fun updateMessages() {
        val last = RideHub.messages.firstOrNull()
        if (last == null) {
            lastMsgFrom.text = getString(R.string.no_messages)
            lastMsgText.text = ""
        } else {
            lastMsgFrom.text = getString(R.string.from_app, last.from, last.app)
            lastMsgText.text = last.text
        }
        if (current === screenMessages) renderMessages()

        val nm = RideHub.lastNewMessage ?: return
        val id = nm.key + nm.text
        if (id != lastSpoken) {
            lastSpoken = id
            if (Prefs.readAloud(this) && abs(System.currentTimeMillis() - nm.time) < 120_000) {
                speak(getString(R.string.tts_message, nm.from, nm.text))
            }
        }
    }

    private fun updateCall() {
        when (RideHub.call) {
            RideHub.Call.RINGING -> {
                callOverlay.visibility = View.VISIBLE
                callStatus.text = getString(R.string.incoming_call)
                callName.text = RideHub.callerName
                btnAnswer.visibility = View.VISIBLE
                btnReject.text = getString(R.string.reject)
                val name = RideHub.callerName
                if (name != lastRingAnnounced && name != "Incoming call") {
                    lastRingAnnounced = name
                    speak(getString(R.string.tts_call, name))
                }
            }
            RideHub.Call.OFFHOOK -> {
                callOverlay.visibility = View.VISIBLE
                callStatus.text = getString(R.string.on_call)
                callName.text = RideHub.callerName
                btnAnswer.visibility = View.GONE
                btnReject.text = getString(R.string.end_call)
            }
            RideHub.Call.IDLE -> {
                callOverlay.visibility = View.GONE
                lastRingAnnounced = ""
            }
        }
    }

    private fun renderMessages() {
        messageList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        if (RideHub.messages.isEmpty()) {
            messageList.addView(TextView(this).apply {
                text = getString(R.string.no_messages_long)
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(16, 32, 16, 32)
            })
            return
        }
        for (m in RideHub.messages) {
            val item = inflater.inflate(R.layout.item_message, messageList, false)
            item.findViewById<TextView>(R.id.itemFrom).text = getString(R.string.from_app, m.from, m.app)
            item.findViewById<TextView>(R.id.itemText).text = m.text
            item.findViewById<Button>(R.id.itemRead).setOnClickListener {
                speak(getString(R.string.tts_message, m.from, m.text))
            }
            val reply = item.findViewById<Button>(R.id.itemReply)
            reply.visibility = if (m.canReply) View.VISIBLE else View.GONE
            reply.setOnClickListener { chooseReply(m) }
            messageList.addView(item)
        }
    }

    private fun chooseReply(m: RideHub.Msg) {
        val replies = Prefs.quickReplies(this)
        if (replies.isEmpty()) {
            toast(getString(R.string.add_replies))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reply_to, m.from))
            .setItems(replies.toTypedArray()) { _, i ->
                val ok = Actions.reply(this, m, replies[i])
                toast(getString(if (ok) R.string.sent else R.string.cant_reply))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderFavourites() {
        favList.removeAllViews()
        val favs = Prefs.favourites(this)
        if (favs.isEmpty()) {
            favList.addView(TextView(this).apply {
                text = getString(R.string.no_favourites)
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(16, 32, 16, 32)
            })
            return
        }
        val h = (110 * resources.displayMetrics.density).toInt()
        for ((name, number) in favs) {
            val b = Button(this).apply {
                text = getString(R.string.call_name, name)
                textSize = 26f
                isAllCaps = false
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setBackgroundResource(R.drawable.bg_answer)
                setOnClickListener {
                    if (Actions.dial(this@MainActivity, number)) toast(getString(R.string.calling, name))
                    else toast(getString(R.string.cant_call))
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
            lp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
            favList.addView(b, lp)
        }
    }

    // ---------------- helpers ----------------

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "rd" + System.nanoTime())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
