package com.pranav.atminigame.combat

// ---------------------------------------------------------------------------------------
// Threat states
// ---------------------------------------------------------------------------------------
internal const val STATE_DORMANT = 0
internal const val STATE_ACTIVE = 1
internal const val STATE_WARNING = 2   // monolith: shadow on the ground, block above screen
internal const val STATE_FALLING = 3
internal const val STATE_LANDED = 4    // monolith rubble fading out (harmless)
internal const val STATE_SHIELDED = 5  // boss
internal const val STATE_VOLLEY = 6    // boss firing
internal const val STATE_OPEN = 7      // boss core exposed (vulnerable)

// Firewall Arc timing (seconds)
internal const val ARC_ON_TIME = 1.2f
internal const val ARC_OFF_TIME = 1.6f
internal const val ARC_CYCLE = ARC_ON_TIME + ARC_OFF_TIME
internal const val ARC_WARN_TIME = 0.35f
internal const val ARC_HEIGHT = 560f

internal const val TWO_PI = 6.2831855f

/**
 * One pooled threat. Instances are allocated once and reused across runs;
 * nothing here allocates during gameplay.
 */
internal class Threat {
    var active = false
    var kind = ThreatKind.RUST_SHARD
    var state = STATE_DORMANT

    /** World-space center. */
    var x = 0f
    var y = 0f
    var anchorX = 0f
    var anchorY = 0f
    var vx = 0f
    var vy = 0f

    /** Half extents for rectangle shapes. */
    var hw = 0f
    var hh = 0f
    /** When > 0 the threat uses a circle shape of this radius. */
    var radius = 0f

    var hp = 1
    var maxHp = 1
    var timer = 0f
    var timer2 = 0f
    var phase = 0f
    var shots = 0

    /** Hit-flash countdown. */
    var flash = 0f
    /** Purification animation countdown; threat is harmless while > 0. */
    var purify = 0f
    var purifyMax = 0.4f
    /** True when a decoy wisp was touched (fades dark instead of into light). */
    var darkFade = false

    val isCircle: Boolean get() = radius > 0f
    val isPurifying: Boolean get() = purify > 0f
    val visualSize: Float get() = if (radius > 0f) radius else maxOf(hw, hh)

    fun clear() {
        active = false
        state = STATE_DORMANT
        x = 0f; y = 0f; anchorX = 0f; anchorY = 0f; vx = 0f; vy = 0f
        hw = 0f; hh = 0f; radius = 0f
        hp = 1; maxHp = 1
        timer = 0f; timer2 = 0f; phase = 0f; shots = 0
        flash = 0f; purify = 0f; purifyMax = 0.4f; darkFade = false
    }

    // --- Firewall Arc cycle -------------------------------------------------------------

    private val arcPos: Float get() = (timer % ARC_CYCLE + ARC_CYCLE) % ARC_CYCLE

    fun arcOn(): Boolean = arcPos < ARC_ON_TIME

    /** Seconds until the arc turns on (0 while on). */
    fun arcTimeUntilOn(): Float = if (arcOn()) 0f else ARC_CYCLE - arcPos

    // --- Geometry ------------------------------------------------------------------------

    fun overlaps(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (isCircle) {
            val nx = x.coerceIn(left, right)
            val ny = y.coerceIn(top, bottom)
            val dx = x - nx
            val dy = y - ny
            return dx * dx + dy * dy < radius * radius
        }
        return x - hw < right && x + hw > left && y - hh < bottom && y + hh > top
    }
}

/** Deterministic integer hash -> [0, 1). */
internal fun combatHash(i: Int, salt: Int): Float {
    var v = i * 374761393 + salt * 668265263
    v = (v xor (v ushr 13)) * 1274126177
    v = v xor (v ushr 16)
    return (v and 0x7FFFFFFF) / 2147483648f
}
