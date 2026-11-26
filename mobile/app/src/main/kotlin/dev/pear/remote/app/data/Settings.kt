package dev.pear.remote.app.data

import android.content.Context

/** Non-secret preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The desktop the remote controls (instance ID), or null. */
    var selectedDesktop: String?
        get() = prefs.getString("selected", null)
        set(v) = prefs.edit().apply { if (v == null) remove("selected") else putString("selected", v) }.apply()
}
