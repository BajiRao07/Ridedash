package com.ridedash.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object UiHelpers {
    /** Shows Android's own "Set as default home app?" popup (Android 10+), else the Home settings. */
    @Suppress("DEPRECATION")
    fun requestHomeRole(a: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = a.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                        Toast.makeText(a, R.string.wiz_home_already, Toast.LENGTH_SHORT).show()
                    } else {
                        a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 21)
                    }
                    return
                }
            }
            a.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(a, R.string.open_settings_manually, Toast.LENGTH_LONG).show()
        }
    }

    /** Lets the user pick which music app RideDash controls. */
    fun chooseMusicApp(a: Activity, done: () -> Unit) {
        val apps = Slots.installedMusicApps(a)
        if (apps.isEmpty()) {
            Toast.makeText(a, R.string.music_none, Toast.LENGTH_LONG).show()
            return
        }
        val labels = apps.map { Slots.label(a, it) }.toTypedArray()
        AlertDialog.Builder(a)
            .setTitle(R.string.music_choose)
            .setItems(labels) { _, i ->
                Prefs.setMusicApp(a, apps[i])
                done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
