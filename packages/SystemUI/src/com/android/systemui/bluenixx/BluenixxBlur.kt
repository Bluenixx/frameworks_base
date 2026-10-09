package com.android.systemui.bluenixx

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

object BluenixxBlur {
    const val KEY_ENABLED = "bluenixx_blur_enabled"
    const val KEY_INTENSITY = "bluenixx_blur_intensity"
    private const val DEFAULT_INTENSITY = 50

    @Volatile private var enabled = true
    @Volatile private var intensity = DEFAULT_INTENSITY
    @Volatile private var registered = false

    private fun load(context: Context) {
        val cr = context.contentResolver
        enabled = Settings.System.getInt(cr, KEY_ENABLED, 1) == 1
        intensity = Settings.System.getInt(cr, KEY_INTENSITY, DEFAULT_INTENSITY).coerceIn(0, 100)
    }

    @Synchronized
    private fun ensureRegistered(context: Context) {
        if (registered) return
        val app = context.applicationContext ?: context
        load(app)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = load(app)
        }
        val cr = app.contentResolver
        cr.registerContentObserver(Settings.System.getUriFor(KEY_ENABLED), false, obs)
        cr.registerContentObserver(Settings.System.getUriFor(KEY_INTENSITY), false, obs)
        registered = true
    }

    @JvmStatic
    fun scale(context: Context?, radius: Int): Int {
        if (context == null) return radius
        ensureRegistered(context)
        if (!enabled) return 0
        return (radius * intensity / DEFAULT_INTENSITY.toFloat()).toInt()
    }
}
