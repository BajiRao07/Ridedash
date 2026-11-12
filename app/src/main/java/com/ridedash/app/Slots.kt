package com.ridedash.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * The 8 home-screen icons. Bike mode shows the first 6, car mode all 8.
 * Each slot is a built-in RideDash function or an installed app.
 */
object Slots {
    const val NAV = "nav"
    const val MUSIC = "music"
    const val CHATS = "chats"
    const val CALL = "call"
    const val APPS = "apps"
    const val EMPTY = "empty"
    const val COUNT = 8

    val BUILTINS = listOf(NAV, MUSIC, CHATS, CALL, APPS)

    /** Popular apps placed by default, if installed. */
    private val DEFAULT_APPS = listOf(
        "com.whatsapp", "com.google.android.apps.youtube.music", "com.google.android.youtube",
        "com.spotify.music", "com.instagram.android", "com.jio.media.jiobeats",
        "com.google.android.apps.maps", "org.telegram.messenger", "com.whatsapp.w4b", "com.gaana"
    )

    data class Slot(val builtin: String?, val pkg: String?)

    fun isInstalled(c: Context, pkg: String) =
        c.packageManager.getLaunchIntentForPackage(pkg) != null

    fun load(c: Context): MutableList<Slot> {
        val raw = Prefs.slotsRaw(c)
        val list = if (raw != null) {
            raw.split(",").mapNotNull { e ->
                when {
                    e.startsWith("b:") -> Slot(e.substring(2), null)
                    e.startsWith("p:") -> Slot(null, e.substring(2))
                    else -> null
                }
            }.toMutableList()
        } else {
            val l = mutableListOf(Slot(NAV, null), Slot(MUSIC, null), Slot(CHATS, null), Slot(CALL, null))
            DEFAULT_APPS.filter { isInstalled(c, it) }.take(3).forEach { l.add(Slot(null, it)) }
            l.add(Slot(APPS, null))
            l
        }
        while (list.size < COUNT) list.add(Slot(EMPTY, null))
        return list.take(COUNT).map {
            // An app that was uninstalled becomes an empty slot.
            if (it.pkg != null && !isInstalled(c, it.pkg)) Slot(EMPTY, null) else it
        }.toMutableList()
    }

    fun save(c: Context, list: List<Slot>) {
        Prefs.setSlotsRaw(c, list.joinToString(",") { if (it.pkg != null) "p:${it.pkg}" else "b:${it.builtin}" })
    }

    data class AppEntry(val pkg: String, val label: String)

    /** Every app with a launcher icon, sorted by name. */
    fun launchableApps(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(i, 0)
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != c.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) { emptyList() }
    }

    fun label(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    fun icon(c: Context, pkg: String): Drawable? = try {
        c.packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { null }
}
