package com.pranav.atminigame.combat

/**
 * Atmini's eye laser: an instant beam with a short cooldown, so it stays skillful
 * rather than spammable. Holds only state; drawing lives in [ThreatPainter].
 */
internal class EyeLaser {

    var cooldown = 0f
        private set
    var beamTimer = 0f
        private set
    var startX = 0f
        private set
    var startY = 0f
        private set
    var endX = 0f
        private set
    var endY = 0f
        private set
    var impact = IMPACT_NONE
        private set

    val isReady: Boolean get() = cooldown <= 0f
    /** 0 right after firing -> 1 when ready again. */
    val readiness: Float get() = 1f - (cooldown / COOLDOWN).coerceIn(0f, 1f)

    fun update(dt: Float) {
        if (cooldown > 0f) cooldown -= dt
        if (beamTimer > 0f) beamTimer -= dt
    }

    fun fire(sx: Float, sy: Float, ex: Float, ey: Float, impactType: Int) {
        startX = sx; startY = sy; endX = ex; endY = ey
        impact = impactType
        cooldown = COOLDOWN
        beamTimer = BEAM_TIME
    }

    fun reset() {
        cooldown = 0f
        beamTimer = 0f
    }

    companion object {
        const val COOLDOWN = 0.6f
        const val BEAM_TIME = 0.16f
        /** Max manual range (world px). */
        const val RANGE = 950f
        /** Untargeted shots travel this far forward. */
        const val FREE_SHOT_LENGTH = 700f

        const val IMPACT_NONE = 0
        const val IMPACT_HIT = 1
        const val IMPACT_DEFLECT = 2
    }
}
