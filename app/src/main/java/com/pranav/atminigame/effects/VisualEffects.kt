package com.pranav.atminigame.effects

import android.graphics.*
import kotlin.math.sin

data class Shockwave(
    var x: Float,
    var y: Float,
    var maxRadius: Float,
    var radius: Float = 0f,
    var alpha: Float = 1.0f,
    var color: Int = Color.CYAN,
    var life: Float = 0.35f,
    var maxLife: Float = 0.35f
)

data class FloatingScore(
    var x: Float,
    var y: Float,
    var text: String,
    var alpha: Float = 1.0f,
    var life: Float = 0.6f,
    var maxLife: Float = 0.6f
)

/**
 * Handles radial shockwaves, floating score popups, AI targeting laser HUD, and orb halos.
 */
class VisualEffects {

    private val shockwaves = mutableListOf<Shockwave>()
    private val scorePopups = mutableListOf<FloatingScore>()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val scoreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 38f
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(100, 220, 255)
    }

    private var dashOffset = 0f

    fun addShockwave(x: Float, y: Float, isBig: Boolean) {
        val maxR = if (isBig) 120f else 70f
        val color = if (isBig) Color.rgb(255, 120, 120) else Color.rgb(120, 220, 255)
        shockwaves += Shockwave(x, y, maxR, color = color)
    }

    fun addScorePopup(x: Float, y: Float, isBig: Boolean) {
        val text = if (isBig) "+5" else "+1"
        scorePopups += FloatingScore(x, y, text)
    }

    fun update(dt: Float) {
        dashOffset = (dashOffset + dt * 60f) % 25f
        laserPaint.pathEffect = DashPathEffect(floatArrayOf(15f, 10f), dashOffset)

        // Update shockwaves
        val swIter = shockwaves.iterator()
        while (swIter.hasNext()) {
            val sw = swIter.next()
            sw.life -= dt
            if (sw.life <= 0f) {
                swIter.remove()
                continue
            }
            val progress = 1f - (sw.life / sw.maxLife)
            sw.radius = sw.maxRadius * progress
            sw.alpha = 1f - progress
        }

        // Update floating score text
        val scoreIter = scorePopups.iterator()
        while (scoreIter.hasNext()) {
            val sp = scoreIter.next()
            sp.life -= dt
            if (sp.life <= 0f) {
                scoreIter.remove()
                continue
            }
            sp.y -= 70f * dt // Float upward
            sp.alpha = (sp.life / sp.maxLife).coerceIn(0f, 1f)
        }
    }

    fun drawShockwaves(canvas: Canvas) {
        for (sw in shockwaves) {
            ringPaint.color = sw.color
            ringPaint.alpha = (sw.alpha * 220).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = 6f * sw.alpha
            canvas.drawCircle(sw.x, sw.y, sw.radius, ringPaint)
        }
    }

    fun drawFloatingScores(canvas: Canvas) {
        for (sp in scorePopups) {
            scoreTextPaint.alpha = (sp.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawText(sp.text, sp.x - 20f, sp.y, scoreTextPaint)
        }
    }

    fun drawAiTargetingLaser(canvas: Canvas, startX: Float, startY: Float, targetX: Float, targetY: Float) {
        // Glowing dashed laser line connecting Atmini to target orb
        laserPaint.color = Color.rgb(0, 230, 255)
        canvas.drawLine(startX, startY, targetX, targetY, laserPaint)

        // Target reticle / HUD corner brackets around orb
        val size = 45f + sin(System.nanoTime() / 100_000_000.0).toFloat() * 5f
        val left = targetX - size
        val top = targetY - size
        val right = targetX + size
        val bottom = targetY + size

        // Top-left bracket
        canvas.drawLine(left, top, left + 15f, top, hudPaint)
        canvas.drawLine(left, top, left, top + 15f, hudPaint)
        // Top-right bracket
        canvas.drawLine(right, top, right - 15f, top, hudPaint)
        canvas.drawLine(right, top, right, top + 15f, hudPaint)
        // Bottom-left bracket
        canvas.drawLine(left, bottom, left + 15f, bottom, hudPaint)
        canvas.drawLine(left, bottom, left, bottom - 15f, hudPaint)
        // Bottom-right bracket
        canvas.drawLine(right, bottom, right - 15f, bottom, hudPaint)
        canvas.drawLine(right, bottom, right, bottom - 15f, hudPaint)
    }
}
