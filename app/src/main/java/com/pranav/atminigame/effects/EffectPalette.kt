package com.pranav.atminigame.effects

import android.graphics.Color

/**
 * Live colors shared between the world background and gameplay effects, so bursts,
 * shockwaves and the AI link always harmonize with the zone currently on screen.
 *
 * Written once per frame by [WorldBackgroundRenderer.exportPalette]; read by
 * [ParticleSystem] and [VisualEffects]. Defaults match zone I ("Dawn of Awakening"),
 * so effects look correct even if the palette is never wired up.
 */
class EffectPalette {
    /** World accent: aurora, blossoms, neural threads. */
    var primary: Int = 0xFFFFB3D1.toInt()
    /** Secondary accent: birds, sparkles. */
    var secondary: Int = 0xFFFFE9A8.toInt()
    /** Warm sun glow. */
    var warm: Int = 0xFFFFC37A.toInt()
    /** Tower-light highlight. */
    var highlight: Int = 0xFFFFD58A.toInt()
    /** Dark ground tone, used for contrast outlines and text shadows. */
    var shadow: Int = 0xFF2E2346.toInt()

    companion object {
        /** Gameplay-semantic orb hues: identical to the orb colors drawn in GameView. */
        val ORB_SMALL: Int = Color.rgb(120, 220, 255)
        val ORB_BIG: Int = Color.rgb(255, 120, 120)
    }
}

/** Allocation-free ARGB blend shared by the effects classes. */
internal fun mixColor(a: Int, b: Int, t: Float): Int {
    val it = 1f - t
    return (((a ushr 24 and 0xFF) * it + (b ushr 24 and 0xFF) * t + 0.5f).toInt() shl 24) or
        (((a shr 16 and 0xFF) * it + (b shr 16 and 0xFF) * t + 0.5f).toInt() shl 16) or
        (((a shr 8 and 0xFF) * it + (b shr 8 and 0xFF) * t + 0.5f).toInt() shl 8) or
        ((a and 0xFF) * it + (b and 0xFF) * t + 0.5f).toInt()
}

/** Replaces the alpha channel of [color]. */
internal fun alphaOf(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)
