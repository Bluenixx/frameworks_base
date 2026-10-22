package com.android.systemui.statusbar.phone.fragment.dagger

import android.app.AlertDialog
import android.content.Context
import android.widget.SeekBar
import android.os.SystemClock
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.media.session.MediaController
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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.palette.graphics.Palette
import com.android.systemui.dagger.qualifiers.RootView
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
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
    private val statusBarStateController: StatusBarStateController,
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
    private var playerDialog: AlertDialog? = null

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

    private val statusBarStateListener =
        object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                render()
            }
        }

    fun init() {
        artwork.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        artwork.clipToOutline = true

        capsule.isClickable = true
        capsule.isFocusable = true
        capsule.setOnClickListener {
            showPlayerPanel()
        }

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
        statusBarStateController.addCallback(statusBarStateListener)

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
        playerDialog?.dismiss()
        playerDialog = null
        paletteCacheKey = null

        mediaDataManager.removeListener(mediaListener)
        statusBarStateController.removeCallback(statusBarStateListener)

        if (observerRegistered) {
            context.contentResolver.unregisterContentObserver(settingsObserver)
            observerRegistered = false
        }
    }

    private fun currentMedia(): MediaData? =
        players.values.lastOrNull {
            it.active && !it.resumption &&
                (!it.song.isNullOrBlank() || !it.artist.isNullOrBlank())
        }

    private fun showPlayerPanel() {
        val media = currentMedia() ?: return
        playerDialog?.dismiss()

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 30, 34))
                cornerRadius = dp(28).toFloat()
            }
        }

        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val drawable = runCatching { media.artwork?.loadDrawable(context) }.getOrNull()
            setImageDrawable(drawable)
            background = GradientDrawable().apply {
                setColor(Color.rgb(55, 55, 60))
                cornerRadius = dp(20).toFloat()
            }
            clipToOutline = true
        }
        panel.addView(cover, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            bottomMargin = dp(20)
        })

        panel.addView(TextView(context).apply {
            text = media.song ?: media.app ?: "Reproduzindo mídia"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        panel.addView(TextView(context).apply {
            text = media.artist ?: media.app.orEmpty()
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6), 0, dp(18))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val mediaController = media.token?.let { token ->
            runCatching { MediaController(context, token) }.getOrNull()
        }
        val duration = mediaController?.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.coerceAtLeast(0L) ?: 0L

        if (duration > 0L) {
            val timeLabels = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            fun formatTime(milliseconds: Long): String {
                val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
                return "%d:%02d".format(seconds / 60L, seconds % 60L)
            }

            val elapsedLabel = TextView(context).apply {
                text = "0:00"
                textSize = 12f
                setTextColor(Color.LTGRAY)
            }
            val durationLabel = TextView(context).apply {
                text = formatTime(duration)
                textSize = 12f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.END
            }

            timeLabels.addView(
                elapsedLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            timeLabels.addView(
                durationLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val seekBar = SeekBar(context).apply {
                max = 1000
                val playbackState = mediaController?.playbackState
                val canSeek = playbackState != null &&
                    (playbackState.actions and PlaybackState.ACTION_SEEK_TO) != 0L
                isEnabled = canSeek
                contentDescription = "Progresso da reprodução"
            }

            var userSeeking = false
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }

                override fun onProgressChanged(
                    bar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        elapsedLabel.text = formatTime(duration * progress / 1000L)
                    }
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    val controller = mediaController
                    val state = controller?.playbackState
                    val canSeek = state != null &&
                        (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L

                    if (canSeek) {
                        controller?.transportControls?.seekTo(duration * bar.progress / 1000L)
                    }
                    userSeeking = false
                }
            })

            panel.addView(
                timeLabels,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            panel.addView(
                seekBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            )

            val progressUpdater = object : Runnable {
                override fun run() {
                    if (playerDialog == null || !panel.isShown) return

                    val controller = mediaController
                    val state = controller?.playbackState
                    val currentDuration = controller?.metadata
                        ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        ?.takeIf { it > 0L } ?: duration

                    if (state != null && !userSeeking) {
                        val updateTime = state.lastPositionUpdateTime
                        val elapsedSinceUpdate = if (updateTime > 0L) {
                            (SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                        val estimatedPosition = if (
                            state.state == PlaybackState.STATE_PLAYING && updateTime > 0L
                        ) {
                            state.position +
                                (elapsedSinceUpdate * state.playbackSpeed).toLong()
                        } else {
                            state.position
                        }
                        val position = estimatedPosition.coerceIn(0L, currentDuration)
                        seekBar.progress =
                            (position * seekBar.max / currentDuration).toInt()
                        elapsedLabel.text = formatTime(position)
                        durationLabel.text = formatTime(currentDuration)

                        val canSeek =
                            (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
                        seekBar.isEnabled = canSeek
                    }

                    handler.postDelayed(this, 500L)
                }
            }

            handler.post(progressUpdater)
            // Remove the updater when the panel closes to avoid retaining the view.
            panel.setTag(R.id.dynamic_bar_capsule, progressUpdater)
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addControl(label: String, description: String, action: (() -> Unit)?) {
            val button = TextView(context).apply {
                text = label
                textSize = if (label == "▶" || label == "Ⅱ") 26f else 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = description
                isClickable = action != null
                isFocusable = action != null
                setOnClickListener { action?.invoke() }
            }
            controls.addView(button, LinearLayout.LayoutParams(dp(64), dp(56)))
        }

        val buttons = media.semanticActions
        addControl("|◀", "Faixa anterior") { buttons?.prevOrCustom?.action?.run() }
        addControl(
            if (media.isPlaying == true) "Ⅱ" else "▶",
            if (media.isPlaying == true) "Pausar" else "Reproduzir",
        ) { buttons?.playOrPause?.action?.run() }
        addControl("▶|", "Próxima faixa") { buttons?.nextOrCustom?.action?.run() }
        panel.addView(controls)

        val dialog = AlertDialog.Builder(context)
            .setView(panel)
            .create()
        dialog.setOnDismissListener {
            (panel.getTag(R.id.dynamic_bar_capsule) as? Runnable)?.let(handler::removeCallbacks)
            if (playerDialog === dialog) playerDialog = null
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        playerDialog = dialog
        dialog.show()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        dialog.window?.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
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

        val unlockedStatusBar =
            statusBarStateController.getState() != StatusBarState.KEYGUARD
        val shouldShow = enabled && media != null && unlockedStatusBar

        if (!shouldShow) {
            playerDialog?.dismiss()
            playerDialog = null
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
