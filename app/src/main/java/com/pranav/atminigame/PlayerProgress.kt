package com.pranav.atminigame

import android.content.Context

/** Small local profile for settings, records and unlocks. No account or network required. */
class PlayerProgress(context: Context) {
    private val prefs = context.getSharedPreferences("atmini_player", Context.MODE_PRIVATE)

    var musicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC_ENABLED, value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var reducedEffects: Boolean
        get() = prefs.getBoolean(KEY_REDUCED_EFFECTS, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCED_EFFECTS, value).apply()

    var reducedMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCED_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCED_MOTION, value).apply()

    var musicVolume: Float
        get() = prefs.getFloat(KEY_MUSIC_VOLUME, 0.72f)
        set(value) = prefs.edit().putFloat(KEY_MUSIC_VOLUME, value.coerceIn(0f, 1f)).apply()

    var effectsVolume: Float
        get() = prefs.getFloat(KEY_EFFECTS_VOLUME, 0.9f)
        set(value) = prefs.edit().putFloat(KEY_EFFECTS_VOLUME, value.coerceIn(0f, 1f)).apply()

    var difficulty: Int
        get() = prefs.getInt(KEY_DIFFICULTY, 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_DIFFICULTY, value.coerceIn(0, 2)).apply()

    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL, value).apply()

    var bestCampaignScore: Int
        get() = prefs.getInt(KEY_CAMPAIGN_BEST, 0)
        private set(value) = prefs.edit().putInt(KEY_CAMPAIGN_BEST, value).apply()

    var bestEndlessDistance: Int
        get() = prefs.getInt(KEY_ENDLESS_BEST, 0)
        private set(value) = prefs.edit().putInt(KEY_ENDLESS_BEST, value).apply()

    var totalRuns: Int
        get() = prefs.getInt(KEY_RUNS, 0)
        private set(value) = prefs.edit().putInt(KEY_RUNS, value).apply()

    var totalBosses: Int
        get() = prefs.getInt(KEY_BOSSES, 0)
        private set(value) = prefs.edit().putInt(KEY_BOSSES, value).apply()

    var bestStreak: Int
        get() = prefs.getInt(KEY_STREAK, 0)
        private set(value) = prefs.edit().putInt(KEY_STREAK, value).apply()

    var cleanRuns: Int
        get() = prefs.getInt(KEY_CLEAN_RUNS, 0)
        private set(value) = prefs.edit().putInt(KEY_CLEAN_RUNS, value).apply()

    var fastestBossSeconds: Int
        get() = prefs.getInt(KEY_FASTEST_BOSS, 0)
        private set(value) = prefs.edit().putInt(KEY_FASTEST_BOSS, value).apply()

    var cosmeticBits: Int
        get() = prefs.getInt(KEY_COSMETICS, 0)
        private set(value) = prefs.edit().putInt(KEY_COSMETICS, value).apply()

    var selectedCosmetic: Int
        get() = prefs.getInt(KEY_SELECTED_COSMETIC, 0).coerceIn(0, 3)
        set(value) = prefs.edit().putInt(KEY_SELECTED_COSMETIC, value.coerceIn(0, 3)).apply()

    var companionEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPANION, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPANION, value).apply()

    var discoveredZones: Int
        get() = prefs.getInt(KEY_ZONES, 1)
        private set(value) = prefs.edit().putInt(KEY_ZONES, value).apply()

    fun recordRun(
        score: Int, distance: Int, endless: Boolean, bossDefeated: Boolean,
        clean: Boolean, streak: Int, elapsedSeconds: Int
    ) {
        totalRuns = totalRuns + 1
        bestStreak = maxOf(bestStreak, streak)
        if (endless) bestEndlessDistance = maxOf(bestEndlessDistance, distance)
        else bestCampaignScore = maxOf(bestCampaignScore, score)
        if (bossDefeated) {
            totalBosses = totalBosses + 1
            if (fastestBossSeconds == 0 || elapsedSeconds < fastestBossSeconds) fastestBossSeconds = elapsedSeconds
        }
        if (clean) cleanRuns++

        // Deterministic milestones reward play without timers or missable rewards.
        val earned = (if (totalRuns >= 1) 1 else 0) or
            (if (score >= 100) 2 else 0) or (if (totalBosses >= 1) 4 else 0)
        cosmeticBits = cosmeticBits or earned
    }

    fun hasCosmetic(bit: Int): Boolean = cosmeticBits and bit != 0

    fun discoverZone(zone: Int) {
        if (zone in 0..4) discoveredZones = discoveredZones or (1 shl zone)
    }

    fun hasDiscoveredZone(zone: Int): Boolean = discoveredZones and (1 shl zone) != 0

    companion object {
        private const val KEY_MUSIC_ENABLED = "music_enabled"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_REDUCED_EFFECTS = "reduced_effects"
        private const val KEY_REDUCED_MOTION = "reduced_motion"
        private const val KEY_MUSIC_VOLUME = "music_volume"
        private const val KEY_EFFECTS_VOLUME = "effects_volume"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_TUTORIAL = "tutorial_seen"
        private const val KEY_CAMPAIGN_BEST = "campaign_best"
        private const val KEY_ENDLESS_BEST = "endless_best"
        private const val KEY_RUNS = "total_runs"
        private const val KEY_BOSSES = "total_bosses"
        private const val KEY_STREAK = "best_streak"
        private const val KEY_CLEAN_RUNS = "clean_runs"
        private const val KEY_FASTEST_BOSS = "fastest_boss_seconds"
        private const val KEY_COSMETICS = "cosmetics"
        private const val KEY_SELECTED_COSMETIC = "selected_cosmetic"
        private const val KEY_COMPANION = "companion_enabled"
        private const val KEY_ZONES = "discovered_zones"
    }
}
