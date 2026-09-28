package com.pranav.atminigame.combat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.pranav.atminigame.effects.EffectPalette
import com.pranav.atminigame.effects.alphaOf
import com.pranav.atminigame.effects.mixColor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws every combat visual. Threats use an "unhealed old world" identity (rust, void
 * violet, corrupted rose glow) that stays distinct from the cyan/coral orbs in every zone;
 * purification always resolves into warm light. Zero allocations per frame.
 */
internal class ThreatPainter(private val palette: EffectPalette) {

    private var glowMask: Bitmap? = null
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val oval = RectF()
    private val dst = RectF()
    private val bolt = FloatArray(BOLT_SEGMENTS * 4)

    private val laserColor: Int get() = mixColor(LASER_BASE, palette.primary, 0.2f)

    // =====================================================================================
    // Threats
    // =====================================================================================

    fun drawThreat(c: Canvas, t: Threat, time: Float, groundY: Float, px: Float, py: Float) {
        if (t.isPurifying) {
            drawPurify(c, t)
            return
        }
        when (t.kind) {
            ThreatKind.RUST_SHARD -> drawShard(c, t, time, groundY)
            ThreatKind.FIREWALL_ARC -> drawArc(c, t, time, groundY)
            ThreatKind.FALLING_MONOLITH -> drawMonolith(c, t, time, groundY)
            ThreatKind.GLITCH_DRONE -> drawDrone(c, t, time, px, py)
            ThreatKind.RUST_CRAWLER -> drawCrawler(c, t, time, groundY)
            ThreatKind.SHADOW_WISP -> drawWisp(c, t, time)
            ThreatKind.OVERSEER_PULSE -> drawPulse(c, t)
            ThreatKind.LAST_OVERSEER -> drawBoss(c, t, time, px, py)
        }
        if (t.flash > 0f) {
            val s = t.visualSize * 1.4f
            glow(c, t.x, t.y, s, s, Color.WHITE, (t.flash / FLASH_TIME) * 0.8f)
        }
    }

    private fun drawPurify(c: Canvas, t: Threat) {
        val p = (t.purify / t.purifyMax).coerceIn(0f, 1f) // 1 -> 0
        val s = t.visualSize
        if (t.darkFade) {
            glow(c, t.x, t.y, s * (1f + (1f - p)), s * (1f + (1f - p)), WISP_GLOW, p * 0.6f)
            glow(c, t.x, t.y, s * 0.5f * p + 1f, s * 0.5f * p + 1f, VOID, p)
        } else {
            val r = s * (1.2f + (1f - p) * 1.6f)
            glow(c, t.x, t.y, r, r, CORE_LIGHT, p * 0.7f)
            glow(c, t.x, t.y, s * 0.45f * p + 2f, s * 0.45f * p + 2f, Color.WHITE, p)
        }
    }

    private fun drawShard(c: Canvas, t: Threat, time: Float, gY: Float) {
        val pulse = 0.6f + 0.4f * sin(time * 3f + t.phase)
        glow(c, t.x, gY - 4f, 50f, 14f, DANGER, 0.35f * pulse)
        spike(c, t.x - 15f, gY, 15f, 36f)
        spike(c, t.x + 2f, gY, 19f, 58f)
        spike(c, t.x + 17f, gY, 13f, 30f)
    }

    private fun spike(c: Canvas, cx: Float, baseY: Float, halfW: Float, height: Float) {
        val tipX = cx + halfW * 0.15f
        path.reset()
        path.moveTo(cx - halfW, baseY)
        path.lineTo(tipX, baseY - height)
        path.lineTo(cx + halfW, baseY)
        path.close()
        fill.color = RUST
        c.drawPath(path, fill)

        path.reset()
        path.moveTo(cx - halfW, baseY)
        path.lineTo(tipX, baseY - height)
        path.lineTo(cx - halfW * 0.05f, baseY)
        path.close()
        fill.color = RUST_DARK
        c.drawPath(path, fill)

        stroke.color = DANGER
        stroke.alpha = 200
        stroke.strokeWidth = 2f
        c.drawLine(tipX, baseY - height, cx + halfW, baseY, stroke)
    }

