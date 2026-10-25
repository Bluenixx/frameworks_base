package com.android.systemui.statusbar.phone.fragment.dagger

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.text.TextUtils
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TextView de uma linha usado na Bluenixx Dynamic Bar.
 *
 * Quando o texto não cabe, ele rola pra esquerda em "páginas": anda, para pra dar tempo de ler,
 * anda de novo, e no fim volta pro começo e repete. O texto é cortado nos limites da própria
 * view (passa por trás da borda, nunca por cima de outros elementos), com um fade suave nas
 * pontas que só aparece do lado em que ainda tem texto escondido.
 *
 * Substitui o "..." (ellipsize) por esse fade, então ignora setEllipsize().
 */
class BluenixxMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    private class Phase(val start: Int, val end: Int, val from: Float, val to: Float)

    private val density = resources.displayMetrics.density
    private val fadePx = FADE_DP * density
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val easing = AccelerateDecelerateInterpolator()

    private var leftShader: Shader? = null
    private var rightShader: Shader? = null
    private var phases: List<Phase> = emptyList()
    private var animator: ValueAnimator? = null
    private var maxOffset = 0

    // false enquanto o construtor do TextView roda (ele chama onTextChanged/setEllipsize antes
    // dos campos desta classe serem inicializados).
    private var ready = false

    init {
        setHorizontallyScrolling(true)
        maxLines = 1
        super.setEllipsize(null)
        ready = true
    }

    override fun setEllipsize(where: TextUtils.TruncateAt?) {
        super.setEllipsize(null)
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int,
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (ready) rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        leftShader = LinearGradient(
            0f, 0f, fadePx, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        rightShader = LinearGradient(
            w - fadePx, 0f, w.toFloat(), 0f, Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP,
        )
        if (ready) rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ready) rebuild()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!ready) return
        if (isVisible) rebuild() else stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val x = scrollX
        val left = if (maxOffset > 0) min(1f, x / fadePx) else 0f
        val right = if (maxOffset > 0) min(1f, (maxOffset - x) / fadePx) else 0f
        if (left <= 0f && right <= 0f) {
            super.onDraw(canvas)
            return
        }

        val h = height.toFloat()
        val saved = canvas.saveLayer(x.toFloat(), 0f, (x + width).toFloat(), h, null)
        super.onDraw(canvas)

        // Máscara em coordenadas locais da view (desfaz o deslocamento do scroll).
        canvas.save()
        canvas.translate(x.toFloat(), 0f)
        if (left > 0f) {
            maskPaint.shader = leftShader
            maskPaint.alpha = (left * 255).roundToInt()
            canvas.drawRect(0f, 0f, fadePx, h, maskPaint)
        }
        if (right > 0f) {
            maskPaint.shader = rightShader
            maskPaint.alpha = (right * 255).roundToInt()
            canvas.drawRect(width - fadePx, 0f, width.toFloat(), h, maskPaint)
        }
        canvas.restore()
        canvas.restoreToCount(saved)
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        phases = emptyList()
        if (scrollX != 0) scrollTo(0, 0)
    }

    private fun rebuild() {
        stopAnimation()

        val viewport = width - compoundPaddingLeft - compoundPaddingRight
        val content = if (TextUtils.isEmpty(text)) {
            0
        } else {
            ceil(paint.measureText(text, 0, text.length)).toInt()
        }
        maxOffset = if (viewport > 0) max(0, content - viewport) else 0
        invalidate()

        if (maxOffset <= 0 || !isAttachedToWindow || !isShown ||
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            return
        }

        val timeline = buildTimeline(viewport)
        val total = timeline.last().end
        phases = timeline
        animator = ValueAnimator.ofInt(0, total).apply {
            duration = total.toLong()
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { applyTime(it.animatedValue as Int) }
            start()
        }
    }

    /**
     * Linha do tempo de um ciclo: pausa no começo, [anda, pausa] até o fim do texto, e volta
     * rápido pro começo. Cada "página" anda quase a largura visível, então o que sai pela
     * esquerda já foi lido e o que entra pela direita é novo.
     */
    private fun buildTimeline(viewport: Int): List<Phase> {
        val step = max(MIN_STEP_DP * density, viewport - 2 * fadePx).toInt()
        val pxPerMs = SPEED_DP_PER_S * density / 1000f
        val list = ArrayList<Phase>()
        var t = 0

        fun add(ms: Int, from: Float, to: Float) {
            list.add(Phase(t, t + ms, from, to))
            t += ms
        }

        add(INITIAL_HOLD_MS, 0f, 0f)
        var pos = 0
        while (pos < maxOffset) {
            val next = min(pos + step, maxOffset)
            add(max(MIN_MOVE_MS, ((next - pos) / pxPerMs).toInt()), pos.toFloat(), next.toFloat())
            add(if (next == maxOffset) END_HOLD_MS else HOLD_MS, next.toFloat(), next.toFloat())
            pos = next
        }
        add(RETURN_MS, maxOffset.toFloat(), 0f)
        return list
    }

    private fun applyTime(time: Int) {
        val phase = phases.firstOrNull { time < it.end } ?: phases.lastOrNull() ?: return
        val span = max(1, phase.end - phase.start)
        val fraction = ((time - phase.start).toFloat() / span).coerceIn(0f, 1f)
        val offset = if (phase.from == phase.to) {
            phase.from
        } else {
            phase.from + (phase.to - phase.from) * easing.getInterpolation(fraction)
        }
        val x = offset.roundToInt()
        if (x != scrollX) scrollTo(x, 0)
    }

    private companion object {
        const val FADE_DP = 8f
        const val MIN_STEP_DP = 24f
        const val SPEED_DP_PER_S = 30f
        const val INITIAL_HOLD_MS = 1500
        const val HOLD_MS = 1100
        const val END_HOLD_MS = 1300
        const val MIN_MOVE_MS = 350
        const val RETURN_MS = 450
    }
}
