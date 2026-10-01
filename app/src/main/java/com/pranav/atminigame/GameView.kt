package com.pranav.atminigame

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.KeyEvent
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private enum class UiScreen { TITLE, TUTORIAL, PLAYING, PAUSED, SETTINGS, ARCHIVE, LOADOUT, PHOTO, RUN_END, UPGRADE }
    private var uiScreen = UiScreen.TITLE
    private var endlessMode = false
    private var previousScreen = UiScreen.TITLE
    private val progress = PlayerProgress(context)
    private var tutorialPage = 0
    private var runSeed = Random.nextInt()
    private var runStartedAt = 0L
    private var runElapsed = 0f
    private var runDistance = 0f
    private var newRecord = false
    private var runEpoch = 0
    private var orbMagnet = 0f
    private var highJumpUpgrade = 0f
    private var endlessSegmentIndex = 0
    private var endlessDirector: EndlessRunDirector? = null
    private var nextEndlessOrbX = 0f
    private var lastMilestone = 0
    private var dailyChallenge = false
    private var requestedRunSeed: Int? = null
    private var cleanRun = true
    private var combo = 0
    private var bestCombo = 0
    private var jumpBuffer = 0f
    private var coyoteTime = 0f
    private var facingLeft = false
    private var walkAnimationPhase = 0f
    private var walkAnimationBlend = 0f
    private var dustTimer = 0f
    private var lastMusicZone = -1
    private var aiBusySinceNanos = 0L
    private var aiFallbackActive = false
    private var aiInitializationStarted = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val orbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val orbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spriteRect = RectF()
    private val sprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.atministanding)
    // Full 10-frame side-view walk cycle loop
    private val walkFrames: Array<Bitmap> = intArrayOf(
        R.drawable.atmini_walk_01, R.drawable.atmini_walk_02, R.drawable.atmini_walk_03,
        R.drawable.atmini_walk_04, R.drawable.atmini_walk_05, R.drawable.atmini_walk_06,
        R.drawable.atmini_walk_07, R.drawable.atmini_walk_08, R.drawable.atmini_walk_09,
        R.drawable.atmini_walk_10
    ).map { BitmapFactory.decodeResource(resources, it) }.toTypedArray()

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
            override fun onPlayerHit(harmonyLeft: Int) { cleanRun = false; combo = 0 }
            override fun onShieldBlocked(chargesLeft: Int) { cleanRun = false; combo = 0 }
        }
    }

    private fun syncCombatBody() {
        val b = combat.body
        b.x = x; b.y = y
        b.w = sprite.width * CHAR_SCALE; b.h = sprite.height * CHAR_SCALE
        b.vx = vx; b.vy = vy; b.grounded = grounded
        b.groundY = height - 250f
        b.facing = if (facingLeft) -1f else 1f
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
    private var gamePaused = false
    private var leftPressed = false
    private var rightPressed = false
    private var jumpPressed = false
    private var isAutoMode = false


    private enum class AiPhase { PICK_ORB, DECIDE_APPROACH, MOVING, DECIDE_JUMP, BUSY }
    private var aiPhase = AiPhase.PICK_ORB
    private var aiTargetX: Float? = null
    private var aiTargetY: Float? = null

    private data class Orb(
        var x: Float, var y: Float, var taken: Boolean = false,
        var isBig: Boolean = false, var bonus: Boolean = false
    )
    private val orbs = mutableListOf<Orb>()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 52f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        soundManager.soundEnabled = progress.effectsVolume > 0f
        soundManager.musicEnabled = progress.musicEnabled
        soundManager.hapticsEnabled = progress.hapticsEnabled
        soundManager.musicVolume = progress.musicVolume
        soundManager.effectsVolume = progress.effectsVolume
        worldBackground.reducedEffects = progress.reducedEffects
        worldBackground.reducedMotion = progress.reducedMotion
        soundManager.startMusic("landing")
    }

    private fun reset(endless: Boolean = endlessMode) {
        runEpoch++
        endlessMode = endless
        runSeed = requestedRunSeed ?: Random.nextInt()
        requestedRunSeed = null
        endlessDirector = if (endlessMode) EndlessRunDirector(runSeed) else null
        endlessSegmentIndex = 0
        lastMilestone = 0
        lastMusicZone = -1
        nextEndlessOrbX = 0f
        orbMagnet = 0f
        highJumpUpgrade = 0f
        runElapsed = 0f
        runDistance = 0f
        newRecord = false
        cleanRun = true
        combo = 0
        bestCombo = 0
        jumpBuffer = 0f
        coyoteTime = 0f
        facingLeft = false
        walkAnimationPhase = 0f
        walkAnimationBlend = 0f
        aiBusySinceNanos = 0L
        aiFallbackActive = false
        aiPhase = AiPhase.PICK_ORB
        aiTargetX = null
        aiTargetY = null
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
        if (!endlessMode) {
            val runRandom = Random(runSeed)
            for (i in 0 until 30) {
                orbs += Orb(420f + i * 260f, 430f + runRandom.nextInt(-80, 50), isBig = (i % 5 == 0))
            }
        }
        soundManager.playReset()
        particleSystem.clear()
        visualEffects.clear()
        if (endlessMode) {
            combat.reset(FloatArray(0), runSeed)
            appendEndlessSegments(width.coerceAtLeast(1280).toFloat() * 2.5f)
        } else {
            combat.reset(FloatArray(orbs.size) { orbs[it].x }, runSeed)
        }
        runStartedAt = System.nanoTime()
        uiScreen = if (!progress.tutorialSeen) UiScreen.TUTORIAL else UiScreen.PLAYING
        gamePaused = uiScreen != UiScreen.PLAYING
        if (uiScreen == UiScreen.TUTORIAL) tutorialPage = 0
        soundManager.playReset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dt = min(0.033f, (System.nanoTime() - lastNanos) / 1_000_000_000f)
        lastNanos = System.nanoTime()

        update(dt)
        worldBackground.update(dt, cameraX)
        worldBackground.exportPalette(effectPalette)
        applyCosmeticPalette()
        // Update particle system & visual effects timers
        particleSystem.update(dt)
        visualEffects.update(dt)
        drawWorld(canvas)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runEpoch++
        scope.cancel()
        worldBackground.release()
        combat.release()
        soundManager.release()
    }

    fun handleBackPressed(): Boolean {
        return when (uiScreen) {
            UiScreen.PLAYING -> { pauseRun(); true }
            UiScreen.PAUSED -> { uiScreen = UiScreen.PLAYING; gamePaused = false; true }
            UiScreen.SETTINGS -> { uiScreen = previousScreen; gamePaused = uiScreen != UiScreen.PLAYING; true }
            UiScreen.ARCHIVE, UiScreen.LOADOUT -> { uiScreen = UiScreen.TITLE; true }
            UiScreen.PHOTO -> { uiScreen = UiScreen.PAUSED; true }
            UiScreen.TUTORIAL -> {
                progress.tutorialSeen = true; uiScreen = UiScreen.PLAYING; gamePaused = false
                runStartedAt = System.nanoTime(); true
            }
            UiScreen.RUN_END -> { uiScreen = UiScreen.TITLE; gameOver = false; true }
            UiScreen.UPGRADE -> { uiScreen = UiScreen.PAUSED; gamePaused = true; true }
            else -> false
        }
    }

    fun onHostPaused() {
        if (uiScreen == UiScreen.PLAYING) {
            pauseRun()
        }
        soundManager.stopMusic()
    }

    private fun pauseRun() {
        uiScreen = UiScreen.PAUSED
        gamePaused = true
        leftPressed = false; rightPressed = false; jumpPressed = false
        runEpoch++
        aiPhase = AiPhase.PICK_ORB
        aiBusySinceNanos = 0L
        aiTargetX = null; aiTargetY = null
    }

    fun onHostResumed() {
        soundManager.resumeMusic()
    }

    private fun appendEndlessSegments(untilWorldX: Float) {
        val director = endlessDirector ?: return
        while (nextEndlessOrbX < untilWorldX) {
            val segment = director.segment(endlessSegmentIndex++)
            segment.orbs.forEach { spawn ->
                orbs += Orb(spawn.x, spawn.y, isBig = spawn.big, bonus = spawn.bonus)
            }
            val recoverySegment = segment.index % 8 == 7
            combat.appendEndlessChunk(
                if (recoverySegment) FloatArray(0) else FloatArray(segment.orbs.size) { segment.orbs[it].x },
                segment.bossX,
                runSeed xor segment.index
            )
            nextEndlessOrbX = segment.endX
        }
    }

    private fun beginRun(endless: Boolean, daily: Boolean = false) {
        dailyChallenge = daily
        requestedRunSeed = if (daily) localDailySeed() else null
        reset(endless)
        gamePaused = uiScreen != UiScreen.PLAYING
        runStartedAt = System.nanoTime()
        soundManager.startMusic(if (isAutoMode) "aimode" else "game")
    }

    private fun exitGame() {
        (context as? Activity)?.finish()
    }

    private fun applyCosmeticPalette() {
        val tint = when (progress.selectedCosmetic) {
            1 -> Color.rgb(70, 235, 255)
            2 -> Color.rgb(208, 125, 255)
            3 -> Color.rgb(255, 208, 92)
            else -> return
        }
        effectPalette.primary = blend(effectPalette.primary, tint, 0.42f)
        effectPalette.secondary = blend(effectPalette.secondary, Color.WHITE, 0.25f)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return (0xFF shl 24) or
            (((Color.red(a) * u + Color.red(b) * t).toInt() and 0xFF) shl 16) or
            (((Color.green(a) * u + Color.green(b) * t).toInt() and 0xFF) shl 8) or
            ((Color.blue(a) * u + Color.blue(b) * t).toInt() and 0xFF)
    }

    private fun localDailySeed(): Int {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return calendar.get(java.util.Calendar.YEAR) * 1000 + calendar.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun chooseUpgrade(choice: Int) {
        when (choice) {
            0 -> combat.grantShieldCharge()
            1 -> orbMagnet = min(MAX_ORB_MAGNET_RADIUS, orbMagnet + ORB_MAGNET_UPGRADE_RADIUS)
            2 -> highJumpUpgrade = min(0.3f, highJumpUpgrade + 0.12f)
        }
        gamePaused = false
        uiScreen = UiScreen.PLAYING
        runStartedAt = System.nanoTime() - (runElapsed * 1_000_000_000f).toLong()
    }

    private fun handleUiTap(px: Float, py: Float) {
        when (uiScreen) {
            UiScreen.TITLE -> when {
                py in height * 0.35f..height * 0.48f && px < width * 0.5f -> beginRun(false)
                py in height * 0.35f..height * 0.48f && px >= width * 0.5f -> beginRun(true)
                py in height * 0.50f..height * 0.61f -> beginRun(true, daily = true)
                py in height * 0.63f..height * 0.74f && px < width * 0.33f -> uiScreen = UiScreen.ARCHIVE
                py in height * 0.63f..height * 0.74f && px < width * 0.49f -> uiScreen = UiScreen.LOADOUT
                py in height * 0.63f..height * 0.74f && px < width * 0.65f -> { previousScreen = UiScreen.TITLE; uiScreen = UiScreen.SETTINGS }
                py in height * 0.63f..height * 0.74f -> exitGame()
                py > height * 0.75f -> toggleAutoMode()
            }
            UiScreen.TUTORIAL -> when {
                py in height * 0.63f..height * 0.74f -> if (tutorialPage == 0) tutorialPage = 1 else {
                    progress.tutorialSeen = true; uiScreen = UiScreen.PLAYING; gamePaused = false; runStartedAt = System.nanoTime()
                }
                py > height * 0.75f -> { progress.tutorialSeen = true; uiScreen = UiScreen.PLAYING; gamePaused = false; runStartedAt = System.nanoTime() }
            }
            UiScreen.PAUSED -> when {
                py in height * 0.30f..height * 0.41f -> { uiScreen = UiScreen.PLAYING; gamePaused = false; runStartedAt = System.nanoTime() - (runElapsed * 1_000_000_000f).toLong() }
                py in height * 0.42f..height * 0.53f -> { previousScreen = UiScreen.PAUSED; uiScreen = UiScreen.SETTINGS }
                py in height * 0.54f..height * 0.65f -> uiScreen = UiScreen.PHOTO
                py in height * 0.66f..height * 0.77f -> beginRun(endlessMode, dailyChallenge)
                py > height * 0.78f -> { uiScreen = UiScreen.TITLE; gamePaused = true; gameOver = false; soundManager.startMusic("landing") }
            }
            UiScreen.SETTINGS -> when {
                py in height * 0.25f..height * 0.36f -> {
                    if (px in width * 0.43f..width * 0.57f) progress.musicEnabled = !progress.musicEnabled
                    else progress.musicVolume = (progress.musicVolume + if (px > width * 0.5f) 0.1f else -0.1f).coerceIn(0f, 1f)
                    soundManager.musicVolume = progress.musicVolume
                    soundManager.musicEnabled = progress.musicEnabled
                    if (progress.musicEnabled) soundManager.resumeMusic() else soundManager.stopMusic()
                }
                py in height * 0.37f..height * 0.47f -> {
                    progress.effectsVolume = (progress.effectsVolume + if (px > width * 0.5f) 0.1f else -0.1f).coerceIn(0f, 1f)
                    soundManager.effectsVolume = progress.effectsVolume
                    soundManager.soundEnabled = progress.effectsVolume > 0f
                }
                py in height * 0.48f..height * 0.58f -> { progress.hapticsEnabled = !progress.hapticsEnabled; soundManager.hapticsEnabled = progress.hapticsEnabled }
                py in height * 0.59f..height * 0.69f -> { progress.reducedEffects = !progress.reducedEffects; worldBackground.reducedEffects = progress.reducedEffects }
                py in height * 0.70f..height * 0.80f -> {
                    progress.reducedMotion = !progress.reducedMotion
                    worldBackground.reducedMotion = progress.reducedMotion
                }
                py > height * 0.81f -> { uiScreen = previousScreen; gamePaused = uiScreen != UiScreen.PLAYING }
            }
            UiScreen.ARCHIVE -> if (py > height * 0.75f) uiScreen = UiScreen.TITLE
            UiScreen.LOADOUT -> when {
                py in height * 0.32f..height * 0.49f && px < width * 0.40f && progress.hasCosmetic(1) -> progress.selectedCosmetic = 1
                py in height * 0.32f..height * 0.49f && px < width * 0.60f && progress.hasCosmetic(2) -> progress.selectedCosmetic = 2
                py in height * 0.32f..height * 0.49f && progress.hasCosmetic(4) -> progress.selectedCosmetic = 3
                py in height * 0.50f..height * 0.67f && progress.hasCosmetic(1) -> progress.companionEnabled = !progress.companionEnabled
                py > height * 0.75f -> uiScreen = UiScreen.TITLE
            }
            UiScreen.PHOTO -> when {
                py in height * 0.40f..height * 0.63f -> sharePhoto()
                py > height * 0.64f -> uiScreen = UiScreen.PAUSED
            }
            UiScreen.UPGRADE -> when {
                py in height * 0.38f..height * 0.68f && px < width * 0.40f -> chooseUpgrade(0)
                py in height * 0.38f..height * 0.68f && px < width * 0.60f -> chooseUpgrade(1)
                py in height * 0.38f..height * 0.68f -> chooseUpgrade(2)
                py > height * 0.75f -> chooseUpgrade(-1)
            }
            UiScreen.RUN_END -> when {
                py in height * 0.62f..height * 0.74f && px < width * 0.50f -> beginRun(endlessMode, dailyChallenge)
                py in height * 0.62f..height * 0.74f -> shareRun()
                py > height * 0.75f -> { uiScreen = UiScreen.TITLE; gameOver = false; soundManager.startMusic("landing") }
            }
            UiScreen.PLAYING -> Unit
        }
    }

    private fun shareRun() {
        val cardUri = createSignalPrint()
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (cardUri != null) "image/png" else "text/plain"
            if (cardUri != null) {
                putExtra(android.content.Intent.EXTRA_STREAM, cardUri)
                clipData = android.content.ClipData.newUri(context.contentResolver, "Atmini Signal Print", cardUri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            putExtra(android.content.Intent.EXTRA_TEXT,
                " ${if (endlessMode) "ENDLESS ASCENSION" else "LUMINOUS ASCENSION"}\n" +
                    "Score $score • Distance ${runDistance.toInt()}m • Best streak $bestCombo\n" +
                    "Seed $runSeed • Can you beat my signal? #Atmini")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share your signal print").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sharePhoto() {
        try {
            val photo = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            drawWorld(Canvas(photo), includeUi = false)
            val dir = java.io.File(context.cacheDir, "signal-prints")
            if (!dir.exists() && !dir.mkdirs()) return
            val file = java.io.File(dir, "atmini-photo-${System.currentTimeMillis()}.png")
            file.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
            photo.recycle()
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, "Atmini Photo", uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share your Atmini moment").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Keep the frozen photo view available even if Android's share target is unavailable.
        }
    }

    private fun createSignalPrint(): android.net.Uri? {
        return try {
            val card = Bitmap.createBitmap(1200, 675, Bitmap.Config.ARGB_8888)
            val c = Canvas(card)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 1200f, 675f,
                intArrayOf(Color.rgb(16, 20, 60), effectPalette.primary, Color.rgb(16, 20, 60)),
                null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 1200f, 675f, p)
            p.shader = null
            p.color = Color.argb(180, 9, 12, 35)
            c.drawRoundRect(44f, 44f, 1156f, 631f, 38f, 38f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = effectPalette.secondary
            c.drawRoundRect(44f, 44f, 1156f, 631f, 38f, 38f, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); p.textAlign = Paint.Align.LEFT
            p.textSize = 58f
            c.drawText("SIGNAL PRINT", 92f, 136f, p)
            p.textSize = 33f; p.color = effectPalette.secondary
            c.drawText(if (endlessMode) "ENDLESS ASCENSION  •  LOOP ${(cameraX / EndlessRunDirector.WORLD_LOOP_LENGTH).toInt() + 1}" else "LUMINOUS ASCENSION", 96f, 195f, p)
            p.color = Color.WHITE; p.textSize = 38f
            c.drawText("SCORE   $score", 96f, 300f, p)
            c.drawText("DISTANCE   ${runDistance.toInt()} M", 96f, 366f, p)
            c.drawText("BEST STREAK   $bestCombo", 96f, 432f, p)
            p.color = effectPalette.primary; p.textSize = 28f
            c.drawText("SEED $runSeed     #ATMINI", 96f, 532f, p)
            p.alpha = 230
            c.drawBitmap(sprite, null, RectF(800f, 170f, 1080f, 570f), p)
            val dir = java.io.File(context.cacheDir, "signal-prints")
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = java.io.File(dir, "atmini-${runSeed}.png")
            file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            card.recycle()
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    private fun steerAiFallback() {
        val centerNow = x + sprite.width * CHAR_SCALE * 0.5f
        var target: Orb? = null
        var bestDistance = Float.MAX_VALUE
        for (orb in orbs) {
            if (orb.taken || orb.x < centerNow - 25f) continue
            val d = abs(orb.x - centerNow)
            if (d < bestDistance) { bestDistance = d; target = orb }
        }
        if (target == null) {
            leftPressed = false; rightPressed = false
            return
        }
        val center = x + sprite.width * CHAR_SCALE * 0.5f
        val dx = target.x - center
        leftPressed = dx < -24f
        rightPressed = dx > 24f
        if (grounded && target.y + 35f < y + sprite.height * CHAR_SCALE * 0.5f && abs(dx) < 520f) {
            jumpPressed = true
            jumpBuffer = JUMP_BUFFER_SECONDS
        }
    }

    private fun update(dt: Float) {
        if (gameOver || gamePaused || uiScreen != UiScreen.PLAYING) return
        runElapsed = (System.nanoTime() - runStartedAt) / 1_000_000_000f

        if (endlessMode) {
            appendEndlessSegments(cameraX + width * 2.7f + 2600f)
            orbs.removeAll { it.x < cameraX - 800f }
            val loop = (cameraX / EndlessRunDirector.WORLD_LOOP_LENGTH).toInt()
            if (loop > lastMilestone) {
                lastMilestone = loop
                runEpoch++
                aiPhase = AiPhase.PICK_ORB
                aiBusySinceNanos = 0L
                aiTargetX = null; aiTargetY = null
                if (isAutoMode) {
                    chooseUpgrade(if (combat.shieldChargesLeft == 0) 0 else 1)
                } else {
                    uiScreen = UiScreen.UPGRADE
                    gamePaused = true
                }
                return
            }
        }

        // Deterministic Kotlin steering keeps AI mode playable if local inference is
        // unavailable, busy, cancelled by lifecycle, or temporarily cannot answer.
        if (isAutoMode && aiPhase == AiPhase.BUSY && aiBusySinceNanos != 0L &&
            System.nanoTime() - aiBusySinceNanos > AI_CALL_TIMEOUT_NANOS
        ) {
            runEpoch++ // Ignore a late result from the timed-out request.
            aiFallbackActive = true
            aiPhase = AiPhase.PICK_ORB
            aiTargetX = null
            aiTargetY = null
            aiBusySinceNanos = 0L
        }
        if (isAutoMode && aiController?.isInitialized == true && !aiFallbackActive) {
            val actorCenterX = x + sprite.width * CHAR_SCALE * 0.5f
            val activeOrbs = if (aiPhase == AiPhase.PICK_ORB) {
                orbs.filter { !it.taken && it.x >= actorCenterX - 25f }.map { it.x to it.y }
            } else emptyList()

            when (aiPhase) {
                AiPhase.PICK_ORB -> {
                    if (activeOrbs.isNotEmpty() && aiController?.isInitialized == true) {
                        aiPhase = AiPhase.BUSY
                        aiBusySinceNanos = System.nanoTime()
                        val requestEpoch = runEpoch
                        val actorX = x; val actorY = y
                        scope.launch {
                            val target = aiController?.decideTargetOrb(actorX, actorY, activeOrbs)
                            withContext(Dispatchers.Main) {
                                if (requestEpoch == runEpoch && isAutoMode && uiScreen == UiScreen.PLAYING) {
                                    if (target != null) {
                                        aiTargetX = target.first
                                        aiTargetY = target.second
                                        aiPhase = AiPhase.DECIDE_APPROACH
                                    } else {
                                        // A valid model can still return no usable target. Stop
                                        // retrying it and hand the run to deterministic steering.
                                        aiFallbackActive = true
                                        aiPhase = AiPhase.PICK_ORB
                                    }
                                    aiBusySinceNanos = 0L
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
                        aiBusySinceNanos = System.nanoTime()
                        val requestEpoch = runEpoch
                        val actorX = x; val actorY = y
                        scope.launch {
                            val decision = aiController?.decideApproach(actorX, actorY, tx, ty)
                            withContext(Dispatchers.Main) {
                                if (requestEpoch == runEpoch && isAutoMode && uiScreen == UiScreen.PLAYING) {
                                    if (decision == "AWAY") {
                                        aiTargetX = null
                                        aiTargetY = null
                                        aiPhase = AiPhase.PICK_ORB
                                    } else {
                                        aiPhase = AiPhase.MOVING
                                    }
                                    aiBusySinceNanos = 0L
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
                        aiBusySinceNanos = System.nanoTime()
                        val requestEpoch = runEpoch
                        val actorX = x; val actorY = y
                        scope.launch {
                            val decision = aiController?.decideJumpOrSkip(actorX, actorY, tx, ty)
                            withContext(Dispatchers.Main) {
                                if (requestEpoch == runEpoch && isAutoMode && uiScreen == UiScreen.PLAYING) {
                                    if (decision == "JUMP" && grounded) {
                                        jumpPressed = true
                                        jumpBuffer = JUMP_BUFFER_SECONDS
                                    }
                                    aiTargetX = null
                                    aiTargetY = null
                                    aiPhase = AiPhase.PICK_ORB
                                    aiBusySinceNanos = 0L
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

        if (isAutoMode && (aiController?.isInitialized != true || aiFallbackActive)) {
            steerAiFallback()
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
            dustTimer = 0f
        } else {
            soundManager.playMove(direction > 0f)
            if (grounded) {
                dustTimer += dt
                if (dustTimer >= 0.14f) {
                    dustTimer = 0f
                    val charW = sprite.width * CHAR_SCALE
                    particleSystem.emitFootstepDust(x + charW / 2f, y + sprite.height * CHAR_SCALE, direction)
                }
            }
        }
        vx = vx.coerceIn(-700f, 700f)

        if (leftPressed || vx < -20f) {
            facingLeft = true
        } else if (rightPressed || vx > 20f) {
            facingLeft = false
        }

        val moving = abs(vx) > WALK_ANIMATION_MIN_SPEED
        if (grounded) {
            if (moving) {
                val strideProgress = (abs(vx) * dt / STRIDE_PIXELS) * walkFrames.size
                walkAnimationPhase = (walkAnimationPhase + strideProgress) % walkFrames.size
            } else {
                walkAnimationPhase = (walkAnimationPhase * (1f - dt * 8f)).coerceAtLeast(0f)
            }
        } else {
            // Airborne (jump): freeze pose if moving, decay to neutral pose if stationary
            if (!moving) {
                walkAnimationPhase = (walkAnimationPhase * (1f - dt * 8f)).coerceAtLeast(0f)
            }
        }
        val isWalkingOrAirborne = moving || !grounded
        val blendStep = dt / WALK_BLEND_SECONDS
        walkAnimationBlend = (walkAnimationBlend + if (isWalkingOrAirborne) blendStep else -blendStep).coerceIn(0f, 1f)

        jumpBuffer = (jumpBuffer - dt).coerceAtLeast(0f)
        coyoteTime = if (grounded) COYOTE_SECONDS else (coyoteTime - dt).coerceAtLeast(0f)
        if ((jumpPressed || jumpBuffer > 0f) && (grounded || coyoteTime > 0f)) {
            vy = -jumpVelocity * (1f + highJumpUpgrade)
            grounded = false
            jumpBuffer = 0f
            jumpPressed = false
            coyoteTime = 0f
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
        runDistance = max(runDistance, x)
        val zone = ((cameraX + width * 0.5f) / 1600f).toInt().coerceAtLeast(0) % 5
        if (zone != lastMusicZone) {
            lastMusicZone = zone
            progress.discoverZone(zone)
            soundManager.playWorldZone(zone)
        }

        val charCenterX = x + charW / 2f
        val charCenterY = y + charH / 2f
        for (orb in orbs) {
            if (orb.taken) continue
            var dx = charCenterX - orb.x
            var dy = charCenterY - orb.y
            var distance = sqrt(dx * dx + dy * dy)
            if (orbMagnet > 0f && distance < orbMagnet) {
                val previousOrbX = orb.x
                val pullSpeed = MAGNET_BASE_PULL_SPEED + (orbMagnet - distance) * MAGNET_PULL_FALLOFF
                val pullDistance = min(distance, pullSpeed * dt)
                if (distance > 0.001f) {
                    orb.x += dx / distance * pullDistance
                    orb.y += dy / distance * pullDistance
                }
                if (aiTargetX == previousOrbX) {
                    aiTargetX = orb.x
                    aiTargetY = orb.y
                }
                dx = charCenterX - orb.x
                dy = charCenterY - orb.y
                distance = sqrt(dx * dx + dy * dy)
            }
            if (abs(dx) < 65f && abs(dy) < 100f) {
                orb.taken = true
                score += if (orb.bonus) 10 else if (orb.isBig) 5 else 1
                orbsCollected++
                combo++
                bestCombo = max(bestCombo, combo)
                soundManager.playCollectOrb(orb.isBig)
                particleSystem.emitOrbCollect(orb.x, orb.y, orb.isBig)
                visualEffects.addShockwave(orb.x, orb.y, orb.isBig)
                visualEffects.addScorePopup(orb.x, orb.y - 30f, orb.isBig)
                if (isAutoMode && aiTargetX == orb.x) {
                    runEpoch++
                    aiTargetX = null; aiTargetY = null; aiPhase = AiPhase.PICK_ORB
                    aiBusySinceNanos = 0L
                }
            }
        }

        val wasGameOver = gameOver
        val allOrbsTaken = !endlessMode && orbs.isNotEmpty() && orbs.all { it.taken }
        syncCombatBody()
        combat.update(dt, cameraX, width)
        if (combat.body.consumeImpulse()) {
            vx = combat.body.impulseVx; vy = combat.body.impulseVy; grounded = false
        }
        score = max(0, score + combat.consumeScoreDelta())

        if (combat.isDefeated) cleanRun = false
        gameOver = combat.isDefeated || (allOrbsTaken && combat.isBossDefeated)
        if (gameOver && !wasGameOver && !combat.isDefeated) {
            soundManager.playGameComplete()
            particleSystem.emitVictoryShower(x + width * 0.2f, height * 0.3f)
        }
        if (gameOver && !wasGameOver) {
            val oldBest = if (endlessMode) progress.bestEndlessDistance else progress.bestCampaignScore
            progress.recordRun(score, runDistance.toInt(), endlessMode, combat.isBossDefeated,
                cleanRun, bestCombo, runElapsed.toInt())
            newRecord = if (endlessMode) runDistance.toInt() > oldBest else score > oldBest
            uiScreen = UiScreen.RUN_END
            gamePaused = true
        }
    }

    private fun drawWorld(canvas: Canvas, includeUi: Boolean = true) {
        worldBackground.draw(canvas, width, height, height - 250f)

        canvas.save()
        canvas.translate(-cameraX, 0f)

        // Draw Collectibles with pulsating neon halos
        for (orb in orbs) {
            if (orb.taken) continue
            val radius = if (orb.isBig) 40f else 28f
            val pulse = radius + sin(System.nanoTime() / 120_000_000.0).toFloat() * 4f

            val hue = if (orb.bonus) Color.rgb(255, 220, 120) else if (orb.isBig) Color.rgb(255, 120, 120) else Color.rgb(120, 220, 255)
            orbGlowPaint.color = hue
            orbGlowPaint.alpha = 82
            canvas.drawCircle(orb.x, orb.y, pulse, orbGlowPaint)
            orbCorePaint.color = hue
            orbCorePaint.alpha = 225
            canvas.drawCircle(orb.x, orb.y, if (orb.isBig) 13f else 9f, orbCorePaint)
            orbCorePaint.color = Color.WHITE
            orbCorePaint.alpha = 235
            canvas.drawCircle(orb.x - pulse * 0.16f, orb.y - pulse * 0.16f, if (orb.isBig) 4.5f else 3.5f, orbCorePaint)
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

        // Keep the walk-cycle pose, direction and cadence through jumps so the
        // character does not snap back to a front-facing idle frame mid-stride.
        val drawW = sprite.width * CHAR_SCALE
        val drawH = sprite.height * CHAR_SCALE
        val moving = abs(vx) > WALK_ANIMATION_MIN_SPEED
        val bob = if (grounded) {
            if (moving) {
                abs(sin((walkAnimationPhase / walkFrames.size) * (2f * Math.PI.toFloat()))) * -3.0f
            } else {
                0f
            }
        } else {
            if (progress.reducedMotion) 0f else sin(System.nanoTime() / 80_000_000.0).toFloat() * 4f
        }

        val phase = walkAnimationPhase % walkFrames.size
        val frameIdx1 = phase.toInt()
        val frameIdx2 = (frameIdx1 + 1) % walkFrames.size
        val frameFraction = phase - frameIdx1

        val frame1 = walkFrames[frameIdx1]
        val frame2 = walkFrames[frameIdx2]

        canvas.save()
        if (facingLeft) {
            canvas.scale(-1f, 1f, x + drawW / 2f, y + drawH / 2f)
        }
        spriteRect.set(x, y + bob, x + drawW, y + drawH)
        val playerAlpha = combat.playerAlpha()
        val totalWalkAlpha = playerAlpha * walkAnimationBlend

        if (walkAnimationBlend < 1f) {
            spritePaint.alpha = (playerAlpha * (1f - walkAnimationBlend)).toInt()
            canvas.drawBitmap(sprite, null, spriteRect, spritePaint)
        }
        if (totalWalkAlpha > 0f) {
            spritePaint.alpha = (totalWalkAlpha * (1f - frameFraction)).toInt()
            canvas.drawBitmap(frame1, null, spriteRect, spritePaint)

            spritePaint.alpha = (totalWalkAlpha * frameFraction).toInt()
            canvas.drawBitmap(frame2, null, spriteRect, spritePaint)
        }
        spritePaint.alpha = playerAlpha
        canvas.restore()

        if (progress.companionEnabled && progress.hasCosmetic(1)) {
            val floatOffset = if (progress.reducedMotion) 0f else sin(System.nanoTime() / 260_000_000.0).toFloat() * 9f
            orbGlowPaint.color = effectPalette.primary
            orbGlowPaint.alpha = 75
            canvas.drawLine(x + drawW, y + drawH * 0.33f, x + drawW + 22f, y + drawH * 0.33f + floatOffset, orbGlowPaint)
            canvas.drawCircle(x + drawW + 28f, y + drawH * 0.33f + floatOffset, 16f, orbGlowPaint)
            orbCorePaint.color = effectPalette.primary
            orbCorePaint.alpha = 230
            canvas.drawCircle(x + drawW + 28f, y + drawH * 0.33f + floatOffset, 8f, orbCorePaint)
        }

        combat.drawOverlay(canvas)

        // Floating Score Popups
        visualEffects.drawFloatingScores(canvas)

        canvas.restore()

        if (includeUi && uiScreen != UiScreen.PHOTO) {
        // HUD & UI Controls Overlay
        textPaint.textSize = 46f
        if (isAutoMode) {
            canvas.drawText("ATMINI • AI Sentinel 🔮", 28f, 58f, textPaint)
        } else {
            canvas.drawText("ATMINI", 28f, 58f, textPaint)
        }
        textPaint.textSize = 34f
        canvas.drawText(if (endlessMode) "Orbs: $orbsCollected" else "Orbs: $orbsCollected / 30", 30f, 102f, textPaint)
        canvas.drawText("Score: $score", 30f, 142f, textPaint)
        if (endlessMode) {
            val loop = (cameraX / EndlessRunDirector.WORLD_LOOP_LENGTH).toInt() + 1
            canvas.drawText("ASCENSION $loop  •  ${(runDistance / 1000f).toInt()} KM", 30f, 182f, textPaint)
        }

        combat.drawHud(canvas, width)
        if (combat.shieldChargesLeft > 0) {
            textPaint.textSize = 28f
            canvas.drawText("SHIELD ${combat.shieldChargesLeft}", width * 0.52f, 54f, textPaint)
        }

        if (uiScreen == UiScreen.PLAYING) {
            val bTop = height - 140f; val bBot = height - 20f
            drawButton(canvas, width * 0.02f, bTop, width * 0.13f, bBot, "◀")
            drawButton(canvas, width * 0.15f, bTop, width * 0.26f, bBot, "▶")
            drawButton(canvas, width * 0.42f, bTop, width * 0.58f, bBot, if (isAutoMode) "AI ON" else "AI")
            drawButton(canvas, width * 0.73f, bTop, width * 0.84f, bBot, "BLAST")
            drawButton(canvas, width * 0.86f, bTop, width * 0.97f, bBot, "JUMP")
            drawButton(canvas, width - 120f, 20f, width - 24f, 84f, "Ⅱ")
        }
        }

        if (includeUi) drawUiOverlay(canvas)
    }

    private fun drawUiOverlay(canvas: Canvas) {
        if (uiScreen == UiScreen.PLAYING) return
        paint.shader = null
        paint.color = Color.argb(205, 10, 12, 34)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val left = width * 0.16f; val right = width * 0.84f
        val top = height * 0.12f; val bottom = height * 0.90f
        paint.color = Color.argb(235, 20, 25, 58)
        canvas.drawRoundRect(left, top, right, bottom, 34f, 34f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(230, 100, 230, 255)
        canvas.drawRoundRect(left, top, right, bottom, 34f, 34f, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        when (uiScreen) {
            UiScreen.TITLE -> {
                drawCentered(canvas, "LUMINOUS ASCENSION", height * 0.22f, 48f)
                drawCentered(canvas, "Atmini world journey", height * 0.29f, 25f)
                drawButton(canvas, width * 0.20f, height * 0.35f, width * 0.48f, height * 0.47f, "STORY RUN")
                drawButton(canvas, width * 0.52f, height * 0.35f, width * 0.80f, height * 0.47f, "ENDLESS")
                drawButton(canvas, width * 0.28f, height * 0.50f, width * 0.72f, height * 0.60f, "DAILY ASCENSION")
                drawButton(canvas, width * 0.18f, height * 0.63f, width * 0.32f, height * 0.73f, "ARCHIVE")
                drawButton(canvas, width * 0.34f, height * 0.63f, width * 0.48f, height * 0.73f, "LOADOUT")
                drawButton(canvas, width * 0.50f, height * 0.63f, width * 0.64f, height * 0.73f, "SETTINGS")
                drawButton(canvas, width * 0.66f, height * 0.63f, width * 0.80f, height * 0.73f, "EXIT")
                drawButton(canvas, width * 0.32f, height * 0.76f, width * 0.68f, height * 0.85f, "AI MODE  ${if (isAutoMode) "ON" else "OFF"}")
            }
            UiScreen.TUTORIAL -> {
                drawCentered(canvas, if (tutorialPage == 0) "SYNC WITH ATMINI" else "READ THE SIGNALS", height * 0.25f, 44f)
                drawCentered(canvas, if (tutorialPage == 0) "HOLD ◀ / ▶ TO MOVE   •   TAP JUMP TO LEAP" else "DODGE WARNINGS  •  BLAST THREATS  •  COLLECT ORBS", height * 0.44f, 26f)
                drawCentered(canvas, "AI MODE CAN PLAY THE RUN FOR YOU; MANUAL CONTROLS ALWAYS WORK.", height * 0.54f, 21f)
                drawButton(canvas, width * 0.34f, height * 0.63f, width * 0.66f, height * 0.73f, if (tutorialPage == 0) "NEXT" else "START")
                drawButton(canvas, width * 0.38f, height * 0.76f, width * 0.62f, height * 0.84f, "SKIP")
            }
            UiScreen.PAUSED -> {
                drawCentered(canvas, "RUN PAUSED", height * 0.23f, 48f)
                drawButton(canvas, width * 0.32f, height * 0.30f, width * 0.68f, height * 0.40f, "RESUME")
                drawButton(canvas, width * 0.32f, height * 0.42f, width * 0.68f, height * 0.52f, "SETTINGS")
                drawButton(canvas, width * 0.32f, height * 0.54f, width * 0.68f, height * 0.64f, "PHOTO MODE")
                drawButton(canvas, width * 0.32f, height * 0.66f, width * 0.68f, height * 0.76f, "RESTART")
                drawButton(canvas, width * 0.32f, height * 0.78f, width * 0.68f, height * 0.87f, "TITLE")
            }
            UiScreen.SETTINGS -> {
                drawCentered(canvas, "ACCESS / SIGNAL SETTINGS", height * 0.20f, 36f)
                drawButton(canvas, width * 0.20f, height * 0.26f, width * 0.80f, height * 0.35f, "MUSIC  ${if (progress.musicEnabled) volumeLabel(progress.musicVolume) else "MUTED"}  ◀   ▶")
                drawButton(canvas, width * 0.20f, height * 0.37f, width * 0.80f, height * 0.46f, "EFFECTS  ${volumeLabel(progress.effectsVolume)}  ◀   ▶")
                drawButton(canvas, width * 0.20f, height * 0.48f, width * 0.80f, height * 0.57f, "HAPTICS  ${if (progress.hapticsEnabled) "ON" else "OFF"}")
                drawButton(canvas, width * 0.20f, height * 0.59f, width * 0.80f, height * 0.68f, "LOW EFFECTS  ${if (progress.reducedEffects) "ON" else "OFF"}")
                drawButton(canvas, width * 0.20f, height * 0.70f, width * 0.80f, height * 0.79f, "REDUCED MOTION  ${if (progress.reducedMotion) "ON" else "OFF"}")
                drawButton(canvas, width * 0.38f, height * 0.81f, width * 0.62f, height * 0.88f, "BACK")
            }
            UiScreen.UPGRADE -> {
                drawCentered(canvas, "ASCENSION $lastMilestone , CHOOSE A SIGNAL", height * 0.24f, 38f)
                drawButton(canvas, width * 0.18f, height * 0.38f, width * 0.37f, height * 0.66f, "SHIELD CHARGE")
                drawButton(canvas, width * 0.41f, height * 0.38f, width * 0.59f, height * 0.66f, "ORB MAGNET")
                drawButton(canvas, width * 0.63f, height * 0.38f, width * 0.82f, height * 0.66f, "PHASE LEAP")
                drawCentered(canvas, "MOVEMENT SPEED STAYS THE SAME. TAKE A BREATHER OR CHOOSE WHEN READY.", height * 0.72f, 19f)
                drawButton(canvas, width * 0.38f, height * 0.78f, width * 0.62f, height * 0.86f, "SKIP")
            }
            UiScreen.RUN_END -> {
                drawCentered(canvas, if (combat.isDefeated) "SIGNAL LOST" else "ASCENSION CLEARED", height * 0.22f, 40f)
                drawCentered(canvas, "SCORE  $score     DISTANCE  ${runDistance.toInt()} M", height * 0.33f, 28f)
                drawCentered(canvas, "STREAK  $bestCombo  •  TIME  ${runElapsed.toInt()} SEC  •  ${if (cleanRun) "CLEAN" else "HIT RECORDED"}", height * 0.41f, 21f)
                drawCentered(canvas, "PERSONAL BEST STREAK  ${progress.bestStreak}  •  CLEAN RUNS  ${progress.cleanRuns}", height * 0.48f, 20f)
                if (dailyChallenge) drawCentered(canvas, "DAILY SEED  $runSeed", height * 0.54f, 19f)
                drawCentered(canvas, if (newRecord) "NEW PERSONAL RECORD" else "BEST  ${if (endlessMode) progress.bestEndlessDistance else progress.bestCampaignScore}", height * 0.59f, 22f)
                drawButton(canvas, width * 0.20f, height * 0.65f, width * 0.48f, height * 0.75f, "RUN AGAIN")
                drawButton(canvas, width * 0.52f, height * 0.65f, width * 0.80f, height * 0.75f, "SHARE RUN")
                drawButton(canvas, width * 0.38f, height * 0.78f, width * 0.62f, height * 0.87f, "TITLE")
            }
            UiScreen.ARCHIVE -> {
                drawCentered(canvas, "ASCENSION ARCHIVE", height * 0.22f, 40f)
                val names = arrayOf("DAWN OF AWAKENING", "VERDANT ARCOLOGIES", "SEA OF LIGHT", "AURORA HEIGHTS", "CELESTIAL SINGULARITY")
                val lore = arrayOf("A city waits beneath rose-gold light.", "Gardens reconnect the old towers.", "Crystal spires carry a signal across the sea.", "The sky remembers how to sing.", "Atmini reaches the heart of the loop.")
                for (i in names.indices) {
                    val y = height * (0.31f + i * 0.08f)
                    drawCentered(canvas, if (progress.hasDiscoveredZone(i)) "${names[i]}  •  ${lore[i]}" else "SIGNAL ${i + 1}  •  UNDISCOVERED", y, 18f)
                }
                drawButton(canvas, width * 0.38f, height * 0.78f, width * 0.62f, height * 0.86f, "BACK")
            }
            UiScreen.LOADOUT -> {
                drawCentered(canvas, "SIGNAL LOADOUT", height * 0.22f, 40f)
                drawButton(canvas, width * 0.18f, height * 0.32f, width * 0.37f, height * 0.47f, if (progress.hasCosmetic(1)) "CYAN FLUX" else "LOCKED")
                drawButton(canvas, width * 0.41f, height * 0.32f, width * 0.59f, height * 0.47f, if (progress.hasCosmetic(2)) "VIOLET GHOST" else "LOCKED")
                drawButton(canvas, width * 0.63f, height * 0.32f, width * 0.82f, height * 0.47f, if (progress.hasCosmetic(4)) "SOLAR GOLD" else "LOCKED")
                drawButton(canvas, width * 0.28f, height * 0.51f, width * 0.72f, height * 0.64f, "COMPANION  ${if (progress.companionEnabled) "ON" else "OFF"}")
                drawCentered(canvas, "UNLOCK COSMETICS THROUGH RUNS, SCORE, AND BOSS WINS.", height * 0.71f, 19f)
                drawButton(canvas, width * 0.38f, height * 0.77f, width * 0.62f, height * 0.86f, "BACK")
            }
            UiScreen.PHOTO -> {
                drawCentered(canvas, "PHOTO MODE // FREEZE THE MOMENT", height * 0.25f, 36f)
                drawButton(canvas, width * 0.28f, height * 0.42f, width * 0.72f, height * 0.58f, "CAPTURE & SHARE")
                drawButton(canvas, width * 0.38f, height * 0.68f, width * 0.62f, height * 0.80f, "BACK")
            }
            UiScreen.PLAYING -> Unit
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCentered(canvas: Canvas, text: String, y: Float, size: Float) {
        textPaint.textSize = size
        canvas.drawText(text, width * 0.5f, y, textPaint)
    }

    private fun volumeLabel(value: Float) = "${(value * 100f).toInt()}%"

    private fun drawButton(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String) {
        paint.color = Color.argb(145, 35, 45, 60)
        canvas.drawRoundRect(l, t, r, b, 22f, 22f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(170, 150, 180, 210)
        canvas.drawRoundRect(l, t, r, b, 22f, 22f, paint)
        paint.style = Paint.Style.FILL
        val baseTextSize = if (label.length <= 5) 28f else 22f
        textPaint.textSize = baseTextSize
        val naturalTextWidth = textPaint.measureText(label)
        val allowedTextWidth = (r - l - 20f).coerceAtLeast(1f)
        if (naturalTextWidth > allowedTextWidth) {
            textPaint.textSize = baseTextSize * (allowedTextWidth / naturalTextWidth)
        }
        val fm = textPaint.fontMetrics
        val ty = t + (b - t) / 2f - (fm.ascent + fm.descent) / 2f
        val oldAlign = textPaint.textAlign
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, (l + r) * 0.5f, ty, textPaint)
        textPaint.textAlign = oldAlign
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (uiScreen != UiScreen.PLAYING) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                handleUiTap(width * 0.5f, height * 0.55f)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> leftPressed = true
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> rightPressed = true
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_W -> {
                jumpPressed = true; jumpBuffer = JUMP_BUFFER_SECONDS
            }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_F -> { syncCombatBody(); combat.fireManual() }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_START -> { uiScreen = UiScreen.PAUSED; gamePaused = true }
            KeyEvent.KEYCODE_BUTTON_Y -> toggleAutoMode()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> leftPressed = false
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> rightPressed = false
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_W -> jumpPressed = false
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    private fun toggleAutoMode() {
        setAutoMode(!isAutoMode)
    }

    private fun setAutoMode(enabled: Boolean) {
        runEpoch++
        isAutoMode = enabled
        aiBusySinceNanos = 0L
        aiFallbackActive = false
        if (isAutoMode && !aiInitializationStarted) {
            aiInitializationStarted = true
            scope.launch { aiController?.initialize() }
        }
        soundManager.playAiMode(isAutoMode)
        if (!isAutoMode) {
            aiPhase = AiPhase.PICK_ORB
            aiTargetX = null
            aiTargetY = null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIdx = event.actionIndex

        if (action == MotionEvent.ACTION_DOWN && uiScreen != UiScreen.PLAYING) {
            handleUiTap(event.getX(0), event.getY(0))
            return true
        }

        if (uiScreen != UiScreen.PLAYING) return true

        var newLeft = false
        var newRight = false

        for (p in 0 until event.pointerCount) {
            if ((action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) && p == actionIdx) {
                continue
            }
            val px = event.getX(p)
            val py = event.getY(p)

            val isNewPress = (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && p == actionIdx

            // Pause button (top right corner)
            if (isNewPress && px > width - 140f && py < 100f) {
                pauseRun()
                return true
            }

            // Directional & Action controls (bottom bar)
            if (py > height - 200f) {
                if (px < width * 0.135f) {
                    newLeft = true
                } else if (px in (width * 0.135f)..(width * 0.30f)) {
                    newRight = true
                } else if (px in (width * 0.38f)..(width * 0.62f)) {
                    if (isNewPress) {
                        toggleAutoMode()
                    }
                } else if (px in (width * 0.68f)..(width * 0.845f)) {
                    if (isNewPress) {
                        syncCombatBody()
                        combat.fireManual()
                    }
                } else if (px > width * 0.845f) {
                    if (isNewPress) {
                        jumpPressed = true
                        jumpBuffer = JUMP_BUFFER_SECONDS
                    }
                }
            }
        }

        leftPressed = newLeft
        rightPressed = newRight

        return true
    }

    companion object {
        private const val ORIGINAL_SCALE = 0.46f
        private const val CHAR_SCALE = 0.46f * 0.42f   // 25% smaller
        private const val JUMP_BUFFER_SECONDS = 0.12f
        private const val COYOTE_SECONDS = 0.10f
        private const val AI_CALL_TIMEOUT_NANOS = 2_000_000_000L
        private const val STRIDE_PIXELS = 360f
        private const val WALK_ANIMATION_MIN_SPEED = 55f
        private const val WALK_BLEND_SECONDS = 0.12f
        private const val MAX_ORB_MAGNET_RADIUS = 480f
        private const val ORB_MAGNET_UPGRADE_RADIUS = 240f
        private const val MAGNET_BASE_PULL_SPEED = 200f
        private const val MAGNET_PULL_FALLOFF = 2f
    }
}
