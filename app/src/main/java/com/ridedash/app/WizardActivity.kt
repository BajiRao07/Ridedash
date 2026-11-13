package com.ridedash.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * First-run setup in 6 short steps, one decision per screen:
 * Welcome → Bike or car → Permissions → Connect → Safety → Ready.
 * Steps already done show as ticked, so returning users fly through.
 */
class WizardActivity : AppCompatActivity() {

    private var step = 1
    private val total = 6
    private lateinit var progress: LinearLayout
    private lateinit var back: ImageView
    private lateinit var counter: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var primary: TextView
    private lateinit var secondary: TextView

    private var ride = Prefs.LAYOUT_AUTO
    private var sosField: EditText? = null
    private val favFields = mutableListOf<EditText>()

    private val white = 0xFFFFFFFF.toInt()
    private val soft = 0xFFAEB4D0.toInt()
    private val teal = 0xFF4FF0CF.toInt()

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ride = Prefs.layoutMode(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glow)
            setPadding(dp(22f), dp(16f), dp(22f), dp(20f))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_w)
            setBackgroundResource(R.drawable.circle_glass)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            contentDescription = getString(R.string.back)
            setOnClickListener { go(step - 1) }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(12f) })
        progress = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(progress, LinearLayout.LayoutParams(0, dp(6f), 1f))
        counter = text("", 13f, soft)
        top.addView(counter, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(44f)))

        scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        primary = text("", 18f, 0xFF04121F.toInt(), true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_primary)
            isClickable = true
            isFocusable = true
        }
        root.addView(primary, LinearLayout.LayoutParams(-1, dp(60f)).apply { topMargin = dp(12f) })
        secondary = text("", 15f, soft).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        root.addView(secondary, LinearLayout.LayoutParams(-1, dp(44f)))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step > 1) go(step - 1) else finish()
            }
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a system screen (permissions, Wi-Fi): refresh ticks.
        if (step == 3 || step == 4) render()
    }

    private fun go(s: Int) {
        if (s < 1 || s > total) return
        saveFields()
        step = s
        render()
    }

    // ---------------- building blocks ----------------

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun heading(title: String, sub: String) {
        content.addView(text(title, 30f, white, true).apply { setLineSpacing(0f, 1.05f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22f) })
        content.addView(text(sub, 16f, soft).apply { setLineSpacing(dp(3f).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f); bottomMargin = dp(20f) })
    }

    private fun circle(bg: Int, icon: Int, size: Float, pad: Float = 0.26f): ImageView = ImageView(this).apply {
        setBackgroundResource(bg)
        setImageResource(icon)
        val p = (dp(size) * pad).toInt()
        setPadding(p, p, p, p)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A card row: round icon, title + subtitle, and an optional view on the right. */
    private fun row(bg: Int, icon: Int, title: String, sub: String, right: View?, selected: Boolean = false,
                    onClick: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (selected) R.drawable.bg_card_sel else R.drawable.bg_card)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        r.addView(circle(bg, icon, 50f), LinearLayout.LayoutParams(dp(50f), dp(50f)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 17f, white, true))
        if (sub.isNotEmpty()) col.addView(text(sub, 13.5f, soft))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14f); rightMargin = dp(8f) })
        if (right != null) r.addView(right)
        content.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
        return r
    }

    private fun tick(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_check)
        setBackgroundResource(R.drawable.circle_ok)
        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
    }

    private fun pill(label: String, white: Boolean, onClick: () -> Unit): View = text(label, 14f,
        if (white) 0xFF070815.toInt() else this.white, true).apply {
        setBackgroundResource(if (white) R.drawable.bg_white_pill else R.drawable.bg_chip)
        setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun field(label: String, hint: String, value: String): EditText {
        content.addView(text(label, 15f, white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f); bottomMargin = dp(6f) })
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(0xFF8F96B5.toInt())
            setTextColor(white)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(14f), 0, dp(14f), 0)
        }
        content.addView(e, LinearLayout.LayoutParams(-1, dp(52f)).apply { bottomMargin = dp(12f) })
        return e
    }

    private fun buttons(main: String, second: String?, onMain: () -> Unit, onSecond: (() -> Unit)? = null) {
        primary.text = main
        primary.setOnClickListener { onMain() }
        secondary.text = second ?: ""
        secondary.visibility = if (second == null) View.INVISIBLE else View.VISIBLE
        secondary.setOnClickListener { onSecond?.invoke() }
    }

    // ---------------- steps ----------------

    private fun render() {
        content.removeAllViews()
        sosField = null
        favFields.clear()
        back.visibility = if (step == 1) View.INVISIBLE else View.VISIBLE
        counter.text = "$step/$total"
        progress.removeAllViews()
        for (i in 1..total) {
            progress.addView(View(this).apply {
                setBackgroundResource(if (i <= step) R.drawable.seg_on else R.drawable.seg_off)
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 1) leftMargin = dp(5f) })
        }
        when (step) {
            1 -> welcome()
            2 -> rideStep()
            3 -> permissions()
            4 -> connect()
            5 -> safety()
            else -> ready()
        }
        scroll.scrollTo(0, 0)
    }

    private fun welcome() {
        val hero = FrameLayout(this)
        val phone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_phone)
        }
        phone.addView(text("42", 44f, white, true).apply { gravity = Gravity.CENTER })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val icons = listOf(
            R.drawable.circle_nav to R.drawable.ic_tile_map, R.drawable.circle_music to R.drawable.ic_tile_music,
            R.drawable.circle_chats to R.drawable.ic_tile_chats, R.drawable.circle_call to R.drawable.ic_tile_call
        )
        for (r in 0..1) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0..1) {
                val (bg, ic) = icons[r * 2 + c]
                line.addView(circle(bg, ic, 54f), LinearLayout.LayoutParams(dp(54f), dp(54f)).apply {
                    setMargins(dp(7f), dp(7f), dp(7f), dp(7f))
                })
            }
            grid.addView(line)
        }
        phone.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        hero.addView(phone, FrameLayout.LayoutParams(dp(170f), dp(280f), Gravity.CENTER))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(310f)).apply { topMargin = dp(16f) })

        content.addView(text("RIDEDASH", 14f, teal, true).apply { letterSpacing = 0.25f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        content.addView(text(getString(R.string.wiz_welcome_title), 31f, white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        content.addView(text(getString(R.string.wiz_welcome_sub), 16f, soft),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        buttons(getString(R.string.wiz_get_started), null, { go(2) })
    }

    private fun rideStep() {
        heading(getString(R.string.wiz_ride_title), getString(R.string.wiz_ride_sub))
        fun option(mode: String, bg: Int, icon: Int, title: String, sub: String) {
            val sel = ride == mode
            val right: View = if (sel) tick() else View(this).apply {
                setBackgroundResource(R.drawable.circle_glass)
                layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
            }
            row(bg, icon, title, sub, right, sel) { ride = mode; render() }
        }
        option(Prefs.LAYOUT_BIKE, R.drawable.circle_music, R.drawable.ic_bike,
            getString(R.string.wiz_bike), getString(R.string.wiz_bike_sub))
        option(Prefs.LAYOUT_CAR, R.drawable.circle_call, R.drawable.ic_car,
            getString(R.string.wiz_car), getString(R.string.wiz_car_sub))
        option(Prefs.LAYOUT_AUTO, R.drawable.circle_nav, R.drawable.ic_star,
            getString(R.string.wiz_both), getString(R.string.wiz_both_sub))
        buttons(getString(R.string.wiz_continue), null, {
            Prefs.setLayoutMode(this, ride)
            go(3)
        })
    }

    private data class Perm(val bg: Int, val icon: Int, val title: String, val sub: String,
                            val ok: Boolean, val ask: () -> Unit)

    private fun perms(): List<Perm> = listOf(
        Perm(R.drawable.circle_nav, R.drawable.ic_pin, getString(R.string.wiz_p_location),
            getString(R.string.wiz_p_location_sub), Perms.location(this)) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
        },
        Perm(R.drawable.circle_chats, R.drawable.ic_bell, getString(R.string.wiz_p_notif),
            getString(R.string.wiz_p_notif_sub), Perms.notifAccess(this)) {
            toast(getString(R.string.wiz_notif_hint))
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Exception) { }
        },
        Perm(R.drawable.circle_call, R.drawable.ic_tile_call, getString(R.string.wiz_p_phone),
            getString(R.string.wiz_p_phone_sub), Perms.phone(this)) {
            val l = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 26) l.add(Manifest.permission.ANSWER_PHONE_CALLS)
            ActivityCompat.requestPermissions(this, l.toTypedArray(), 12)
        },
        Perm(R.drawable.circle_music, R.drawable.ic_bt, getString(R.string.wiz_p_bt),
            getString(R.string.wiz_p_bt_sub), Perms.bluetooth(this)) {
            val l = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
            if (l.isNotEmpty()) ActivityCompat.requestPermissions(this, l.toTypedArray(), 13)
        }
    )

    private fun permissions() {
        heading(getString(R.string.wiz_perm_title), getString(R.string.wiz_perm_sub))
        val list = perms()
        val next = list.firstOrNull { !it.ok }
        for (p in list) {
            val right: View = when {
                p.ok -> tick()
                p === next -> pill(getString(R.string.wiz_allow), true) { p.ask() }
                else -> text(getString(R.string.wiz_next), 14f, 0xFF8F96B5.toInt())
            }
            row(p.bg, p.icon, p.title, p.sub, right, p === next)
        }
        content.addView(text(getString(R.string.wiz_privacy), 13.5f, 0xFFDDF7A8.toInt()).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        if (next != null) {
            buttons(getString(R.string.wiz_allow_x, next.title), getString(R.string.wiz_skip), { next.ask() }, { go(4) })
        } else {
            buttons(getString(R.string.wiz_continue), null, { go(4) })
        }
    }

    private fun onWifi(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    } catch (e: Exception) { false }

    @SuppressLint("MissingPermission")
    private fun connect() {
        heading(getString(R.string.wiz_connect_title), getString(R.string.wiz_connect_sub))
        if (onWifi()) {
            row(R.drawable.circle_nav, R.drawable.ic_wifi, getString(R.string.wiz_net_ok), getString(R.string.wiz_net_ok_sub), tick())
        } else {
            row(R.drawable.circle_nav, R.drawable.ic_wifi, getString(R.string.wiz_net_no), getString(R.string.wiz_net_no_sub),
                pill(getString(R.string.wiz_open), true) {
                    try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (e: Exception) { }
                }, true)
        }
        content.addView(text(getString(R.string.wiz_bt_title), 15f, white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f); bottomMargin = dp(4f) })
        content.addView(text(getString(R.string.wiz_bt_sub), 13.5f, soft),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val devices = try {
            if (adapter != null && Perms.bluetooth(this) && adapter.isEnabled) adapter.bondedDevices.toList() else emptyList()
        } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            content.addView(text(getString(R.string.wiz_bt_none), 14f, 0xFF8F96B5.toInt()).apply {
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        for (d in devices.take(6)) {
            val name = try { d.name ?: d.address } catch (e: SecurityException) { d.address }
            val role = when (d.address) {
                Prefs.btBike(this) -> "bike"
                Prefs.btCar(this) -> "car"
                else -> null
            }
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun chip(label: String, r: String) {
                chips.addView(text(label, 13f, white, true).apply {
                    setBackgroundResource(if (role == r) R.drawable.bg_chip_sel else R.drawable.bg_chip)
                    setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                    isClickable = true
                    setOnClickListener {
                        Prefs.setBtRole(this@WizardActivity, d.address, if (role == r) null else r)
                        render()
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6f) })
            }
            chip(getString(R.string.wiz_bike), "bike")
            chip(getString(R.string.wiz_car), "car")
            row(R.drawable.circle_glass, R.drawable.ic_bt, name, "", chips, role != null)
        }
        buttons(getString(R.string.wiz_continue), null, { go(5) })
    }

    private fun safety() {
        heading(getString(R.string.wiz_safety_title), getString(R.string.wiz_safety_sub))
        sosField = field(getString(R.string.h_sos), getString(R.string.sos_hint), Prefs.sosRaw(this))
        content.addView(text(getString(R.string.favourites), 15f, white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f); bottomMargin = dp(6f) })
        for (i in 1..3) {
            val e = EditText(this).apply {
                setText(Prefs.favRaw(this@WizardActivity, i))
                hint = getString(R.string.fav_hint)
                setHintTextColor(0xFF8F96B5.toInt())
                setTextColor(white)
                textSize = 15f
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(14f), 0, dp(14f), 0)
            }
            content.addView(e, LinearLayout.LayoutParams(-1, dp(50f)).apply { bottomMargin = dp(10f) })
            favFields.add(e)
        }
        buttons(getString(R.string.wiz_save), getString(R.string.wiz_skip), { go(6) }, { go(6) })
    }

    private fun saveFields() {
        sosField?.let { Prefs.setSos(this, it.text.toString()) }
        favFields.forEachIndexed { i, e -> Prefs.setFav(this, i + 1, e.text.toString()) }
    }

    private fun ready() {
        val hero = FrameLayout(this)
        val check = ImageView(this).apply {
            setBackgroundResource(R.drawable.circle_hero)
            setImageResource(R.drawable.ic_check_dark)
            setPadding(dp(36f), dp(36f), dp(36f), dp(36f))
        }
        hero.addView(check, FrameLayout.LayoutParams(dp(150f), dp(150f), Gravity.CENTER))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(220f)).apply { topMargin = dp(24f) })
        content.addView(text(getString(R.string.wiz_ready_title), 32f, white, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2))
        content.addView(text(getString(R.string.wiz_ready_sub), 16f, soft).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f); bottomMargin = dp(22f) })
        row(R.drawable.circle_nav, R.drawable.ic_home_stroke, getString(R.string.wiz_home_title),
            getString(R.string.wiz_home_sub), pill(getString(R.string.wiz_set), true) { requestHomeRole() })
        buttons(getString(R.string.wiz_start), null, { finishWizard() })
    }

    private fun requestHomeRole() = UiHelpers.requestHomeRole(this)

    private fun finishWizard() {
        saveFields()
        Prefs.setLayoutMode(this, ride)
        if (Prefs.mode(this) == Prefs.MODE_DASHBOARD || Prefs.mode(this) == Prefs.MODE_COMPANION) {
            // keep advanced two-phone mode if the user chose it in Settings earlier
        } else {
            Prefs.setMode(this, Prefs.MODE_STANDALONE)
        }
        Prefs.setSetupDone(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
