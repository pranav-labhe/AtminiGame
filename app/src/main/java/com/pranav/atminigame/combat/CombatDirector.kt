package com.pranav.atminigame.combat

import android.graphics.Canvas
import com.pranav.atminigame.effects.EffectPalette
import com.pranav.atminigame.effects.ParticleSystem
import com.pranav.atminigame.effects.VisualEffects
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Single entry point for everything combat: threat spawning, simulation, collisions,
 * the eye laser, harmony (health), the auto-mode reflex and all combat drawing.
 *
 * GameView integration (see the integration notes):
 *   reset()        -> combat.reset(orbXs)
 *   every frame    -> fill combat.body, call autoReflex() (auto mode) and update()
 *   drawing        -> draw() before Atmini, drawOverlay() after her, drawHud() in screen space
 *
 * Difficulty follows the world's five zones (1600 world px each, matching
 * WorldBackgroundRenderer.ZONE_LENGTH):
 *   I   Rust Shards
 *   II  + Glitch Drones
 *   III + Firewall Arcs, Falling Monoliths
 *   IV+ + Shadow Wisps, Rust Crawlers
 *   end   The Last Overseer guards the final stretch
 *
 * Zero allocations per frame: all threats, pulses and popups are pooled.
 */
class CombatDirector(
    private val particles: ParticleSystem,
    private val effects: VisualEffects,
    palette: EffectPalette,
    var listener: CombatListener? = null
) {
    /** Written by GameView every frame (world coordinates). */
    val body = CombatBody()

    var harmony = MAX_HARMONY
        private set
    val maxHarmony: Int get() = MAX_HARMONY
    val shieldChargesLeft: Int get() = shieldCharges
    var isDefeated = false
        private set
    /** True when the final guardian has been purified (or the run has no guardian). */
    var isBossDefeated = true
        private set
    val isLaserReady: Boolean get() = laser.isReady
    /** Kind of the threat that last hit Atmini (analytics / death screen tips). */
    var lastHitBy: ThreatKind? = null
        private set

    private val threats = Array(MAX_PLANNED) { Threat() }
    private val pulses = Array(MAX_PULSES) { Threat() }
    private var boss: Threat? = null

    private val laser = EyeLaser()
    private val reflex = AutoReflex()
    private val painter = ThreatPainter(palette)
    private val command = ReflexCommand()

    private var time = 0f
    private var invuln = 0f
    private var shieldCharges = 0
    private var pendingScore = 0
    private var cameraX = 0f
    private var viewW = 0

    // Popup pool
    private val popX = FloatArray(MAX_POPUPS)
    private val popY = FloatArray(MAX_POPUPS)
    private val popLife = FloatArray(MAX_POPUPS)
    private val popText = IntArray(MAX_POPUPS)
    private val popColor = IntArray(MAX_POPUPS)
    private var popCount = 0

    // =====================================================================================
    // Run lifecycle
    // =====================================================================================

    /**
     * Builds this run's threat layout. Call at the end of GameView.reset(), after orbs exist.
     * Ground hazards keep clear of orb columns so no orb ever sits above a hazard.
     */
    fun reset(orbXs: FloatArray, seed: Int = Random.nextInt()) {
        for (t in threats) t.clear()
        for (p in pulses) p.clear()
        boss = null
        harmony = MAX_HARMONY
        isDefeated = false
        invuln = 0f
        shieldCharges = 0
        pendingScore = 0
        popCount = 0
        laser.reset()
        lastHitBy = null

        if (orbXs.isEmpty()) {
            isBossDefeated = true
            return
        }

        // Threats sit at the midpoints between neighbouring orbs, so every orb column
        // stays clear and orbs never hover directly above a hazard.
        val sorted = orbXs.copyOf().also { it.sort() }   // once per run, not per frame
        val lastOrb = sorted[sorted.size - 1]
        val rnd = Random(seed)
        var lastGroundSlot = -10
        var n = 0
        var slot = 0
        while (slot < sorted.size - 1 && n < MAX_PLANNED - 1) {
            val a = sorted[slot]
            val b = sorted[slot + 1]
            val x = (a + b) * 0.5f
            if (x >= FIRST_THREAT_X) {
                val zone = ((x / ZONE_LENGTH).toInt()) % ZONE_COUNT
                var kind: ThreatKind? = pickKind(zone, rnd.nextFloat())
                val roomy = b - a >= MIN_ORB_GAP_FOR_GROUND
                if (kind != null && isGroundHazard(kind) &&
                    (!roomy || slot - lastGroundSlot < MIN_GROUND_SLOT_GAP)
                ) {
                    kind = if (zone >= 1 && rnd.nextFloat() < 0.5f) ThreatKind.GLITCH_DRONE else null
                }
                if (kind != null) {
                    spawnPlanned(threats[n++], kind, x, rnd)
                    if (isGroundHazard(kind)) lastGroundSlot = slot
                }
            }
            slot += if (rnd.nextFloat() < SKIP_SLOT_CHANCE) 2 else 1
        }

        val guardian = threats[n]
        spawnPlanned(guardian, ThreatKind.LAST_OVERSEER, lastOrb + BOSS_OFFSET, rnd)
        boss = guardian
        isBossDefeated = false
    }

    /** Add one deterministic, bounded endless chunk; expired threats are recycled in update(). */
    fun appendEndlessChunk(orbXs: FloatArray, bossX: Float?, seed: Int) {
        if (orbXs.size > 1) {
            val sorted = orbXs.copyOf().also { it.sort() }
            val rnd = Random(seed)
            var lastGroundX = Float.NEGATIVE_INFINITY
            for (i in 0 until sorted.lastIndex) {
                val x = (sorted[i] + sorted[i + 1]) * 0.5f
                val loop = (x / (ZONE_LENGTH * ZONE_COUNT)).toInt()
                val skipChance = (SKIP_SLOT_CHANCE - loop * 0.04f).coerceAtLeast(0.22f)
                if (x < FIRST_THREAT_X || rnd.nextFloat() < skipChance) continue
                val zone = ((x / ZONE_LENGTH).toInt() % ZONE_COUNT)
                var kind = pickKind(zone, rnd.nextFloat()) ?: continue
                if (isGroundHazard(kind) &&
                    (sorted[i + 1] - sorted[i] < MIN_ORB_GAP_FOR_GROUND || x - lastGroundX < MIN_GROUND_SLOT_GAP * 260f)
                ) {
                    kind = if (zone >= 1 && rnd.nextFloat() < 0.5f) ThreatKind.GLITCH_DRONE else continue
                }
                val slot = threats.firstOrNull { !it.active } ?: continue
                spawnPlanned(slot, kind, x, rnd)
                if (isGroundHazard(kind)) lastGroundX = x
            }
        }
        if (bossX != null) {
            val slot = threats.firstOrNull { !it.active }
            if (slot != null) {
                val rnd = Random(seed xor 0x61C88647)
                spawnPlanned(slot, ThreatKind.LAST_OVERSEER, bossX, rnd)
                boss = slot
                isBossDefeated = false
            }
        }
    }

    fun grantShieldCharge() {
        shieldCharges = (shieldCharges + 1).coerceAtMost(3)
    }

    /** Frees cached drawing resources (call from onDetachedFromWindow). */
    fun release() {
        painter.release()
    }

    // =====================================================================================
    // Per-frame API
    // =====================================================================================

    /**
     * Auto-mode reflex. Call every frame in auto mode BEFORE GameView computes movement.
     * Fires the laser itself when a threat is in range; returns movement/jump overrides.
     */
    fun autoReflex(): ReflexCommand {
        command.clear()
        if (isDefeated) return command
        if (laser.isReady) {
            val target = findTarget(auto = true)
            if (target != null) {
                fireAt(target)
                command.fired = true
            }
        }
        reflex.decide(threats, pulses, body, laser.isReady, command)
        return command
    }

    /** Manual BLAST. Aims at the nearest threat ahead, or fires straight ahead. */
    fun fireManual(): Boolean {
        if (isDefeated || !laser.isReady) return false
        val target = findTarget(auto = false)
        if (target != null) {
            fireAt(target)
        } else {
            laser.fire(body.eyeX, body.eyeY, body.eyeX + body.facing * EyeLaser.FREE_SHOT_LENGTH,
                body.eyeY, EyeLaser.IMPACT_NONE)
            listener?.onLaserFired()
        }
        return true
    }

    /** Simulates threats and resolves collisions. Call after GameView's physics step. */
    fun update(dt: Float, cameraX: Float, viewWidth: Int) {
        val step = if (dt.isNaN() || dt < 0f) 0f else min(dt, MAX_DT)
        time += step
        if (time > TIME_WRAP) time -= TIME_WRAP
        this.cameraX = cameraX
        viewW = viewWidth

        laser.update(step)
        if (invuln > 0f) invuln -= step
        updatePopups(step)

        val gY = body.groundY
        for (t in threats) if (t.active) {
            if (t.kind != ThreatKind.LAST_OVERSEER && t.x < cameraX - CULL_MARGIN) t.clear()
            else updateThreat(t, step, gY)
        }
        for (p in pulses) if (p.active) updatePulse(p, step, gY)

        if (!isDefeated) resolveCollisions()
    }

    /** Score change since the last call (bonuses and decoy penalties). */
    fun consumeScoreDelta(): Int {
        val d = pendingScore
        pendingScore = 0
        return d
    }

    /** Sprite alpha for invulnerability blinking (255 = fully opaque). */
    fun playerAlpha(): Int =
        if (invuln > 0f && !isDefeated && (time * 18f).toInt() % 2 == 0) BLINK_ALPHA else 255

    // =====================================================================================
    // Drawing
    // =====================================================================================

    /** World space, before Atmini is drawn. */
    fun draw(canvas: Canvas) {
        val gY = body.groundY
        val px = body.centerX
        val py = body.centerY
        val minX = cameraX - CULL_MARGIN
        val maxX = cameraX + viewW + CULL_MARGIN
        for (t in threats) {
            if (!t.active) continue
            if (t.kind != ThreatKind.LAST_OVERSEER && (t.x < minX || t.x > maxX)) continue
            painter.drawThreat(canvas, t, time, gY, px, py)
        }
        for (p in pulses) if (p.active) painter.drawThreat(canvas, p, time, gY, px, py)
    }

    /** World space, after Atmini is drawn: eye glow, laser beam, combat popups. */
    fun drawOverlay(canvas: Canvas) {
        if (!isDefeated) painter.drawEyes(canvas, body, laser, time)
        painter.drawLaser(canvas, laser)
        for (i in 0 until popCount) {
            val p = 1f - popLife[i] / POPUP_LIFE
            val rise = 1f - (1f - p) * (1f - p)
            val scale = if (p < 0.15f) 0.7f + p / 0.15f * 0.4f else 1.1f - min(0.1f, (p - 0.15f))
            val alpha = if (p < 0.7f) 1f else (1f - p) / 0.3f
            painter.drawPopup(canvas, popX[i], popY[i] - 60f * rise, POPUP_TEXTS[popText[i]],
                popColor[i], alpha, scale)
        }
    }

    /** Screen space, with the rest of the HUD. */
    fun drawHud(canvas: Canvas, viewWidth: Int) {
        painter.drawHud(canvas, viewWidth, harmony, MAX_HARMONY, laser, boss, time)
    }

    // =====================================================================================
    // Simulation
    // =====================================================================================

    private fun updateThreat(t: Threat, step: Float, gY: Float) {
        if (t.flash > 0f) t.flash -= step
        if (t.isPurifying) {
            t.purify -= step
            t.y -= PURIFY_RISE * step
            if (t.purify <= 0f) t.active = false
            return
        }
        val dx = t.x - body.centerX
        when (t.kind) {
            ThreatKind.RUST_SHARD -> t.y = gY - t.hh
            ThreatKind.FIREWALL_ARC -> {
                t.timer += step
                t.y = gY - t.hh
            }
            ThreatKind.FALLING_MONOLITH -> updateMonolith(t, step, gY, dx)
            ThreatKind.GLITCH_DRONE -> updateDrone(t, step, dx)
            ThreatKind.RUST_CRAWLER -> updateCrawler(t, step, gY, dx)
            ThreatKind.SHADOW_WISP -> {
                t.x = t.anchorX + sin(time * 0.7f + t.phase) * 30f
                t.y = t.anchorY + kotlin.math.cos(time * 1.1f + t.phase) * 12f
            }
            ThreatKind.LAST_OVERSEER -> updateBoss(t, step, gY)
            ThreatKind.OVERSEER_PULSE -> Unit // pulses live in their own pool
        }
    }

    private fun updateMonolith(t: Threat, step: Float, gY: Float, dx: Float) {
        when (t.state) {
            STATE_DORMANT -> if (abs(dx) < MONOLITH_TRIGGER) {
                t.state = STATE_WARNING
                t.timer = ThreatPainter.MONOLITH_WARN
                t.y = -t.hh - 20f
            }
            STATE_WARNING -> {
                t.timer -= step
                if (t.timer <= 0f) {
                    t.state = STATE_FALLING
                    t.vy = 200f
                }
            }
            STATE_FALLING -> {
                t.vy += MONOLITH_GRAVITY * step
                t.y += t.vy * step
                if (t.y + t.hh >= gY) {
                    t.y = gY - t.hh
                    t.state = STATE_LANDED
                    t.timer = ThreatPainter.RUBBLE_TIME
                    effects.addShockwave(t.x, gY, true)
                    particles.emitJumpThruster(t.x, gY)
                }
            }
            STATE_LANDED -> {
                t.timer -= step
                if (t.timer <= 0f) t.active = false
            }
        }
    }

    private fun updateDrone(t: Threat, step: Float, dx: Float) {
        if (t.state == STATE_DORMANT) {
            t.x = t.anchorX
            t.y = t.anchorY
            if (abs(dx) < DRONE_WAKE) {
                t.state = STATE_ACTIVE
                t.timer2 = 1.5f + (t.phase / TWO_PI)
            }
            return
        }
        t.x = t.anchorX + sin(time * 0.9f + t.phase) * DRONE_PATROL
        t.y = t.anchorY + sin(time * 2.1f + t.phase) * 14f
        t.timer2 -= step
        if (t.timer2 <= 0f) {
            if (abs(dx) < DRONE_FIRE_RANGE && isOnScreen(t)) {
                firePulse(t.x, t.y + 10f, body.centerX, body.centerY, PULSE_SPEED)
            }
            t.timer2 = 2.8f + (t.phase / TWO_PI) * 0.8f
        }
    }

    private fun updateCrawler(t: Threat, step: Float, gY: Float, dx: Float) {
        t.y = gY - t.hh
        if (t.state == STATE_DORMANT) {
            if (abs(dx) < CRAWLER_WAKE) t.state = STATE_ACTIVE
            return
        }
        t.vx = if (dx > 0f) -CRAWLER_SPEED else CRAWLER_SPEED
        t.x = (t.x + t.vx * step).coerceIn(t.anchorX - CRAWLER_LEASH, t.anchorX + CRAWLER_LEASH)
    }

    private fun updateBoss(t: Threat, step: Float, gY: Float) {
        val hover = gY - BOSS_HOVER_HEIGHT
        if (t.state == STATE_DORMANT) {
            t.x = t.anchorX
            t.y = hover
            if (body.centerX > t.anchorX - BOSS_WAKE) {
                t.state = STATE_SHIELDED
                t.timer = BOSS_SHIELD_TIME
                listener?.onBossAwakened()
            }
            return
        }
        // Keeps a fixed lead ahead of Atmini so the final guardian can't be skipped.
        val targetX = body.centerX + BOSS_LEAD
        t.x += (targetX - t.x) * min(1f, step * 1.4f)
        t.y = hover + sin(time * 1.3f) * 18f
        t.timer -= step
        when (t.state) {
            STATE_SHIELDED -> if (t.timer <= 0f) {
                t.state = STATE_VOLLEY
                t.timer = BOSS_VOLLEY_TIME
                t.shots = 0
                t.timer2 = 0.15f
            }
            STATE_VOLLEY -> {
                t.timer2 -= step
                val pattern = ((t.phase / TWO_PI) * 3f).toInt().coerceIn(0, 2)
                val shotsThisVolley = when (pattern) { 0 -> 3; 1 -> 2; else -> 4 }
                if (t.timer2 <= 0f && t.shots < shotsThisVolley) {
                    val spread = (t.shots - (shotsThisVolley - 1) * 0.5f) * (if (pattern == 2) 62f else 48f)
                    firePulse(t.x - t.radius * 0.5f, t.y, body.centerX, body.centerY + spread, PULSE_SPEED)
                    t.shots++
                    t.timer2 = if (pattern == 1) 0.48f else 0.35f
                }
                if (t.timer <= 0f) {
                    t.state = STATE_OPEN
                    t.timer = BOSS_OPEN_TIME
                }
            }
            STATE_OPEN -> if (t.timer <= 0f) {
                t.state = STATE_SHIELDED
                t.timer = BOSS_SHIELD_TIME
            }
        }
    }

    private fun updatePulse(p: Threat, step: Float, gY: Float) {
        if (p.flash > 0f) p.flash -= step
        if (p.isPurifying) {
            p.purify -= step
            if (p.purify <= 0f) p.active = false
            return
        }
        p.x += p.vx * step
        p.y += p.vy * step
        p.timer -= step
        if (p.timer <= 0f || p.y > gY - 4f) p.active = false
    }

    private fun firePulse(sx: Float, sy: Float, tx: Float, ty: Float, speed: Float) {
        var slot: Threat? = null
        for (p in pulses) if (!p.active) { slot = p; break }
        val p = slot ?: return
        val dx = tx - sx
        val dy = ty - sy
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        p.clear()
        p.active = true
        p.kind = ThreatKind.OVERSEER_PULSE
        p.state = STATE_ACTIVE
        p.x = sx; p.y = sy
        p.vx = dx / len * speed
        p.vy = dy / len * speed
        p.radius = 13f
        p.timer = PULSE_LIFETIME
    }

    // =====================================================================================
    // Collisions & damage
    // =====================================================================================

    private fun resolveCollisions() {
        val l = body.hitLeft
        val t = body.hitTop
        val r = body.hitRight
        val b = body.hitBottom

        for (th in threats) {
            if (!th.active || th.isPurifying) continue
            if (th.kind == ThreatKind.SHADOW_WISP) {
                if (th.overlaps(l, t, r, b)) touchDecoy(th)
                continue
            }
            if (invuln <= 0f && isHarmful(th) && th.overlaps(l, t, r, b)) {
                hitPlayer(th)
                return
            }
        }
        if (invuln > 0f) return
        for (p in pulses) {
            if (p.active && !p.isPurifying && p.overlaps(l, t, r, b)) {
                p.active = false
                hitPlayer(p)
                return
            }
        }
    }

    private fun isHarmful(t: Threat): Boolean = when (t.kind) {
        ThreatKind.RUST_SHARD, ThreatKind.RUST_CRAWLER, ThreatKind.GLITCH_DRONE -> true
        ThreatKind.FIREWALL_ARC -> t.arcOn()
        ThreatKind.FALLING_MONOLITH -> t.state == STATE_FALLING && t.y + t.hh > 0f
        ThreatKind.LAST_OVERSEER -> t.state != STATE_DORMANT
        ThreatKind.OVERSEER_PULSE -> true
        ThreatKind.SHADOW_WISP -> false
    }

    private fun hitPlayer(source: Threat) {
        lastHitBy = source.kind
        if (shieldCharges > 0) {
            shieldCharges--
            invuln = INVULN_TIME
            val dir = if (body.centerX < source.x) -1f else 1f
            body.requestImpulse(dir * KNOCK_VX * 0.55f, KNOCK_VY * 0.6f)
            effects.addShockwave(body.centerX, body.centerY, true)
            listener?.onShieldBlocked(shieldCharges)
            return
        }
        harmony--
        invuln = INVULN_TIME
        val dir = if (body.centerX < source.x) -1f else 1f
        body.requestImpulse(dir * KNOCK_VX, KNOCK_VY)
        effects.addShockwave(body.centerX, body.centerY, true)
        listener?.onPlayerHit(harmony)
        if (harmony <= 0) {
            harmony = 0
            isDefeated = true
            listener?.onDefeated()
        }
    }

    private fun touchDecoy(t: Threat) {
        t.purify = DECOY_FADE_TIME
        t.purifyMax = DECOY_FADE_TIME
        t.darkFade = true
        pendingScore += DECOY_PENALTY
        addPopup(t.x, t.y - 20f, TEXT_MINUS_2, ThreatPainter.WISP_GLOW)
        listener?.onDecoyTouched()
    }

    // =====================================================================================
    // Laser
    // =====================================================================================

    private fun findTarget(auto: Boolean): Threat? {
        var best: Threat? = null
        var bestScore = Float.MAX_VALUE
        val ex = body.eyeX
        val ey = body.eyeY
        for (pass in 0..1) {
            val pool = if (pass == 0) threats else pulses
            for (t in pool) {
                if (!t.active || t.isPurifying || !t.kind.blastable) continue
                if (!isTargetable(t, auto) || !isOnScreen(t)) continue
                val dx = t.x - ex
                val dy = t.y - ey
                if (dx * body.facing < -AIM_BACK_TOLERANCE) continue
                val range = if (auto) autoRange(t.kind) else EyeLaser.RANGE
                val d2 = dx * dx + dy * dy
                if (d2 > range * range) continue
                val score = sqrt(d2) * (if (auto) autoPriority(t.kind) else 1f)
                if (score < bestScore) {
                    bestScore = score
                    best = t
                }
            }
        }
        return best
    }

    private fun isTargetable(t: Threat, auto: Boolean): Boolean = when (t.kind) {
        ThreatKind.FALLING_MONOLITH -> t.state == STATE_FALLING && t.y > 0f
        ThreatKind.LAST_OVERSEER -> t.state != STATE_DORMANT && (!auto || t.state == STATE_OPEN)
        ThreatKind.OVERSEER_PULSE ->
            !auto || (t.vx * (body.centerX - t.x) + t.vy * (body.centerY - t.y)) > 0f
        else -> true
    }

    private fun fireAt(t: Threat) {
        val deflect = t.kind == ThreatKind.LAST_OVERSEER && t.state != STATE_OPEN
        laser.fire(body.eyeX, body.eyeY, t.x, t.y,
            if (deflect) EyeLaser.IMPACT_DEFLECT else EyeLaser.IMPACT_HIT)
        listener?.onLaserFired()
        t.flash = ThreatPainter.FLASH_TIME
        if (deflect) return
        t.hp--
        if (t.hp <= 0) purifyThreat(t) else effects.addShockwave(t.x, t.y, false)
    }

    private fun purifyThreat(t: Threat) {
        val isBoss = t.kind == ThreatKind.LAST_OVERSEER
        t.purifyMax = if (isBoss) BOSS_PURIFY_TIME else PURIFY_TIME
        t.purify = t.purifyMax
        t.darkFade = false
        particles.emitOrbCollect(t.x, t.y, isBoss)
        effects.addShockwave(t.x, t.y, isBoss || t.kind == ThreatKind.RUST_CRAWLER)

        val reward = when (t.kind) {
            ThreatKind.GLITCH_DRONE, ThreatKind.RUST_CRAWLER -> TEXT_PLUS_2
            ThreatKind.SHADOW_WISP -> TEXT_PLUS_3
            ThreatKind.FALLING_MONOLITH -> TEXT_PLUS_1
            ThreatKind.LAST_OVERSEER -> TEXT_PLUS_25
            else -> -1
        }
        if (reward >= 0) {
            pendingScore += POPUP_VALUES[reward]
            addPopup(t.x, t.y - 30f, reward, ThreatPainter.CORE_LIGHT)
        }
        listener?.onThreatPurified(t.kind)
        if (isBoss) {
            isBossDefeated = true
            particles.emitVictoryShower(t.x, t.y)
            listener?.onBossDefeated()
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private fun isOnScreen(t: Threat): Boolean =
        t.x > cameraX - SCREEN_MARGIN && t.x < cameraX + viewW + SCREEN_MARGIN && t.y > -40f

    private fun addPopup(x: Float, y: Float, textIndex: Int, color: Int) {
        val i = if (popCount < MAX_POPUPS) popCount++ else 0
        popX[i] = x; popY[i] = y
        popLife[i] = POPUP_LIFE
        popText[i] = textIndex
        popColor[i] = color
    }

    private fun updatePopups(step: Float) {
        var i = popCount - 1
        while (i >= 0) {
            popLife[i] -= step
            if (popLife[i] <= 0f) {
                val last = --popCount
                if (i != last) {
                    popX[i] = popX[last]; popY[i] = popY[last]; popLife[i] = popLife[last]
                    popText[i] = popText[last]; popColor[i] = popColor[last]
                }
            }
            i--
        }
    }

    private fun spawnPlanned(t: Threat, kind: ThreatKind, x: Float, rnd: Random) {
        t.clear()
        t.active = true
        t.kind = kind
        t.x = x
        t.anchorX = x
        t.phase = rnd.nextFloat() * TWO_PI
        when (kind) {
            ThreatKind.RUST_SHARD -> { t.hw = 26f; t.hh = 29f; t.state = STATE_ACTIVE }
            ThreatKind.FIREWALL_ARC -> {
                t.hw = 16f; t.hh = ARC_HEIGHT * 0.5f; t.state = STATE_ACTIVE
                t.timer = rnd.nextFloat() * ARC_CYCLE
            }
            ThreatKind.FALLING_MONOLITH -> { t.hw = 34f; t.hh = 54f; t.y = -200f }
            ThreatKind.GLITCH_DRONE -> {
                t.radius = 24f; t.anchorY = 360f + rnd.nextFloat() * 90f; t.y = t.anchorY
            }
            ThreatKind.RUST_CRAWLER -> { t.hw = 30f; t.hh = 20f; t.hp = 2; t.maxHp = 2 }
            ThreatKind.SHADOW_WISP -> {
                t.radius = 20f; t.anchorY = 370f + rnd.nextFloat() * 90f
                t.y = t.anchorY; t.state = STATE_ACTIVE
            }
            ThreatKind.LAST_OVERSEER -> { t.radius = 92f; t.hp = BOSS_HP; t.maxHp = BOSS_HP }
            ThreatKind.OVERSEER_PULSE -> Unit
        }
    }

    private fun pickKind(zone: Int, r: Float): ThreatKind = when (zone) {
        0 -> ThreatKind.RUST_SHARD
        1 -> if (r < 0.55f) ThreatKind.RUST_SHARD else ThreatKind.GLITCH_DRONE
        2 -> when {
            r < 0.3f -> ThreatKind.RUST_SHARD
            r < 0.5f -> ThreatKind.GLITCH_DRONE
            r < 0.75f -> ThreatKind.FIREWALL_ARC
            else -> ThreatKind.FALLING_MONOLITH
        }
        else -> when {
            r < 0.18f -> ThreatKind.RUST_SHARD
            r < 0.34f -> ThreatKind.GLITCH_DRONE
            r < 0.48f -> ThreatKind.FIREWALL_ARC
            r < 0.62f -> ThreatKind.FALLING_MONOLITH
            r < 0.8f -> ThreatKind.SHADOW_WISP
            else -> ThreatKind.RUST_CRAWLER
        }
    }

    private fun isGroundHazard(kind: ThreatKind): Boolean =
        kind == ThreatKind.RUST_SHARD || kind == ThreatKind.FIREWALL_ARC ||
            kind == ThreatKind.RUST_CRAWLER || kind == ThreatKind.FALLING_MONOLITH

    private fun autoRange(kind: ThreatKind): Float = when (kind) {
        ThreatKind.OVERSEER_PULSE -> 480f
        ThreatKind.FALLING_MONOLITH -> 700f
        ThreatKind.LAST_OVERSEER -> 950f
        ThreatKind.RUST_CRAWLER -> 720f
        ThreatKind.GLITCH_DRONE -> 700f
        ThreatKind.SHADOW_WISP -> 640f
        else -> 0f
    }

    private fun autoPriority(kind: ThreatKind): Float = when (kind) {
        ThreatKind.OVERSEER_PULSE -> 0.3f
        ThreatKind.FALLING_MONOLITH -> 0.4f
        ThreatKind.LAST_OVERSEER -> 0.5f
        ThreatKind.RUST_CRAWLER -> 0.7f
        ThreatKind.GLITCH_DRONE -> 0.8f
        else -> 1f
    }

    private companion object {
        const val MAX_HARMONY = 3
        const val MAX_PLANNED = 64
        const val MAX_PULSES = 24
        const val MAX_POPUPS = 12

        // Layout
        const val ZONE_LENGTH = 1600f
        const val ZONE_COUNT = 5
        const val FIRST_THREAT_X = 950f
        const val SKIP_SLOT_CHANCE = 0.45f      // ~1 threat per 1.45 orb gaps
        const val MIN_GROUND_SLOT_GAP = 3       // >= 780px: a full jump (~640px) always lands in a safe gap
        const val MIN_ORB_GAP_FOR_GROUND = 200f
        const val BOSS_OFFSET = 520f

        // Behaviour
        const val MONOLITH_TRIGGER = 620f
        const val MONOLITH_GRAVITY = 2600f
        const val DRONE_WAKE = 1150f
        const val DRONE_PATROL = 110f
        const val DRONE_FIRE_RANGE = 760f
        const val CRAWLER_WAKE = 950f
        const val CRAWLER_SPEED = 95f
        const val CRAWLER_LEASH = 1100f
        const val PULSE_SPEED = 290f
        const val PULSE_LIFETIME = 5f
        const val BOSS_HP = 6
        const val BOSS_WAKE = 1000f
        const val BOSS_LEAD = 520f
        const val BOSS_HOVER_HEIGHT = 470f
        const val BOSS_SHIELD_TIME = 3f
        const val BOSS_VOLLEY_TIME = 1.2f
        const val BOSS_VOLLEY_SHOTS = 3
        const val BOSS_OPEN_TIME = 2.2f

        // Player
        const val INVULN_TIME = 1.3f
        const val KNOCK_VX = 430f
        const val KNOCK_VY = -480f
        const val BLINK_ALPHA = 90
        const val AIM_BACK_TOLERANCE = 80f
        const val DECOY_PENALTY = -2
        const val DECOY_FADE_TIME = 0.3f

        // Visual timing
        const val PURIFY_TIME = 0.4f
        const val BOSS_PURIFY_TIME = 1.2f
        const val PURIFY_RISE = 40f
        const val POPUP_LIFE = 0.9f
        const val CULL_MARGIN = 200f
        const val ENDLESS_RECYCLE_MARGIN = 900f
        const val SCREEN_MARGIN = 40f
        const val MAX_DT = 0.05f
        const val TIME_WRAP = 3600f

        const val TEXT_PLUS_1 = 0
        const val TEXT_PLUS_2 = 1
        const val TEXT_PLUS_3 = 2
        const val TEXT_MINUS_2 = 3
        const val TEXT_PLUS_25 = 4
        val POPUP_TEXTS = arrayOf("+1", "+2", "+3", "\u22122", "+25")
        val POPUP_VALUES = intArrayOf(1, 2, 3, -2, 25)
    }
}
