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
    const val MODE_STANDALONE = "standalone"

    const val LAYOUT_AUTO = "auto"
    const val LAYOUT_BIKE = "bike"
    const val LAYOUT_CAR = "car"

    private const val DEFAULT_REPLIES =
        "Riding now, will call you later\nOn my bike, reach in 10 minutes\nOK\nCall you soon"

    private fun p(c: Context) = c.getSharedPreferences("ridedash", Context.MODE_PRIVATE)

    fun mode(c: Context): String = p(c).getString("mode", MODE_STANDALONE) ?: MODE_STANDALONE
    fun setMode(c: Context, v: String) = p(c).edit().putString("mode", v).apply()
    fun isCompanion(c: Context) = mode(c) == MODE_COMPANION
    fun isStandalone(c: Context) = mode(c) == MODE_STANDALONE

    fun layoutMode(c: Context): String = p(c).getString("layout", LAYOUT_AUTO) ?: LAYOUT_AUTO
    fun setLayoutMode(c: Context, v: String) = p(c).edit().putString("layout", v).apply()

    fun peerAddress(c: Context): String? = p(c).getString("peer_addr", null)
    fun peerName(c: Context): String = p(c).getString("peer_name", "") ?: ""
    fun setPeer(c: Context, addr: String, name: String) =
        p(c).edit().putString("peer_addr", addr).putString("peer_name", name).apply()

    fun clearPeer(c: Context) = p(c).edit().remove("peer_addr").remove("peer_name").apply()

    fun isPaired(c: Context) = peerAddress(c) != null && SecureStore.loadKey(c) != null

    /** Main phone: user asked to pair with the chosen bike phone. */
    fun pairingRequested(c: Context) = p(c).getBoolean("pair_req", false)
    fun setPairingRequested(c: Context, v: Boolean) = p(c).edit().putBoolean("pair_req", v).apply()

    /** Bike phone: pairing window, open for 3 minutes. */
    fun pairingOpen(c: Context) = System.currentTimeMillis() < p(c).getLong("pair_until", 0L)
    fun openPairing(c: Context) =
        p(c).edit().putLong("pair_until", System.currentTimeMillis() + 3 * 60_000L).apply()
    fun closePairing(c: Context) = p(c).edit().putLong("pair_until", 0L).apply()

    fun setupDone(c: Context) = p(c).getBoolean("setup_done", false)
    fun setSetupDone(c: Context) = p(c).edit().putBoolean("setup_done", true).apply()

    fun readAloud(c: Context) = p(c).getBoolean("read_aloud", true)
    fun setReadAloud(c: Context, v: Boolean) = p(c).edit().putBoolean("read_aloud", v).apply()

    fun quickRepliesRaw(c: Context): String = p(c).getString("quick_replies", DEFAULT_REPLIES) ?: DEFAULT_REPLIES
    fun setQuickReplies(c: Context, v: String) = p(c).edit().putString("quick_replies", v).apply()
    fun quickReplies(c: Context): List<String> =
        quickRepliesRaw(c).lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Music app chosen by the user (package name). */
    fun musicApp(c: Context): String? = p(c).getString("music_app", null)
    fun setMusicApp(c: Context, v: String) = p(c).edit().putString("music_app", v).apply()

    /** Bluetooth devices that switch the layout: helmet = bike, car stereo = car. */
    fun btBike(c: Context): String? = p(c).getString("bt_bike", null)
    fun btCar(c: Context): String? = p(c).getString("bt_car", null)
    fun setBtRole(c: Context, addr: String, role: String?) {
        val e = p(c).edit()
        if (btBike(c) == addr) e.remove("bt_bike")
        if (btCar(c) == addr) e.remove("bt_car")
        when (role) {
            "bike" -> e.putString("bt_bike", addr)
            "car" -> e.putString("bt_car", addr)
        }
        e.apply()
    }

    fun slotsMigrated(c: Context) = p(c).getBoolean("slots_v2", false)
    fun setSlotsMigrated(c: Context) = p(c).edit().putBoolean("slots_v2", true).apply()

    fun clearSlots(c: Context) = p(c).edit().remove("slots").apply()

    fun slotsRaw(c: Context): String? = p(c).getString("slots", null)
    fun setSlotsRaw(c: Context, v: String) = p(c).edit().putString("slots", v).apply()

    fun sosRaw(c: Context): String = p(c).getString("sos", "") ?: ""
    fun setSos(c: Context, v: String) = p(c).edit().putString("sos", v.trim()).apply()

    /** SOS contact as (name, number), or null. */
    fun sosContact(c: Context): Pair<String, String>? {
        val raw = sosRaw(c)
        if (raw.isBlank()) return null
        val idx = raw.lastIndexOf(',')
        val name = if (idx < 0) raw else raw.substring(0, idx).trim()
        val num = if (idx < 0) raw.trim() else raw.substring(idx + 1).trim()
        if (num.count { it.isDigit() } < 6) return null
        return Pair(name.ifBlank { num }, num)
    }

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