    private fun drawArc(c: Canvas, t: Threat, time: Float, gY: Float) {
        val x = t.x
        val top = gY - ARC_HEIGHT
        val y0 = top + 26f
        val y1 = gY - 34f

        // Emitters (bottom pylon + floating top node).
        fill.color = VOID
        c.drawRect(x - 18f, gY - 34f, x + 18f, gY, fill)
        c.drawRect(x - 18f, top, x + 18f, top + 26f, fill)
        stroke.color = VOID_EDGE
        stroke.alpha = 255
        stroke.strokeWidth = 2f
        c.drawRect(x - 18f, gY - 34f, x + 18f, gY, stroke)
        c.drawRect(x - 18f, top, x + 18f, top + 26f, stroke)

        if (t.arcOn()) {
            glow(c, x, (y0 + y1) * 0.5f, 30f, (y1 - y0) * 0.5f, DANGER, 0.5f)
            buildBolt(x, y0, y1, (time * 24f).toInt(), (t.phase * 1000f).toInt())
            stroke.color = DANGER
            stroke.alpha = 170
            stroke.strokeWidth = 5f
            c.drawLines(bolt, stroke)
            stroke.color = Color.WHITE
            stroke.alpha = 255
            stroke.strokeWidth = 2f
            c.drawLines(bolt, stroke)
            glow(c, x, y0, 22f, 14f, DANGER, 0.9f)
            glow(c, x, y1, 22f, 14f, DANGER, 0.9f)
        } else {
            stroke.color = DANGER
            stroke.alpha = 40
            stroke.strokeWidth = 1.5f
            c.drawLine(x, y0, x, y1, stroke)
            if (t.arcTimeUntilOn() < ARC_WARN_TIME) {
                val flicker = if ((time * 30f).toInt() % 2 == 0) 0.8f else 0.25f
                glow(c, x, y0, 18f, 12f, DANGER, flicker)
                glow(c, x, y1, 18f, 12f, DANGER, flicker)
            }
        }
    }

    private fun buildBolt(x: Float, y0: Float, y1: Float, frame: Int, seed: Int) {
        val seg = BOLT_SEGMENTS
        var n = 0
        for (i in 0 until seg) {
            val ya = y0 + (y1 - y0) * i / seg
            val yb = y0 + (y1 - y0) * (i + 1) / seg
            val xa = if (i == 0) x else x + (combatHash(i * 7 + frame * 131, seed) - 0.5f) * 24f
            val xb = if (i + 1 == seg) x else x + (combatHash((i + 1) * 7 + frame * 131, seed) - 0.5f) * 24f
            bolt[n++] = xa; bolt[n++] = ya; bolt[n++] = xb; bolt[n++] = yb
        }
    }

    private fun drawMonolith(c: Canvas, t: Threat, time: Float, gY: Float) {
        if (t.state == STATE_DORMANT) return

        if (t.state == STATE_WARNING || t.state == STATE_FALLING) {
            val progress = if (t.state == STATE_WARNING) 1f - t.timer / MONOLITH_WARN else 1f
            val flicker = 0.75f + 0.25f * sin(time * 20f)
            glow(c, t.x, gY - 3f, t.hw * (0.8f + 0.8f * progress), 11f, DANGER, (0.25f + 0.5f * progress) * flicker)
            oval.set(t.x - t.hw * 1.2f, gY - 8f, t.x + t.hw * 1.2f, gY + 4f)
            stroke.color = DANGER
            stroke.alpha = (120f * progress).toInt()
            stroke.strokeWidth = 2f
            c.drawOval(oval, stroke)
        }
        if (t.state == STATE_WARNING) return

        val a = if (t.state == STATE_LANDED) (t.timer / RUBBLE_TIME).coerceIn(0f, 1f) else 1f
        if (t.state == STATE_FALLING) {
            glow(c, t.x, t.y - t.hh - 50f, t.hw * 0.6f, 70f, DANGER, 0.3f)
        }
        fill.color = RUST_DARK
        fill.alpha = (a * 255f).toInt()
        c.drawRect(t.x - t.hw, t.y - t.hh, t.x + t.hw, t.y + t.hh, fill)
        stroke.color = VOID_EDGE
        stroke.alpha = (a * 255f).toInt()
        stroke.strokeWidth = 3f
        c.drawRect(t.x - t.hw, t.y - t.hh, t.x + t.hw, t.y + t.hh, stroke)
        stroke.color = DANGER
        stroke.alpha = (a * (0.6f + 0.4f * sin(time * 6f)) * 255f).toInt()
        stroke.strokeWidth = 2f
        c.drawLine(t.x - t.hw * 0.5f, t.y - t.hh * 0.7f, t.x + t.hw * 0.1f, t.y - t.hh * 0.1f, stroke)
        c.drawLine(t.x + t.hw * 0.1f, t.y - t.hh * 0.1f, t.x - t.hw * 0.2f, t.y + t.hh * 0.6f, stroke)
        c.drawLine(t.x + t.hw * 0.1f, t.y - t.hh * 0.1f, t.x + t.hw * 0.6f, t.y + t.hh * 0.2f, stroke)
    }

