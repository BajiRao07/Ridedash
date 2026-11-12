package com.ridedash.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class SetupActivity : AppCompatActivity() {

    private lateinit var modeGroup: RadioGroup
    private lateinit var permStatus: TextView
    private lateinit var peerText: TextView
    private lateinit var linkStatusText: TextView
    private lateinit var fav1: EditText
    private lateinit var fav2: EditText
    private lateinit var fav3: EditText
    private lateinit var quickReplies: EditText
    private lateinit var sosContact: EditText
    private lateinit var readAloud: CheckBox
    private lateinit var layoutGroup: RadioGroup
    private lateinit var pairingSection: View
    private lateinit var standaloneHelp: View

    private val hubListener: (RideHub.Change) -> Unit = { change ->
        when (change) {
            RideHub.Change.LINK -> { linkStatusText.text = RideHub.linkStatus; updatePeer() }
            RideHub.Change.PAIRING -> PairingDialog.update(this)
            else -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        modeGroup = findViewById(R.id.modeGroup)
        permStatus = findViewById(R.id.permStatus)
        peerText = findViewById(R.id.peerText)
        linkStatusText = findViewById(R.id.linkStatusText)
        fav1 = findViewById(R.id.fav1)
        fav2 = findViewById(R.id.fav2)
        fav3 = findViewById(R.id.fav3)
        quickReplies = findViewById(R.id.quickReplies)
        sosContact = findViewById(R.id.sosContact)
        readAloud = findViewById(R.id.readAloud)
        layoutGroup = findViewById(R.id.layoutGroup)
        pairingSection = findViewById(R.id.pairingSection)
        standaloneHelp = findViewById(R.id.standaloneHelp)

        modeGroup.check(
            when (Prefs.mode(this)) {
                Prefs.MODE_COMPANION -> R.id.modeCompanion
                Prefs.MODE_DASHBOARD -> R.id.modeDashboard
                else -> R.id.modeStandalone
            }
        )
        layoutGroup.check(
            when (Prefs.layoutMode(this)) {
                Prefs.LAYOUT_BIKE -> R.id.layoutBike
                Prefs.LAYOUT_CAR -> R.id.layoutCar
                else -> R.id.layoutAuto
            }
        )
        modeGroup.setOnCheckedChangeListener { _, _ -> updateSections() }
        updateSections()
        fav1.setText(Prefs.favRaw(this, 1))
        fav2.setText(Prefs.favRaw(this, 2))
        fav3.setText(Prefs.favRaw(this, 3))
        quickReplies.setText(Prefs.quickRepliesRaw(this))
        sosContact.setText(Prefs.sosRaw(this))
        readAloud.isChecked = Prefs.readAloud(this)

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener {
            ActivityCompat.requestPermissions(this, Perms.runtime(), 1)
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            else toast(getString(R.string.not_needed))
        }
        findViewById<Button>(R.id.btnBluetooth).setOnClickListener {
            open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        findViewById<Button>(R.id.btnStartPairing).setOnClickListener { startPairingOnBike() }
        findViewById<Button>(R.id.btnPickPeer).setOnClickListener { pickPeer() }
        findViewById<Button>(R.id.btnPairNow).setOnClickListener { pairFromMain() }
        findViewById<Button>(R.id.btnForget).setOnClickListener { forget() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndStart() }
    }

    override fun onResume() {
        super.onResume()
        RideHub.addListener(hubListener)
        updateStatus()
        updatePeer()
        linkStatusText.text = RideHub.linkStatus
        PairingDialog.update(this)
    }

    override fun onPause() {
        RideHub.removeListener(hubListener)
        PairingDialog.detach()
        super.onPause()
    }

    private fun updateStatus() {
        fun line(ok: Boolean, label: String) = (if (ok) "✓  " else "✗  ") + label
        permStatus.text = listOf(
            line(Perms.notifAccess(this), getString(R.string.st_notif)),
            line(Perms.location(this), getString(R.string.st_location)),
            line(Perms.phone(this), getString(R.string.st_phone)),
            line(Perms.bluetooth(this), getString(R.string.st_bluetooth))
        ).joinToString("\n")
    }

    private fun updatePeer() {
        val name = Prefs.peerName(this).ifBlank { Prefs.peerAddress(this) ?: "" }
        peerText.text = when {
            Prefs.isPaired(this) -> getString(R.string.paired_with, name)
            Prefs.peerAddress(this) != null -> getString(R.string.chosen_not_paired, name)
            else -> getString(R.string.not_paired)
        }
    }

    private fun selectedMode(): String = when (modeGroup.checkedRadioButtonId) {
        R.id.modeCompanion -> Prefs.MODE_COMPANION
        R.id.modeDashboard -> Prefs.MODE_DASHBOARD
        else -> Prefs.MODE_STANDALONE
    }

    private fun selectedCompanion() = selectedMode() == Prefs.MODE_COMPANION

    /** Standalone needs no pairing: show its setup tips instead. */
    private fun updateSections() {
        val standalone = selectedMode() == Prefs.MODE_STANDALONE
        pairingSection.visibility = if (standalone) View.GONE else View.VISIBLE
        standaloneHelp.visibility = if (standalone) View.VISIBLE else View.GONE
    }

    private fun saveSettings() {
        val mode = selectedMode()
        if (mode != Prefs.mode(this)) {
            // Changing role invalidates any existing pairing.
            SecureStore.clear(this)
            Prefs.clearPeer(this)
        }
        Prefs.setMode(this, mode)
        Prefs.setLayoutMode(
            this,
            when (layoutGroup.checkedRadioButtonId) {
                R.id.layoutBike -> Prefs.LAYOUT_BIKE
                R.id.layoutCar -> Prefs.LAYOUT_CAR
                else -> Prefs.LAYOUT_AUTO
            }
        )
        Prefs.setFav(this, 1, fav1.text.toString())
        Prefs.setFav(this, 2, fav2.text.toString())
        Prefs.setFav(this, 3, fav3.text.toString())
        Prefs.setQuickReplies(this, quickReplies.text.toString())
        Prefs.setSos(this, sosContact.text.toString())
        Prefs.setReadAloud(this, readAloud.isChecked)
        Prefs.setSetupDone(this)
    }

    private fun checkBluetoothReady(): Boolean {
        if (!Perms.bluetooth(this)) { toast(getString(R.string.allow_bt_first)); return false }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { toast(getString(R.string.no_bt)); return false }
        if (!adapter.isEnabled) { toast(getString(R.string.turn_on_bt)); return false }
        return true
    }

    /** Bike phone: open a 3-minute pairing window. */
    private fun startPairingOnBike() {
        if (selectedMode() != Prefs.MODE_DASHBOARD) { toast(getString(R.string.wrong_mode_dash)); return }
        if (!checkBluetoothReady()) return
        saveSettings()
        Prefs.openPairing(this)
        LinkService.restart(this)
        toast(getString(R.string.pairing_opened))
    }

    /** Main phone: connect to the chosen bike phone and run the pairing exchange. */
    private fun pairFromMain() {
        if (!selectedCompanion()) { toast(getString(R.string.wrong_mode_comp)); return }
        if (!checkBluetoothReady()) return
        if (Prefs.peerAddress(this) == null) { toast(getString(R.string.choose_peer_first)); return }
        saveSettings()
        Prefs.setPairingRequested(this, true)
        LinkService.restart(this)
        toast(getString(R.string.pairing_started))
    }

    private fun forget() {
        SecureStore.clear(this)
        Prefs.clearPeer(this)
        Prefs.setPairingRequested(this, false)
        Prefs.closePairing(this)
        LinkService.restart(this)
        updatePeer()
        toast(getString(R.string.forgot))
    }

    @SuppressLint("MissingPermission")
    private fun pickPeer() {
        if (!selectedCompanion()) { toast(getString(R.string.wrong_mode_comp)); return }
        if (!checkBluetoothReady()) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        try {
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) { toast(getString(R.string.pair_first)); return }
            val labels = devices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_bike_phone)
                .setItems(labels) { _, i ->
                    val d = devices[i]
                    if (!d.address.equals(Prefs.peerAddress(this), ignoreCase = true)) {
                        SecureStore.clear(this) // new device: old key no longer valid
                    }
                    Prefs.setPeer(this, d.address, d.name ?: d.address)
                    updatePeer()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: SecurityException) {
            toast(getString(R.string.allow_bt_first))
        }
    }

    private fun saveAndStart() {
        saveSettings()
        if (Prefs.isStandalone(this)) LinkService.stop(this) else LinkService.restart(this)
        if (!Perms.notifAccess(this)) toast(getString(R.string.notif_reminder))
        finish()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast(getString(R.string.open_settings_manually)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
