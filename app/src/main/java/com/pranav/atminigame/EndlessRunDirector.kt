package com.pranav.atminigame

import kotlin.random.Random

/** Deterministic, bounded content chunks for Endless Ascension. Player speed is never modified. */
internal class EndlessRunDirector(private val seed: Int) {
    data class OrbSpawn(val x: Float, val y: Float, val big: Boolean, val bonus: Boolean)
    data class Segment(
        val index: Int,
        val startX: Float,
        val endX: Float,
        val orbs: List<OrbSpawn>,
        val bossX: Float?,
        val loop: Int
    )

    fun segment(index: Int): Segment {
        val rnd = Random(seed xor (index * 0x45D9F3B))
        val start = FIRST_ORB_X + index * SEGMENT_LENGTH
        val loop = (start / WORLD_LOOP_LENGTH).toInt()
        val orbs = ArrayList<OrbSpawn>(ORB_COUNT)
        for (i in 0 until ORB_COUNT) {
            val x = start + i * ORB_SPACING
            val riskyRoute = rnd.nextFloat() < 0.28f
            val y = if (riskyRoute) 320f + rnd.nextInt(-35, 36) else 430f + rnd.nextInt(-70, 46)
            val big = (index * ORB_COUNT + i) % 5 == 0 || (riskyRoute && rnd.nextFloat() < 0.45f)
            orbs += OrbSpawn(x, y, big, bonus = riskyRoute && big)
        }
        val end = start + (ORB_COUNT - 1) * ORB_SPACING
        val firstLoopBoundary = ((start / WORLD_LOOP_LENGTH).toInt() + 1) * WORLD_LOOP_LENGTH
        val bossX = if (firstLoopBoundary <= end + ORB_SPACING) firstLoopBoundary + 520f else null
        return Segment(index, start, end + ORB_SPACING, orbs, bossX, loop)
    }

    companion object {
        private const val FIRST_ORB_X = 420f
        private const val ORB_SPACING = 260f
        private const val ORB_COUNT = 8
        private const val SEGMENT_LENGTH = ORB_SPACING * ORB_COUNT
        const val WORLD_LOOP_LENGTH = 8000f
    }
}
