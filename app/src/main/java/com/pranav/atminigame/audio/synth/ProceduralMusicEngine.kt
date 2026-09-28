package com.pranav.atminigame.audio.synth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pranav.atminigame.audio.model.MusicLibrary
import com.pranav.atminigame.audio.model.TrackConfig
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * High-performance real-time procedural synthesizer engine.
 * Generates dynamic PCM floating-point audio for background music and interactive sound effects.
 */
class ProceduralMusicEngine(private val library: MusicLibrary) {

    private val sampleRate = 44100
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT
    )

    private var audioTrack: AudioTrack? = null
    private var synthJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var currentTrackId: String? = null
    @Volatile private var currentTrack: TrackConfig? = null

    // Background Synthesis Voices
    private var bassOsc: Oscillator = SquareWaveOscillator()
    private var currentBassWaveform = "square"
    private val bassEnv = AdsrEnvelope().apply {
        attackTime = 0.008f
        decayTime = 0.12f
        sustainLevel = 0.45f
        releaseTime = 0.10f
    }
    private var bassLpfOut = 0f
    private val bassLpfAlpha = 0.12f // 1-pole Low-Pass Filter (~1100 Hz cutoff) to tame high-frequency square wave fizz

    private var padOsc: Oscillator = SawtoothWaveOscillator()
    private var currentPadWaveform = "sawtooth"
    private val padEnv = AdsrEnvelope()

    private var arpOsc: Oscillator = SquareWaveOscillator()
    private val arpEnv = AdsrEnvelope()

    // Sound Effect Voice 1: Collect / Chime / Fanfare
    private val collectOsc1 = SineWaveOscillator()
    private val collectOsc2 = TriangleWaveOscillator()
    private val collectEnv = AdsrEnvelope().apply {
        attackTime = 0.005f
        decayTime = 0.05f
        sustainLevel = 0.4f
        releaseTime = 0.35f
    }
    private var collectFreq1 = 880f
    private var collectFreq2 = 1320f
    private var collectVolume = 0.3f

    // Sound Effect Voice 2: Jump Sweep
    private val jumpOsc = SquareWaveOscillator()
    private val jumpEnv = AdsrEnvelope().apply {
        attackTime = 0.008f
        decayTime = 0.08f
        sustainLevel = 0.2f
        releaseTime = 0.16f
    }
    private var jumpCurrentFreq = 180f
    private var jumpTargetFreq = 620f
    private var jumpProgressSamples = 0
    private var jumpTotalSamples = 1

    // Sound Effect Voice 3: Move Stepper / Footstep Servo
    private val moveOsc = TriangleWaveOscillator()
    private val moveEnv = AdsrEnvelope().apply {
        attackTime = 0.003f
        decayTime = 0.02f
        sustainLevel = 0.0f
        releaseTime = 0.035f
    }
    private var moveFreq = 320f

    // Sound Effect Voice 4: AI Mode Handshake / Fanfare Sequencer
    private val sfxSeqOsc = SawtoothWaveOscillator()
    private val sfxSeqEnv = AdsrEnvelope()
    private var sfxSeqNotes = floatArrayOf()
    private var sfxSeqStep = 0
    private var sfxSeqSampleCount = 0L
    private var sfxSeqSamplesPerStep = 0

    // Delay & Spatial Effect
    private val delayBuffer = FloatArray(sampleRate) // 1.0 second delay line
    private var delayWriteIdx = 0
    private var feedback = 0.32f

    // DC Blocker & High-Pass (Cutoff ~20 Hz at 44.1 kHz)
    private var lastOut = 0f
    private var lastIn = 0f
    private val hpAlpha = 0.997f

    // Sequencer State
    private var sampleCount = 0L
    private var cachedBassFreq = 0f
    private var targetBassFreq = 0f
    private var cachedPadFreq = 0f
    private var targetPadFreq = 0f
    private var lastArpIdx = -1
    private var cachedArpFreq = 0f
    private var targetArpFreq = 0f

    // LFO for Wobble
    private var lfoPhase = 0f
    private val lfoFreq = 0.45f

    init {
        ensureAudioTrack()
    }

    private fun ensureAudioTrack() {
        if (audioTrack == null || audioTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
            val trackBufferSize = bufferSize.coerceAtLeast(4096) * 2
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    fun play(trackId: String) {
        val config = library.tracks[trackId] ?: return
        if (currentTrackId == trackId && synthJob?.isActive == true) return

        currentTrackId = trackId
        currentTrack = config

        ensureAudioTrack()

        if (config.bass.waveform != currentBassWaveform) {
            bassOsc = createOscillator(config.bass.waveform)
            currentBassWaveform = config.bass.waveform
        }
        if (config.pad.waveform != currentPadWaveform) {
            padOsc = createOscillator(config.pad.waveform)
            currentPadWaveform = config.pad.waveform
        }

        padEnv.attackTime = config.pad.attack
        padEnv.releaseTime = config.pad.release
        padEnv.gate(true, retrigger = true)

        arpEnv.attackTime = 0.01f
        arpEnv.releaseTime = 0.12f

        targetBassFreq = 0f
        targetPadFreq = 0f
        targetArpFreq = 0f
        lastArpIdx = -1
        sampleCount = 0L

        if (synthJob == null || synthJob?.isActive == false) {
            startLoop()
        }
    }

    private fun createOscillator(waveform: String): Oscillator {
        return when (waveform.lowercase()) {
            "square" -> SquareWaveOscillator()
            "sawtooth", "saw" -> SawtoothWaveOscillator()
            "triangle" -> TriangleWaveOscillator()
            else -> SineWaveOscillator()
        }
    }

    private fun startLoop() {
        synthJob = scope.launch {
            try {
                audioTrack?.play()
            } catch (e: Exception) {
                // Ignore if audio track had transient error
            }
            val floatBuffer = FloatArray(bufferSize / 4)
            while (isActive) {
                for (i in floatBuffer.indices) {
                    floatBuffer[i] = generateSample()
                }
                audioTrack?.write(floatBuffer, 0, floatBuffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    private fun generateSample(): Float {
        val config = currentTrack

        var bassSample = 0f
        var padSample = 0f
        var arpSample = 0f

        if (config != null) {
            val baseBassFreq = ScaleHelper.rootToFrequency(config.root, config.bass.octave + 1)
            val basePadFreq = ScaleHelper.rootToFrequency(config.root, config.pad.octave + 1)

            if (targetBassFreq != baseBassFreq) {
                targetBassFreq = baseBassFreq
                targetPadFreq = basePadFreq
                if (cachedBassFreq == 0f) cachedBassFreq = targetBassFreq
                if (cachedPadFreq == 0f) cachedPadFreq = targetPadFreq
            }

            cachedBassFreq += (targetBassFreq - cachedBassFreq) * 0.05f
            cachedPadFreq += (targetPadFreq - cachedPadFreq) * 0.05f

            // LFO Pitch Wobble
            lfoPhase += 2f * PI.toFloat() * lfoFreq / sampleRate
            if (lfoPhase > 2f * PI.toFloat()) lfoPhase -= 2f * PI.toFloat()
            val wobbleOffset = sin(lfoPhase) * config.pad.wobble * 3.5f

            val samplesPerBeat = (sampleRate * 60) / config.tempo
            val samplesPerArp = when (config.arpeggio.noteLength) {
                "sixteenth" -> samplesPerBeat / 4
                "eighth" -> samplesPerBeat / 2
                "half" -> samplesPerBeat * 2
                else -> samplesPerBeat // quarter
            }

            // Bass Rhythm Envelope Retriggering (eighth note pulse for clear note attacks)
            val samplesPerBassStep = samplesPerBeat / 2
            if (sampleCount % samplesPerBassStep.coerceAtLeast(1) == 0L) {
                bassEnv.gate(true, retrigger = true)
            } else if (sampleCount % samplesPerBassStep.coerceAtLeast(1) == (samplesPerBassStep * 0.70f).toLong()) {
                bassEnv.gate(false)
            }

            val rawBass = bassOsc.nextSample(cachedBassFreq, sampleRate) * bassEnv.nextLevel(sampleRate) * config.bass.volume
            // Low-pass filter to smooth square wave edges and tame high-frequency buzz
            bassLpfOut += (rawBass - bassLpfOut) * bassLpfAlpha
            bassSample = bassLpfOut

            padSample = padOsc.nextSample(cachedPadFreq + wobbleOffset, sampleRate) * padEnv.nextLevel(sampleRate) * config.pad.volume

            val arpStep = (sampleCount / samplesPerArp.coerceAtLeast(1)).toInt()
            val arpIdx = arpStep % config.arpeggio.pattern.size.coerceAtLeast(1)

            if (arpIdx != lastArpIdx && config.arpeggio.pattern.isNotEmpty()) {
                val degree = config.arpeggio.pattern[arpIdx]
                targetArpFreq = ScaleHelper.degreeToFreq(config.root, config.bass.octave + 2, degree, config.scale)
                if (cachedArpFreq == 0f) cachedArpFreq = targetArpFreq
                lastArpIdx = arpIdx
            }

            cachedArpFreq += (targetArpFreq - cachedArpFreq) * 0.08f

            if (sampleCount % samplesPerArp.coerceAtLeast(1) == 0L) {
                arpEnv.gate(true, retrigger = true)
            } else if (sampleCount % samplesPerArp.coerceAtLeast(1) == (samplesPerArp * 0.75f).toLong()) {
                arpEnv.gate(false)
            }

            arpSample = arpOsc.nextSample(cachedArpFreq + wobbleOffset, sampleRate) *
                    arpEnv.nextLevel(sampleRate) *
                    config.arpeggio.volume
        }

        // --- Interactive Sound Effects Synthesis ---
        // 1. Collect Chime
        val collectLevel = collectEnv.nextLevel(sampleRate)
        val sfxCollect = if (collectEnv.isActive()) {
            (collectOsc1.nextSample(collectFreq1, sampleRate) * 0.6f +
             collectOsc2.nextSample(collectFreq2, sampleRate) * 0.4f) * collectLevel * collectVolume
        } else 0f

        // 2. Jump Pitch Sweep
        val jumpLevel = jumpEnv.nextLevel(sampleRate)
        val sfxJump = if (jumpEnv.isActive()) {
            if (jumpProgressSamples < jumpTotalSamples) {
                val t = jumpProgressSamples.toFloat() / jumpTotalSamples
                jumpCurrentFreq = 180f + (jumpTargetFreq - 180f) * (t * t)
                jumpProgressSamples++
            }
            jumpOsc.nextSample(jumpCurrentFreq, sampleRate) * jumpLevel * 0.28f
        } else 0f

        // 3. Move Step / Servo
        val moveLevel = moveEnv.nextLevel(sampleRate)
        val sfxMove = if (moveEnv.isActive()) {
            moveOsc.nextSample(moveFreq, sampleRate) * moveLevel * 0.18f
        } else 0f

        // 4. SFX Sequence (AI Mode Chirp / Victory Fanfare)
        val seqLevel = sfxSeqEnv.nextLevel(sampleRate)
        var sfxSeq = 0f
        if (sfxSeqNotes.isNotEmpty() && sfxSeqStep < sfxSeqNotes.size) {
            val currentFreq = sfxSeqNotes[sfxSeqStep]
            sfxSeq = sfxSeqOsc.nextSample(currentFreq, sampleRate) * seqLevel * 0.32f
            sfxSeqSampleCount++
            if (sfxSeqSampleCount >= sfxSeqSamplesPerStep) {
                sfxSeqSampleCount = 0
                sfxSeqStep++
                if (sfxSeqStep < sfxSeqNotes.size) {
                    sfxSeqEnv.gate(true, retrigger = true)
                } else {
                    sfxSeqEnv.gate(false)
                }
            } else if (sfxSeqSampleCount == (sfxSeqSamplesPerStep * 0.8f).toLong()) {
                sfxSeqEnv.gate(false)
            }
        }

        sampleCount++

        // Mixer & Space Delay
        val drySfx = sfxCollect + sfxJump + sfxMove + sfxSeq
        val spatialMelody = (padSample + arpSample + sfxCollect * 0.4f + sfxSeq * 0.3f) * 0.3f

        val delayReadIdx = (delayWriteIdx + 1) % delayBuffer.size
        val delayedSignal = delayBuffer[delayReadIdx]
        val spatialOut = spatialMelody + delayedSignal * feedback
        delayBuffer[delayWriteIdx] = spatialOut
        delayWriteIdx = (delayWriteIdx + 1) % delayBuffer.size

        // Total Mix
        val mixed = (bassSample * 0.42f) + spatialOut + (drySfx * 0.85f)

        // DC High-Pass Filter (Cutoff ~20 Hz)
        val out = hpAlpha * (lastOut + mixed - lastIn)
        lastIn = mixed
        lastOut = out

        return softLimit(out)
    }

    private fun softLimit(x: Float): Float {
        // Continuous, smooth hyperbolic tangent limiter without hard knee discontinuities
        return tanh(x.toDouble()).toFloat()
    }

    // --- Dynamic Sound Effects API ---

    fun triggerCollect(isBig: Boolean) {
        if (isBig) {
            collectFreq1 = 1174.66f // D6
            collectFreq2 = 1760.00f // A6
            collectVolume = 0.45f
            collectEnv.attackTime = 0.005f
            collectEnv.releaseTime = 0.6f
        } else {
            collectFreq1 = 880.00f  // A5
            collectFreq2 = 1318.51f // E6
            collectVolume = 0.32f
            collectEnv.attackTime = 0.004f
            collectEnv.releaseTime = 0.28f
        }
        collectEnv.gate(true, retrigger = true)
    }

    fun triggerJump() {
        jumpCurrentFreq = 180f
        jumpTargetFreq = 650f
        jumpProgressSamples = 0
        jumpTotalSamples = (sampleRate * 0.16f).toInt()
        jumpEnv.gate(true, retrigger = true)
    }

    fun triggerMove(forward: Boolean) {
        moveFreq = if (forward) 360f else 260f
        moveEnv.gate(true, retrigger = true)
    }

    fun triggerAiMode(enabled: Boolean) {
        sfxSeqNotes = if (enabled) {
            floatArrayOf(587.33f, 880.00f, 1174.66f) // D5 -> A5 -> D6
        } else {
            floatArrayOf(1174.66f, 880.00f, 587.33f) // D6 -> A5 -> D5
        }
        sfxSeqSamplesPerStep = (sampleRate * 0.08f).toInt()
        sfxSeqStep = 0
        sfxSeqSampleCount = 0
        sfxSeqEnv.attackTime = 0.005f
        sfxSeqEnv.releaseTime = 0.08f
        sfxSeqEnv.gate(true, retrigger = true)
    }

    fun triggerVictoryFanfare() {
        sfxSeqNotes = floatArrayOf(
            587.33f,  // D5
            739.99f,  // F#5
            880.00f,  // A5
            1174.66f, // D6
            1479.98f, // F#6
            1760.00f  // A6
        )
        sfxSeqSamplesPerStep = (sampleRate * 0.10f).toInt()
        sfxSeqStep = 0
        sfxSeqSampleCount = 0
        sfxSeqEnv.attackTime = 0.005f
        sfxSeqEnv.releaseTime = 0.25f
        sfxSeqEnv.gate(true, retrigger = true)
    }

    fun stop() {
        synthJob?.cancel()
        synthJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore on teardown
        }
        audioTrack = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
