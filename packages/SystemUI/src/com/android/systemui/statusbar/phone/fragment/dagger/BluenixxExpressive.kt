package com.android.systemui.statusbar.phone.fragment.dagger

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.SeekBar
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cores da Dynamic Bar derivadas da capa: fundo (compartilhado por cápsula e painel), cor de
 * destaque (botão play, onda, thumb) e três cores pro glow das ondas.
 */
data class BluenixxPalette(
    val background: Int,
    val accent: Int,
    val glowA: Int,
    val glowB: Int,
    val glowC: Int,
) {
    fun blend(to: BluenixxPalette, f: Float) = BluenixxPalette(
        mix(background, to.background, f),
        mix(accent, to.accent, f),
        mix(glowA, to.glowA, f),
        mix(glowB, to.glowB, f),
        mix(glowC, to.glowC, f),
    )

    companion object {
        private const val TINT_FACTOR = 0.42f
        private val evaluator = ArgbEvaluator()

        fun mix(from: Int, to: Int, f: Float): Int = evaluator.evaluate(f, from, to) as Int

        fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        private fun luma(c: Int): Float =
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

        /** Clareia a cor até ela ter brilho suficiente pra aparecer sobre o fundo escuro. */
        private fun lightenTo(color: Int, minLuma: Float): Int {
            var out = color
            var i = 0
            while (luma(out) < minLuma && i < 8) {
                out = mix(out, Color.WHITE, 0.18f)
                i++
            }
            return out
        }

        private fun darken(color: Int): Int = Color.rgb(
            (Color.red(color) * TINT_FACTOR).toInt(),
            (Color.green(color) * TINT_FACTOR).toInt(),
            (Color.blue(color) * TINT_FACTOR).toInt(),
        )

        fun default(base: Int) = BluenixxPalette(
            background = darken(base),
            accent = Color.rgb(208, 210, 224),
            glowA = Color.rgb(124, 140, 214),
            glowB = Color.rgb(176, 150, 214),
            glowC = Color.rgb(120, 190, 214),
        )

        fun from(p: Palette, fallback: Int): BluenixxPalette {
            val dominant = p.getDominantColor(fallback)
            val vibrant = p.getVibrantColor(dominant)
            val lightVibrant = p.getLightVibrantColor(vibrant)
            val muted = p.getMutedColor(dominant)
            val lightMuted = p.getLightMutedColor(muted)
            return BluenixxPalette(
                background = darken(dominant),
                accent = lightenTo(lightVibrant, 0.68f),
                glowA = lightenTo(vibrant, 0.42f),
                glowB = lightenTo(lightMuted, 0.55f),
                glowC = lightenTo(muted, 0.45f),
            )
        }
    }
}

/**
 * Fundo de botão que muda o raio dos cantos (shape morph, estilo MD3 Expressive): cantos mais
 * fechados ao pressionar e volta com um leve overshoot.
 */
class BluenixxMorphShape(private var rest: Float, private val pressed: Float) {
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = rest
    }

    private var isDown = false
    private var animator: ValueAnimator? = null

    fun setColor(color: Int) {
        drawable.setColor(color)
    }

    /** Raio de repouso (ex.: play/pause muda de forma conforme o estado). */
    fun setRest(radius: Float) {
        rest = radius
        if (!isDown) morphTo(radius, REST_MS, true)
    }

    fun press(down: Boolean) {
        isDown = down
        if (down) morphTo(pressed, PRESS_MS, false) else morphTo(rest, REST_MS, true)
    }

    private fun morphTo(target: Float, durationMs: Long, overshoot: Boolean) {
        animator?.cancel()
        animator = null
        val from = drawable.cornerRadius
        if (abs(from - target) < 0.5f) {
            drawable.cornerRadius = target
            return
        }
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = durationMs
            interpolator = if (overshoot) OvershootInterpolator(1.6f) else DecelerateInterpolator()
            addUpdateListener { drawable.cornerRadius = max(0f, it.animatedValue as Float) }
            start()
        }
    }

    private companion object {
        const val PRESS_MS = 120L
        const val REST_MS = 320L
    }
}

enum class ExpressiveIcon { PLAY, PAUSE, PREVIOUS, NEXT }