    private fun drawDrone(c: Canvas, t: Threat, time: Float, px: Float, py: Float) {
        val dx = px - t.x
        val dy = py - t.y
        val len = max(1f, sqrt(dx * dx + dy * dy))
        val dirX = dx / len
        val dirY = dy / len

        glow(c, t.x, t.y, 46f, 46f, DANGER, 0.16f)
        val flap = sin(time * 18f + t.phase) * 4f
        stroke.color = VOID_EDGE
        stroke.alpha = 255
        stroke.strokeWidth = 4f
        c.drawLine(t.x - 22f, t.y, t.x - 36f, t.y - 6f + flap, stroke)
        c.drawLine(t.x + 22f, t.y, t.x + 36f, t.y - 6f - flap, stroke)

        fill.color = VOID
        c.drawCircle(t.x, t.y, 22f, fill)
        stroke.strokeWidth = 3f
        c.drawCircle(t.x, t.y, 22f, stroke)

        val charge = if (t.state == STATE_ACTIVE && t.timer2 < DRONE_CHARGE) 1f - t.timer2 / DRONE_CHARGE else 0f
        val ex = t.x + dirX * 7f
        val ey = t.y + dirY * 6f
        glow(c, ex, ey, 9f + charge * 10f, 9f + charge * 10f, DANGER, 0.85f)
        fill.color = Color.WHITE
        c.drawCircle(ex, ey, 2.6f, fill)
    }

    private fun drawCrawler(c: Canvas, t: Threat, time: Float, gY: Float) {
        val dir = if (t.vx > 0f) 1f else -1f
        stroke.color = RUST
        stroke.alpha = 255
        stroke.strokeWidth = 3f
        for (k in 0 until 4) {
            val lx = t.x - t.hw * 0.7f + k * (t.hw * 1.4f / 3f)
            val swing = if (t.state == STATE_ACTIVE) sin(time * 12f + k * 1.6f) * 6f else 0f
            c.drawLine(lx, t.y + t.hh * 0.4f, lx + swing, gY, stroke)
        }
        oval.set(t.x - t.hw, t.y - t.hh, t.x + t.hw, t.y + t.hh * 1.6f)
        fill.color = RUST_DARK
        c.drawArc(oval, 180f, 180f, true, fill)
        stroke.strokeWidth = 2.5f
        c.drawArc(oval, 180f, 180f, false, stroke)

        val damaged = t.hp < t.maxHp
        val eyeA = if (damaged) 0.6f + 0.4f * sin(time * 25f) else 0.9f
        glow(c, t.x + dir * t.hw * 0.5f, t.y + t.hh * 0.1f, 10f, 4f, DANGER, eyeA)
        if (damaged) glow(c, t.x, t.y, 14f, 10f, DANGER, 0.5f)
    }

    private fun drawWisp(c: Canvas, t: Threat, time: Float) {
        val flick = 0.85f + 0.15f * sin(time * 9f + t.phase)
        glow(c, t.x, t.y, 34f, 34f, WISP_GLOW, 0.45f * flick)
        glow(c, t.x, t.y, 20f, 20f, VOID_EDGE, 0.8f)
        fill.color = VOID
        c.drawCircle(t.x, t.y, 11f, fill)
        stroke.color = WISP_GLOW
        stroke.alpha = 180
        stroke.strokeWidth = 1.6f
        c.drawCircle(t.x, t.y, 15f, stroke)
        for (k in 0 until 3) {
            val a = time * 1.8f + k * (TWO_PI / 3f)
            c.drawCircle(t.x + kotlin.math.cos(a) * 20f, t.y + sin(a) * 20f, 3.5f, fill)
        }
    }

    private fun drawPulse(c: Canvas, t: Threat) {
        val len = max(1f, sqrt(t.vx * t.vx + t.vy * t.vy))
        val ux = t.vx / len
        val uy = t.vy / len
        for (k in 1..3) {
            val r = 14f - k * 3f
            glow(c, t.x - ux * k * 9f, t.y - uy * k * 9f, r, r, DANGER, 0.35f - k * 0.08f)
        }
        glow(c, t.x, t.y, 22f, 22f, DANGER, 0.6f)
        fill.color = Color.WHITE
        c.drawCircle(t.x, t.y, 4.5f, fill)
    }

