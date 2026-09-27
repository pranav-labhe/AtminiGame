package com.pranav.atminigame.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Basic interface for a waveform generator.
 */
interface Oscillator {
    fun nextSample(frequency: Float, sampleRate: Int): Float
}

class SineWaveOscillator : Oscillator {
    private var phase = 0f

    override fun nextSample(frequency: Float, sampleRate: Int): Float {
        val sample = sin(phase).toFloat()
        phase += 2f * PI.toFloat() * frequency / sampleRate
        while (phase > 2f * PI.toFloat()) phase -= 2f * PI.toFloat()
        return sample
    }
}

class TriangleWaveOscillator : Oscillator {
    private var phase = 0f

    override fun nextSample(frequency: Float, sampleRate: Int): Float {
        while (phase >= 2f * PI.toFloat()) phase -= 2f * PI.toFloat()
        while (phase < 0f) phase += 2f * PI.toFloat()
        
        val x = phase / PI.toFloat() // Range 0..2
        val sample = 1f - 2f * abs(1f - x)
        
        phase += 2f * PI.toFloat() * frequency / sampleRate
        return sample
    }
}

class SquareWaveOscillator : Oscillator {
    private var phase = 0f

    override fun nextSample(frequency: Float, sampleRate: Int): Float {
        while (phase >= 2f * PI.toFloat()) phase -= 2f * PI.toFloat()
        while (phase < 0f) phase += 2f * PI.toFloat()

        val sample = if (phase < PI.toFloat()) 1f else -1f
        
        phase += 2f * PI.toFloat() * frequency / sampleRate
        return sample
    }
}

class SawtoothWaveOscillator : Oscillator {
    private var phase = 0f

    override fun nextSample(frequency: Float, sampleRate: Int): Float {
        while (phase >= 2f * PI.toFloat()) phase -= 2f * PI.toFloat()
        while (phase < 0f) phase += 2f * PI.toFloat()

        // Linearly goes from 1 down to -1 as phase goes from 0 to 2*PI
        val sample = 1f - (phase / PI.toFloat())
        
        phase += 2f * PI.toFloat() * frequency / sampleRate
        return sample
    }
}

/**
 * ADSR envelope to shape the amplitude dynamics of a voice.
 */
class AdsrEnvelope {
    var attackTime: Float = 0.05f   // seconds
    var decayTime: Float = 0.05f    // seconds
    var sustainLevel: Float = 1.0f  // 0.0 to 1.0
    var releaseTime: Float = 0.2f   // seconds

    private var currentLevel = 0f
    private var state = State.IDLE

    enum class State { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    fun gate(on: Boolean, retrigger: Boolean = false) {
        if (on) {
            if (retrigger) currentLevel = 0f
            state = State.ATTACK
        } else {
            state = State.RELEASE
        }
    }

    fun nextLevel(sampleRate: Int): Float {
        val attackStep = 1f / (attackTime.coerceAtLeast(0.001f) * sampleRate)
        val decayStep = 1f / (decayTime.coerceAtLeast(0.001f) * sampleRate)
        val releaseStep = 1f / (releaseTime.coerceAtLeast(0.001f) * sampleRate)

        when (state) {
            State.ATTACK -> {
                currentLevel += attackStep
                if (currentLevel >= 1f) {
                    currentLevel = 1f
                    state = if (sustainLevel < 1f) State.DECAY else State.SUSTAIN
                }
            }
            State.DECAY -> {
                currentLevel -= decayStep
                if (currentLevel <= sustainLevel) {
                    currentLevel = sustainLevel
                    state = State.SUSTAIN
                }
            }
            State.SUSTAIN -> {
                currentLevel = sustainLevel
            }
            State.RELEASE -> {
                currentLevel -= releaseStep
                if (currentLevel <= 0f) {
                    currentLevel = 0f
                    state = State.IDLE
                }
            }
            State.IDLE -> {
                currentLevel = 0f
            }
        }
        return currentLevel
    }

    fun isActive() = state != State.IDLE
}
