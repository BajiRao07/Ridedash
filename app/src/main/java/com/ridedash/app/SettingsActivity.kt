package com.ridedash.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Settings in the familiar Android style: grouped cards, each row with a round icon,
 * a title, a one-line summary and an arrow. Details open in small dialogs.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var syncSummary: TextView? = null

    private val hubListener: (RideHub.Change) -> Unit = { change ->
        when (change) {
            RideHub.Change.LINK -> { syncSummary?.text = syncLabel() }
            RideHub.Change.PAIRING -> {
                PairingDialog.update(this)
                if (RideHub.pairingCode == null) build()
            }
            else -> {}
        }
    }
    private val white = 0xFFFFFFFF.toInt()
    private val soft = 0xFFAEB4D0.toInt()

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundResource(R.drawable.bg_glow) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(28f))
        }
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        RideHub.addListener(hubListener)
        build()
        PairingDialog.update(this)
    }

    override fun onPause() {
        RideHub.removeListener(hubListener)
        PairingDialog.detach()
        super.onPause()
    }

    private fun syncLabel(): String = when {
        Prefs.mode(this) != Prefs.MODE_DASHBOARD -> getString(R.string.set_off)
        LinkService.isConnected -> getString(R.string.sync_connected)
        Prefs.isPaired(this) -> getString(R.string.sync_waiting)
        Prefs.pairingOpen(this) -> getString(R.string.sync_pairing)
        else -> getString(R.string.sync_not_paired)
    }

    /** Old phone: pair with RideDash Link on the main phone, or stop syncing. */
    private fun syncMenu() {
        val paired = Prefs.mode(this) == Prefs.MODE_DASHBOARD && Prefs.isPaired(this)
        val items = if (paired) arrayOf(getString(R.string.sync_stop), getString(R.string.sync_advanced))
        else arrayOf(getString(R.string.sync_start), getString(R.string.sync_advanced))
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_title)
            .setItems(items) { _, i ->
                when {
                    i == 1 -> startActivity(Intent(this, SetupActivity::class.java))
                    paired -> {
                        SecureStore.clear(this)
                        Prefs.clearPeer(this)
                        Prefs.closePairing(this)
                        Prefs.setMode(this, Prefs.MODE_STANDALONE)
                        LinkService.stop(this)
                        build()
                    }
                    else -> {
                        if (!Perms.bluetooth(this)) {
                            toast(getString(R.string.allow_bt_first))
                            startActivity(Intent(this, WizardActivity::class.java))
                            return@setItems
                        }
                        Prefs.setMode(this, Prefs.MODE_DASHBOARD)
                        Prefs.openPairing(this)
                        LinkService.restart(this)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.sync_title)
                            .setMessage(R.string.sync_steps)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        build()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    trailing: View? = null, onClick: (() -> Unit)?): TextView? {
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
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
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
        val sum = if (summary.isNotEmpty()) text(summary, 13.5f, soft).apply { maxLines = 2 } else null
        sum?.let { col.addView(it) }
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14f); rightMargin = dp(8f) })
        r.addView(trailing ?: text("›", 26f, 0xFF6F7695.toInt()))
        card.addView(r, LinearLayout.LayoutParams(-1, -2))
        return sum
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun editDialog(title: String, hint: String, value: String, multiLine: Boolean = false, save: (String) -> Unit) {
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT
            if (multiLine) minLines = 4
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(e, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton(R.string.wiz_save) { _, _ -> save(e.text.toString()); build() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- the page ----------------

    private fun build() {
        list.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back_w)
            setBackgroundResource(R.drawable.circle_glass)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        header.addView(text(getString(R.string.settings), 30f, white, true),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14f) })
        list.addView(header, LinearLayout.LayoutParams(-1, dp(56f)))

        // ---- Ride ----
        val ride = section(getString(R.string.set_ride))
        val layoutLabel = when (Prefs.layoutMode(this)) {
            Prefs.LAYOUT_BIKE -> getString(R.string.layout_bike)
            Prefs.LAYOUT_CAR -> getString(R.string.layout_car)
            else -> getString(R.string.layout_auto)
        }
        row(ride, R.drawable.circle_nav, R.drawable.ic_bike, getString(R.string.h_layout), layoutLabel) {
            val modes = listOf(Prefs.LAYOUT_AUTO, Prefs.LAYOUT_BIKE, Prefs.LAYOUT_CAR)
            val labels = arrayOf(getString(R.string.layout_auto), getString(R.string.layout_bike), getString(R.string.layout_car))
            AlertDialog.Builder(this)
                .setTitle(R.string.h_layout)
                .setSingleChoiceItems(labels, modes.indexOf(Prefs.layoutMode(this)).coerceAtLeast(0)) { d, i ->
                    Prefs.setLayoutMode(this, modes[i])
                    d.dismiss()
                    build()
                }
                .show()
        }
        val music = Prefs.musicApp(this)?.let { Slots.label(this, it) } ?: getString(R.string.set_not_chosen)
        row(ride, R.drawable.circle_music, R.drawable.ic_tile_music, getString(R.string.music_choose), music) {
            UiHelpers.chooseMusicApp(this) { build() }
        }
        val sw = SwitchCompat(this).apply {
            isChecked = Prefs.readAloud(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setReadAloud(this@SettingsActivity, v) }
        }
        row(ride, R.drawable.circle_chats, R.drawable.ic_bell, getString(R.string.set_read_aloud),
            getString(R.string.set_read_aloud_sub), sw) { sw.toggle() }
        row(ride, R.drawable.circle_glass, R.drawable.ic_grid, getString(R.string.set_icons),
            getString(R.string.set_icons_sub)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_icons_reset)
                .setMessage(R.string.set_icons_reset_msg)
                .setPositiveButton(R.string.set_reset) { _, _ ->
                    Prefs.clearSlots(this)
                    toast(getString(R.string.set_done))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // ---- Safety & contacts ----
        val safety = section(getString(R.string.set_safety))
        val sos = Prefs.sosContact(this)
        row(safety, R.drawable.circle_sos, R.drawable.ic_sos_tri, getString(R.string.h_sos),
            sos?.let { "${it.first} · ${it.second}" } ?: getString(R.string.set_not_set)) {
            editDialog(getString(R.string.h_sos), getString(R.string.sos_hint), Prefs.sosRaw(this)) { Prefs.setSos(this, it) }
        }
        val favCount = Prefs.favourites(this).size
        row(safety, R.drawable.circle_call, R.drawable.ic_tile_call, getString(R.string.favourites),
            if (favCount == 0) getString(R.string.set_not_set) else getString(R.string.set_saved_n, favCount)) {
            editFavourites()
        }
        val replies = Prefs.quickReplies(this)
        row(safety, R.drawable.circle_chats, R.drawable.ic_tile_chats, getString(R.string.set_replies),
            replies.firstOrNull()?.let { "“$it”" + if (replies.size > 1) " +${replies.size - 1}" else "" } ?: getString(R.string.set_not_set)) {
            editDialog(getString(R.string.set_replies), getString(R.string.set_replies_hint),
                Prefs.quickRepliesRaw(this), true) { Prefs.setQuickReplies(this, it) }
        }

        // ---- Riding safety ----
        val safe = section(getString(R.string.set_riding_safety))
        val limit = Prefs.speedLimit(this)
        row(safe, R.drawable.circle_sos, R.drawable.ic_bike, getString(R.string.set_speed),
            if (limit == 0) getString(R.string.set_off) else getString(R.string.set_speed_sub, limit)) {
            val values = listOf(0, 40, 60, 80, 100, 120)
            val labels = values.map { if (it == 0) getString(R.string.set_off) else "$it km/h" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.set_speed)
                .setSingleChoiceItems(labels, values.indexOf(limit).coerceAtLeast(0)) { d, i ->
                    Prefs.setSpeedLimit(this, values[i])
                    d.dismiss()
                    build()
                }
                .show()
        }
        val lock = SwitchCompat(this).apply {
            isChecked = Prefs.ridingLock(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setRidingLock(this@SettingsActivity, v) }
        }
        row(safe, R.drawable.circle_call, R.drawable.ic_check, getString(R.string.set_lock),
            getString(R.string.set_lock_sub), lock) { lock.toggle() }
        val auto = SwitchCompat(this).apply {
            isChecked = Prefs.autoReply(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoReply(this@SettingsActivity, v) }
        }
        row(safe, R.drawable.circle_chats, R.drawable.ic_tile_chats, getString(R.string.set_autoreply),
            getString(R.string.set_autoreply_sub), auto) { auto.toggle() }
        row(safe, R.drawable.circle_glass, R.drawable.ic_tile_chats, getString(R.string.set_autoreply_text),
            "“" + Prefs.autoReplyText(this) + "”") {
            editDialog(getString(R.string.set_autoreply_text), "", Prefs.autoReplyText(this)) {
                if (it.isNotBlank()) Prefs.setAutoReplyText(this, it)
            }
        }

        // ---- Phone ----
        val phone = section(getString(R.string.set_phone))
        row(phone, R.drawable.circle_nav, R.drawable.ic_home_stroke, getString(R.string.wiz_home_title),
            getString(R.string.wiz_home_sub)) { UiHelpers.requestHomeRole(this) }
        val allowed = listOf(Perms.location(this), Perms.notifAccess(this), Perms.phone(this), Perms.bluetooth(this)).count { it }
        row(phone, R.drawable.circle_music, R.drawable.ic_bt, getString(R.string.set_perms),
            getString(R.string.set_perms_sub, allowed)) {
            startActivity(Intent(this, WizardActivity::class.java))
        }
        if (Build.VERSION.SDK_INT >= 23) {
            row(phone, R.drawable.circle_chats, R.drawable.ic_star, getString(R.string.set_battery),
                getString(R.string.set_battery_sub)) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { }
            }
        }

        // ---- Advanced ----
        val adv = section(getString(R.string.set_advanced))
        syncSummary = row(adv, R.drawable.circle_call, R.drawable.ic_bt, getString(R.string.sync_title), syncLabel()) {
            syncMenu()
        }
        row(adv, R.drawable.circle_glass, R.drawable.ic_star, getString(R.string.run_wizard), "") {
            startActivity(Intent(this, WizardActivity::class.java))
        }

        // ---- About ----
        val about = section(getString(R.string.set_about))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        row(about, R.drawable.circle_nav, R.drawable.ic_launcher_fg, "RideDash", getString(R.string.set_version, version ?: ""), View(this), null)
        row(about, R.drawable.circle_chats, R.drawable.ic_check, getString(R.string.set_privacy),
            getString(R.string.wiz_privacy), View(this), null)
    }

    private fun editFavourites() {
        val fields = (1..3).map { i ->
            EditText(this).apply {
                setText(Prefs.favRaw(this@SettingsActivity, i))
                hint = getString(R.string.fav_hint)
                inputType = InputType.TYPE_CLASS_TEXT
            }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            fields.forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.favourites)
            .setView(wrap)
            .setPositiveButton(R.string.wiz_save) { _, _ ->
                fields.forEachIndexed { i, e -> Prefs.setFav(this, i + 1, e.text.toString()) }
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
