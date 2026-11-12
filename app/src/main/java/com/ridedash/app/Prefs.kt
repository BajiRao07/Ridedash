package com.ridedash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Prefs {
    const val MODE_DASHBOARD = "dashboard"
    const val MODE_COMPANION = "companion"

    private const val DEFAULT_REPLIES =
        "Riding now, will call you later\nOn my bike, reach in 10 minutes\nOK\nCall you soon"

    private fun p(c: Context) = c.getSharedPreferences("ridedash", Context.MODE_PRIVATE)

    fun mode(c: Context): String = p(c).getString("mode", MODE_DASHBOARD) ?: MODE_DASHBOARD
    fun setMode(c: Context, v: String) = p(c).edit().putString("mode", v).apply()
    fun isCompanion(c: Context) = mode(c) == MODE_COMPANION

    fun peerAddress(c: Context): String? = p(c).getString("peer_addr", null)
    fun peerName(c: Context): String = p(c).getString("peer_name", "") ?: ""
    fun setPeer(c: Context, addr: String, name: String) =
        p(c).edit().putString("peer_addr", addr).putString("peer_name", name).apply()

    fun setupDone(c: Context) = p(c).getBoolean("setup_done", false)
    fun setSetupDone(c: Context) = p(c).edit().putBoolean("setup_done", true).apply()

    fun readAloud(c: Context) = p(c).getBoolean("read_aloud", true)
    fun setReadAloud(c: Context, v: Boolean) = p(c).edit().putBoolean("read_aloud", v).apply()

    fun quickRepliesRaw(c: Context): String = p(c).getString("quick_replies", DEFAULT_REPLIES) ?: DEFAULT_REPLIES
    fun setQuickReplies(c: Context, v: String) = p(c).edit().putString("quick_replies", v).apply()
    fun quickReplies(c: Context): List<String> =
        quickRepliesRaw(c).lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Favourites stored as "Name, number". */
    fun favRaw(c: Context, i: Int): String = p(c).getString("fav$i", "") ?: ""
    fun setFav(c: Context, i: Int, v: String) = p(c).edit().putString("fav$i", v.trim()).apply()
    fun favourites(c: Context): List<Pair<String, String>> = (1..3).mapNotNull { i ->
        val raw = favRaw(c, i)
        if (raw.isBlank()) return@mapNotNull null
        val idx = raw.lastIndexOf(',')
        if (idx < 0) Pair(raw, raw)
        else {
            val name = raw.substring(0, idx).trim()
            val num = raw.substring(idx + 1).trim()
            if (num.isEmpty()) null else Pair(name.ifEmpty { num }, num)
        }
    }
}

object Perms {
    fun has(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun notifAccess(c: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun location(c: Context) = has(c, Manifest.permission.ACCESS_FINE_LOCATION)

    fun bluetooth(c: Context) =
        Build.VERSION.SDK_INT < 31 || has(c, Manifest.permission.BLUETOOTH_CONNECT)

    fun phone(c: Context) = has(c, Manifest.permission.READ_PHONE_STATE)

    fun runtime(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 26) l.add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }
}
