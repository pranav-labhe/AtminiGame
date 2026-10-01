package com.pranav.atminigame.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.pranav.atminigame.R
import com.pranav.atminigame.audio.parser.GameMusicParser
import com.pranav.atminigame.audio.synth.ProceduralMusicEngine

/**
 * High-level manager coordinating procedural background music, interactive sound effects,
 * and tactile haptic feedback.
 */
class SoundManager(context: Context) {

    private val vibrator: Vibrator? = try {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } catch (e: Exception) {
        null
    }

    private val musicParser = GameMusicParser(context)
    private val musicLibrary = musicParser.parse(R.xml.game_music)
    private val musicEngine = ProceduralMusicEngine(musicLibrary)

    var soundEnabled: Boolean = true
    var hapticsEnabled: Boolean = true
    var musicEnabled: Boolean = true
    var musicVolume: Float = 0.72f
        set(value) { field = value.coerceIn(0f, 1f); musicEngine.musicVolume = field }
    var effectsVolume: Float = 0.9f
        set(value) { field = value.coerceIn(0f, 1f); musicEngine.effectsVolume = field }

    private var currentTrackId: String? = null
    private var lastMoveSoundTime = 0L

    fun startMusic(trackId: String) {
        currentTrackId = trackId
        if (musicEnabled) {
            musicEngine.play(trackId)
        } else musicEngine.stop()
    }

    fun stopMusic() {
        musicEngine.stop()
    }

    fun resumeMusic() {
        if (musicEnabled) {
            val tid = currentTrackId ?: "game"
            startMusic(tid)
        }
    }

    fun playCollectOrb(isBig: Boolean) {
        if (soundEnabled) {
            musicEngine.triggerCollect(isBig)
        }
        vibrate(if (isBig) 40L else 18L)
    }

    fun playJump() {
        if (soundEnabled) {
            musicEngine.triggerJump()
        }
        vibrate(25L)
    }

    fun playMove(forward: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastMoveSoundTime > 110L) { // Throttle step clicks for pleasant rhythm
            lastMoveSoundTime = now
            if (soundEnabled) {
                musicEngine.triggerMove(forward)
            }
        }
    }

    fun playAiMode(enabled: Boolean) {
        if (soundEnabled) {
            musicEngine.triggerAiMode(enabled)
            // Switch background mood to AI track when enabled, return to game track when disabled
            val nextTrack = if (enabled) "aimode" else "game"
            startMusic(nextTrack)
        }
        vibratePattern(longArrayOf(0, 30, 40, 30))
    }

    fun playGameComplete() {
        if (soundEnabled) {
            musicEngine.triggerVictoryFanfare()
            startMusic("success")
        }
        vibratePattern(longArrayOf(0, 60, 50, 80, 50, 120))
    }

    fun playReset() {
        if (soundEnabled) {
            startMusic("game")
        }
        vibrate(30L)
    }

    fun playWorldZone(zone: Int) {
        val track = when (((zone % 5) + 5) % 5) {
            0 -> "zone_dawn"
            1 -> "zone_garden"
            2 -> "zone_ocean"
            3 -> "zone_aurora"
            else -> "zone_celestial"
        }
        if (musicEnabled && currentTrackId != track) startMusic(track)
    }

    private fun vibrate(durationMs: Long) {
        if (!hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignore haptic failures gracefully
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        if (!hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            // Ignore haptic failures gracefully
        }
    }

    fun release() {
        musicEngine.release()
    }
}
