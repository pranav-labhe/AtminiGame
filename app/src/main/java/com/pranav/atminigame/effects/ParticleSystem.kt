package com.pranav.atminigame.effects

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Particle data representation.
 */
data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var color: Int,
    var size: Float,
    var alpha: Float = 1.0f,
    var maxLife: Float = 0.5f,
    var life: Float = 0.5f
)

/**
 * High-performance 2D particle emitter for orb bursts, jump thrusters, and victory fireworks.
 */
class ParticleSystem {

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun emitOrbCollect(x: Float, y: Float, isBig: Boolean) {
        val count = if (isBig) 32 else 18
        val baseColor = if (isBig) Color.rgb(255, 130, 130) else Color.rgb(130, 220, 255)

        for (i in 0 until count) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = Random.nextFloat() * (if (isBig) 520f else 340f) + 120f
            val life = Random.nextFloat() * 0.45f + 0.35f
            val size = Random.nextFloat() * (if (isBig) 12f else 8f) + 4f

            particles += Particle(
                x = x,
                y = y,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                color = baseColor,
                size = size,
                alpha = 1.0f,
                maxLife = life,
                life = life
            )
        }
    }

    fun emitJumpThruster(x: Float, y: Float) {
        for (i in 0 until 12) {
            val vx = Random.nextFloat() * 160f - 80f
            val vy = Random.nextFloat() * 220f + 180f
            val life = Random.nextFloat() * 0.25f + 0.15f

            particles += Particle(
                x = x + Random.nextFloat() * 24f - 12f,
                y = y,
                vx = vx,
                vy = vy,
                color = Color.rgb(255, 200, 100),
                size = Random.nextFloat() * 6f + 3f,
                alpha = 1.0f,
                maxLife = life,
                life = life
            )
        }
    }

    fun emitVictoryShower(centerX: Float, centerY: Float) {
        val colors = intArrayOf(
            Color.rgb(255, 100, 100),
            Color.rgb(100, 255, 150),
            Color.rgb(100, 200, 255),
            Color.rgb(255, 230, 100),
            Color.rgb(220, 140, 255)
        )

        for (i in 0 until 120) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = Random.nextFloat() * 750f + 200f
            val life = Random.nextFloat() * 1.2f + 0.6f

            particles += Particle(
                x = centerX + Random.nextFloat() * 200f - 100f,
                y = centerY + Random.nextFloat() * 100f - 50f,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                color = colors[Random.nextInt(colors.size)],
                size = Random.nextFloat() * 10f + 5f,
                alpha = 1.0f,
                maxLife = life,
                life = life
            )
        }
    }

    fun update(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life -= dt
            if (p.life <= 0f) {
                iterator.remove()
                continue
            }

            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 220f * dt // Slight gravity pull
            p.vx *= 0.94f     // Drag
            p.alpha = (p.life / p.maxLife).coerceIn(0f, 1f)
        }
    }

    fun draw(canvas: Canvas) {
        for (p in particles) {
            paint.color = p.color
            paint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.size * p.alpha, paint)
        }
    }
}
