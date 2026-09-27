package com.pranav.atminigame

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import com.pranav.atminigame.audio.SoundManager
import com.pranav.atminigame.effects.ParticleSystem
import com.pranav.atminigame.effects.VisualEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * GameView incorporating procedural rich audio synthesis, haptics, particle systems,
 * and eye-catching visual effects (shockwaves, floating scores, AI HUD targeting, victory fireworks).
 */
class GameView(context: Context, var aiController: AiController?) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val characterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 255
    }
    private val sprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.atmini)

    private val density = resources.displayMetrics.density
    private fun dpToPx(dp: Float): Float = dp * density

    private val charH = dpToPx(110f)
    private val charW = charH * (sprite.width.toFloat() / sprite.height.toFloat())

    // Sound and Effects subsystems
    private val soundManager = SoundManager(context)
    private val particleSystem = ParticleSystem()
    private val visualEffects = VisualEffects()

    private var lastNanos = System.nanoTime()
    private var x = 180f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var grounded = false
    private var score = 0
    private var orbsCollected = 0
    private var cameraX = 0f
    private var gameOver = false
    private var leftPressed = false
    private var rightPressed = false
    private var jumpPressed = false
    private var isAutoMode = false

    private var lastAiCallNanos = 0L
    private val aiCallIntervalNanos = 350_000_000L // 350ms AI cadence

    private enum class AiPhase { PICK_ORB, DECIDE_APPROACH, MOVING, DECIDE_JUMP, BUSY }
    private var aiPhase = AiPhase.PICK_ORB
    private var aiTargetX: Float? = null
    private var aiTargetY: Float? = null

    private data class Orb(var x: Float, var y: Float, var taken: Boolean = false, var isBig: Boolean = false)
    private val orbs = mutableListOf<Orb>()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 52f
    }

    // Vector drawables for background objects
    private val towerDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_tower)
    private val cyberTreeDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_cyber_tree)
    private val orbDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_orb)
    private val floorDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_floor)
    private val skyDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_sky)
    private val cloudDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_cloud)

    private val scope = CoroutineScope(Dispatchers.Default)

    // Animation controllers loaded from res/anim/
    private val orbPulseAnimation = AnimationUtils.loadAnimation(context, R.anim.orb_pulse_anim)
    private val beaconBlinkAnimation = AnimationUtils.loadAnimation(context, R.anim.beacon_blink_anim)

    init {
        isFocusable = true
        scope.launch {
            aiController?.initialize()
        }
        reset()
        soundManager.startMusic("landing")
    }

    private fun reset() {
        x = 180f
        vx = 0f
        vy = 0f
        score = 0
        orbsCollected = 0
        cameraX = 0f
        gameOver = false
        if (height > 0) {
            val groundY = height - 250f
            y = groundY - charH
            grounded = true
        } else {
            y = 0f
            grounded = false
        }
        orbs.clear()
        for (i in 0 until 30) {
            orbs += Orb(420f + i * 260f, 430f + Random.nextInt(-80, 50), isBig = (i % 5 == 0))
        }
        soundManager.playReset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dt = min(0.033f, (System.nanoTime() - lastNanos) / 1_000_000_000f)
        lastNanos = System.nanoTime()

        update(dt)
        drawWorld(canvas)
        postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        if (gameOver) return

        // Update particle system & visual effects timers
        particleSystem.update(dt)
        visualEffects.update(dt)

        if (isAutoMode) {
            val activeOrbs = orbs.filter { !it.taken }.map { it.x to it.y }

            when (aiPhase) {
                AiPhase.PICK_ORB -> {
                    if (activeOrbs.isNotEmpty() && aiController?.isInitialized == true) {
                        aiPhase = AiPhase.BUSY
                        scope.launch {
                            val target = aiController?.decideTargetOrb(x, y, activeOrbs)
                            withContext(Dispatchers.Main) {
                                if (isAutoMode) {
                                    if (target != null) {
                                        aiTargetX = target.first
                                        aiTargetY = target.second
                                        aiPhase = AiPhase.DECIDE_APPROACH
                                    } else {
                                        aiPhase = AiPhase.PICK_ORB
                                    }
                                }
                            }
                        }
                    }
                }

                AiPhase.DECIDE_APPROACH -> {
                    val tx = aiTargetX
                    val ty = aiTargetY
                    if (tx != null && ty != null && aiController?.isInitialized == true) {
                        aiPhase = AiPhase.BUSY
                        scope.launch {
                            val decision = aiController?.decideApproach(x, y, tx, ty)
                            withContext(Dispatchers.Main) {
                                if (isAutoMode) {
                                    if (decision == "AWAY") {
                                        aiTargetX = null
                                        aiTargetY = null
                                        aiPhase = AiPhase.PICK_ORB
                                    } else {
                                        aiPhase = AiPhase.MOVING
                                    }
                                }
                            }
                        }
                    }
                }

                AiPhase.MOVING -> {
                    val tx = aiTargetX
                    val ty = aiTargetY
                    if (tx == null || ty == null) {
                        aiPhase = AiPhase.PICK_ORB
                    } else {
                        val charCenterX = x + 60f
                        val dxToTarget = tx - charCenterX
                        if (abs(dxToTarget) <= 25f) {
                            vx = 0f
                            leftPressed = false
                            rightPressed = false
                            aiPhase = AiPhase.DECIDE_JUMP
                        }
                    }
                }

                AiPhase.DECIDE_JUMP -> {
                    val tx = aiTargetX
                    val ty = aiTargetY
                    if (tx != null && ty != null && aiController?.isInitialized == true) {
                        aiPhase = AiPhase.BUSY
                        scope.launch {
                            val decision = aiController?.decideJumpOrSkip(x, y, tx, ty)
                            withContext(Dispatchers.Main) {
                                if (isAutoMode) {
                                    if (decision == "JUMP" && grounded) {
                                        jumpPressed = true
                                    }
                                    aiTargetX = null
                                    aiTargetY = null
                                    aiPhase = AiPhase.PICK_ORB
                                }
                            }
                        }
                    }
                }

                AiPhase.BUSY -> {
                    // waiting on an in-flight AI call, do nothing this frame
                }
            }

            // Kotlin steering: runs every frame while MOVING, capped exactly at target x
            val tx = aiTargetX
            val ty = aiTargetY
            if (aiPhase == AiPhase.MOVING && tx != null && ty != null) {
                val charCenterX = x + 60f
                val dxToTarget = tx - charCenterX
                val absDx = abs(dxToTarget)
                when {
                    absDx <= 25f -> {
                        vx = 0f
                        leftPressed = false
                        rightPressed = false
                    }
                    else -> {
                        leftPressed = dxToTarget < 0
                        rightPressed = dxToTarget > 0
                    }
                }
            } else {
                leftPressed = false
                rightPressed = false
            }
        }

        val direction = when {
            leftPressed && !rightPressed -> -1f
            rightPressed && !leftPressed -> 1f
            else -> 0f
        }
        vx += direction * 1900f * dt
        if (direction == 0f) {
            vx *= 0.82f
        } else {
            soundManager.playMove(direction > 0f)
            // Emit slight running dust particles
            particleSystem.emitJumpThruster(x + charW / 2f, y + charH)
        }
        vx = vx.coerceIn(-700f, 700f)

        if (jumpPressed && grounded) {
            vy = -1050f
            grounded = false
            jumpPressed = false
            soundManager.playJump()
            particleSystem.emitJumpThruster(x + charW / 2f, y + charH)
        }

        vy += 2300f * dt
        x += vx * dt
        y += vy * dt

        val groundY = height - 250f
        if (height > 0 && y == 0f) {
            y = groundY - charH
            grounded = true
        }

        if (y + charH >= groundY) {
            y = groundY - charH
            vy = 0f
            if (!grounded) {
                // Landing shockwave & step sound
                visualEffects.addShockwave(x + charW / 2f, groundY, isBig = false)
            }
            grounded = true
        }

        x = max(0f, x)
        cameraX = max(0f, x - width * 0.35f)

        val charCenterX = x + charW / 2f
        val charCenterY = y + charH / 2f
        for (orb in orbs) {
            // Expanded collision bounds so orbs are reliably collected when jumping through/over them
            if (!orb.taken && abs(orb.x - charCenterX) < 95f && abs(orb.y - charCenterY) < 110f) {
                orb.taken = true
                score += if (orb.isBig) 5 else 1
                orbsCollected++
                soundManager.playCollectOrb(orb.isBig)
                particleSystem.emitOrbCollect(orb.x, orb.y, orb.isBig)
                visualEffects.addShockwave(orb.x, orb.y, orb.isBig)
                visualEffects.addScorePopup(orb.x, orb.y - 30f, orb.isBig)
            }
        }

        val wasGameOver = gameOver
        gameOver = orbs.isNotEmpty() && orbs.all { it.taken }
        if (gameOver && !wasGameOver) {
            soundManager.playGameComplete()
            particleSystem.emitVictoryShower(x + width * 0.2f, height * 0.3f)
        }
    }

    private fun drawWorld(canvas: Canvas) {
        // Draw Background Sky Vector
        skyDrawable?.let { sky ->
            sky.setBounds(0, 0, width, height)
            sky.draw(canvas)
        }

        canvas.save()
        // Parallax background soft glowing cyberpunk nebula clouds using cloud vector
        canvas.translate(-cameraX * 0.1f, 0f)
        for (i in -1..10) {
            val cx = i * 480f + sin((System.nanoTime() + i * 150_000_000L) / 500_000_000.0).toFloat() * 25f
            val cy = 80f + (i * 97f) % 180f
            cloudDrawable?.let { cloud ->
                cloud.setBounds(cx.toInt(), cy.toInt(), (cx + 220f).toInt(), (cy + 120f).toInt())
                cloud.draw(canvas)
            }
        }
        canvas.restore()

        canvas.save()
        // Parallax background skyline (towers & cyber-trees vectors)
        canvas.translate(-cameraX * 0.25f, 0f)
        val groundY = height - 250f
        for (i in -2..15) {
            val twx = i * 450f
            val twh = 320f + (i * 73) % 180f
            towerDrawable?.let { tower ->
                tower.setBounds(twx.toInt(), (groundY - twh).toInt(), (twx + 220f).toInt(), groundY.toInt())
                tower.draw(canvas)
            }
            
            if (i % 2 == 0) {
                cyberTreeDrawable?.let { tree ->
                    tree.setBounds((twx + 300f).toInt(), (groundY - 210f).toInt(), (twx + 360f).toInt(), groundY.toInt())
                    tree.draw(canvas)
                }
            }
        }
        canvas.restore()

        canvas.save()
        canvas.translate(-cameraX, 0f)

        // Industrial Metal Floor using floor vector (repeating across the entire world width)
        floorDrawable?.let { floor ->
            for (i in -5..50) {
                val fx = i * 180f
                floor.setBounds(fx.toInt(), groundY.toInt(), (fx + 180f).toInt(), (groundY + 250f).toInt())
                floor.draw(canvas)
            }
        }

        // Draw Soul-like Ethereal Orbs using orb vector with XML animation scaling transformation
        val orbTransformation = Transformation()
        val currentAnimationTime = AnimationUtils.currentAnimationTimeMillis()
        orbPulseAnimation.getTransformation(currentAnimationTime, orbTransformation)
        val orbScaleFactor = orbTransformation.matrix.let { m ->
            val values = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            m.getValues(values)
            values[Matrix.MSCALE_X]
        }.let { if (it > 0f) it else 1.0f }

        for (orb in orbs) {
            if (orb.taken) continue
            val soulBob = sin(System.nanoTime() / 150_000_000.0 + orb.x).toFloat() * 9f
            val currentOrbY = orb.y + soulBob

            val baseRadius = if (orb.isBig) 48f else 34f
            val radius = baseRadius * orbScaleFactor
            orbDrawable?.let { orbVec ->
                orbVec.setBounds((orb.x - radius).toInt(), (currentOrbY - radius).toInt(), (orb.x + radius).toInt(), (currentOrbY + radius).toInt())
                orbVec.draw(canvas)
            }
        }

        // Draw Visual Effects (Shockwaves & Particles)
        visualEffects.drawShockwaves(canvas)
        particleSystem.draw(canvas)

        // Draw AI Targeting Laser if in Auto Mode
        if (isAutoMode && aiTargetX != null && aiTargetY != null) {
            visualEffects.drawAiTargetingLaser(canvas, x + charW / 2f, y + charH / 2f, aiTargetX!!, aiTargetY!!)
        }

        // Floating Score Popups
        visualEffects.drawFloatingScores(canvas)

        canvas.restore()

        // Atmini Character drawn cleanly on true foreground canvas with exact native aspect ratio
        val bob = if (grounded) 0f else sin(System.nanoTime() / 80_000_000.0).toFloat() * 4f
        val screenAtminiX = x - cameraX
        val dst = RectF(screenAtminiX, y + bob, screenAtminiX + charW, y + bob + charH)

        canvas.save()
        if (vx < -20f) {
            canvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
        }
        canvas.drawBitmap(sprite, null, dst, characterPaint)
        canvas.restore()

        // HUD & UI Controls Overlay
        textPaint.textSize = 46f
        if (isAutoMode) {
            canvas.drawText("ATMINI • AI Sentinel 🔮", 28f, 58f, textPaint)
        } else {
            canvas.drawText("ATMINI", 28f, 58f, textPaint)
        }
        textPaint.textSize = 34f
        canvas.drawText("Orbs: $orbsCollected / 30", 30f, 102f, textPaint)
        canvas.drawText("Score: $score", 30f, 142f, textPaint)

        // On-screen Buttons
        drawButton(canvas, 20f, height - 160f, 140f, height - 20f, "◀")
        drawButton(canvas, 160f, height - 160f, 280f, height - 20f, "▶")
        drawButton(canvas, 300f, height - 160f, 420f, height - 20f, if (isAutoMode) "AUTO [ON]" else "AUTO")
        drawButton(canvas, width - 200f, height - 160f, width - 20f, height - 20f, "JUMP")

        if (gameOver) {
            paint.color = Color.argb(180, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            textPaint.textSize = 60f
            canvas.drawText("RUN COMPLETE", width / 2f - 205f, height / 2f - 20f, textPaint)
            textPaint.textSize = 34f
            canvas.drawText("Tap anywhere to play again", width / 2f - 190f, height / 2f + 40f, textPaint)
        }
    }

    private fun drawButton(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String) {
        paint.color = Color.argb(145, 35, 45, 60)
        canvas.drawRoundRect(l, t, r, b, 26f, 26f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(170, 150, 180, 210)
        canvas.drawRoundRect(l, t, r, b, 26f, 26f, paint)
        paint.style = Paint.Style.FILL
        textPaint.textSize = if (label == "JUMP" || label.contains("AUTO")) 24f else 48f
        val tw = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        val ty = t + (b - t) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, (l + r - tw) / 2f, ty, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (gameOver) return true
                val px = event.x
                val py = event.y
                leftPressed = px < 140f && py > height - 200f
                rightPressed = px in 160f..280f && py > height - 200f
                if (px in 300f..420f && py > height - 200f) {
                    // Toggle event triggered once on down
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        isAutoMode = !isAutoMode
                        soundManager.playAiMode(isAutoMode)
                        if (!isAutoMode) {
                            aiPhase = AiPhase.PICK_ORB
                            aiTargetX = null
                            aiTargetY = null
                        }
                    }
                }
                if (px > width - 200f && py > height - 200f) jumpPressed = true
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gameOver) {
                    reset()
                    return true
                }
                leftPressed = false
                rightPressed = false
                return true
            }
        }
        return true
    }
}
