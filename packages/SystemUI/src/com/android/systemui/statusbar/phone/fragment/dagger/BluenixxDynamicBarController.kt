package com.android.systemui.statusbar.phone.fragment.dagger

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.dagger.qualifiers.RootView
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import javax.inject.Inject

/**
 * Bluenixx Dynamic Bar: compact media capsule in the status bar.
 */
@HomeStatusBarScope
class BluenixxDynamicBarController @Inject constructor(
    @RootView private val root: PhoneStatusBarView,
    private val mediaDataManager: MediaDataManager,
) {
    private val context: Context
        get() = root.context

    private val handler = Handler(Looper.getMainLooper())
    private val players = linkedMapOf<String, MediaData>()

    private var attached = false
    private var observerRegistered = false

    private val capsule: LinearLayout =
        root.requireViewById(R.id.dynamic_bar_capsule)
    private val artwork: ImageView =
        root.requireViewById(R.id.dynamic_bar_artwork)
    private val title: TextView =
        root.requireViewById(R.id.dynamic_bar_title)
    private val subtitle: TextView =
        root.requireViewById(R.id.dynamic_bar_artist)

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            render()
        }
    }

    private val mediaListener = object : MediaDataManager.Listener {
        override fun onMediaDataLoaded(
            key: String,
            oldKey: String?,
            data: MediaData,
            immediately: Boolean,
        ) {
            oldKey?.let { players.remove(it) }
            players[key] = data
            render()
        }

        override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
            players.remove(key)
            render()
        }
    }

    fun init() {
        capsule.background = GradientDrawable().apply {
            setColor(Color.rgb(24, 24, 27))
            cornerRadius = 48f * context.resources.displayMetrics.density
        }

        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    register()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    unregister()
                }
            }
        )

        if (root.isAttachedToWindow) register()
    }

    private fun register() {
        if (attached) return
        attached = true

        mediaDataManager.addListener(mediaListener)

        if (!observerRegistered) {
            val keys = listOf(
                KEY_ENABLED,
                KEY_MEDIA,
            )
            keys.forEach { key ->
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key),
                    false,
                    settingsObserver,
                )
            }
            observerRegistered = true
        }

        render()
    }

    private fun unregister() {
        if (!attached) return
        attached = false

        mediaDataManager.removeListener(mediaListener)

        if (observerRegistered) {
            context.contentResolver.unregisterContentObserver(settingsObserver)
            observerRegistered = false
        }
    }

    private fun settingEnabled(key: String): Boolean =
        Settings.System.getInt(context.contentResolver, key, 1) != 0

    private fun render() {
        if (!attached) return

        val enabled = settingEnabled(KEY_ENABLED) && settingEnabled(KEY_MEDIA)
        val media = players.values.lastOrNull {
            it.active && !it.resumption &&
                (!it.song.isNullOrBlank() || !it.artist.isNullOrBlank())
        }

        val centerClock = root.findViewById<View>(R.id.clock_center)
        val shouldShow = enabled && media != null &&
            (centerClock == null || centerClock.visibility != View.VISIBLE)

        if (!shouldShow) {
            capsule.animate().cancel()
            capsule.visibility = View.GONE
            return
        }

        title.text = media?.song ?: media?.app ?: "Reproduzindo mídia"
        subtitle.text = media?.artist ?: media?.app.orEmpty()

        val drawable = runCatching {
            media?.artwork?.loadDrawable(context)
        }.getOrNull()

        artwork.setImageDrawable(drawable)
        artwork.visibility = if (drawable != null) View.VISIBLE else View.GONE

        if (capsule.visibility != View.VISIBLE) {
            capsule.alpha = 0f
            capsule.scaleX = 0.88f
            capsule.scaleY = 0.88f
            capsule.visibility = View.VISIBLE
            capsule.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .start()
        }
    }

    private companion object {
        const val KEY_ENABLED = "bluenixx_dynamic_bar_enabled"
        const val KEY_MEDIA = "bluenixx_dynamic_bar_media"
    }
}
