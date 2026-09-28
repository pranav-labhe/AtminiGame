package com.pranav.atminigame.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * WorldBackgroundRenderer — "The Luminous Ascension"
 *
 * A procedural, Canvas-only living world painted behind all gameplay. It is the old
 * dystopian megacity seen through the eyes of a god: as Atmini travels forward, the
 * world she passes through heals and brightens, zone by zone, into paradise. The
 * journey then loops into a new dawn.
 *
 *   I   Dawn of Awakening       - ancient towers touched by the first golden light
 *   II  Verdant Arcologies      - sky gardens and floating islands reclaim the city
 *   III Sea of Light            - crystal spires under an open azure sky
 *   IV  Aurora Heights          - living aurora curtains, a sky that thinks
 *   V   Celestial Singularity   - golden halo, the world fully transcendent
 *
 * GAMEPLAY-SAFETY CONTRACT
 *  - Read-only: consumes cameraX and groundY, never writes any gameplay state.
 *  - Draws strictly in screen space BEFORE canvas.translate(-cameraX, 0f), so it sits
 *    behind orbs, effects, the character and the HUD.
 *  - No physics, collision or input involvement of any kind.
 *
 * PERFORMANCE CONTRACT
 *  - Zero allocations in update()/draw(). All shapes are cached once per viewport size
 *    as ALPHA_8 masks and tinted at draw time via Paint color, so palettes can blend
 *    continuously without creating shaders or bitmaps per frame.
 *  - Every cached bitmap is capped at 2048px per side (safe GPU texture size).
 *  - Motion is delta-time driven, so it looks identical on 60/90/120Hz displays.
 *  - Bitmaps are released to the GC rather than recycle()d, because HWUI may still
 *    reference the previous frame's display list.
 */
class WorldBackgroundRenderer {

    /** Name of the biome currently dominating the screen (handy for zone titles/analytics). */
    var currentZoneName: String = ZONES[0].name
        private set

    /** Skips the most fill-rate-heavy layers. Enable for low-end devices. */
    var reducedEffects: Boolean = false

    // ---------------------------------------------------------------- viewport
    private var viewW = 0
    private var viewH = 0
    private var rawGroundY = Float.NaN   // value passed by caller (used for change detection)
    private var groundY = 0f             // sanitized value used for layout
    private var unit = 1f                // viewH / 1080: resolution-independent sizing
    private var built = false

    // ---------------------------------------------------------------- time & camera
    private var time = 0f
    private var cameraX = 0f
    private var lastCameraX = 0f
    private var cameraKnown = false
    private var shownProgress = 0f

    // ---------------------------------------------------------------- zone blend
    private var zoneA = 0
    private var zoneB = 1
    private var zoneT = 0f
    private val blend = Blend().also { it.mix(ZONES[0], ZONES[1], 0f) }

    // ---------------------------------------------------------------- cached assets
    private val skyBitmaps = arrayOfNulls<Bitmap>(ZONES.size)
    private var glowMask: Bitmap? = null
    private var rayMask: Bitmap? = null
    private var ringMask: Bitmap? = null
    private var islandMask: Bitmap? = null
    private var fadeUpMask: Bitmap? = null     // alpha 0 at top -> 255 at bottom
    private var fadeDownMask: Bitmap? = null   // alpha 255 at top -> 0 at bottom

    private val ranges = arrayOf(
        // far: soft, tall, slow
        RangeLayer(parallax = 0.07f, rise = 0.36f, resScale = 0.5f,
            freqs = intArrayOf(2, 3, 5, 9), spikeFreq = 11, spiky = 0.12f, seed = 101),
        // mid: sharper ridges with occasional megastructure spires
        RangeLayer(parallax = 0.16f, rise = 0.27f, resScale = 0.75f,
            freqs = intArrayOf(3, 4, 7, 12), spikeFreq = 17, spiky = 0.32f, seed = 202),
        // near: low rolling terraces that tuck behind the ground rim
        RangeLayer(parallax = 0.34f, rise = 0.13f, resScale = 1f,
            freqs = intArrayOf(4, 6, 9, 15), spikeFreq = 23, spiky = 0.08f, seed = 303)
    )

    // Orbital ring geometry (screen space)
    private var ringCx = 0f
    private var ringCy = 0f
    private var ringRx = 0f
    private var ringRy = 0f
    private val ringRotRad = RING_ROT_DEG * PI_F / 180f

