package com.pranav.atminigame.effects

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Shockwaves, floating score popups and the AI "neural link" targeting HUD, restyled to
 * belong to the Luminous Ascension world.
 *
 * Public API and all positioning are identical to the original placeholder (same
 * shockwave radii, popup origin, laser endpoints, reticle size and pulse), so GameView
 * needs no call-site changes. What changed:
 *  - Visuals: layered glow rings with a white echo, rotating light spokes on big orbs,
 *    popups that pop in and ease upward, and an AI link that matches the world's
 *    neural threads, with pulses travelling toward the target.
 *  - Legibility: dark contrast outlines and text shadows keep every effect readable on
 *    the bright zone skies.
 *  - Pooled arrays and pre-built DashPathEffects: zero allocations per frame (the
 *    original created a new DashPathEffect every frame).
 *  - All animation is driven by dt instead of System.nanoTime().
 */
class VisualEffects(private val palette: EffectPalette = EffectPalette()) {

    private var time = 0f
    private var dashIndex = 0
    private var dashOffset = 0f

    // ---- Shockwave pool ----
    private val swX = FloatArray(MAX_SHOCKWAVES)
    private val swY = FloatArray(MAX_SHOCKWAVES)
    private val swMaxR = FloatArray(MAX_SHOCKWAVES)
    private val swLife = FloatArray(MAX_SHOCKWAVES)
    private val swMaxLife = FloatArray(MAX_SHOCKWAVES)
    private val swBig = BooleanArray(MAX_SHOCKWAVES)
    private var swCount = 0

    // ---- Score popup pool ----
    private val spX = FloatArray(MAX_POPUPS)
    private val spY0 = FloatArray(MAX_POPUPS)
    private val spLife = FloatArray(MAX_POPUPS)
    private val spMaxLife = FloatArray(MAX_POPUPS)
    private val spBig = BooleanArray(MAX_POPUPS)
    private var spCount = 0

