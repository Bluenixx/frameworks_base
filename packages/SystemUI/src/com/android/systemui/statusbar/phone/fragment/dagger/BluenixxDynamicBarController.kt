package com.android.systemui.statusbar.phone.fragment.dagger

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.palette.graphics.Palette
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.RootView
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import java.util.Locale
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Bluenixx Dynamic Bar: compact media capsule in the status bar.
 *
 * - Capsule: artwork + title/subtitle, tinted with the artwork's dominant color.
 * - Tap on the capsule toggles a OneUI-style player panel below the status bar.
 *
 * The panel is rebuilt from the latest [MediaData] on every [render], so play/pause icon,
 * title, artwork and actions never go stale while it is open.
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

    // Insertion order == recency of the last update (see mediaListener).
    private val players = linkedMapOf<String, MediaData>()

    private var initialized = false
    private var attached = false
    private var observerRegistered = false

    // Capsule visibility target. Source of truth while enter/exit animations run.
    private var capsuleVisible = false

    // Already-darkened color shared by capsule and panel.
    private var tintColor = darken(DEFAULT_COLOR)
    private var tintKey: String? = null
    private var tintAnimator: ValueAnimator? = null

    // Which Icon is currently bound to the capsule artwork (identity compare).
    private var boundArtworkSource: Any? = null
    private var boundArtworkDrawable: Drawable? = null

    private var playerDialog: AlertDialog? = null
    private var playerPanel: PlayerPanel? = null

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
            if (oldKey != null && oldKey != key) players.remove(oldKey)
            // Remove + put moves the entry to the end, so "last" really means "most recent".
            players.remove(key)
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
        if (initialized) return
        initialized = true

        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        artwork.clipToOutline = true

        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(Color.WHITE)
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        subtitle.setTextColor(SECONDARY_TEXT)

        capsule.isClickable = true
        capsule.isFocusable = true
        capsule.setOnClickListener { togglePlayerPanel() }
        capsule.background = GradientDrawable().apply {
            setColor(tintColor)
            cornerRadius = dp(48).toFloat()
        }
        capsule.visibility = View.GONE
        capsuleVisible = false

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
            listOf(KEY_ENABLED, KEY_MEDIA).forEach { key ->
                // USER_ALL: the setting belongs to whoever is the current user, not only user 0.
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key),
                    false,
                    settingsObserver,
                    UserHandle.USER_ALL,
                )
            }
            observerRegistered = true
        }

        render()
    }

    private fun unregister() {
        if (!attached) return
        attached = false

        dismissPanel()
        handler.removeCallbacksAndMessages(null)

        tintAnimator?.cancel()
        tintAnimator = null
        tintKey = null
        boundArtworkSource = null
        boundArtworkDrawable = null

        capsule.animate().cancel()
        capsule.visibility = View.GONE
        capsuleVisible = false

        mediaDataManager.removeListener(mediaListener)
        statusBarStateController.removeCallback(statusBarStateListener)

        if (observerRegistered) {
            context.contentResolver.unregisterContentObserver(settingsObserver)
            observerRegistered = false
        }
    }

    // ------------------------------------------------------------------------------------
    // Media selection / text helpers (single source of truth, used by capsule AND panel)
    // ------------------------------------------------------------------------------------

    private fun MediaData.hasContent(): Boolean =
        this.active && !this.resumption &&
            (!this.song.isNullOrBlank() || !this.artist.isNullOrBlank())

    /** Prefers whatever is actually playing; otherwise the most recently updated player. */
    private fun currentMedia(): MediaData? {
        val candidates = players.values.filter { it.hasContent() }
        return candidates.lastOrNull { it.isPlaying == true } ?: candidates.lastOrNull()
    }

    private fun CharSequence?.orNullIfBlank(): CharSequence? = this?.takeIf { it.isNotBlank() }

    private fun titleOf(media: MediaData): CharSequence =
        media.song.orNullIfBlank()
            ?: media.artist.orNullIfBlank()
            ?: media.app.orNullIfBlank()
            ?: FALLBACK_TITLE

    private fun subtitleOf(media: MediaData): CharSequence =
        if (media.song.orNullIfBlank() != null) {
            media.artist.orNullIfBlank() ?: media.app.orEmpty()
        } else {
            media.app.orEmpty()
        }

    private fun loadIconDrawable(icon: Icon?): Drawable? =
        icon?.let { runCatching { it.loadDrawable(context) }.getOrNull() }

    private fun isFeatureEnabled(): Boolean =
        settingEnabled(KEY_ENABLED) && settingEnabled(KEY_MEDIA)

    private fun settingEnabled(key: String): Boolean =
        Settings.System.getIntForUser(
            context.contentResolver,
            key,
            1,
            UserHandle.USER_CURRENT,
        ) != 0

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) * TINT_FACTOR).toInt(),
        (Color.green(color) * TINT_FACTOR).toInt(),
        (Color.blue(color) * TINT_FACTOR).toInt(),
    )

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    // ------------------------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------------------------

    private fun render() {
        if (!attached) return

        val media = currentMedia()

        // Only plain SHADE: hide on KEYGUARD *and* SHADE_LOCKED (shade pulled on the lockscreen).
        val inShadeState = statusBarStateController.getState() == StatusBarState.SHADE
        val shouldShow = media != null && inShadeState && isFeatureEnabled()

        if (media == null || !shouldShow) {
            dismissPanel()
            hideCapsule()
            return
        }

        bindCapsule(media)
        playerPanel?.bind(media)
        showCapsule()
    }

    private fun bindCapsule(media: MediaData) {
        val titleText = titleOf(media)
        val subtitleText = subtitleOf(media)

        if (!TextUtils.equals(title.text, titleText)) title.text = titleText
        if (!TextUtils.equals(subtitle.text, subtitleText)) subtitle.text = subtitleText
        subtitle.visibility = if (subtitleText.isBlank()) View.GONE else View.VISIBLE

        capsule.contentDescription =
            if (subtitleText.isBlank()) titleText else "$titleText, $subtitleText"

        // Artwork, falling back to the app icon so the capsule layout never jumps.
        val source: Any? = media.artwork ?: media.appIcon
        if (source !== boundArtworkSource) {
            boundArtworkSource = source
            val drawable = loadIconDrawable(media.artwork) ?: loadIconDrawable(media.appIcon)
            boundArtworkDrawable = drawable
            artwork.setImageDrawable(drawable)
            artwork.visibility = if (drawable != null) View.VISIBLE else View.GONE
        }

        val key = "${media.packageName}|$titleText|$subtitleText|" +
            System.identityHashCode(source)
        if (key != tintKey) {
            tintKey = key
            updateTint(key, boundArtworkDrawable)
        }
    }

    // ------------------------------------------------------------------------------------
    // Tint (palette)
    // ------------------------------------------------------------------------------------

    private fun updateTint(key: String, drawable: Drawable?) {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap == null || bitmap.isRecycled) {
            setTint(darken(DEFAULT_COLOR))
            return
        }

        paletteExecutor.execute {
            val color = runCatching {
                // Palette can't read HARDWARE bitmaps; copy to a software one first.
                val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
                if (readable == null) {
                    DEFAULT_COLOR
                } else {
                    Palette.from(readable).generate().getDominantColor(DEFAULT_COLOR)
                }
            }.getOrDefault(DEFAULT_COLOR)

            handler.post {
                // Drop stale results: another track may have taken over meanwhile.
                if (attached && tintKey == key) setTint(darken(color))
            }
        }
    }

    private fun setTint(target: Int) {
        if (target == tintColor) return

        tintAnimator?.cancel()
        tintAnimator = null

        // No animation while the capsule is hidden (first show / background updates).
        if (!capsuleVisible) {
            tintColor = target
            applyTint()
            return
        }

        val animator = ValueAnimator.ofObject(ArgbEvaluator(), tintColor, target)
        animator.duration = COLOR_ANIM_MS
        animator.addUpdateListener {
            tintColor = it.animatedValue as Int
            applyTint()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (tintAnimator === animation) tintAnimator = null
            }
        })
        tintAnimator = animator
        animator.start()
    }

    private fun applyTint() {
        (capsule.background as? GradientDrawable)?.setColor(tintColor)
        playerPanel?.applyTint(tintColor)
    }

    // ------------------------------------------------------------------------------------
    // Capsule show / hide
    // ------------------------------------------------------------------------------------

    private fun showCapsule() {
        if (capsuleVisible) return
        capsuleVisible = true

        capsule.animate().cancel()
        if (capsule.visibility != View.VISIBLE) {
            capsule.alpha = 0f
            capsule.scaleX = HIDDEN_SCALE
            capsule.scaleY = HIDDEN_SCALE
            capsule.visibility = View.VISIBLE
        }
        capsule.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SHOW_MS)
            .start()
    }

    private fun hideCapsule() {
        if (!capsuleVisible) return
        capsuleVisible = false

        capsule.animate().cancel()
        if (capsule.visibility != View.VISIBLE) return

        capsule.animate()
            .alpha(0f)
            .scaleX(HIDDEN_SCALE)
            .scaleY(HIDDEN_SCALE)
            .setDuration(HIDE_MS)
            .withEndAction {
                // A show() may have happened while we were animating out.
                if (!capsuleVisible) capsule.visibility = View.GONE
            }
            .start()
    }

    // ------------------------------------------------------------------------------------
    // Player panel (OneUI style)
    // ------------------------------------------------------------------------------------

    private fun togglePlayerPanel() {
        if (playerDialog != null) {
            dismissPanel()
        } else {
            showPlayerPanel()
        }
    }

    private fun dismissPanel() {
        val dialog = playerDialog ?: return
        playerDialog = null
        playerPanel?.release()
        playerPanel = null
        dialog.dismiss()
    }

    private fun showPlayerPanel() {
        val media = currentMedia() ?: return

        val panel = PlayerPanel()
        panel.applyTint(tintColor)
        panel.bind(media)

        val dialog = AlertDialog.Builder(context)
            .setView(panel.view)
            .create()

        // Dismissed from outside (tap outside, system): clean up our side too.
        dialog.setOnDismissListener {
            if (playerDialog === dialog) {
                playerDialog = null
                playerPanel?.release()
                playerPanel = null
            }
        }

        dialog.window?.let { window ->
            // Type must be set BEFORE show(); setting it afterwards does nothing.
            window.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
            // Without this the AlertDialog draws its own card behind our rounded panel.
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)

            val screenWidth = context.resources.displayMetrics.widthPixels
            val width = minOf(dp(PANEL_WIDTH_DP), screenWidth - dp(PANEL_SIDE_MARGIN_DP) * 2)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

            val params = window.attributes
            params.y = panelTopOffset()
            window.attributes = params
        }

        playerDialog = dialog
        playerPanel = panel
        dialog.show()
        panel.start()
    }

    /** Panel sits just under the status bar, like the OneUI pop-up. */
    private fun panelTopOffset(): Int {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        return location[1] + root.height + dp(PANEL_TOP_MARGIN_DP)
    }

    private inner class PlayerPanel {
        private var released = false
        private var userSeeking = false
        private var seekHoldUntil = 0L
        private var currentDuration = 0L

        private var controller: MediaController? = null
        private var boundToken: Any? = null
        private var boundCoverSource: Any? = null

        private val appLabel = TextView(context).apply {
            setTextColor(SECONDARY_TEXT)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        private val titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        private val artistView = TextView(context).apply {
            setTextColor(SECONDARY_TEXT)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        private val coverView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(COVER_PLACEHOLDER)
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
        }

        private val elapsedLabel = TextView(context).apply {
            setTextColor(SECONDARY_TEXT)
            textSize = 11f
            text = formatTime(0L)
        }

        private val durationLabel = TextView(context).apply {
            setTextColor(SECONDARY_TEXT)
            textSize = 11f
            gravity = Gravity.END
            text = DURATION_UNKNOWN
        }

        private val seekBar = SeekBar(context).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK_COLOR)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            contentDescription = DESC_SEEK
            isEnabled = false
        }

        // 0 = custom0, 1 = previous, 2 = play/pause, 3 = next, 4 = custom1
        private val slots = Array(5) { index ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                val padding = if (index == 2) dp(10) else dp(14)
                setPadding(padding, padding, padding, padding)
                if (index == 2) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(PLAY_BACKGROUND)
                    }
                }
            }
        }

        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(tintColor)
                cornerRadius = dp(32).toFloat()
            }

            addView(
                appLabel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            )

            // Title/artist on the left, artwork on the right (OneUI layout).
            val info = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(
                    artistView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                )
            }
            info.addView(
                texts,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            )
            info.addView(
                coverView,
                LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(12) }
            )
            addView(
                info,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            )

            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )

            val times = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), 0, dp(10), 0)
                addView(
                    elapsedLabel,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    durationLabel,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            addView(
                times,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) }
            )

            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            slots.forEach { slot ->
                controls.addView(slot, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            addView(
                controls,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }

        private val ticker = object : Runnable {
            override fun run() {
                if (released) return
                refreshProgress()
                handler.postDelayed(this, TICK_MS)
            }
        }

        init {
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && currentDuration > 0L) {
                        elapsedLabel.text = formatTime(currentDuration * progress / PROGRESS_MAX)
                    }
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    val duration = currentDuration
                    if (duration > 0L && bar.isEnabled) {
                        controller?.transportControls?.seekTo(duration * bar.progress / PROGRESS_MAX)
                        // The app takes a moment to publish the new position; don't snap back.
                        seekHoldUntil = SystemClock.uptimeMillis() + SEEK_HOLD_MS
                    }
                    userSeeking = false
                }
            })
        }

        fun applyTint(color: Int) {
            (view.background as? GradientDrawable)?.setColor(color)
        }

        /** Rebinds everything from the latest data. Safe to call as often as needed. */
        fun bind(media: MediaData) {
            if (released) return

            val app = media.app.orEmpty()
            appLabel.text = app
            appLabel.visibility = if (app.isBlank()) View.GONE else View.VISIBLE

            titleView.text = titleOf(media)
            val subtitleText = subtitleOf(media)
            artistView.text = subtitleText
            artistView.visibility = if (subtitleText.isBlank()) View.GONE else View.VISIBLE

            val coverSource: Any? = media.artwork ?: media.appIcon
            if (coverSource !== boundCoverSource) {
                boundCoverSource = coverSource
                val drawable = loadIconDrawable(media.artwork) ?: loadIconDrawable(media.appIcon)
                coverView.setImageDrawable(drawable)
                coverView.visibility = if (drawable != null) View.VISIBLE else View.GONE
            }

            val token = media.token
            if (token != boundToken) {
                boundToken = token
                controller = token?.let { runCatching { MediaController(context, it) }.getOrNull() }
            }

            bindControls(media)
            refreshProgress()
        }

        private fun bindControls(media: MediaData) {
            val actions = media.semanticActions
            val playing = media.isPlaying == true

            val hasCustom0 = bindSlot(
                slots[0],
                actions?.custom0?.icon,
                actions?.custom0?.action,
                actions?.custom0?.contentDescription,
                0,
                null,
            )
            bindSlot(
                slots[1],
                actions?.prevOrCustom?.icon,
                actions?.prevOrCustom?.action,
                actions?.prevOrCustom?.contentDescription,
                android.R.drawable.ic_media_previous,
                DESC_PREVIOUS,
            )
            bindSlot(
                slots[2],
                actions?.playOrPause?.icon,
                actions?.playOrPause?.action,
                actions?.playOrPause?.contentDescription,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) DESC_PAUSE else DESC_PLAY,
            )
            bindSlot(
                slots[3],
                actions?.nextOrCustom?.icon,
                actions?.nextOrCustom?.action,
                actions?.nextOrCustom?.contentDescription,
                android.R.drawable.ic_media_next,
                DESC_NEXT,
            )
            val hasCustom1 = bindSlot(
                slots[4],
                actions?.custom1?.icon,
                actions?.custom1?.action,
                actions?.custom1?.contentDescription,
                0,
                null,
            )

            // Keep the 3 main buttons centered: reserve space if only one side has a custom action.
            slots[0].visibility = when {
                hasCustom0 -> View.VISIBLE
                hasCustom1 -> View.INVISIBLE
                else -> View.GONE
            }
            slots[4].visibility = when {
                hasCustom1 -> View.VISIBLE
                hasCustom0 -> View.INVISIBLE
                else -> View.GONE
            }
        }

        /** @return true when the slot ended up with an icon. */
        private fun bindSlot(
            slot: ImageView,
            icon: Drawable?,
            action: Runnable?,
            description: CharSequence?,
            fallbackIconRes: Int,
            fallbackDescription: CharSequence?,
        ): Boolean {
            val drawable = icon?.let { independentCopy(it) }
                ?: if (fallbackIconRes != 0) context.getDrawable(fallbackIconRes)?.mutate() else null

            slot.setImageDrawable(drawable)
            slot.contentDescription = description.orNullIfBlank() ?: fallbackDescription

            val usable = drawable != null && action != null
            slot.setOnClickListener(
                if (usable) View.OnClickListener { action?.run() } else null
            )
            // setOnClickListener() forces clickable = true, so set these afterwards.
            slot.isClickable = usable
            slot.isEnabled = usable
            slot.alpha = if (usable) 1f else DISABLED_ALPHA
            return drawable != null
        }

        // The MediaAction drawable is shared with SystemUI's own media UI; never reuse the instance.
        private fun independentCopy(drawable: Drawable): Drawable =
            drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable

        fun start() {
            view.alpha = 0f
            view.translationY = -dp(12).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(SHOW_MS)
                .start()

            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        fun release() {
            released = true
            handler.removeCallbacks(ticker)
            view.animate().cancel()
            controller = null
            boundToken = null
        }

        private fun refreshProgress() {
            val mediaController = controller
            val playback = mediaController?.playbackState
            val duration = mediaController?.metadata
                ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ?.takeIf { it > 0L } ?: 0L
            currentDuration = duration

            val canSeek = duration > 0L && playback != null &&
                (playback.actions and PlaybackState.ACTION_SEEK_TO) != 0L
            seekBar.isEnabled = canSeek
            durationLabel.text = if (duration > 0L) formatTime(duration) else DURATION_UNKNOWN

            if (userSeeking || SystemClock.uptimeMillis() < seekHoldUntil) return

            if (playback == null || duration <= 0L) {
                seekBar.progress = 0
                elapsedLabel.text = formatTime(0L)
                return
            }

            val position = estimatePosition(playback).coerceIn(0L, duration)
            seekBar.progress = (position * PROGRESS_MAX / duration).toInt()
            elapsedLabel.text = formatTime(position)
        }

        private fun estimatePosition(playback: PlaybackState): Long {
            val position = playback.position.coerceAtLeast(0L)
            if (playback.state != PlaybackState.STATE_PLAYING) return position

            val updateTime = playback.lastPositionUpdateTime
            if (updateTime <= 0L) return position

            val elapsed = (SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L)
            return position + (elapsed * playback.playbackSpeed).toLong()
        }
    }

    private companion object {
        const val KEY_ENABLED = "bluenixx_dynamic_bar_enabled"
        const val KEY_MEDIA = "bluenixx_dynamic_bar_media"

        const val TINT_FACTOR = 0.42f
        const val PROGRESS_MAX = 10_000
        const val TICK_MS = 500L
        const val SEEK_HOLD_MS = 1_000L
        const val COLOR_ANIM_MS = 300L
        const val SHOW_MS = 220L
        const val HIDE_MS = 160L
        const val HIDDEN_SCALE = 0.88f
        const val DISABLED_ALPHA = 0.35f

        const val PANEL_WIDTH_DP = 340
        const val PANEL_SIDE_MARGIN_DP = 16
        const val PANEL_TOP_MARGIN_DP = 6

        // Strings kept here until they move to strings.xml.
        const val FALLBACK_TITLE = "Reproduzindo mídia"
        const val DURATION_UNKNOWN = "--:--"
        const val DESC_SEEK = "Progresso da reprodução"
        const val DESC_PREVIOUS = "Faixa anterior"
        const val DESC_NEXT = "Próxima faixa"
        const val DESC_PLAY = "Reproduzir"
        const val DESC_PAUSE = "Pausar"

        val DEFAULT_COLOR: Int = Color.rgb(36, 36, 40)
        val SECONDARY_TEXT: Int = Color.argb(179, 255, 255, 255)
        val TRACK_COLOR: Int = Color.argb(77, 255, 255, 255)
        val COVER_PLACEHOLDER: Int = Color.argb(40, 255, 255, 255)
        val PLAY_BACKGROUND: Int = Color.argb(46, 255, 255, 255)
    }
}
