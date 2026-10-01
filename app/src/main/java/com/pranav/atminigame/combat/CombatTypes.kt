package com.pranav.atminigame.combat

/**
 * Every hazard in the Luminous Ascension: remnants of the old world that have not healed yet.
 * Blasting one does not destroy it; it is purified into light.
 */
enum class ThreatKind(val blastable: Boolean) {
    /** Jagged rust crystals on the ground. Jump over. */
    RUST_SHARD(blastable = false),
    /** Ancient tower fragment falling from the sky after a ground-shadow warning. Dodge or blast. */
    FALLING_MONOLITH(blastable = true),
    /** Tall energy curtain pulsing on/off. Pass while it is off. */
    FIREWALL_ARC(blastable = false),
    /** Hovering sentinel of the old regime at orb height; fires pulses. One blast. */
    GLITCH_DRONE(blastable = true),
    /** Slow ground walker that approaches Atmini. Two blasts, or jump over. */
    RUST_CRAWLER(blastable = true),
    /** Dark decoy that looks like an orb. Touch costs score; blast purifies it for a bonus. */
    SHADOW_WISP(blastable = true),
    /** Slow energy projectile. Dodge or blast to cancel. */
    OVERSEER_PULSE(blastable = true),
    /** Final guardian: a giant eye. Only its open core can be damaged. */
    LAST_OVERSEER(blastable = true)
}

/**
 * Snapshot of Atmini that GameView writes every frame (world coordinates).
 * Combat reads it and may request a knockback impulse, which GameView applies.
 * This keeps the combat package fully decoupled from GameView internals.
 */
class CombatBody {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    var vx = 0f
    var vy = 0f
    var grounded = false
    /** +1 facing right, -1 facing left (GameView flips the sprite when vx < -20). */
    var facing = 1f
    var groundY = 0f

    /** Eye position as a fraction of the sprite box (facing right). Tune to your artwork. */
    var eyeXFraction = 0.58f
    var eyeYFraction = 0.2f

    val centerX: Float get() = x + w * 0.5f
    val centerY: Float get() = y + h * 0.5f
    val eyeX: Float get() = x + w * (0.5f + (eyeXFraction - 0.5f) * facing)
    val eyeY: Float get() = y + h * eyeYFraction

    // Fair hitbox, slightly inset from the sprite bounds.
    val hitLeft: Float get() = x + w * 0.22f
    val hitRight: Float get() = x + w * 0.78f
    val hitTop: Float get() = y + h * 0.12f
    val hitBottom: Float get() = y + h

    var impulseVx = 0f
        private set
    var impulseVy = 0f
        private set
    private var impulsePending = false

    internal fun requestImpulse(vx: Float, vy: Float) {
        impulseVx = vx
        impulseVy = vy
        impulsePending = true
    }

    /** Returns true once per requested knockback; then read [impulseVx]/[impulseVy]. */
    fun consumeImpulse(): Boolean {
        val pending = impulsePending
        impulsePending = false
        return pending
    }
}

/** Per-frame auto-mode decision. Reused instance: no allocation per frame. */
class ReflexCommand {
    /** When true, GameView must use [moveDir] instead of the AI's own steering this frame. */
    var overrideMove = false
        internal set
    /** -1 left, 0 hold still, +1 right. Only meaningful when [overrideMove] is true. */
    var moveDir = 0
        internal set
    var jump = false
        internal set
    /** Informational: the reflex already fired the laser this frame. */
    var fired = false
        internal set

    internal fun clear() {
        overrideMove = false
        moveDir = 0
        jump = false
        fired = false
    }

    internal fun move(dir: Int) {
        overrideMove = true
        moveDir = dir
    }
}

/** Optional hooks for sound, haptics and analytics. All methods default to no-ops. */
interface CombatListener {
    fun onLaserFired() {}
    fun onThreatPurified(kind: ThreatKind) {}
    fun onDecoyTouched() {}
    fun onPlayerHit(harmonyLeft: Int) {}
    fun onShieldBlocked(chargesLeft: Int) {}
    fun onDefeated() {}
    fun onBossAwakened() {}
    fun onBossDefeated() {}
}
