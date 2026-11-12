package com.ridedash.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var fav1: EditText
    private lateinit var fav2: EditText
    private lateinit var fav3: EditText
    private lateinit var quickReplies: EditText
    private lateinit var readAloud: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        modeGroup = findViewById(R.id.modeGroup)
        permStatus = findViewById(R.id.permStatus)
        peerText = findViewById(R.id.peerText)
        fav1 = findViewById(R.id.fav1)
        fav2 = findViewById(R.id.fav2)
        fav3 = findViewById(R.id.fav3)
        quickReplies = findViewById(R.id.quickReplies)
        readAloud = findViewById(R.id.readAloud)

        modeGroup.check(if (Prefs.isCompanion(this)) R.id.modeCompanion else R.id.modeDashboard)
        fav1.setText(Prefs.favRaw(this, 1))
        fav2.setText(Prefs.favRaw(this, 2))
        fav3.setText(Prefs.favRaw(this, 3))
        quickReplies.setText(Prefs.quickRepliesRaw(this))
        readAloud.isChecked = Prefs.readAloud(this)
        updatePeer()

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
        findViewById<Button>(R.id.btnPickPeer).setOnClickListener { pickPeer() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndStart() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
        val name = Prefs.peerName(this)
        peerText.text = if (Prefs.peerAddress(this) == null) getString(R.string.no_peer)
        else getString(R.string.peer_selected, name.ifBlank { Prefs.peerAddress(this) ?: "" })
    }

    @SuppressLint("MissingPermission")
    private fun pickPeer() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { toast(getString(R.string.no_bt)); return }
        if (!Perms.bluetooth(this)) { toast(getString(R.string.allow_bt_first)); return }
        try {
            if (!adapter.isEnabled) { toast(getString(R.string.turn_on_bt)); return }
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) { toast(getString(R.string.pair_first)); return }
            val labels = devices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_bike_phone)
                .setItems(labels) { _, i ->
                    Prefs.setPeer(this, devices[i].address, devices[i].name ?: devices[i].address)
                    updatePeer()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: SecurityException) {
            toast(getString(R.string.allow_bt_first))
        }
    }

    private fun saveAndStart() {
        val companion = modeGroup.checkedRadioButtonId == R.id.modeCompanion
        Prefs.setMode(this, if (companion) Prefs.MODE_COMPANION else Prefs.MODE_DASHBOARD)
        Prefs.setFav(this, 1, fav1.text.toString())
        Prefs.setFav(this, 2, fav2.text.toString())
        Prefs.setFav(this, 3, fav3.text.toString())
        Prefs.setQuickReplies(this, quickReplies.text.toString())
        Prefs.setReadAloud(this, readAloud.isChecked)
        Prefs.setSetupDone(this)

        if (companion && Prefs.peerAddress(this) == null) {
            toast(getString(R.string.choose_peer_first))
            return
        }
        // Restart the link so a mode change takes effect.
        LinkService.stop(this)
        LinkService.start(this)
        if (!Perms.notifAccess(this)) toast(getString(R.string.notif_reminder))
        finish()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast(getString(R.string.open_settings_manually)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