    private fun drawBoss(c: Canvas, t: Threat, time: Float, px: Float, py: Float) {
        val r = t.radius
        val open = t.state == STATE_OPEN
        glow(c, t.x, t.y, r * 2f, r * 2f, if (open) CORE_LIGHT else DANGER, 0.18f)

        fill.color = BOSS_METAL
        c.drawCircle(t.x, t.y, r, fill)
        stroke.color = BOSS_EDGE
        stroke.alpha = 255
        stroke.strokeWidth = 6f
        c.drawCircle(t.x, t.y, r, stroke)

        // Rotating shield plates; they retract while the core is open.
        val plateR = if (open) r * 1.4f else r * 1.12f
        oval.set(t.x - plateR, t.y - plateR, t.x + plateR, t.y + plateR)
        stroke.alpha = if (open) 110 else 255
        stroke.strokeWidth = 12f
        val rot = time * 40f
        for (k in 0 until 6) c.drawArc(oval, rot + k * 60f, 38f, false, stroke)

        // Iris follows Atmini.
        val dx = px - t.x
        val dy = py - t.y
        val len = max(1f, sqrt(dx * dx + dy * dy))
        val irisR = r * 0.46f
        val ix = t.x + dx / len * r * 0.12f
        val iy = t.y + dy / len * r * 0.12f
        if (open) {
            val pulse = 0.5f + 0.5f * sin(time * 8f)
            glow(c, ix, iy, irisR * 1.6f, irisR * 1.6f, CORE_LIGHT, 0.7f)
            fill.color = CORE_LIGHT
            c.drawCircle(ix, iy, irisR * 0.8f, fill)
            stroke.color = Color.WHITE
            stroke.alpha = 180
            stroke.strokeWidth = 2.5f
            c.drawCircle(ix, iy, irisR * (1.1f + 0.2f * pulse), stroke)
        } else {
            val charge = if (t.state == STATE_VOLLEY) 1f else 0.5f
            glow(c, ix, iy, irisR * 1.4f, irisR * 1.4f, DANGER, 0.5f + 0.4f * charge)
            fill.color = DANGER
            c.drawCircle(ix, iy, irisR * 0.75f, fill)
        }
        fill.color = VOID
        c.drawCircle(ix + dx / len * irisR * 0.25f, iy + dy / len * irisR * 0.25f, irisR * 0.35f, fill)

        // Remaining-health pips.
        for (k in 0 until t.maxHp) {
            fill.color = if (k < t.hp) DANGER else BOSS_EDGE
            c.drawCircle(t.x - (t.maxHp - 1) * 9f + k * 18f, t.y - r - 26f, 5f, fill)
        }
    }

    // =====================================================================================
    // Laser, eyes, popups
    // =====================================================================================

    fun drawLaser(c: Canvas, laser: EyeLaser) {
        if (laser.beamTimer <= 0f) return
        val a = (laser.beamTimer / EyeLaser.BEAM_TIME).coerceIn(0f, 1f)
        val col = laserColor
        val sx = laser.startX; val sy = laser.startY; val ex = laser.endX; val ey = laser.endY

        stroke.color = col
        stroke.alpha = (a * 0.35f * 255f).toInt()
        stroke.strokeWidth = 18f * a + 4f
        c.drawLine(sx, sy, ex, ey, stroke)
        stroke.color = mixColor(col, Color.WHITE, 0.3f)
        stroke.alpha = (a * 0.85f * 255f).toInt()
        stroke.strokeWidth = 7f
        c.drawLine(sx, sy, ex, ey, stroke)
        stroke.color = Color.WHITE
        stroke.alpha = (a * 255f).toInt()
        stroke.strokeWidth = 2.5f
        c.drawLine(sx, sy, ex, ey, stroke)

        glow(c, sx, sy, 20f * a + 6f, 20f * a + 6f, col, a)
        when (laser.impact) {
            EyeLaser.IMPACT_HIT -> {
                glow(c, ex, ey, 30f, 30f, col, a)
                glow(c, ex, ey, 12f, 12f, Color.WHITE, a)
            }
            EyeLaser.IMPACT_DEFLECT -> glow(c, ex, ey, 18f, 18f, DANGER, a)
        }
    }

    fun drawEyes(c: Canvas, body: CombatBody, laser: EyeLaser, time: Float) {
        val a = if (laser.isReady) 0.45f + 0.25f * sin(time * 5f) else 0.15f * laser.readiness
        glow(c, body.eyeX, body.eyeY, 10f, 7f, laserColor, a)
    }

    fun drawPopup(c: Canvas, x: Float, y: Float, text: String, color: Int, alpha: Float, scale: Float) {
        textPaint.textSize = 34f
        textPaint.color = color
        textPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        textPaint.setShadowLayer(8f, 0f, 2f, alphaOf(palette.shadow, alpha * 0.75f))
        c.save()
        c.scale(scale, scale, x, y)
        c.drawText(text, x, y, textPaint)
        c.restore()
    }

