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
    const val CALENDAR = "calendar"
    const val SETTINGS = "settings"
    const val COUNT = 8

    val BUILTINS = listOf(NAV, MUSIC, CHATS, CALL, CALENDAR, SETTINGS, APPS)

    /** Apps paused while riding (video and social feeds). */
    val DISTRACTING = setOf(
        "com.google.android.youtube", "com.instagram.android", "com.facebook.katana", "com.facebook.lite",
        "com.snapchat.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "in.mohalla.video",
        "com.netflix.mediaclient", "in.startv.hotstar", "com.mxtech.videoplayer.ad", "com.amazon.avod.thirdpartyclient",
        "com.twitter.android", "com.reddit.frontpage", "com.sharechat.app"
    )

    /** Popular apps placed by default, if installed. Music and maps apps are left out:
     *  the Music and Map icons already cover them. */
    private val DEFAULT_APPS = listOf(
        "com.whatsapp", "com.instagram.android", "com.google.android.youtube",
        "org.telegram.messenger", "com.whatsapp.w4b", "com.truecaller"
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
            val apps = DEFAULT_APPS.filter { isInstalled(c, it) }
            apps.take(1).forEach { l.add(Slot(null, it)) }
            l.add(Slot(CALENDAR, null))
            l.add(Slot(SETTINGS, null))
            apps.drop(1).take(COUNT - l.size).forEach { l.add(Slot(null, it)) }
            l
        }
        while (list.size < COUNT) list.add(Slot(EMPTY, null))
        // Older versions put YouTube Music and an "All apps" icon on the home screen: drop them.
        if (raw != null && !Prefs.slotsMigrated(c)) {
            for (i in list.indices) {
                val s = list[i]
                if (s.builtin == APPS || s.pkg == "com.google.android.apps.youtube.music") list[i] = Slot(EMPTY, null)
            }
            Prefs.setSlotsMigrated(c)
            save(c, list)
        }
        // New icons (Calendar, Settings) go into free spots for people upgrading.
        if (raw != null && !Prefs.slotsV3(c)) {
            for (b in listOf(CALENDAR, SETTINGS)) {
                if (list.none { it.builtin == b }) {
                    val i = list.indexOfFirst { it.builtin == EMPTY }
                    if (i >= 0) list[i] = Slot(b, null)
                }
            }
            Prefs.setSlotsV3(c)
            save(c, list)
        }
        return list.take(COUNT).map {
            // An app that was uninstalled becomes an empty slot.
            if (it.pkg != null && !isInstalled(c, it.pkg)) Slot(EMPTY, null) else it
        }.toMutableList()
    }

    fun save(c: Context, list: List<Slot>) {
        Prefs.setSlotsRaw(c, list.joinToString(",") { if (it.pkg != null) "p:${it.pkg}" else "b:${it.builtin}" })
    }

    /** Music apps RideDash can control, in order of popularity in India. */
    val MUSIC_APPS = listOf(
        "com.google.android.apps.youtube.music", "com.spotify.music", "com.jio.media.jiobeats",
        "com.gaana", "com.bsbportal.music", "com.amazon.mp3", "com.apple.android.music",
        "com.sec.android.app.music", "com.google.android.youtube"
    )

    fun installedMusicApps(c: Context) = MUSIC_APPS.filter { isInstalled(c, it) }

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