/**
 * Ícones de mídia preenchidos com pontas arredondadas (visual do Material Symbols Rounded).
 * Desenhados num grid 24x24; o arredondamento vem do stroke com join/cap redondos.
 * A cor vem do imageTintList do ImageView.
 */
class BluenixxExpressiveIconDrawable(
    icon: ExpressiveIcon,
    private val sizePx: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f
    }
    private val path = Path()
    private var color = Color.WHITE
    private var alphaValue = 255

    init {
        when (icon) {
            ExpressiveIcon.PLAY -> {
                path.moveTo(8f, 6f)
                path.lineTo(8f, 18f)
                path.lineTo(18f, 12f)
                path.close()
            }
            ExpressiveIcon.PAUSE -> {
                path.addRect(7f, 6f, 9f, 18f, Path.Direction.CW)
                path.addRect(15f, 6f, 17f, 18f, Path.Direction.CW)
            }
            ExpressiveIcon.PREVIOUS -> {
                path.addRect(6f, 6.5f, 7f, 17.5f, Path.Direction.CW)
                path.moveTo(17.5f, 6.5f)
                path.lineTo(17.5f, 17.5f)
                path.lineTo(9.5f, 12f)
                path.close()
            }
            ExpressiveIcon.NEXT -> {
                path.addRect(17f, 6.5f, 18f, 17.5f, Path.Direction.CW)
                path.moveTo(6.5f, 6.5f)
                path.lineTo(6.5f, 17.5f)
                path.lineTo(14.5f, 12f)
                path.close()
            }
        }
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        if (s <= 0f) return
        paint.color = color
        paint.alpha = Color.alpha(color) * alphaValue / 255
        canvas.save()
        canvas.translate(b.exactCenterX() - 12f * s, b.exactCenterY() - 12f * s)
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setTintList(tint: ColorStateList?) {
        color = tint?.getColorForState(state, tint.defaultColor) ?: Color.WHITE
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * SeekBar que desenha o progresso como uma onda animada (MD3 Expressive) com um glow de ondas
 * translúcidas atrás, nas cores do álbum. Herda de SeekBar só pra manter toque, progresso,
 * listener e acessibilidade; todo o desenho é próprio.
 *
 * - Tocando: a onda anda e o glow ondula. Pausado: tudo relaxa pra uma linha reta.
 * - Entre o trecho já tocado (onda) e o resto (linha reta) fica o thumb em forma de pílula,
 *   que afina enquanto o dedo está em cima.
 * - Sem duração conhecida (isEnabled = false): só a trilha reta, apagada.
 */
class BluenixxWaveSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private var accent = Color.WHITE
    private val glow = intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE)
    private var trackColor = BluenixxPalette.withAlpha(Color.WHITE, 0x40)

    private var waving = false
    private var phase = 0f
    private var amp = 0f
    private var shown = 0f
    private var pressK = 0f
    private var primed = false
    private var lastMs = 0L

    private val wavePaint = strokePaint()
    private val glowPaint = strokePaint()
    private val trackPaint = strokePaint()
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val blur by lazy { BlurMaskFilter(dp(5f), BlurMaskFilter.Blur.NORMAL) }

    init {
        thumb = null
        background = null
        setPadding(dp(6f).roundToInt(), 0, dp(6f).roundToInt(), 0)
    }

    private fun dp(v: Float): Float = v * density

    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setColors(accent: Int, glowA: Int, glowB: Int, glowC: Int) {
        this.accent = accent
        glow[0] = glowA
        glow[1] = glowB
        glow[2] = glowC
        trackColor = BluenixxPalette.withAlpha(accent, 0x40)
        invalidate()
    }

    fun setPlaying(playing: Boolean) {
        if (waving == playing) return
        waving = playing
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(HEIGHT_DP).roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val strokeW = dp(4f)
        val left = paddingLeft + strokeW / 2f
        val right = width - paddingRight - strokeW / 2f
        if (right <= left) return
        val cy = h / 2f

        if (!isEnabled) {
            primed = false
            trackPaint.strokeWidth = strokeW
            trackPaint.color = BluenixxPalette.withAlpha(trackColor, 0x30)
            canvas.drawLine(left, cy, right, cy, trackPaint)
            return
        }

        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else min(48L, now - lastMs) / 1000f
        lastMs = now

        val target = if (max > 0) progress.toFloat() / max else 0f
        if (!primed || isPressed) {
            shown = target
            primed = true
        } else {
            shown += (target - shown) * min(1f, dt * 10f)
            if (abs(target - shown) < 0.0004f) shown = target
        }
        amp += ((if (waving) 1f else 0f) - amp) * min(1f, dt * 5f)
        if (!waving && amp < 0.01f) amp = 0f
        pressK += ((if (isPressed) 1f else 0f) - pressK) * min(1f, dt * 14f)
        phase += dt * WAVE_SPEED

        val thumbW = dp(4f) - dp(1.5f) * pressK
        val thumbH = dp(26f) + dp(4f) * pressK
        val gap = dp(5f)
        val thumbX = left + (right - left) * shown
        val waveEnd = thumbX - thumbW / 2f - gap
        val trackStart = thumbX + thumbW / 2f + gap

        if (waveEnd > left) {
            // Glow: ondas largas, borradas e translúcidas, só sobre o trecho já tocado.
            canvas.save()
            canvas.clipRect(left - strokeW, 0f, thumbX - thumbW / 2f, h)
            glowPaint.maskFilter = blur
            for (i in 0 until 3) {
                val amplitude = dp(3f) * GLOW_AMP[i] * (0.3f + 0.7f * amp)
                buildWave(
                    left, waveEnd, cy, amplitude,
                    dp(WAVELENGTH_DP) * GLOW_LAMBDA[i],
                    phase * GLOW_SPEED[i] + i * 1.7f,
                )
                glowPaint.strokeWidth = dp(GLOW_STROKE_DP[i])
                glowPaint.color = BluenixxPalette.withAlpha(glow[i], GLOW_ALPHA[i])
                canvas.drawPath(path, glowPaint)
            }
            canvas.restore()

            buildWave(left, waveEnd, cy, dp(3f) * amp, dp(WAVELENGTH_DP), phase)
            wavePaint.strokeWidth = strokeW
            wavePaint.color = accent
            canvas.drawPath(path, wavePaint)
        }

        if (trackStart < right) {
            trackPaint.strokeWidth = strokeW
            trackPaint.color = trackColor
            canvas.drawLine(trackStart, cy, right, cy, trackPaint)
        }

        thumbPaint.color = accent
        canvas.drawRoundRect(
            thumbX - thumbW / 2f, cy - thumbH / 2f,
            thumbX + thumbW / 2f, cy + thumbH / 2f,
            thumbW / 2f, thumbW / 2f, thumbPaint,
        )

        val settling = abs(target - shown) > 0.0004f || pressK in 0.01f..0.99f
        if (waving || amp > 0.01f || settling) postInvalidateOnAnimation()
    }

    private fun buildWave(
        x0: Float,
        x1: Float,
        cy: Float,
        amplitude: Float,
        wavelength: Float,
        ph: Float,
    ) {
        path.reset()
        val step = dp(2f)
        val taper = dp(14f)
        val k = (2.0 * Math.PI).toFloat() / wavelength
        var x = x0
        var first = true
        while (true) {
            val xx = if (x > x1) x1 else x
            val t = min(1f, (xx - x0) / taper)
            val y = cy + amplitude * t * sin(k * (xx - x0) - ph)
            if (first) {
                path.moveTo(xx, y)
                first = false
            } else {
                path.lineTo(xx, y)
            }
            if (xx >= x1) break
            x += step
        }
    }

    private companion object {
        const val HEIGHT_DP = 40f
        const val WAVELENGTH_DP = 26f
        const val WAVE_SPEED = 2.6f
        val GLOW_AMP = floatArrayOf(1.7f, 1.35f, 2.1f)
        val GLOW_LAMBDA = floatArrayOf(1.0f, 1.45f, 0.75f)
        val GLOW_SPEED = floatArrayOf(-0.7f, 0.5f, 1.1f)
        val GLOW_STROKE_DP = floatArrayOf(7f, 9f, 6f)
        val GLOW_ALPHA = intArrayOf(0x66, 0x4D, 0x4D)
    }
}