    // ---------------------------------------------------------------- particles (pre-sized)
    private val starX = FloatArray(STAR_COUNT)
    private val starY = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)
    private val starPhase = FloatArray(STAR_COUNT)

    private val moteX = FloatArray(MOTE_COUNT)
    private val moteY = FloatArray(MOTE_COUNT)
    private val moteVy = FloatArray(MOTE_COUNT)
    private val moteSize = FloatArray(MOTE_COUNT)
    private val motePhase = FloatArray(MOTE_COUNT)
    private val moteHue = IntArray(MOTE_COUNT)

    private var flockActive = false
    private var flockX = 0f
    private var flockY = 0f
    private var flockDir = 1f
    private var flockSpeed = 0f
    private var flockScale = 1f
    private var flockTimer = 4f
    private val birdLines = FloatArray(BIRDS * 8)

    private var cometActive = false
    private var cometX = 0f
    private var cometY = 0f
    private var cometVx = 0f
    private var cometVy = 0f
    private var cometLife = 0f
    private var cometTimer = 6f

    // ---------------------------------------------------------------- reusable draw objects
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dst = RectF()
    private val matrix = Matrix()

    // =====================================================================================
    // Public API
    // =====================================================================================

    /**
     * Advances ambient animation and resolves which zone(s) are visible.
     * Call once per frame after gameplay update, with the same dt.
     */
    fun update(dt: Float, cameraX: Float) {
        val step = if (dt.isNaN() || dt < 0f) 0f else min(dt, MAX_DT)
        time += step
        if (time > TIME_WRAP) time -= TIME_WRAP

        if (!cameraKnown) {
            lastCameraX = cameraX
            cameraKnown = true
        }
        val camDelta = cameraX - lastCameraX
        lastCameraX = cameraX
        this.cameraX = cameraX

        updateZone(step)
        if (!built) return

        // A jump larger than one screen means a reset/teleport: don't drag particles along.
        updateMotes(step, if (abs(camDelta) > viewW) 0f else camDelta)
        updateFlock(step)
        updateComet(step)
    }

    /**
     * Paints the full background in screen space. Call BEFORE canvas.translate(-cameraX, 0f).
     *
     * @param groundY    the same ground line gameplay uses (e.g. height - 250f).
     * @param drawGround true to paint the luminous ground surface below groundY
     *                   (replaces the original floor rects; purely visual).
     */
    fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int, groundY: Float, drawGround: Boolean = true) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (!built || viewWidth != viewW || viewHeight != viewH || groundY != rawGroundY) {
            build(viewWidth, viewHeight, groundY)
        }

        drawSky(canvas)
        drawStars(canvas)
        drawSun(canvas)
        if (!reducedEffects) drawRing(canvas)
        drawAurora(canvas)
        drawRange(canvas, ranges[0], blend.farRange)
        drawIslands(canvas)
        drawRange(canvas, ranges[1], blend.midRange)
        drawTowers(canvas)
        drawHaze(canvas)
        drawRange(canvas, ranges[2], blend.nearRange)
        drawComet(canvas)
        drawFlock(canvas)
        if (drawGround) drawGroundSurface(canvas)
        drawMotes(canvas)
        drawHudScrim(canvas)
    }

    /**
     * Copies the live, zone-blended colors into [out] so gameplay effects match the world.
     * Allocation-free; call once per frame after [update].
     */
    fun exportPalette(out: EffectPalette) {
        out.primary = blend.accent
        out.secondary = blend.accent2
        out.warm = blend.sunGlow
        out.highlight = blend.structureLight
        out.shadow = blend.ground
    }

    /** Drops all cached bitmaps. Safe to call anytime; caches rebuild lazily on next draw. */
    fun release() {
        built = false
        for (i in skyBitmaps.indices) skyBitmaps[i] = null
        glowMask = null
        rayMask = null
        ringMask = null
        islandMask = null
        fadeUpMask = null
        fadeDownMask = null
        for (layer in ranges) layer.bitmap = null
    }

    // =====================================================================================
    // Update helpers
    // =====================================================================================

    private fun updateZone(dt: Float) {
        val center = max(0f, cameraX + viewW * 0.5f)
        val target = center / ZONE_LENGTH
        val gap = target - shownProgress
        // Normal travel is followed exactly; big jumps (game reset) rewind smoothly.
        shownProgress = if (abs(gap) > SNAP_THRESHOLD) {
            shownProgress + gap * min(1f, dt * REWIND_SPEED)
        } else {
            target
        }

        val base = floor(shownProgress).toInt()
        val frac = shownProgress - base
        val n = ZONES.size
        zoneA = ((base % n) + n) % n
        zoneB = (zoneA + 1) % n
        val raw = ((frac - (1f - BLEND_FRACTION)) / BLEND_FRACTION).coerceIn(0f, 1f)
        zoneT = raw * raw * (3f - 2f * raw) // smoothstep
        blend.mix(ZONES[zoneA], ZONES[zoneB], zoneT)
        currentZoneName = ZONES[if (zoneT < 0.5f) zoneA else zoneB].name
    }

    private fun updateMotes(dt: Float, camDelta: Float) {
        val w = viewW.toFloat()
        val topLimit = groundY * MOTE_TOP_FRACTION
        val wrapW = w + 40f
        for (i in 0 until MOTE_COUNT) {
            moteY[i] -= moteVy[i] * dt
            moteX[i] += sin(time * 0.8f + motePhase[i]) * 10f * unit * dt - camDelta * MOTE_PARALLAX
            if (moteX[i] < -20f) moteX[i] += wrapW
            else if (moteX[i] > w + 20f) moteX[i] -= wrapW
            if (moteY[i] < topLimit) respawnMote(i, fromGround = true)
        }
    }

    private fun respawnMote(i: Int, fromGround: Boolean) {
        moteX[i] = Random.nextFloat() * viewW
        moteY[i] = if (fromGround) {
            groundY + Random.nextFloat() * 20f * unit
        } else {
            groundY * MOTE_TOP_FRACTION + Random.nextFloat() * groundY * (1f - MOTE_TOP_FRACTION)
        }
        moteVy[i] = (14f + Random.nextFloat() * 28f) * unit
        moteSize[i] = (3f + Random.nextFloat() * 6f) * unit
        motePhase[i] = Random.nextFloat() * TWO_PI
        moteHue[i] = Random.nextInt(3)
    }

    private fun updateFlock(dt: Float) {
        if (!flockActive) {
            flockTimer -= dt
            if (flockTimer <= 0f) {
                flockDir = if (Random.nextBoolean()) 1f else -1f
                flockX = if (flockDir > 0f) -viewW * 0.1f else viewW * 1.1f
                flockY = viewH * (0.12f + Random.nextFloat() * 0.18f)
                flockSpeed = (70f + Random.nextFloat() * 50f) * unit
                flockScale = (0.7f + Random.nextFloat() * 0.6f) * unit
                flockActive = true
            }
            return
        }
        flockX += flockDir * flockSpeed * dt
        flockY += sin(time * 0.5f) * 6f * unit * dt
        val margin = 260f * unit
        if ((flockDir > 0f && flockX > viewW + margin) || (flockDir < 0f && flockX < -margin)) {
            flockActive = false
            flockTimer = 8f + Random.nextFloat() * 10f
        }
    }

    private fun updateComet(dt: Float) {
        if (!cometActive) {
            cometTimer -= dt
            if (cometTimer <= 0f) {
                val dir = if (Random.nextBoolean()) 1f else -1f
                cometX = viewW * (0.15f + Random.nextFloat() * 0.7f)
                cometY = viewH * Random.nextFloat() * 0.16f
                cometVx = dir * (520f + Random.nextFloat() * 240f) * unit
                cometVy = (180f + Random.nextFloat() * 80f) * unit
                cometLife = COMET_LIFE
                cometActive = true
            }
            return
        }
        cometX += cometVx * dt
        cometY += cometVy * dt
        cometLife -= dt
        if (cometLife <= 0f) {
            cometActive = false
            cometTimer = 6f + Random.nextFloat() * 9f
        }
    }

    // =====================================================================================
    // Draw helpers
    // =====================================================================================

    private fun drawSky(canvas: Canvas) {
        val a = skyBitmaps[zoneA]
        val b = skyBitmaps[zoneB]
        if (a == null || b == null) {
            canvas.drawColor(blend.skyMid)
            return
        }
        dst.set(0f, 0f, viewW.toFloat(), viewH.toFloat())
        bitmapPaint.alpha = 255
        canvas.drawBitmap(a, null, dst, bitmapPaint)
        if (zoneT > 0.001f) {
            bitmapPaint.alpha = (zoneT * 255f + 0.5f).toInt()
            canvas.drawBitmap(b, null, dst, bitmapPaint)
        }
    }

    private fun drawStars(canvas: Canvas) {
        val vis = blend.stars
        if (vis < 0.02f) return
        val stride = if (reducedEffects) 2 else 1
        var i = 0
        while (i < STAR_COUNT) {
            val twinkle = 0.45f + 0.55f * (0.5f + 0.5f * sin(time * 1.6f + starPhase[i]))
            val r = starSize[i] * 3.2f
            drawGlow(canvas, starX[i], starY[i], r, r, Color.WHITE, vis * twinkle * 0.8f)
            i += stride
        }
    }

    private fun drawSun(canvas: Canvas) {
        val sx = blend.sunX * viewW
        val sy = blend.sunY * viewH

        // Slowly rotating god rays (two counter-rotating fans for depth).
        rayMask?.let { rays ->
            val size = viewH * 1.8f
            val scale = size / rays.width
            val half = rays.width * 0.5f

            matrix.reset()
            matrix.postTranslate(-half, -half)
            matrix.postScale(scale, scale)
            matrix.postRotate(time * 2.2f)
            matrix.postTranslate(sx, sy)
            tint(blend.sunGlow, 0.30f + 0.08f * sin(time * 0.6f))
            canvas.drawBitmap(rays, matrix, maskPaint)

            if (!reducedEffects) {
                matrix.reset()
                matrix.postTranslate(-half, -half)
                matrix.postScale(scale * 0.72f, scale * 0.72f)
                matrix.postRotate(-time * 1.4f + 9f)
                matrix.postTranslate(sx, sy)
                tint(blend.sun, 0.20f + 0.06f * sin(time * 0.9f + 1f))
                canvas.drawBitmap(rays, matrix, maskPaint)
            }
        }

        // Layered bloom: wide warm glow -> bright disc -> white-hot core.
        val breathe = 1f + 0.04f * sin(time * 0.7f)
        drawGlow(canvas, sx, sy, viewH * 0.34f * breathe, viewH * 0.34f * breathe, blend.sunGlow, 0.55f)
        drawGlow(canvas, sx, sy, viewH * 0.11f, viewH * 0.11f, blend.sun, 0.95f)
        drawGlow(canvas, sx, sy, viewH * 0.05f, viewH * 0.05f, Color.WHITE, 1f)

        // Transcendent halo rings (strongest in the Celestial zone).
        val halo = blend.halo
        if (halo > 0.02f) {
            strokePaint.strokeWidth = 2f * unit
            for (k in 0 until 3) {
                val r = viewH * (0.12f + k * 0.07f) * (1f + 0.03f * sin(time * 0.8f + k))
                strokePaint.color = blend.sun
                strokePaint.alpha = (halo * (0.35f - k * 0.08f) * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(sx, sy, r, strokePaint)
            }
        }
    }

    private fun drawRing(canvas: Canvas) {
        val ring = ringMask ?: return
        val ringColor = lerpColor(blend.structureLight, Color.WHITE, 0.35f)
        tint(ringColor, 0.5f + 0.08f * sin(time * 0.4f))
        dst.set(0f, 0f, viewW.toFloat(), viewH.toFloat())
        canvas.drawBitmap(ring, null, dst, maskPaint)

        // Light pulses travelling along the visible upper arc of the ring.
        val cosR = cos(ringRotRad)
        val sinR = sin(ringRotRad)
        for (k in 0 until 2) {
            val theta = PI_F + ((time * 0.045f + k * 0.5f) % 1f) * PI_F
            val ex = ringRx * cos(theta)
            val ey = ringRy * sin(theta)
            val px = ringCx + ex * cosR - ey * sinR
            val py = ringCy + ex * sinR + ey * cosR
            drawGlow(canvas, px, py, 48f * unit, 16f * unit, ringColor, 0.35f)
            drawGlow(canvas, px, py, 10f * unit, 10f * unit, Color.WHITE, 0.9f)
        }
    }

    private fun drawAurora(canvas: Canvas) {
        val intensity = blend.aurora
        if (intensity < 0.02f) return
        val cols = if (reducedEffects) AURORA_COLS / 2 else AURORA_COLS
        val span = viewW * 1.25f
        val spacing = span / (cols - 1)
        val drift = (cameraX * AURORA_PARALLAX) % spacing
        val curtainH = viewH * 0.16f
        val colW = spacing * 1.3f
        for (ribbon in 0 until 3) {
            if (reducedEffects && ribbon == 1) continue
            val baseY = viewH * (0.10f + ribbon * 0.055f)
            val color = if (ribbon == 1) blend.accent2 else blend.accent
            for (j in 0 until cols) {
                val x = j * spacing - viewW * 0.12f - drift
                val worldU = x + cameraX * AURORA_PARALLAX
                val y = baseY + sin(worldU * 0.0035f + time * 0.32f + ribbon * 1.7f) * viewH * 0.045f
                val pulse = 0.5f + 0.5f * sin(worldU * 0.011f - time * 0.9f + ribbon)
                drawGlow(canvas, x, y + curtainH * 0.35f, colW, curtainH, color,
                    intensity * (0.10f + 0.20f * pulse))
            }
        }
    }

    private fun drawRange(canvas: Canvas, layer: RangeLayer, color: Int) {
        val bmp = layer.bitmap ?: return
        tint(color, 1f)
        val period = layer.period
        var offset = (cameraX * layer.parallax) % period
        if (offset < 0f) offset += period
        var x = -offset
        while (x < viewW) {
            dst.set(x, layer.top, x + period + 1f, layer.bottom) // +1px hides seams
            canvas.drawBitmap(bmp, null, dst, maskPaint)
            x += period
        }
    }

    private fun drawIslands(canvas: Canvas) {
        val a = blend.islands
        if (a < 0.02f) return
        val mask = islandMask ?: return
        val layerLeft = cameraX * ISLAND_PARALLAX
        val first = floor((layerLeft - 400f * unit) / ISLAND_SPACING).toInt()
        val last = floor((layerLeft + viewW) / ISLAND_SPACING).toInt() + 1
        val body = lerpColor(blend.midRange, blend.skyHorizon, 0.3f)
        val fallLen = viewH * 0.22f

        for (i in first..last) {
            if (hash(i, 31) > 0.72f) continue
            val w = (150f + hash(i, 32) * 170f) * unit
            val h = w * ISLAND_ASPECT
            val x = i * ISLAND_SPACING + hash(i, 33) * ISLAND_SPACING * 0.45f - layerLeft
            if (x > viewW || x + w < 0f) continue
            val y = viewH * (0.08f + hash(i, 34) * 0.18f) +
                sin(time * 0.45f + hash(i, 35) * TWO_PI) * 9f * unit

            // Waterfall of light pouring from the island's tip (drawn behind the rock).
            val tipX = x + w * 0.5f
            val tipY = y + h * 0.96f
            drawGlow(canvas, tipX, tipY + fallLen * 0.5f, 7f * unit, fallLen * 0.5f, blend.accent, a * 0.35f)
            for (k in 0 until 3) {
                val p = (time * 0.4f + k / 3f + hash(i, 36)) % 1f
                drawGlow(canvas, tipX, tipY + p * fallLen, 5f * unit, 11f * unit, Color.WHITE, a * (1f - p) * 0.7f)
            }

            tint(body, a)
            dst.set(x, y, x + w, y + h)
            canvas.drawBitmap(mask, null, dst, maskPaint)

            // Glowing garden canopy.
            drawGlow(canvas, x + w * 0.5f, y + h * 0.3f, w * 0.5f, h * 0.2f, blend.accent, a * 0.45f)
        }
    }

    private fun drawTowers(canvas: Canvas) {
        val a = blend.towers
        if (a < 0.02f) return
        val layerLeft = cameraX * TOWER_PARALLAX
        val first = floor(layerLeft / TOWER_SPACING).toInt() - 1
        val last = floor((layerLeft + viewW) / TOWER_SPACING).toInt() + 1
        val baseY = groundY
        val bodyColor = blend.structure
        val edgeColor = lerpColor(blend.structure, blend.structureLight, 0.35f)
        val lightColor = blend.structureLight
        val crownColor = lerpColor(blend.structureLight, blend.accent, 0.3f)
        val bodyAlpha = (a * 255f).toInt().coerceIn(0, 255)
        val windowGap = 28f * unit

        var hasPrev = false
        var prevX = 0f
        var prevY = 0f

        for (i in first..last) {
            if (hash(i, 41) > 0.62f) continue
            val tw = (26f + hash(i, 43) * 46f) * unit
            val th = viewH * (0.22f + hash(i, 44) * 0.30f)
            val tx = i * TOWER_SPACING + hash(i, 42) * TOWER_SPACING * 0.6f - layerLeft
            val top = baseY - th
            val spireW = tw * 0.4f
            val spireH = th * 0.12f
            val crownX = tx + tw * 0.5f
            val crownY = top - spireH

            // Neural thread to the previous tower: the world thinking as one mind.
            if (hasPrev && crownX - prevX < TOWER_SPACING * 2.6f) {
                strokePaint.strokeWidth = 1.6f * unit
                strokePaint.color = blend.accent
                strokePaint.alpha = (a * 70f).toInt()
                canvas.drawLine(prevX, prevY, crownX, crownY, strokePaint)
                val p = (time * 0.35f + hash(i, 46)) % 1f
                drawGlow(canvas, prevX + (crownX - prevX) * p, prevY + (crownY - prevY) * p,
                    7f * unit, 7f * unit, Color.WHITE, a * 0.85f)
            }
            hasPrev = true
            prevX = crownX
            prevY = crownY

            if (tx > viewW || tx + tw < 0f) continue

            // Body, sunlit edge and spire.
            fillPaint.color = bodyColor
            fillPaint.alpha = bodyAlpha
            canvas.drawRect(tx, top, tx + tw, baseY, fillPaint)
            canvas.drawRect(tx + (tw - spireW) * 0.5f, crownY, tx + (tw + spireW) * 0.5f, top, fillPaint)
            fillPaint.color = edgeColor
            fillPaint.alpha = bodyAlpha
            canvas.drawRect(tx + tw * 0.82f, top, tx + tw, baseY, fillPaint)

            // Softly breathing windows (life returning, never flickering or harsh).
            val rows = min(MAX_WINDOW_ROWS, (th / windowGap).toInt())
            fillPaint.color = lightColor
            for (r in 0 until rows) {
                if (hash(i * 31 + r, 45) > 0.55f) continue
                val breathe = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * 0.9f + hash(i + r, 47) * TWO_PI))
                fillPaint.alpha = (a * breathe * 200f).toInt().coerceIn(0, 255)
                val wy = top + 14f * unit + r * windowGap
                canvas.drawRect(tx + tw * 0.3f, wy, tx + tw * 0.7f, wy + 5f * unit, fillPaint)
            }

            // Crown beacon.
            val pulse = 1f + 0.3f * sin(time * 1.3f + hash(i, 48) * TWO_PI)
            drawGlow(canvas, crownX, crownY, 22f * unit * pulse, 22f * unit * pulse, crownColor, a * 0.8f)
        }
    }

    private fun drawHaze(canvas: Canvas) {
        val fade = fadeUpMask ?: return
        tint(blend.skyHorizon, 0.55f)
        dst.set(0f, groundY - viewH * 0.40f, viewW.toFloat(), groundY + 2f)
        canvas.drawBitmap(fade, null, dst, maskPaint)
        drawGlow(canvas, blend.sunX * viewW, groundY - viewH * 0.04f, viewW * 0.55f, viewH * 0.12f,
            blend.sunGlow, 0.35f)
    }

    private fun drawComet(canvas: Canvas) {
        if (!cometActive) return
        val fade = sin((cometLife / COMET_LIFE).coerceIn(0f, 1f) * PI_F)
        val vis = 0.5f + 0.5f * blend.stars
        for (k in 0 until COMET_TRAIL) {
            val back = k * 0.018f
            val r = (4f - k * 0.3f) * unit * 2.5f
            drawGlow(canvas, cometX - cometVx * back, cometY - cometVy * back, r, r,
                if (k == 0) Color.WHITE else blend.sun, fade * vis * (1f - k / COMET_TRAIL.toFloat()) * 0.85f)
        }
    }

    private fun drawFlock(canvas: Canvas) {
        if (!flockActive) return
        val s = flockScale
        var idx = 0
        for (b in 0 until BIRDS) {
            val rank = (b + 1) / 2
            val side = if (b % 2 == 0) 1f else -1f
            val cx = flockX - flockDir * rank * 34f * s
            val cy = flockY + side * rank * 18f * s
            val flap = sin(time * 7f + b * 0.7f)
            val span = 14f * s
            val tipY = cy - flap * 8f * s
            birdLines[idx++] = cx - span; birdLines[idx++] = tipY
            birdLines[idx++] = cx;        birdLines[idx++] = cy
            birdLines[idx++] = cx;        birdLines[idx++] = cy
            birdLines[idx++] = cx + span; birdLines[idx++] = tipY
            drawGlow(canvas, cx, cy, 14f * s, 14f * s, blend.accent2, 0.35f)
        }
        strokePaint.strokeWidth = 2.2f * unit
        strokePaint.color = lerpColor(Color.WHITE, blend.accent2, 0.4f)
        strokePaint.alpha = 220
        canvas.drawLines(birdLines, 0, idx, strokePaint)
    }

    private fun drawGroundSurface(canvas: Canvas) {
        val w = viewW.toFloat()
        val h = viewH.toFloat()
        val top = groundY
        if (top >= h) return

        fillPaint.color = blend.ground
        canvas.drawRect(0f, top, w, h, fillPaint)

        // Sky reflected in the polished surface, fading into depth.
        fadeDownMask?.let {
            tint(blend.skyHorizon, 0.28f)
            dst.set(0f, top, w, top + (h - top) * 0.7f)
            canvas.drawBitmap(it, null, dst, maskPaint)
        }
        fadeUpMask?.let {
            tint(Color.BLACK, 0.30f)
            dst.set(0f, top, w, h)
            canvas.drawBitmap(it, null, dst, maskPaint)
        }
        drawGlow(canvas, blend.sunX * w, top + 18f * unit, w * 0.3f, 22f * unit, blend.sunGlow, 0.35f)

        // World-locked seams on the original 220px rhythm, so ground speed reads 1:1 with gameplay.
        fillPaint.color = blend.groundRim
        fillPaint.alpha = 40
        val seamBottom = top + (h - top) * 0.45f
        var i = floor(cameraX / SEAM_SPACING).toInt()
        val lastSeam = floor((cameraX + w) / SEAM_SPACING).toInt() + 1
        while (i <= lastSeam) {
            val sx = i * SEAM_SPACING - cameraX
            canvas.drawRect(sx, top + 6f * unit, sx + 2f * unit, seamBottom, fillPaint)
            i++
        }

        // Luminous rim.
        fillPaint.color = blend.groundRim
        canvas.drawRect(0f, top, w, top + 4f * unit, fillPaint)
        drawGlow(canvas, w * 0.5f, top + 2f * unit, w * 0.8f, 16f * unit, blend.groundRim, 0.5f)

        // Light blossoms growing along the rim.
        var j = floor(cameraX / BLOSSOM_SPACING).toInt() - 1
        val lastBlossom = floor((cameraX + w) / BLOSSOM_SPACING).toInt() + 1
        while (j <= lastBlossom) {
            if (hash(j, 61) < 0.45f) {
                val fx = j * BLOSSOM_SPACING + hash(j, 62) * 50f - cameraX
                val pulse = 1f + 0.25f * sin(time * 1.5f + hash(j, 63) * TWO_PI)
                drawGlow(canvas, fx, top - unit, 12f * unit * pulse, 7f * unit, blend.accent, 0.55f)
                drawGlow(canvas, fx, top - unit, 4f * unit, 4f * unit, Color.WHITE, 0.9f)
            }
            j++
        }
    }

    private fun drawMotes(canvas: Canvas) {
        val span = groundY * (1f - MOTE_TOP_FRACTION)
        val stride = if (reducedEffects) 2 else 1
        var i = 0
        while (i < MOTE_COUNT) {
            val life = ((groundY - moteY[i]) / span).coerceIn(0f, 1f)
            val fade = sin(life * PI_F)
            val twinkle = 0.6f + 0.4f * sin(time * 3f + motePhase[i])
            val alpha = fade * twinkle * 0.85f
            if (alpha > 0.01f) {
                val color = when (moteHue[i]) {
                    0 -> blend.accent
                    1 -> blend.accent2
                    else -> Color.WHITE
                }
                val s = moteSize[i]
                drawGlow(canvas, moteX[i], moteY[i], s * 3.2f, s * 3.2f, color, alpha * 0.55f)
                drawGlow(canvas, moteX[i], moteY[i], s, s, Color.WHITE, alpha)
            }
            i += stride
        }
    }

    /** Gentle darkening under the HUD so white text stays legible on bright skies. */
    private fun drawHudScrim(canvas: Canvas) {
        val fade = fadeDownMask ?: return
        tint(lerpColor(blend.skyTop, Color.BLACK, 0.55f), 0.42f)
        dst.set(0f, 0f, viewW.toFloat(), 210f * unit)
        canvas.drawBitmap(fade, null, dst, maskPaint)
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, alpha: Float) {
        val glow = glowMask ?: return
        if (alpha <= 0.004f) return
        tint(color, alpha)
        dst.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawBitmap(glow, null, dst, maskPaint)
    }

    private fun tint(color: Int, alpha: Float) {
        maskPaint.color = color
        maskPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    // =====================================================================================
    // One-time cache building (runs on first draw and on viewport change only)
    // =====================================================================================

    private fun build(w: Int, h: Int, requestedGroundY: Float) {
        release()
        viewW = w
        viewH = h
        rawGroundY = requestedGroundY
        groundY = if (requestedGroundY.isNaN()) h * 0.77f else requestedGroundY.coerceIn(h * 0.3f, h.toFloat())
        unit = h / 1080f

        ringCx = w * 0.5f
        ringCy = h * 0.58f
        ringRx = w * 0.95f
        ringRy = h * 0.20f

        try {
            buildSkies()
            glowMask = buildGlowMask()
            fadeUpMask = buildFadeMask(fadeUp = true)
            fadeDownMask = buildFadeMask(fadeUp = false)
            rayMask = buildRayMask()
            islandMask = buildIslandMask()
            for (layer in ranges) buildRange(layer)
            if (!reducedEffects) ringMask = buildRingMask()
        } catch (oom: OutOfMemoryError) {
            // Degrade gracefully: every draw step null-checks its cache.
            reducedEffects = true
            ringMask = null
        }

        seedStars()
        for (i in 0 until MOTE_COUNT) respawnMote(i, fromGround = false)
        flockActive = false
        flockTimer = 4f
        cometActive = false
        cometTimer = 6f
        built = true
    }

    private fun buildSkies() {
        val sw = min(MAX_TEX, max(2, viewW / SKY_DOWNSCALE))
        val sh = min(MAX_TEX, max(2, viewH / SKY_DOWNSCALE))
        val swf = sw.toFloat()
        val shf = sh.toFloat()
        val hf = (groundY / viewH).coerceIn(0.35f, 0.98f)
        val midStop = (hf - 0.08f).coerceIn(0.1f, 0.5f)
        val bandTop = max(0f, hf - 0.22f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        for (i in ZONES.indices) {
            val z = ZONES[i]
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            p.shader = LinearGradient(0f, 0f, 0f, shf,
                intArrayOf(z.skyTop, z.skyMid, z.skyHorizon, lerpColor(z.skyHorizon, z.ground, 0.35f)),
                floatArrayOf(0f, midStop, hf, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, swf, shf, p)

            // Luminous horizon band.
            p.shader = LinearGradient(0f, shf * bandTop, 0f, shf * hf,
                Color.TRANSPARENT, withAlpha(Color.WHITE, 70), Shader.TileMode.CLAMP)
            c.drawRect(0f, shf * bandTop, swf, shf * hf, p)

            // Baked sun bloom (crossfades with the zone).
            p.shader = RadialGradient(z.sunX * swf, z.sunY * shf, swf * 0.6f,
                intArrayOf(withAlpha(z.sunGlow, 150), withAlpha(z.sunGlow, 55), withAlpha(z.sunGlow, 0)),
                floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, swf, shf, p)

            p.shader = null
            skyBitmaps[i] = bmp
        }
    }

    private fun buildGlowMask(): Bitmap {
        val s = GLOW_SIZE
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val half = s * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(half, half, half,
            intArrayOf(withAlpha(Color.WHITE, 255), withAlpha(Color.WHITE, 180),
                withAlpha(Color.WHITE, 48), withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 0.22f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(half, half, half, p)
        return bmp
    }

    private fun buildFadeMask(fadeUp: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(4, 256, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val p = Paint()
        val top = if (fadeUp) Color.TRANSPARENT else Color.WHITE
        val bottom = if (fadeUp) Color.WHITE else Color.TRANSPARENT
        p.shader = LinearGradient(0f, 0f, 0f, 256f, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 4f, 256f, p)
        return bmp
    }

    private fun buildRayMask(): Bitmap {
        val s = min(MAX_TEX / 2, max(256, (min(viewW, viewH) * 0.8f).toInt()))
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val cx = s * 0.5f
        val r = s * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cx, r,
            intArrayOf(withAlpha(Color.WHITE, 208), withAlpha(Color.WHITE, 80), withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        val path = Path()
        for (i in 0 until RAY_COUNT) {
            val angle = i * TWO_PI / RAY_COUNT + (hash(i, 11) - 0.5f) * 0.12f
            val halfW = 0.03f + hash(i, 12) * 0.05f
            path.reset()
            path.moveTo(cx, cx)
            path.lineTo(cx + cos(angle - halfW) * r, cx + sin(angle - halfW) * r)
            path.lineTo(cx + cos(angle + halfW) * r, cx + sin(angle + halfW) * r)
            path.close()
            c.drawPath(path, p)
        }
        return bmp
    }

    private fun buildRingMask(): Bitmap {
        val bw = min(MAX_TEX, max(2, viewW / 2))
        val bh = min(MAX_TEX, max(2, viewH / 2))
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.scale(bw / viewW.toFloat(), bh / viewH.toFloat())
        c.rotate(RING_ROT_DEG, ringCx, ringCy)
        val oval = RectF(ringCx - ringRx, ringCy - ringRy, ringCx + ringRx, ringCy + ringRy)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        p.color = withAlpha(Color.WHITE, 28); p.strokeWidth = 26f * unit; c.drawOval(oval, p)
        p.color = withAlpha(Color.WHITE, 90); p.strokeWidth = 7f * unit; c.drawOval(oval, p)
        p.color = withAlpha(Color.WHITE, 210); p.strokeWidth = 2f * unit; c.drawOval(oval, p)
        return bmp
    }

    private fun buildIslandMask(): Bitmap {
        val w = 256
        val h = (w * ISLAND_ASPECT).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // Flat garden terrace with a soft canopy of rounded foliage.
        c.drawOval(RectF(8f, 50f, 248f, 100f), p)
        for (k in 0 until 7) {
            c.drawCircle(30f + k * 33f, 62f - hash(k, 21) * 14f, 18f + hash(k, 22) * 10f, p)
        }
        // Crystalline rock underside narrowing to a tip.
        val path = Path().apply {
            moveTo(10f, 80f)
            lineTo(40f, 110f); lineTo(62f, 104f); lineTo(88f, 150f); lineTo(110f, 138f)
            lineTo(128f, h - 4f)
            lineTo(146f, 142f); lineTo(170f, 156f); lineTo(192f, 108f); lineTo(216f, 116f)
            lineTo(246f, 80f)
            close()
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun buildRange(layer: RangeLayer) {
        layer.period = max(viewW.toFloat(), 1024f)
        layer.top = groundY - viewH * layer.rise
        layer.bottom = groundY + 6f * unit
        val pxW = min(MAX_TEX, max(64, (layer.period * layer.resScale).toInt()))
        val scale = pxW / layer.period
        val pxH = min(MAX_TEX, max(8, ((layer.bottom - layer.top) * scale).toInt()))
        val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val hF = pxH.toFloat()
        val path = Path()
        path.moveTo(0f, hF)
        var x = 0
        while (x < pxW) {
            path.lineTo(x.toFloat(), hF - layer.profile(x.toFloat() / pxW) * hF)
            x += 3
        }
        path.lineTo(pxW.toFloat(), hF - layer.profile(1f) * hF) // periodic: matches x = 0
        path.lineTo(pxW.toFloat(), hF)
        path.close()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        layer.bitmap = bmp
    }

    private fun seedStars() {
        val rnd = Random(STAR_SEED)
        val maxY = groundY * 0.45f
        for (i in 0 until STAR_COUNT) {
            starX[i] = rnd.nextFloat() * viewW
            starY[i] = rnd.nextFloat() * maxY
            starSize[i] = (2f + rnd.nextFloat() * 4f) * unit
            starPhase[i] = rnd.nextFloat() * TWO_PI
        }
    }

    // =====================================================================================
    // Types & data
    // =====================================================================================

    private class RangeLayer(
        val parallax: Float,
        val rise: Float,
        val resScale: Float,
        private val freqs: IntArray,
        private val spikeFreq: Int,
        private val spiky: Float,
        seed: Int
    ) {
        private val phases = FloatArray(freqs.size) { hash(it, seed) * TWO_PI }
        private val spikePhase = hash(99, seed) * PI_F
        var bitmap: Bitmap? = null
        var period = 1f
        var top = 0f
        var bottom = 0f

        /** Seamlessly periodic ridge profile on u in [0, 1] -> normalized height [0, 1]. */
        fun profile(u: Float): Float {
            var v = 0f
            var norm = 0f
            for (k in freqs.indices) {
                val amp = 1f / (1f + k * 0.9f)
                v += amp * sin(TWO_PI * freqs[k] * u + phases[k])
                norm += amp
            }
            val base = 0.5f + 0.5f * (v / norm)
            val spike = abs(sin(PI_F * spikeFreq * u + spikePhase)).pow(14f) * spiky
            return (0.3f + 0.62f * base + spike).coerceIn(0f, 1f)
        }
    }

    private class Zone(
        val name: String,
        val skyTop: Int, val skyMid: Int, val skyHorizon: Int,
        val sun: Int, val sunGlow: Int, val sunX: Float, val sunY: Float,
        val farRange: Int, val midRange: Int, val nearRange: Int,
        val structure: Int, val structureLight: Int,
        val accent: Int, val accent2: Int,
        val ground: Int, val groundRim: Int,
        val aurora: Float, val islands: Float, val towers: Float, val halo: Float, val stars: Float
    )

    /** Live, continuously blended palette. Mutated in place: no per-frame allocation. */
    private class Blend {
        var skyTop = 0; var skyMid = 0; var skyHorizon = 0
        var sun = 0; var sunGlow = 0; var sunX = 0f; var sunY = 0f
        var farRange = 0; var midRange = 0; var nearRange = 0
        var structure = 0; var structureLight = 0
        var accent = 0; var accent2 = 0
        var ground = 0; var groundRim = 0
        var aurora = 0f; var islands = 0f; var towers = 0f; var halo = 0f; var stars = 0f

        fun mix(a: Zone, b: Zone, t: Float) {
            skyTop = lerpColor(a.skyTop, b.skyTop, t)
            skyMid = lerpColor(a.skyMid, b.skyMid, t)
            skyHorizon = lerpColor(a.skyHorizon, b.skyHorizon, t)
            sun = lerpColor(a.sun, b.sun, t)
            sunGlow = lerpColor(a.sunGlow, b.sunGlow, t)
            sunX = lerp(a.sunX, b.sunX, t)
            sunY = lerp(a.sunY, b.sunY, t)
            farRange = lerpColor(a.farRange, b.farRange, t)
            midRange = lerpColor(a.midRange, b.midRange, t)
            nearRange = lerpColor(a.nearRange, b.nearRange, t)
            structure = lerpColor(a.structure, b.structure, t)
            structureLight = lerpColor(a.structureLight, b.structureLight, t)
            accent = lerpColor(a.accent, b.accent, t)
            accent2 = lerpColor(a.accent2, b.accent2, t)
            ground = lerpColor(a.ground, b.ground, t)
            groundRim = lerpColor(a.groundRim, b.groundRim, t)
            aurora = lerp(a.aurora, b.aurora, t)
            islands = lerp(a.islands, b.islands, t)
            towers = lerp(a.towers, b.towers, t)
            halo = lerp(a.halo, b.halo, t)
            stars = lerp(a.stars, b.stars, t)
        }
    }

    private companion object {
        const val ZONE_LENGTH = 1600f        // world px per biome (~5 biomes per 30-orb run)
        const val BLEND_FRACTION = 0.4f      // last 40% of each zone crossfades into the next
        const val SNAP_THRESHOLD = 0.05f
        const val REWIND_SPEED = 2.5f
        const val MAX_DT = 0.05f
        const val TIME_WRAP = 3600f

        const val MAX_TEX = 2048
        const val SKY_DOWNSCALE = 3
        const val GLOW_SIZE = 128
        const val RAY_COUNT = 18
        const val RING_ROT_DEG = -7f

        const val STAR_COUNT = 46
        const val STAR_SEED = 20260928
        const val MOTE_COUNT = 64
        const val MOTE_TOP_FRACTION = 0.3f
        const val MOTE_PARALLAX = 0.5f
        const val BIRDS = 7
        const val COMET_LIFE = 1.1f
        const val COMET_TRAIL = 10
        const val AURORA_COLS = 34
        const val AURORA_PARALLAX = 0.02f

        const val TOWER_SPACING = 150f
        const val TOWER_PARALLAX = 0.26f
        const val MAX_WINDOW_ROWS = 14
        const val ISLAND_SPACING = 820f
        const val ISLAND_PARALLAX = 0.12f
        const val ISLAND_ASPECT = 200f / 256f
        const val SEAM_SPACING = 220f
        const val BLOSSOM_SPACING = 140f

        val ZONES = arrayOf(
            Zone(
                name = "Dawn of Awakening",
                skyTop = c(0xFF3D3A8C), skyMid = c(0xFFE889B5), skyHorizon = c(0xFFFFD39A),
                sun = c(0xFFFFF4D6), sunGlow = c(0xFFFFC37A), sunX = 0.78f, sunY = 0.30f,
                farRange = c(0xFFB98CC4), midRange = c(0xFF8A5E9E), nearRange = c(0xFF5B3F78),
                structure = c(0xFF4A3668), structureLight = c(0xFFFFD58A),
                accent = c(0xFFFFB3D1), accent2 = c(0xFFFFE9A8),
                ground = c(0xFF2E2346), groundRim = c(0xFFFFC98A),
                aurora = 0.25f, islands = 0f, towers = 1f, halo = 0.2f, stars = 0.6f
            ),
            Zone(
                name = "Verdant Arcologies",
                skyTop = c(0xFF2F7FD1), skyMid = c(0xFF7FD3F0), skyHorizon = c(0xFFFFF1C4),
                sun = c(0xFFFFFBE6), sunGlow = c(0xFFFFE7A1), sunX = 0.70f, sunY = 0.18f,
                farRange = c(0xFF9ED8E0), midRange = c(0xFF5FA8A8), nearRange = c(0xFF2F6E6A),
                structure = c(0xFF2E5E63), structureLight = c(0xFF9CFFD0),
                accent = c(0xFFB8FFD8), accent2 = c(0xFFFFF3A6),
                ground = c(0xFF1E4A45), groundRim = c(0xFFA8FFD6),
                aurora = 0.1f, islands = 1f, towers = 0.8f, halo = 0.3f, stars = 0f
            ),
            Zone(
                name = "Sea of Light",
                skyTop = c(0xFF2450C8), skyMid = c(0xFF63B8FF), skyHorizon = c(0xFFDFFBFF),
                sun = c(0xFFFFFFFF), sunGlow = c(0xFFBFF4FF), sunX = 0.62f, sunY = 0.22f,
                farRange = c(0xFFA9CFFF), midRange = c(0xFF6F9BEA), nearRange = c(0xFF3F5FB8),
                structure = c(0xFF34489A), structureLight = c(0xFFB8F3FF),
                accent = c(0xFF9FF6FF), accent2 = c(0xFFE2D1FF),
                ground = c(0xFF22306E), groundRim = c(0xFFA8F0FF),
                aurora = 0.35f, islands = 0.5f, towers = 0.6f, halo = 0.4f, stars = 0.15f
            ),
            Zone(
                name = "Aurora Heights",
                skyTop = c(0xFF2B2A7A), skyMid = c(0xFF8C6CE6), skyHorizon = c(0xFFFFC7E6),
                sun = c(0xFFFFF0FA), sunGlow = c(0xFFFFA8D8), sunX = 0.80f, sunY = 0.26f,
                farRange = c(0xFFC7A8F0), midRange = c(0xFF8A70C8), nearRange = c(0xFF55428F),
                structure = c(0xFF443676), structureLight = c(0xFFFFB8F0),
                accent = c(0xFF8CFFC9), accent2 = c(0xFFFF9FE0),
                ground = c(0xFF2C2458), groundRim = c(0xFFFFB0E8),
                aurora = 1f, islands = 0.6f, towers = 0.7f, halo = 0.5f, stars = 0.9f
            ),
            Zone(
                name = "Celestial Singularity",
                skyTop = c(0xFF5A3BB8), skyMid = c(0xFFFF8FB0), skyHorizon = c(0xFFFFE9A0),
                sun = c(0xFFFFFFF2), sunGlow = c(0xFFFFD36B), sunX = 0.55f, sunY = 0.24f,
                farRange = c(0xFFFFC2B8), midRange = c(0xFFD98AA8), nearRange = c(0xFF9A5C8E),
                structure = c(0xFF7A4A86), structureLight = c(0xFFFFF0A8),
                accent = c(0xFFFFE08A), accent2 = c(0xFFFFB8E0),
                ground = c(0xFF4A2C5E), groundRim = c(0xFFFFE3A0),
                aurora = 0.4f, islands = 0.8f, towers = 0.9f, halo = 1f, stars = 0.3f
            )
        )
    }
}

// ---------------------------------------------------------------------------------------
// File-private math helpers (allocation-free)
// ---------------------------------------------------------------------------------------

private const val PI_F = 3.1415927f
private const val TWO_PI = 6.2831855f

private fun c(argb: Long): Int = argb.toInt()

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

private fun lerpColor(a: Int, b: Int, t: Float): Int {
    val it = 1f - t
    val aa = (a ushr 24) and 0xFF; val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
    val ba = (b ushr 24) and 0xFF; val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
    return ((aa * it + ba * t + 0.5f).toInt() shl 24) or
        ((ar * it + br * t + 0.5f).toInt() shl 16) or
        ((ag * it + bg * t + 0.5f).toInt() shl 8) or
        (ab * it + bb * t + 0.5f).toInt()
}

/** Deterministic integer hash -> [0, 1). Same slot always yields the same tower/island. */
private fun hash(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7FFFFFFF) / 2147483648f
}
