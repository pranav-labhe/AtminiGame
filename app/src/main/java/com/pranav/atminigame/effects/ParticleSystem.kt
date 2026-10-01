package com.pranav.atminigame.effects

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Luminous particle emitter for orb bursts, stardust footfalls and the victory bloom.
 *
 * Public API, spawn positions, counts, velocities and lifetimes are identical to the
 * original placeholder, so everything stays positioned exactly where it was. What changed:
 *  - Visuals: layered glow sparks, light streaks, twinkling stars and a core flash, all
 *    colored from the live world palette (orb bursts keep their gameplay hue).
 *  - Pooled struct-of-arrays storage: zero allocations per particle or per frame
 *    (emitJumpThruster is called every running frame, so this removes steady GC churn).
 *  - Drag is now frame-rate independent: identical to the old 0.94-per-frame at 60 FPS,
 *    and consistent on 90/120 Hz displays.
 */
class ParticleSystem(private val palette: EffectPalette = EffectPalette()) {

    private val px = FloatArray(CAPACITY)
    private val py = FloatArray(CAPACITY)
    private val pvx = FloatArray(CAPACITY)
    private val pvy = FloatArray(CAPACITY)
    private val pSize = FloatArray(CAPACITY)
    private val pLife = FloatArray(CAPACITY)
    private val pMaxLife = FloatArray(CAPACITY)
    private val pGravity = FloatArray(CAPACITY)
    private val pPhase = FloatArray(CAPACITY)
    private val pColor = IntArray(CAPACITY)
    private val pKind = IntArray(CAPACITY)
    private var count = 0
    private var time = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Number of live particles (useful for profiling overlays). */
    val activeCount: Int get() = count

    // =====================================================================================
    // Emitters (same signatures and spawn parameters as the original)
    // =====================================================================================

    fun emitOrbCollect(x: Float, y: Float, isBig: Boolean) {
        val n = if (isBig) 32 else 18
        val orbHue = if (isBig) EffectPalette.ORB_BIG else EffectPalette.ORB_SMALL

        // Bright core flash at the orb's position.
        spawn(x, y, 0f, 0f, Color.WHITE, if (isBig) 34f else 24f, 0.18f, 0f, KIND_FLASH)

        for (i in 0 until n) {
            val angle = Random.nextFloat() * TWO_PI
            val speed = Random.nextFloat() * (if (isBig) 520f else 340f) + 120f
            val life = Random.nextFloat() * 0.45f + 0.35f
            val size = Random.nextFloat() * (if (isBig) 12f else 8f) + 4f
            val color = when (Random.nextInt(4)) {
                0 -> Color.WHITE
                1 -> palette.secondary
                else -> orbHue
            }
            val kind = if (Random.nextFloat() < 0.4f) KIND_STREAK else KIND_SPARK
            spawn(x, y, cos(angle) * speed, sin(angle) * speed, color, size, life, 1f, kind)
        }
    }

    fun emitJumpThruster(x: Float, y: Float) {
        for (i in 0 until 12) {
            val vx = Random.nextFloat() * 160f - 80f
            val vy = Random.nextFloat() * 220f + 180f
            val life = Random.nextFloat() * 0.25f + 0.15f
            val color = if (Random.nextInt(5) == 0) {
                Color.WHITE
            } else {
                mixColor(palette.warm, palette.highlight, Random.nextFloat())
            }
            spawn(
                x + Random.nextFloat() * 24f - 12f, y, vx, vy, color,
                Random.nextFloat() * 6f + 3f, life, 1f, KIND_SPARK
            )
        }
    }

    fun emitFootstepDust(x: Float, y: Float, dir: Float) {
        val count = Random.nextInt(2, 4)
        for (i in 0 until count) {
            val vx = -dir * (Random.nextFloat() * 80f + 20f) + (Random.nextFloat() * 20f - 10f)
            val vy = -Random.nextFloat() * 40f - 10f
            val life = Random.nextFloat() * 0.18f + 0.10f
            val color = if (Random.nextBoolean()) {
                Color.argb(120, 200, 220, 240)
            } else {
                Color.argb(100, 255, 230, 200)
            }
            spawn(
                x + (Random.nextFloat() * 12f - 6f), y,
                vx, vy, color,
                Random.nextFloat() * 3f + 2f, life, 0.3f, KIND_SPARK
            )
        }
    }

    fun emitVictoryShower(centerX: Float, centerY: Float) {
        for (i in 0 until 120) {
            val angle = Random.nextFloat() * TWO_PI
            val speed = Random.nextFloat() * 750f + 200f
            val life = Random.nextFloat() * 1.2f + 0.6f
            val color = when (Random.nextInt(6)) {
                0 -> palette.primary
                1 -> palette.secondary
                2 -> palette.warm
                3 -> palette.highlight
                4 -> EffectPalette.ORB_SMALL
                else -> Color.WHITE
            }
            val roll = Random.nextFloat()
            val kind = when {
                roll < 0.5f -> KIND_STAR
                roll < 0.8f -> KIND_STREAK
                else -> KIND_SPARK
            }
            // Stars drift like floating light; the rest keep the original gravity.
            val gravity = if (kind == KIND_STAR) 0.35f else 1f
            spawn(
                centerX + Random.nextFloat() * 200f - 100f,
                centerY + Random.nextFloat() * 100f - 50f,
                cos(angle) * speed, sin(angle) * speed, color,
                Random.nextFloat() * 10f + 5f, life, gravity, kind
            )
        }
    }

