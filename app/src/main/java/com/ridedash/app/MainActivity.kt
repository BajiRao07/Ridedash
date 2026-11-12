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
import android.widget.ImageButton
import android.content.pm.ActivityInfo
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
    private lateinit var screenMusic: View
    private lateinit var screenApps: View
    private lateinit var appsList: LinearLayout
    private lateinit var callOverlay: View
    private var current: View? = null

    // Home views: re-inflated for bike (portrait) or car (landscape), so they may be absent.
    private var speedText: TextView? = null
    private var speedSmall: TextView? = null
    private var batteryText: TextView? = null
    private var linkText: TextView? = null
    private var nowPlayingText: TextView? = null
    private var chatBadge: TextView? = null
    private var iconGrid: LinearLayout? = null
    private var homeLandscape: Boolean? = null
    private var lastSpeed = "0"
    private var lastBattery = ""
    private lateinit var mapSpeed: TextView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtist: TextView
    private lateinit var btnPlay: ImageButton
    private var seenMessages = 0
    private var lastLocation: Location? = null
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
            lastLocation = location
            val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
            val txt = if (kmh < 2) "0" else kmh.toString()
            lastSpeed = txt
            speedText?.text = txt
            speedSmall?.text = txt
            mapSpeed.text = getString(R.string.kmh_value, txt)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            lastSpeed = "–"
            speedText?.text = "–"
            speedSmall?.text = "–"
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                lastBattery = "${level * 100 / scale}%"
                batteryText?.text = lastBattery
            }
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
        applyLayout()
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
        applyOrientationSetting()
        applyLayout(force = true)
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
        screenMusic = findViewById(R.id.screenMusic)
        screenApps = findViewById(R.id.screenApps)
        appsList = findViewById(R.id.appsList)
        callOverlay = findViewById(R.id.callOverlay)
        mapSpeed = findViewById(R.id.mapSpeed)
        mediaTitle = findViewById(R.id.mediaTitle)
        mediaArtist = findViewById(R.id.mediaArtist)
        btnPlay = findViewById(R.id.btnPlay)
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
        click(R.id.btnMusicBack) { show(screenDash) }
        click(R.id.btnOpenPlayer) { openMusicApp() }
        click(R.id.btnAppsBack) { show(screenDash) }

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
        listOf(screenDash, screenMap, screenMessages, screenCalls, screenMusic, screenApps).forEach {
            it.visibility = if (it === v) View.VISIBLE else View.GONE
        }
        current = v
        when (v) {
            screenMap -> { ensureMap(); map?.onResume() }
            screenMessages -> {
                seenMessages = RideHub.messages.size
                updateBadge()
                renderMessages()
            }
            screenCalls -> renderFavourites()
            screenApps -> renderAllApps()
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
            speedText?.text = "–"
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
            .setNeutralButton(R.string.show_map) { _, _ -> show(screenMap) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- Home, SOS, app shortcuts ----------------

    /** Opens the phone's normal home screen, even when RideDash is set as the home app. */
    private fun openPhoneHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = try {
            packageManager.queryIntentActivities(home, 0)
                .firstOrNull { it.activityInfo.packageName != packageName }
        } catch (e: Exception) { null }
        try {
            if (other != null) {
                startActivity(
                    Intent(home).setClassName(other.activityInfo.packageName, other.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                startActivity(Intent(home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            moveTaskToBack(true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLocation(): Location? {
        lastLocation?.let { return it }
        if (!Perms.location(this)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { null }
    }

    /** SOS: send live location on WhatsApp (or SMS) and/or call the emergency contact. */
    private fun sos() {
        val contact = Prefs.sosContact(this)
        if (contact == null) {
            toast(getString(R.string.sos_missing))
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        val (name, number) = contact
        val loc = bestLocation()
        val link = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        else getString(R.string.sos_no_location)
        val message = getString(R.string.sos_message, link)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sos_title, name))
            .setMessage(R.string.sos_body)
            .setPositiveButton(R.string.sos_whatsapp) { _, _ -> sendSosMessage(number, message) }
            .setNeutralButton(R.string.sos_call) { _, _ ->
                if (!Actions.dial(this, number)) toast(getString(R.string.cant_call))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendSosMessage(number: String, message: String) {
        val digits = number.filter { it.isDigit() }
        val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(message)))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                startActivity(Intent(wa).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) { }
        }
        try {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    .putExtra("sms_body", message)
            )
        } catch (e: Exception) {
            toast(getString(R.string.cant_open))
        }
    }

    private fun openMusicApp() {
        val pi = Local.controller?.sessionActivity
        try {
            if (pi != null) { pi.send(); return }
        } catch (e: Exception) { }
        try {
            startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast(getString(R.string.no_music_app))
        }
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
        linkText?.text = if (Prefs.isStandalone(this)) "" else RideHub.linkStatus
    }

    // ---------------- auto-fit layout ----------------

    /** Lock orientation if the user chose Bike or Car layout in Setup. */
    private fun applyOrientationSetting() {
        requestedOrientation = when (Prefs.layoutMode(this)) {
            Prefs.LAYOUT_BIKE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Prefs.LAYOUT_CAR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    private fun updateBadge() {
        val unread = RideHub.messages.size - seenMessages
        val b = chatBadge ?: return
        b.visibility = if (unread > 0) View.VISIBLE else View.GONE
        b.text = if (unread > 9) "9+" else unread.toString()
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    /**
     * Builds the home screen for the current shape of the screen:
     * portrait = bike (big speed, 6 round icons), landscape = car (sidebar, 8 round icons).
     */
    private fun applyLayout(force: Boolean = false) {
        val dm = resources.displayMetrics
        val land = dm.widthPixels > dm.heightPixels
        val c = screenDash as FrameLayout
        if (force || homeLandscape != land || c.childCount == 0) {
            homeLandscape = land
            c.removeAllViews()
            layoutInflater.inflate(if (land) R.layout.home_landscape else R.layout.home_portrait, c, true)
            speedText = c.findViewById(R.id.speedText)
            speedSmall = c.findViewById(R.id.speedSmall)
            batteryText = c.findViewById(R.id.batteryText)
            linkText = c.findViewById(R.id.linkText)
            nowPlayingText = c.findViewById(R.id.nowPlayingText)
            iconGrid = c.findViewById(R.id.iconGrid)
            c.findViewById<View>(R.id.btnSos)?.setOnClickListener { sos() }
            c.findViewById<View>(R.id.btnPhoneHome)?.setOnClickListener { openPhoneHome() }
            c.findViewById<View>(R.id.btnAllApps)?.setOnClickListener { show(screenApps) }
            c.findViewById<View>(R.id.nowPlaying)?.setOnClickListener { show(screenMusic) }
            c.findViewById<View>(R.id.btnSetup)?.setOnClickListener {
                startActivity(Intent(this, SetupActivity::class.java))
            }
            speedText?.text = lastSpeed
            speedSmall?.text = lastSpeed
            batteryText?.text = lastBattery
            if (!land) speedText?.textSize = if (dm.heightPixels < dp(700f)) 80f else 104f
        }
        buildGrid()
        updateLink(); updateMedia(); updateBadge()
    }

    private fun buildGrid() {
        val grid = iconGrid ?: return
        grid.removeAllViews()
        chatBadge = null
        val dm = resources.displayMetrics
        val land = homeLandscape == true
        val cols = if (land) 4 else 3
        val count = if (land) 8 else 6
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val size = if (land) minOf((w - dp(120f)) / cols * 0.58f, h * 0.27f).toInt()
        else minOf((w - dp(36f)) / cols * 0.66f, h * 0.12f).toInt()
        val slots = Slots.load(this)
        var row: LinearLayout? = null
        for (i in 0 until count) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
                grid.addView(
                    row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                )
            }
            row!!.addView(
                makeTile(i, slots[i], size),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        updateBadge()
    }

    /** One round icon with its label. Tap opens it, long-press lets the user change it. */
    private fun makeTile(index: Int, slot: Slots.Slot, size: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            clipChildren = false
            isClickable = true
            isFocusable = true
        }
        val frame = FrameLayout(this).apply { clipChildren = false }
        val circle = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.isDuplicateParentStateEnabled = true
        val label: String
        val b = slot.builtin
        if (slot.pkg != null) {
            label = Slots.label(this, slot.pkg)
            circle.setBackgroundResource(R.drawable.circle_glass)
            Slots.icon(this, slot.pkg)?.let { circle.setImageDrawable(it) }
            val pad = (size * 0.13f).toInt()
            circle.setPadding(pad, pad, pad, pad)
        } else {
            val (bg, icon, text) = when (b) {
                Slots.NAV -> Triple(R.drawable.circle_nav, R.drawable.ic_tile_nav, getString(R.string.tile_navigate))
                Slots.MUSIC -> Triple(R.drawable.circle_music, R.drawable.ic_tile_music, getString(R.string.tile_music))
                Slots.CHATS -> Triple(R.drawable.circle_chats, R.drawable.ic_tile_chats, getString(R.string.tile_chats))
                Slots.CALL -> Triple(R.drawable.circle_call, R.drawable.ic_tile_call, getString(R.string.tile_call))
                Slots.APPS -> Triple(R.drawable.circle_glass, R.drawable.ic_grid, getString(R.string.all_apps))
                else -> Triple(R.drawable.circle_glass, R.drawable.ic_plus, getString(R.string.add_app))
            }
            label = text
            circle.setBackgroundResource(bg)
            circle.setImageResource(icon)
            val pad = (size * (if (b == Slots.APPS || b == Slots.EMPTY) 0.32f else 0.25f)).toInt()
            circle.setPadding(pad, pad, pad, pad)
        }
        frame.addView(circle, FrameLayout.LayoutParams(size, size))
        if (b == Slots.CHATS) {
            val badge = TextView(this).apply {
                setBackgroundResource(R.drawable.bg_badge)
                gravity = android.view.Gravity.CENTER
                minWidth = dp(28f)
                setPadding(dp(7f), 0, dp(7f), 0)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                visibility = View.GONE
            }
            frame.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(28f)).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            })
            chatBadge = badge
        }
        cell.addView(frame, LinearLayout.LayoutParams(size, size))
        cell.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            maxWidth = (size * 1.5f).toInt()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })
        cell.contentDescription = label
        cell.setOnClickListener { openSlot(index, slot) }
        cell.setOnLongClickListener { chooseSlot(index); true }
        return cell
    }

    private fun openSlot(index: Int, slot: Slots.Slot) {
        if (slot.pkg != null) {
            launchApp(slot.pkg)
            return
        }
        when (slot.builtin) {
            Slots.NAV -> askDestination()
            Slots.MUSIC -> show(screenMusic)
            Slots.CHATS -> show(screenMessages)
            Slots.CALL -> show(screenCalls)
            Slots.APPS -> show(screenApps)
            else -> chooseSlot(index)
        }
    }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            toast(getString(R.string.cant_open))
            return
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(getString(R.string.cant_open))
        }
    }

    /** Long-press: pick a RideDash function or any installed app for this spot. */
    private fun chooseSlot(index: Int) {
        val builtinLabels = listOf(
            getString(R.string.tile_navigate), getString(R.string.tile_music), getString(R.string.tile_chats),
            getString(R.string.tile_call), getString(R.string.all_apps)
        )
        val apps = Slots.launchableApps(this)
        val labels = (builtinLabels.map { "RideDash · $it" } + apps.map { it.label }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_icon)
            .setItems(labels) { _, which ->
                val list = Slots.load(this)
                list[index] = if (which < Slots.BUILTINS.size) Slots.Slot(Slots.BUILTINS[which], null)
                else Slots.Slot(null, apps[which - Slots.BUILTINS.size].pkg)
                Slots.save(this, list)
                buildGrid()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Grid of every installed app, 4 per row, round icons. */
    private fun renderAllApps() {
        appsList.removeAllViews()
        val apps = Slots.launchableApps(this)
        val size = dp(60f)
        var row: LinearLayout? = null
        apps.forEachIndexed { i, app ->
            if (i % 4 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                appsList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112f)))
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = app.label
                setOnClickListener { launchApp(app.pkg) }
            }
            cell.addView(android.widget.ImageView(this).apply {
                setBackgroundResource(R.drawable.circle_glass)
                Slots.icon(this@MainActivity, app.pkg)?.let { setImageDrawable(it) }
                val pad = dp(8f)
                setPadding(pad, pad, pad, pad)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(size, size))
            cell.addView(TextView(this).apply {
                text = app.label
                setTextColor(0xFFE6E8F5.toInt())
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
            row!!.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
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
        btnPlay.setImageResource(if (m?.playing == true) R.drawable.ic_pause_w else R.drawable.ic_play_w)
        nowPlayingText?.text = if (m == null) getString(R.string.nothing_playing)
        else listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { getString(R.string.unknown_track) }
    }

    private fun updateMessages() {
        if (RideHub.messages.size < seenMessages) seenMessages = RideHub.messages.size
        if (current === screenMessages) {
            seenMessages = RideHub.messages.size
            renderMessages()
        }
        updateBadge()

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
