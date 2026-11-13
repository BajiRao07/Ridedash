package com.ridedash.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat

/**
 * RideDash Link: the small app on the MAIN phone.
 * It sends calls, messages and music to the bike screen over an encrypted,
 * code-paired Bluetooth link. It has no internet permission at all.
 */
class LinkActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var statusText: TextView? = null
    private val white = 0xFFFFFFFF.toInt()
    private val soft = 0xFFAEB4D0.toInt()

    private val hubListener: (RideHub.Change) -> Unit = { change ->
        when (change) {
            RideHub.Change.LINK -> { statusText?.text = RideHub.linkStatus }
            RideHub.Change.PAIRING -> {
                PairingDialog.update(this)
                if (RideHub.pairingCode == null) build()
            }
            else -> {}
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.setMode(this, Prefs.MODE_COMPANION)
        Prefs.setSetupDone(this)
        val scroll = ScrollView(this).apply { setBackgroundResource(R.drawable.bg_glow) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(28f))
        }
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        RideHub.addListener(hubListener)
        build()
        PairingDialog.update(this)
        if (Prefs.isPaired(this) || Prefs.pairingRequested(this)) LinkService.start(this)
    }

    override fun onPause() {
        RideHub.removeListener(hubListener)
        PairingDialog.detach()
        super.onPause()
    }

    // ---------------- building blocks ----------------

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(title: String): LinearLayout {
        list.addView(text(title.uppercase(), 13f, 0xFF4FF0CF.toInt(), true).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22f); bottomMargin = dp(8f); leftMargin = dp(6f) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
        }
        list.addView(card, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun row(card: LinearLayout, bg: Int, icon: Int, title: String, summary: String,
                    trailing: View?, onClick: (() -> Unit)?) {
        if (card.childCount > 0) {
            card.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(68f) })
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        r.addView(ImageView(this).apply {
            setBackgroundResource(bg)
            setImageResource(icon)
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 16.5f, white, true))
        if (summary.isNotEmpty()) col.addView(text(summary, 13.5f, soft))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14f); rightMargin = dp(8f) })
        if (trailing != null) r.addView(trailing)
        card.addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun status(ok: Boolean, onAllow: () -> Unit): View =
        if (ok) ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            setBackgroundResource(R.drawable.circle_ok)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
        } else text(getString(R.string.wiz_allow), 14f, 0xFF070815.toInt(), true).apply {
            setBackgroundResource(R.drawable.bg_white_pill)
            setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
            setOnClickListener { onAllow() }
        }

    private fun switch(key: String, on: Boolean): SwitchCompat = SwitchCompat(this).apply {
        isChecked = on
        setOnCheckedChangeListener { _, v -> Prefs.setShare(this@LinkActivity, key, v) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------- page ----------------

    private fun build() {
        list.removeAllViews()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply {
            setBackgroundResource(R.drawable.circle_hero)
            setImageResource(R.drawable.ic_launcher_fg)
        }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hc.addView(text(getString(R.string.app_name), 26f, white, true))
        hc.addView(text(getString(R.string.link_tagline), 14f, soft))
        head.addView(hc, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14f) })
        list.addView(head)

        // Live status
        val st = text(RideHub.linkStatus, 16f, white, true).apply {
            setBackgroundResource(R.drawable.bg_card_sel)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        statusText = st
        list.addView(st, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })

        // 1. Permissions
        val perms = section(getString(R.string.link_s_allow))
        row(perms, R.drawable.circle_chats, R.drawable.ic_bell, getString(R.string.wiz_p_notif),
            getString(R.string.link_notif_sub), status(Perms.notifAccess(this)) {
                toast(getString(R.string.wiz_notif_hint))
                try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) { }
            }, null)
        row(perms, R.drawable.circle_call, R.drawable.ic_tile_call, getString(R.string.wiz_p_phone),
            getString(R.string.link_phone_sub), status(Perms.phone(this)) {
                val l = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
                if (Build.VERSION.SDK_INT >= 26) l.add(Manifest.permission.ANSWER_PHONE_CALLS)
                ActivityCompat.requestPermissions(this, l.toTypedArray(), 41)
            }, null)
        row(perms, R.drawable.circle_music, R.drawable.ic_bt, getString(R.string.wiz_p_bt),
            getString(R.string.link_bt_sub), status(Perms.bluetooth(this)) {
                val l = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
                if (l.isNotEmpty()) ActivityCompat.requestPermissions(this, l.toTypedArray(), 42)
            }, null)

        // 2. Bike screen
        val bike = section(getString(R.string.link_s_bike))
        val peer = Prefs.peerName(this).ifBlank { Prefs.peerAddress(this) ?: "" }
        row(bike, R.drawable.circle_nav, R.drawable.ic_bike, getString(R.string.link_choose),
            if (peer.isBlank()) getString(R.string.set_not_chosen) else peer, text("›", 26f, 0xFF6F7695.toInt())) { pickBikePhone() }
        if (Prefs.isPaired(this)) {
            row(bike, R.drawable.circle_ok, R.drawable.ic_check, getString(R.string.link_paired),
                getString(R.string.link_paired_sub), null, null)
            row(bike, R.drawable.circle_sos, R.drawable.ic_sos_tri, getString(R.string.btn_forget),
                getString(R.string.link_forget_sub), text("›", 26f, 0xFF6F7695.toInt())) {
                SecureStore.clear(this)
                Prefs.clearPeer(this)
                Prefs.setPairingRequested(this, false)
                LinkService.stop(this)
                build()
            }
        } else {
            row(bike, R.drawable.circle_call, R.drawable.ic_check, getString(R.string.btn_pair_now),
                getString(R.string.link_pair_sub), text("›", 26f, 0xFF6F7695.toInt())) { pairNow() }
        }

        // 3. What to share
        val share = section(getString(R.string.link_s_share))
        val m = switch("share_msgs", Prefs.shareMessages(this))
        row(share, R.drawable.circle_chats, R.drawable.ic_tile_chats, getString(R.string.link_share_msgs),
            getString(R.string.link_share_msgs_sub), m) { m.toggle() }
        val c = switch("share_calls", Prefs.shareCalls(this))
        row(share, R.drawable.circle_call, R.drawable.ic_tile_call, getString(R.string.link_share_calls),
            getString(R.string.link_share_calls_sub), c) { c.toggle() }
        val mu = switch("share_music", Prefs.shareMusic(this))
        row(share, R.drawable.circle_music, R.drawable.ic_tile_music, getString(R.string.link_share_music),
            getString(R.string.link_share_music_sub), mu) { mu.toggle() }

        // 4. Keep running
        val run = section(getString(R.string.link_s_run))
        if (Build.VERSION.SDK_INT >= 23) {
            row(run, R.drawable.circle_glass, R.drawable.ic_star, getString(R.string.set_battery),
                getString(R.string.link_battery_sub), text("›", 26f, 0xFF6F7695.toInt())) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { }
            }
        }
        list.addView(text(getString(R.string.link_privacy), 13.5f, 0xFFDDF7A8.toInt()).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })
    }

    @SuppressLint("MissingPermission")
    private fun pickBikePhone() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { toast(getString(R.string.no_bt)); return }
        if (!Perms.bluetooth(this)) { toast(getString(R.string.allow_bt_first)); return }
        try {
            if (!adapter.isEnabled) { toast(getString(R.string.turn_on_bt)); return }
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) {
                toast(getString(R.string.pair_first))
                try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (e: Exception) { }
                return
            }
            val labels = devices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_bike_phone)
                .setItems(labels) { _, i ->
                    val d = devices[i]
                    if (!d.address.equals(Prefs.peerAddress(this), ignoreCase = true)) SecureStore.clear(this)
                    Prefs.setPeer(this, d.address, d.name ?: d.address)
                    build()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: SecurityException) {
            toast(getString(R.string.allow_bt_first))
        }
    }

    private fun pairNow() {
        if (!Perms.bluetooth(this)) { toast(getString(R.string.allow_bt_first)); return }
        if (Prefs.peerAddress(this) == null) { pickBikePhone(); return }
        Prefs.setMode(this, Prefs.MODE_COMPANION)
        Prefs.setPairingRequested(this, true)
        LinkService.restart(this)
        toast(getString(R.string.link_pairing_toast))
    }
}