    // =====================================================================================
    // Simulation
    // =====================================================================================

    fun update(dt: Float) {
        val step = if (dt.isNaN() || dt < 0f) 0f else min(dt, MAX_DT)
        time += step
        if (time > TIME_WRAP) time -= TIME_WRAP
        val drag = DRAG_PER_FRAME_60.pow(step * 60f)

        var i = count - 1
        while (i >= 0) {
            pLife[i] -= step
            if (pLife[i] <= 0f) {
                removeAt(i)
            } else {
                px[i] += pvx[i] * step
                py[i] += pvy[i] * step
                pvy[i] += GRAVITY * pGravity[i] * step
                pvx[i] *= drag
            }
            i--
        }
    }

    fun draw(canvas: Canvas) {
        for (i in 0 until count) {
            val a = (pLife[i] / pMaxLife[i]).coerceIn(0f, 1f)
            val color = pColor[i]
            val size = pSize[i]
            when (pKind[i]) {
                KIND_FLASH -> {
                    val r = size * (1f + (1f - a) * 1.5f)
                    fill(glowPaint, color, a * 0.45f)
                    canvas.drawCircle(px[i], py[i], r, glowPaint)
                    fill(corePaint, Color.WHITE, a)
                    canvas.drawCircle(px[i], py[i], r * 0.4f, corePaint)
                }
                KIND_STREAK -> {
                    val tailX = px[i] - pvx[i] * STREAK_TIME
                    val tailY = py[i] - pvy[i] * STREAK_TIME
                    linePaint.color = color
                    linePaint.alpha = (a * 220f).toInt()
                    linePaint.strokeWidth = size * 0.5f * a + 1f
                    canvas.drawLine(tailX, tailY, px[i], py[i], linePaint)
                    fill(corePaint, mixColor(color, Color.WHITE, 0.5f), a)
                    canvas.drawCircle(px[i], py[i], size * 0.35f * a + 0.8f, corePaint)
                }
                KIND_STAR -> {
                    val twinkle = 0.55f + 0.45f * sin(time * 14f + pPhase[i])
                    val arm = size * 1.6f * a * twinkle
                    fill(glowPaint, color, a * 0.3f)
                    canvas.drawCircle(px[i], py[i], size * a * 1.8f, glowPaint)
                    linePaint.color = mixColor(color, Color.WHITE, 0.6f)
                    linePaint.alpha = (a * twinkle * 255f).toInt()
                    linePaint.strokeWidth = 1.6f
                    canvas.drawLine(px[i] - arm, py[i], px[i] + arm, py[i], linePaint)
                    canvas.drawLine(px[i], py[i] - arm, px[i], py[i] + arm, linePaint)
                }
                else -> { // KIND_SPARK
                    fill(glowPaint, color, a * 0.28f)
                    canvas.drawCircle(px[i], py[i], size * a * 2.2f, glowPaint)
                    fill(corePaint, mixColor(color, Color.WHITE, 0.35f), a)
                    canvas.drawCircle(px[i], py[i], size * a, corePaint)
                }
            }
        }
    }

    /** Removes all live particles (e.g. on level reset). */
    fun clear() {
        count = 0
    }

    // =====================================================================================
    // Pool internals
    // =====================================================================================

    private fun spawn(
        x: Float, y: Float, vx: Float, vy: Float, color: Int,
        size: Float, life: Float, gravity: Float, kind: Int
    ) {
        if (count >= CAPACITY) return // pool saturated: drop silently, never allocate
        val i = count++
        px[i] = x; py[i] = y; pvx[i] = vx; pvy[i] = vy
        pColor[i] = color; pSize[i] = size
        pLife[i] = life; pMaxLife[i] = life
        pGravity[i] = gravity; pKind[i] = kind
        pPhase[i] = Random.nextFloat() * TWO_PI
    }

    /** O(1) swap-remove. Safe while iterating backwards. */
    private fun removeAt(i: Int) {
        val last = --count
        if (i == last) return
        px[i] = px[last]; py[i] = py[last]; pvx[i] = pvx[last]; pvy[i] = pvy[last]
        pColor[i] = pColor[last]; pSize[i] = pSize[last]
        pLife[i] = pLife[last]; pMaxLife[i] = pMaxLife[last]
        pGravity[i] = pGravity[last]; pKind[i] = pKind[last]; pPhase[i] = pPhase[last]
    }

    private fun fill(paint: Paint, color: Int, alpha: Float) {
        paint.color = color
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
    }

    private companion object {
        const val CAPACITY = 1024
        const val GRAVITY = 220f
        const val DRAG_PER_FRAME_60 = 0.94f
        const val STREAK_TIME = 0.035f
        const val MAX_DT = 0.05f
        const val TIME_WRAP = 3600f
        const val TWO_PI = 6.2831855f

        const val KIND_SPARK = 0
        const val KIND_STREAK = 1
        const val KIND_STAR = 2
        const val KIND_FLASH = 3
    }
}
