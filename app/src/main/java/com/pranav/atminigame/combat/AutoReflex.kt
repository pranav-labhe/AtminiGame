package com.pranav.atminigame.combat

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Auto-mode survival reflexes, evaluated every frame in plain Kotlin.
 *
 * The strategic AI (orb choice) runs asynchronously at ~350 ms, which is too slow to
 * dodge. This layer overrides movement/jump only when a threat demands it, then hands
 * control back. It is deterministic, instant, and works offline.
 *
 * Priority (highest first):
 *   1. Step out from under a falling monolith.
 *   2. Hold before an active (or soon-active) Firewall Arc; hurry through if inside.
 *   3. Rear guard: turn to face (and blast) a crawler approaching from behind, or step away.
 *   4. Jump ground threats (shards, crawlers) at the right moment.
 *   5. Jump incoming pulses when the laser is on cooldown.
 * Laser firing is decided by [CombatDirector] before this runs.
 */
internal class AutoReflex {

    fun decide(
        threats: Array<Threat>,
        pulses: Array<Threat>,
        body: CombatBody,
        laserReady: Boolean,
        out: ReflexCommand
    ) {
        val bodyHalfW = (body.hitRight - body.hitLeft) * 0.5f

        // 1. Falling monolith: evasion outranks everything.
        for (t in threats) {
            if (!t.active || t.isPurifying || t.kind != ThreatKind.FALLING_MONOLITH) continue
            if (t.state != STATE_WARNING && t.state != STATE_FALLING) continue
            val d = body.centerX - t.x
            if (abs(d) < t.hw + bodyHalfW + EVADE_MARGIN) {
                var dir = if (d >= 0f) 1 else -1
                if (dir < 0 && body.x <= 1f) dir = 1 // can't retreat past the world start
                out.move(dir)
                return
            }
        }

        val moveDir = travelDir(body)

        // 2. Firewall arcs.
        for (t in threats) {
            if (!t.active || t.kind != ThreatKind.FIREWALL_ARC) continue
            val inside = body.hitRight > t.x - t.hw && body.hitLeft < t.x + t.hw
            if (inside) {
                if (!t.arcOn()) out.move(moveDir) // keep going: never stall inside a gate
                continue
            }
            val gap = gapAhead(body, t, moveDir)
            if (gap in 0f..ARC_HOLD_DISTANCE) {
                val crossTime = (gap + t.hw * 2f + body.w) / CROSS_SPEED
                val safe = !t.arcOn() && t.arcTimeUntilOn() > crossTime + ARC_SAFETY
                if (!safe) {
                    out.move(0)
                    return
                }
            }
        }

        // 3. Rear guard: a crawler closing in from behind (e.g. while she waits for the boss).
        //    Turn to face it so the laser can fire next frame; if recharging, step away.
        val facingSide = if (body.facing < 0f) -1 else 1
        for (t in threats) {
            if (!t.active || t.isPurifying || t.kind != ThreatKind.RUST_CRAWLER) continue
            if (t.state != STATE_ACTIVE) continue
            val side = if (t.x > body.centerX) 1 else -1
            if (side == facingSide) continue
            val gap = gapAhead(body, t, side)
            if (gap > REAR_GUARD_RANGE) continue
            if (laserReady && gap > REAR_TURN_MIN_GAP) {
                out.move(side)
            } else if (gap < REAR_FLEE_GAP) {
                var away = -side
                if (away < 0 && body.x <= 1f) away = 1
                out.move(away)
            } else {
                continue
            }
            return
        }

        if (!body.grounded) return

        // 4. Ground threats.
        for (t in threats) {
            if (!t.active || t.isPurifying) continue
            if (t.kind != ThreatKind.RUST_SHARD && t.kind != ThreatKind.RUST_CRAWLER) continue
            val gap = gapAhead(body, t, moveDir)
            if (gap < -t.hw || gap > JUMP_LOOKAHEAD) continue
            var closing = if (body.vx * moveDir > 0f) abs(body.vx) else 0f
            if (t.kind == ThreatKind.RUST_CRAWLER && t.state == STATE_ACTIVE) closing += CRAWLER_SPEED
            if (closing < MIN_CLOSING) continue
            if (gap / closing < JUMP_TTC) {
                out.jump = true
                return
            }
        }

        // 5. Incoming pulses (the laser handles them when it's ready).
        if (laserReady) return
        for (p in pulses) {
            if (!p.active || p.isPurifying) continue
            val dx = body.centerX - p.x
            val dy = body.centerY - p.y
            if (p.vx * dx + p.vy * dy <= 0f) continue // moving away
            val speed = sqrt(p.vx * p.vx + p.vy * p.vy)
            if (speed < 1f) continue
            val dist = sqrt(dx * dx + dy * dy)
            if (dist / speed < PULSE_TTC && p.y > body.hitTop) {
                out.jump = true
                return
            }
        }
    }

    private fun travelDir(body: CombatBody): Int = when {
        body.vx > 30f -> 1
        body.vx < -30f -> -1
        else -> if (body.facing < 0f) -1 else 1
    }

    /** Distance from Atmini's leading edge to the threat's near edge along [dir]. */
    private fun gapAhead(body: CombatBody, t: Threat, dir: Int): Float =
        if (dir > 0) (t.x - t.hw) - body.hitRight else body.hitLeft - (t.x + t.hw)

    private companion object {
        const val EVADE_MARGIN = 28f
        const val ARC_HOLD_DISTANCE = 190f
        const val ARC_SAFETY = 0.15f
        const val CROSS_SPEED = 600f
        const val JUMP_LOOKAHEAD = 280f
        const val JUMP_TTC = 0.24f
        const val MIN_CLOSING = 60f
        const val PULSE_TTC = 0.3f
        const val CRAWLER_SPEED = 95f
        const val REAR_GUARD_RANGE = 520f
        const val REAR_TURN_MIN_GAP = 60f
        const val REAR_FLEE_GAP = 260f
    }
}
