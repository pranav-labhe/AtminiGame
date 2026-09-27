package com.pranav.atminigame.audio.synth

import kotlin.math.pow

object ScaleHelper {

    // Semitone intervals from root (tonic) for each scale mode
    private val SCALE_MAP = mapOf(
        "phrygian" to intArrayOf(0, 1, 3, 5, 7, 8, 10),
        "mixolydian" to intArrayOf(0, 2, 4, 5, 7, 9, 10),
        "locrian" to intArrayOf(0, 1, 3, 5, 6, 8, 10),
        "minor" to intArrayOf(0, 2, 3, 5, 7, 8, 10),
        "major" to intArrayOf(0, 2, 4, 5, 7, 9, 11),
        "dorian" to intArrayOf(0, 2, 3, 5, 7, 9, 10)
    )

    fun rootToFrequency(root: String, octave: Int): Float {
        val rootFreq = when (root.uppercase()) {
            "C" -> 16.35f
            "C#" -> 17.32f
            "D" -> 18.35f
            "D#" -> 19.45f
            "E" -> 20.60f
            "F" -> 21.83f
            "F#" -> 23.12f
            "G" -> 24.50f
            "G#" -> 25.96f
            "A" -> 27.50f
            "A#" -> 29.14f
            "B" -> 30.87f
            else -> 18.35f // default D
        }
        return rootFreq * 2f.pow(octave)
    }

    /**
     * Converts a 1-based scale degree (e.g. 1, 4, 5, 8, 10, 13) in a specific scale into semitones from root.
     */
    fun scaleDegreeToSemitones(degree: Int, scaleName: String): Int {
        val scale = SCALE_MAP[scaleName.lowercase()] ?: SCALE_MAP["phrygian"]!!
        val zeroIndex = (degree - 1).coerceAtLeast(0)
        val octaveOffset = zeroIndex / 7
        val scaleStep = zeroIndex % 7
        return octaveOffset * 12 + scale[scaleStep]
    }

    /**
     * Calculates note frequency given root, base octave, scale degree, and scale name.
     */
    fun degreeToFreq(root: String, octave: Int, degree: Int, scaleName: String): Float {
        val baseFreq = rootToFrequency(root, octave)
        val semitones = scaleDegreeToSemitones(degree, scaleName)
        return baseFreq * 2f.pow(semitones / 12f)
    }
}
