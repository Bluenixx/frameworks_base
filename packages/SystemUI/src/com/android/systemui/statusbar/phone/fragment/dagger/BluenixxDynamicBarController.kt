package com.android.systemui.statusbar.phone.fragment.dagger

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
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
import androidx.palette.graphics.Palette
import com.android.systemui.dagger.qualifiers.RootView
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Bluenixx Dynamic Bar: compact media capsule in the status bar.
 */
@HomeStatusBarScope
class BluenixxDynamicBarController @Inject constructor(
    @RootView private val root: PhoneStatusBarView,
    private val mediaDataManager: MediaDataManager,
    @Background private val paletteExecutor: Executor,
) {
    private val context: Context
        get() = root.context

    private val handler = Handler(Looper.getMainLooper())
    private val players = linkedMapOf<String, MediaData>()

    private var attached = false
    private var observerRegistered = false
    private var paletteCacheKey: String? = null
    private var paletteCacheColor = Color.rgb(36, 36, 40)

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
        paletteCacheKey = null

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

        val cacheKey =
            "${media?.packageName}:${media?.song}:${media?.artwork?.hashCode()}"
        if (cacheKey != paletteCacheKey) {
            paletteCacheKey = cacheKey
            val bitmap = (drawable as? BitmapDrawable)?.bitmap
            if (bitmap == null) {
                paletteCacheColor = Color.rgb(36, 36, 40)
            } else {
                paletteExecutor.execute {
                    val color = runCatching {
                        Palette.from(bitmap).generate()
                            .getDominantColor(Color.rgb(36, 36, 40))
                    }.getOrDefault(Color.rgb(36, 36, 40))

                    handler.post {
                        val currentMedia = players.values.lastOrNull {
                            it.active && !it.resumption &&
                                (!it.song.isNullOrBlank() || !it.artist.isNullOrBlank())
                        }
                        val currentKey =
                            "${currentMedia?.packageName}:${currentMedia?.song}:${currentMedia?.artwork?.hashCode()}"
                        if (attached && currentKey == cacheKey) {
                            paletteCacheColor = color
                            val darkColor = Color.rgb(
                                (Color.red(color) * 0.42f).toInt(),
                                (Color.green(color) * 0.42f).toInt(),
                                (Color.blue(color) * 0.42f).toInt(),
                            )
                            (capsule.background as? GradientDrawable)?.setColor(darkColor)
                        }
                    }
                }
            }
        }

        val dominantColor = paletteCacheColor
        val darkColor = Color.rgb(
            (Color.red(dominantColor) * 0.42f).toInt(),
            (Color.green(dominantColor) * 0.42f).toInt(),
            (Color.blue(dominantColor) * 0.42f).toInt(),
        )
        (capsule.background as? GradientDrawable)?.setColor(darkColor)

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