    // =====================================================================================
    // HUD (screen space)
    // =====================================================================================

    fun drawHud(
        c: Canvas, viewW: Int, harmony: Int, maxHarmony: Int,
        laser: EyeLaser, boss: Threat?, time: Float
    ) {
        val w = viewW.toFloat()
        val shardColor = mixColor(palette.primary, Color.WHITE, 0.3f)

        // Harmony shards (top-right).
        for (k in 0 until maxHarmony) {
            val cx = w - 48f - k * 46f
            val cy = 60f
            path.reset()
            path.moveTo(cx, cy - 16f)
            path.lineTo(cx + 12f, cy)
            path.lineTo(cx, cy + 16f)
            path.lineTo(cx - 12f, cy)
            path.close()
            if (maxHarmony - 1 - k < harmony) {
                glow(c, cx, cy, 22f, 22f, shardColor, 0.5f)
                fill.color = shardColor
                c.drawPath(path, fill)
            } else {
                stroke.color = Color.WHITE
                stroke.alpha = 110
                stroke.strokeWidth = 2f
                c.drawPath(path, stroke)
            }
        }

        // Eye-laser readiness bar.
        val right = w - 34f
        val left = w - 48f - (maxHarmony - 1) * 46f - 14f
        fill.color = Color.argb(90, 0, 0, 0)
        c.drawRoundRect(left, 90f, right, 97f, 4f, 4f, fill)
        fill.color = laserColor
        c.drawRoundRect(left, 90f, left + (right - left) * laser.readiness, 97f, 4f, 4f, fill)
        if (laser.isReady) glow(c, right, 93.5f, 12f, 8f, laserColor, 0.5f + 0.3f * sin(time * 5f))

        // Boss health.
        if (boss != null && boss.active && !boss.isPurifying && boss.state != STATE_DORMANT) {
            val barW = w * 0.36f
            val bl = (w - barW) * 0.5f
            textPaint.textSize = 24f
            textPaint.color = Color.WHITE
            textPaint.alpha = 230
            textPaint.setShadowLayer(6f, 0f, 2f, alphaOf(palette.shadow, 0.8f))
            c.drawText(BOSS_TITLE, w * 0.5f, 42f, textPaint)
            val segW = barW / boss.maxHp
            for (k in 0 until boss.maxHp) {
                fill.color = if (k < boss.hp) DANGER else Color.argb(90, 0, 0, 0)
                c.drawRoundRect(bl + k * segW + 2f, 52f, bl + (k + 1) * segW - 2f, 62f, 4f, 4f, fill)
            }
        }
    }

    // =====================================================================================
    // Glow
    // =====================================================================================

    private fun glow(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        val mask = glowMask ?: buildGlowMask().also { glowMask = it }
        maskPaint.color = color
        maskPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        dst.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawBitmap(mask, null, dst, maskPaint)
    }

    private fun buildGlowMask(): Bitmap {
        val size = 96
        val half = size * 0.5f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            half, half, half,
            intArrayOf(alphaOf(Color.WHITE, 1f), alphaOf(Color.WHITE, 0.7f),
                alphaOf(Color.WHITE, 0.18f), alphaOf(Color.WHITE, 0f)),
            floatArrayOf(0f, 0.25f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(bmp).drawCircle(half, half, half, p)
        return bmp
    }

    fun release() {
        glowMask = null
    }

    companion object {
        const val FLASH_TIME = 0.12f
        const val MONOLITH_WARN = 0.9f
        const val RUBBLE_TIME = 0.7f
        const val DRONE_CHARGE = 0.45f
        const val BOLT_SEGMENTS = 12
        const val BOSS_TITLE = "THE LAST OVERSEER"

        val DANGER: Int = Color.rgb(255, 61, 127)
        val RUST: Int = Color.rgb(181, 86, 47)
        val RUST_DARK: Int = Color.rgb(90, 42, 34)
        val VOID: Int = Color.rgb(34, 20, 47)
        val VOID_EDGE: Int = Color.rgb(74, 42, 99)
        val WISP_GLOW: Int = Color.rgb(138, 77, 255)
        val BOSS_METAL: Int = Color.rgb(46, 36, 64)
        val BOSS_EDGE: Int = Color.rgb(107, 90, 138)
        val CORE_LIGHT: Int = Color.rgb(255, 233, 168)
        val LASER_BASE: Int = Color.rgb(0, 230, 255)
    }
}