    // ---- Paints ----
    private val ringGlowPaint = strokePaint()
    private val ringPaint = strokePaint()
    private val spokePaint = strokePaint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 38f
    }

    private val laserOutlinePaint = strokePaint()
    private val laserGlowPaint = strokePaint()
    private val laserCorePaint = strokePaint().apply { strokeCap = Paint.Cap.BUTT }
    private val bracketOutlinePaint = strokePaint()
    private val bracketPaint = strokePaint()

    private val dashEffects = Array(DASH_STEPS) { i ->
        DashPathEffect(floatArrayOf(15f, 10f), i * DASH_PERIOD / DASH_STEPS)
    }
    private val bracketLines = FloatArray(32)

    // =====================================================================================
    // Public API (unchanged signatures)
    // =====================================================================================

    fun addShockwave(x: Float, y: Float, isBig: Boolean) {
        val i = if (swCount < MAX_SHOCKWAVES) swCount++ else oldestShockwave()
        swX[i] = x
        swY[i] = y
        swMaxR[i] = if (isBig) 120f else 70f
        swMaxLife[i] = if (isBig) 0.55f else 0.4f
        swLife[i] = swMaxLife[i]
        swBig[i] = isBig
    }

    fun addScorePopup(x: Float, y: Float, isBig: Boolean) {
        val i = if (spCount < MAX_POPUPS) spCount++ else oldestPopup()
        spX[i] = x
        spY0[i] = y
        spMaxLife[i] = if (isBig) 0.9f else 0.75f
        spLife[i] = spMaxLife[i]
        spBig[i] = isBig
    }

    fun update(dt: Float) {
        val step = if (dt.isNaN() || dt < 0f) 0f else min(dt, MAX_DT)
        time += step
        if (time > TIME_WRAP) time -= TIME_WRAP

        dashOffset = (dashOffset + step * 60f) % DASH_PERIOD
        dashIndex = ((dashOffset / DASH_PERIOD) * DASH_STEPS).toInt().coerceIn(0, DASH_STEPS - 1)

        var i = swCount - 1
        while (i >= 0) {
            swLife[i] -= step
            if (swLife[i] <= 0f) removeShockwave(i)
            i--
        }
        i = spCount - 1
        while (i >= 0) {
            spLife[i] -= step
            if (spLife[i] <= 0f) removePopup(i)
            i--
        }
    }

    fun drawShockwaves(canvas: Canvas) {
        for (i in 0 until swCount) {
            val p = 1f - swLife[i] / swMaxLife[i]
            val ease = 1f - (1f - p) * (1f - p) * (1f - p) // easeOutCubic
            val r = swMaxR[i] * ease
            val fade = (1f - p).pow(1.5f)
            val base = if (swBig[i]) {
                mixColor(EffectPalette.ORB_BIG, palette.warm, 0.35f)
            } else {
                mixColor(EffectPalette.ORB_SMALL, palette.primary, 0.25f)
            }

            // Soft outer bloom.
            ringGlowPaint.color = base
            ringGlowPaint.alpha = (fade * 90f).toInt()
            ringGlowPaint.strokeWidth = 18f * fade + 4f
            canvas.drawCircle(swX[i], swY[i], r, ringGlowPaint)

            // Crisp ring.
            ringPaint.color = base
            ringPaint.alpha = (fade * 230f).toInt()
            ringPaint.strokeWidth = 5f * fade + 1f
            canvas.drawCircle(swX[i], swY[i], r, ringPaint)

            // White inner echo.
            ringPaint.color = Color.WHITE
            ringPaint.alpha = (fade * 170f).toInt()
            ringPaint.strokeWidth = 2f
            canvas.drawCircle(swX[i], swY[i], r * 0.72f, ringPaint)

            // Rotating light spokes for big orbs.
            if (swBig[i]) {
                spokePaint.color = mixColor(palette.secondary, Color.WHITE, 0.3f)
                spokePaint.alpha = (fade * 200f).toInt()
                spokePaint.strokeWidth = 3f * fade + 0.5f
                for (k in 0 until SPOKES) {
                    val a = k * (TWO_PI / SPOKES) + time * 1.2f
                    val c = cos(a)
                    val s = sin(a)
                    canvas.drawLine(
                        swX[i] + c * r * 0.55f, swY[i] + s * r * 0.55f,
                        swX[i] + c * r * 1.1f, swY[i] + s * r * 1.1f,
                        spokePaint
                    )
                }
            }
        }
    }

    fun drawFloatingScores(canvas: Canvas) {
        for (i in 0 until spCount) {
            val p = 1f - spLife[i] / spMaxLife[i]
            val rise = 1f - (1f - p) * (1f - p) * (1f - p)
            val x = spX[i]
            val y = spY0[i] - POPUP_RISE * rise
            val scale = when {
                p < 0.12f -> 0.6f + (1.25f - 0.6f) * (p / 0.12f)
                p < 0.25f -> 1.25f - 0.25f * ((p - 0.12f) / 0.13f)
                else -> 1f
            }
            val alpha = if (p < 0.7f) 1f else (1f - p) / 0.3f
            val big = spBig[i]

            scorePaint.textSize = if (big) 46f else 38f
            scorePaint.color = if (big) mixColor(palette.warm, Color.WHITE, 0.25f) else Color.WHITE
            scorePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            scorePaint.setShadowLayer(8f, 0f, 2f, alphaOf(palette.shadow, alpha * 0.75f))

            canvas.save()
            canvas.scale(scale, scale, x, y)
            canvas.drawText(if (big) TEXT_BIG else TEXT_SMALL, x, y, scorePaint)
            canvas.restore()
        }
    }

    fun drawAiTargetingLaser(canvas: Canvas, startX: Float, startY: Float, targetX: Float, targetY: Float) {
        val linkColor = mixColor(LASER_BASE, palette.primary, 0.25f)
        val coreColor = mixColor(linkColor, Color.WHITE, 0.4f)

        // Neural link: contrast outline, soft glow, animated dashed core.
        laserOutlinePaint.color = alphaOf(palette.shadow, 0.45f)
        laserOutlinePaint.strokeWidth = 7f
        canvas.drawLine(startX, startY, targetX, targetY, laserOutlinePaint)

        laserGlowPaint.color = alphaOf(linkColor, 0.28f)
        laserGlowPaint.strokeWidth = 12f
        canvas.drawLine(startX, startY, targetX, targetY, laserGlowPaint)

        laserCorePaint.color = coreColor
        laserCorePaint.strokeWidth = 3f
        laserCorePaint.pathEffect = dashEffects[dashIndex]
        canvas.drawLine(startX, startY, targetX, targetY, laserCorePaint)

        // Thought-pulses travelling toward the target.
        for (k in 0 until LINK_PULSES) {
            val t = (time * 1.4f + k / LINK_PULSES.toFloat()) % 1f
            val x = startX + (targetX - startX) * t
            val y = startY + (targetY - startY) * t
            val fade = sin(t * PI_F)
            dotPaint.color = alphaOf(linkColor, fade * 0.35f)
            canvas.drawCircle(x, y, 10f, dotPaint)
            dotPaint.color = alphaOf(Color.WHITE, fade)
            canvas.drawCircle(x, y, 3.5f, dotPaint)
        }

        // Reticle: same size and pulse as before, now slowly rotating.
        val size = 45f + sin(time * 10f) * 5f
        fillBrackets(targetX, targetY, size)
        canvas.save()
        canvas.rotate(time * 25f, targetX, targetY)
        bracketOutlinePaint.color = alphaOf(palette.shadow, 0.5f)
        bracketOutlinePaint.strokeWidth = 6f
        canvas.drawLines(bracketLines, bracketOutlinePaint)
        bracketPaint.color = coreColor
        bracketPaint.strokeWidth = 3f
        canvas.drawLines(bracketLines, bracketPaint)
        canvas.restore()

        // Breathing lock ring and center point.
        val breathe = 0.5f + 0.5f * sin(time * 4f)
        ringPaint.color = alphaOf(linkColor, 0.35f + 0.3f * breathe)
        ringPaint.strokeWidth = 2f
        canvas.drawCircle(targetX, targetY, size * (0.5f + 0.06f * breathe), ringPaint)
        dotPaint.color = coreColor
        canvas.drawCircle(targetX, targetY, 3f, dotPaint)
    }

    /** Removes all active effects (e.g. on level reset). */
    fun clear() {
        swCount = 0
        spCount = 0
    }

    // =====================================================================================
    // Internals
    // =====================================================================================

    private fun fillBrackets(cx: Float, cy: Float, size: Float) {
        val l = cx - size
        val t = cy - size
        val r = cx + size
        val b = cy + size
        val arm = BRACKET_ARM
        var n = 0
        // top-left
        n = put(n, l, t, l + arm, t); n = put(n, l, t, l, t + arm)
        // top-right
        n = put(n, r, t, r - arm, t); n = put(n, r, t, r, t + arm)
        // bottom-left
        n = put(n, l, b, l + arm, b); n = put(n, l, b, l, b - arm)
        // bottom-right
        n = put(n, r, b, r - arm, b); put(n, r, b, r, b - arm)
    }

    private fun put(n: Int, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        bracketLines[n] = x0; bracketLines[n + 1] = y0
        bracketLines[n + 2] = x1; bracketLines[n + 3] = y1
        return n + 4
    }

    private fun oldestShockwave(): Int {
        var best = 0
        for (i in 1 until swCount) if (swLife[i] < swLife[best]) best = i
        return best
    }

    private fun oldestPopup(): Int {
        var best = 0
        for (i in 1 until spCount) if (spLife[i] < spLife[best]) best = i
        return best
    }

    private fun removeShockwave(i: Int) {
        val last = --swCount
        if (i == last) return
        swX[i] = swX[last]; swY[i] = swY[last]; swMaxR[i] = swMaxR[last]
        swLife[i] = swLife[last]; swMaxLife[i] = swMaxLife[last]; swBig[i] = swBig[last]
    }

    private fun removePopup(i: Int) {
        val last = --spCount
        if (i == last) return
        spX[i] = spX[last]; spY0[i] = spY0[last]
        spLife[i] = spLife[last]; spMaxLife[i] = spMaxLife[last]; spBig[i] = spBig[last]
    }

    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private companion object {
        const val MAX_SHOCKWAVES = 24
        const val MAX_POPUPS = 16
        const val SPOKES = 8
        const val POPUP_RISE = 56f
        const val BRACKET_ARM = 15f
        const val LINK_PULSES = 3
        const val DASH_STEPS = 25
        const val DASH_PERIOD = 25f
        const val MAX_DT = 0.05f
        const val TIME_WRAP = 3600f
        const val PI_F = 3.1415927f
        const val TWO_PI = 6.2831855f
        const val TEXT_SMALL = "+1"
        const val TEXT_BIG = "+5"
        val LASER_BASE: Int = Color.rgb(0, 230, 255)
    }
}
