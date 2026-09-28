package com.pranav.atminigame

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.util.Log
import com.pranav.atminigame.audio.SoundManager
import com.pranav.atminigame.combat.CombatDirector
import com.pranav.atminigame.combat.CombatListener
import com.pranav.atminigame.combat.ThreatKind
import com.pranav.atminigame.effects.EffectPalette
import com.pranav.atminigame.effects.ParticleSystem
import com.pranav.atminigame.effects.VisualEffects
import com.pranav.atminigame.effects.WorldBackgroundRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * GameView incorporating procedural rich audio synthesis, haptics, particle systems,
 * and eye-catching visual effects (shockwaves, floating scores, AI HUD targeting, victory fireworks).
 */
class GameView(context: Context, var aiController: AiController?) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.atmini)

    // Raises the jump by exactly the height she lost, so every orb stays reachable.
    private val jumpVelocity: Float by lazy {
        val lostHeight = sprite.height * (ORIGINAL_SCALE - CHAR_SCALE)
        sqrt(1050f * 1050f + 2f * 2300f * (lostHeight / 2f))
    }

    // Sound and Effects subsystems
    private val soundManager = SoundManager(context)
    private val effectPalette = EffectPalette()
    private val particleSystem = ParticleSystem(effectPalette)
    private val visualEffects = VisualEffects(effectPalette)
    private val worldBackground = WorldBackgroundRenderer()
    private val combat = CombatDirector(particleSystem, visualEffects, effectPalette).apply {
        listener = object : CombatListener {
            override fun onThreatPurified(kind: ThreatKind) =
                soundManager.playCollectOrb(kind == ThreatKind.LAST_OVERSEER)
        }
    }

    private fun syncCombatBody() {
        val b = combat.body
        b.x = x; b.y = y
        b.w = sprite.width * CHAR_SCALE; b.h = sprite.height * CHAR_SCALE
        b.vx = vx; b.vy = vy; b.grounded = grounded
        b.groundY = height - 250f
        b.facing = if (vx < -20f) -1f else 1f
    }

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

    private val scope = CoroutineScope(Dispatchers.Default)

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
        y = 0f
        vx = 0f
        vy = 0f
        grounded = false
        score = 0
        orbsCollected = 0
        cameraX = 0f
        gameOver = false
        orbs.clear()
        for (i in 0 until 30) {
            orbs += Orb(420f + i * 260f, 430f + Random.nextInt(-80, 50), isBig = (i % 5 == 0))
        }
        soundManager.playReset()
        particleSystem.clear()
        visualEffects.clear()
        combat.reset(FloatArray(orbs.size) { orbs[it].x })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dt = min(0.033f, (System.nanoTime() - lastNanos) / 1_000_000_000f)
        lastNanos = System.nanoTime()

        update(dt)
        worldBackground.update(dt, cameraX)
        worldBackground.exportPalette(effectPalette)
        // Update particle system & visual effects timers
        particleSystem.update(dt)
        visualEffects.update(dt)
        drawWorld(canvas)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        worldBackground.release()
        combat.release()
    }

    private fun update(dt: Float) {
        if (gameOver) return

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
                        val charCenterX = x + (sprite.width * CHAR_SCALE) / 2f
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
                val charCenterX = x + (sprite.width * CHAR_SCALE) / 2f
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

        syncCombatBody()
        if (isAutoMode) {
            val reflex = combat.autoReflex()
            if (reflex.overrideMove) { leftPressed = reflex.moveDir < 0; rightPressed = reflex.moveDir > 0 }
            if (reflex.jump && grounded) jumpPressed = true
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
            val charW = sprite.width * CHAR_SCALE
            particleSystem.emitJumpThruster(x + charW / 2f, y + sprite.height * CHAR_SCALE)
        }
        vx = vx.coerceIn(-700f, 700f)

        if (jumpPressed && grounded) {
            vy = -jumpVelocity
            grounded = false
            jumpPressed = false
            soundManager.playJump()
            val charW = sprite.width * CHAR_SCALE
            val charH = sprite.height * CHAR_SCALE
            particleSystem.emitJumpThruster(x + charW / 2f, y + charH)
        }

        vy += 2300f * dt
        x += vx * dt
        y += vy * dt

        val groundY = height - 250f
        val charH = sprite.height * CHAR_SCALE
        val charW = sprite.width * CHAR_SCALE

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
            if (!orb.taken && abs(orb.x - charCenterX) < 65f && abs(orb.y - charCenterY) < 100f) {
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
        val allOrbsTaken = orbs.isNotEmpty() && orbs.all { it.taken }
        syncCombatBody()
        combat.update(dt, cameraX, width)
        if (combat.body.consumeImpulse()) {
            vx = combat.body.impulseVx; vy = combat.body.impulseVy; grounded = false
        }
        score = max(0, score + combat.consumeScoreDelta())

        gameOver = combat.isDefeated || (allOrbsTaken && combat.isBossDefeated)
        if (gameOver && !wasGameOver && !combat.isDefeated) {
            soundManager.playGameComplete()
            particleSystem.emitVictoryShower(x + width * 0.2f, height * 0.3f)
        }
    }

    private fun drawWorld(canvas: Canvas) {
        worldBackground.draw(canvas, width, height, height - 250f)

        canvas.save()
        canvas.translate(-cameraX, 0f)

        // Draw Collectibles with pulsating neon halos
        for (orb in orbs) {
            if (orb.taken) continue
            val radius = if (orb.isBig) 40f else 28f
            val pulse = radius + sin(System.nanoTime() / 120_000_000.0).toFloat() * 4f

            paint.shader = RadialGradient(
                orb.x, orb.y, pulse,
                intArrayOf(Color.WHITE, if (orb.isBig) Color.rgb(255, 120, 120) else Color.rgb(120, 220, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(orb.x, orb.y, pulse, paint)
            paint.shader = null
        }

        combat.draw(canvas)

        // Draw Visual Effects (Shockwaves & Particles)
        visualEffects.drawShockwaves(canvas)
        particleSystem.draw(canvas)

        // Draw AI Targeting Laser if in Auto Mode
        if (isAutoMode && aiTargetX != null && aiTargetY != null) {
            val charW = sprite.width * CHAR_SCALE
            val charH = sprite.height * CHAR_SCALE
            visualEffects.drawAiTargetingLaser(canvas, x + charW / 2f, y + charH / 2f, aiTargetX!!, aiTargetY!!)
        }

        // Atmini Sprite (Squash, Stretch & Flip)
        val drawW = sprite.width * CHAR_SCALE
        val drawH = sprite.height * CHAR_SCALE
        val bob = if (grounded) 0f else sin(System.nanoTime() / 80_000_000.0).toFloat() * 4f
        canvas.save()
        if (vx < -20f) {
            canvas.scale(-1f, 1f, x + drawW / 2f, y + drawH / 2f)
        }
        val dst = RectF(x, y + bob, x + drawW, y + drawH)
        spritePaint.alpha = combat.playerAlpha()
        canvas.drawBitmap(sprite, null, dst, spritePaint)
        canvas.restore()

        combat.drawOverlay(canvas)

        // Floating Score Popups
        visualEffects.drawFloatingScores(canvas)

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

        combat.drawHud(canvas, width)

        // On-screen Buttons
        drawButton(canvas, 20f, height - 160f, 140f, height - 20f, "◀")
        drawButton(canvas, 160f, height - 160f, 280f, height - 20f, "▶")
        drawButton(canvas, width / 2f - 75f, height - 160f, width / 2f + 75f, height - 20f, if (isAutoMode) "AUTO [ON]" else "AUTO")
        drawButton(canvas, width - 400f, height - 160f, width - 220f, height - 20f, "BLAST")
        drawButton(canvas, width - 200f, height - 160f, width - 20f, height - 20f, "JUMP")

        if (gameOver) {
            paint.color = Color.argb(120, 20, 10, 40)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            textPaint.textSize = 60f
            val title = if (combat.isDefeated) "ATMINI RESTS" else "RUN COMPLETE"
            val tw = textPaint.measureText(title)
            canvas.drawText(title, width / 2f - tw / 2f, height / 2f - 20f, textPaint)
            textPaint.textSize = 34f
            val subTw = textPaint.measureText("Tap anywhere to play again")
            canvas.drawText("Tap anywhere to play again", width / 2f - subTw / 2f, height / 2f + 40f, textPaint)
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
        textPaint.textSize = if (label == "JUMP" || label == "BLAST" || label.contains("AUTO")) 24f else 48f
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
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    px in (width - 400f)..(width - 220f) && py > height - 200f) {
                    syncCombatBody(); combat.fireManual()
                }
                leftPressed = px < 140f && py > height - 200f
                rightPressed = px in 160f..280f && py > height - 200f
                if (px in (width / 2f - 75f)..(width / 2f + 75f) && py > height - 200f) {
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

    companion object {
        private const val ORIGINAL_SCALE = 0.46f
        private const val CHAR_SCALE = 0.46f * 0.42f   // 25% smaller
    }
}