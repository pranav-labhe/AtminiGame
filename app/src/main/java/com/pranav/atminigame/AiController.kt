package com.pranav.atminigame

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiController(private val context: Context, private val modelPath: String) {
    @Volatile
    private var llmInference: LlmInference? = null

    @Volatile
    var isInitialized = false

    private val mutex = Mutex()

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext true
        try {
            val outputFile = context.filesDir.resolve(modelPath)
            if (!outputFile.exists()) {
                context.assets.open(modelPath).use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(outputFile.absolutePath)
                .setMaxTokens(512)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Log.d("AiController", "LLM Initialized successfully")
            true
        } catch (e: Exception) {
            Log.e("AiController", "Failed to initialize: ${e.message}")
            isInitialized = false
            false
        }
    }

    // STEP 1: AI chooses an orb from the available list
    suspend fun decideTargetOrb(
        x: Float,
        y: Float,
        orbs: List<Pair<Float, Float>>
    ): Pair<Float, Float>? {
        if (llmInference == null) return null
        if (!mutex.tryLock()) return null
        try {
            return withContext(Dispatchers.Default) {
                val available = orbs
                    .sortedBy { (ox, oy) -> kotlin.math.hypot((ox - x).toDouble(), (oy - y).toDouble()) }
                    .take(7)

                if (available.isEmpty()) return@withContext null

                val orbDescriptions = available.mapIndexed { i, (ox, oy) ->
                    val side = if (ox > x) "right" else "left"
                    val distance = kotlin.math.hypot((ox - x).toDouble(), (oy - y).toDouble())
                    val dist = when {
                        distance < 150 -> "very close"
                        distance < 400 -> "near"
                        else -> "far"
                    }
                    val needsJump = oy < y - 50
                    val heightNote = if (needsJump) "requires a jump" else "same level"
                    "Orb ${i + 1} is $side, $dist, and $heightNote"
                }.joinToString(", ")

                val prompt = "$orbDescriptions. Which orb number do you choose? Answer with only the number."
                Log.d("AiController", "Target prompt: $prompt")
                val raw = llmInference?.generateResponse(prompt)
                Log.d("AiController", "Target raw: '$raw'")

                val chosenIndex = Regex("\\d+").find(raw ?: "")?.value?.toIntOrNull()?.minus(1)
                chosenIndex?.let { available.getOrNull(it) } ?: available.first()
            }
        } catch (e: Exception) {
            Log.e("AiController", "Target inference error: ${e.message}")
            return null
        } finally {
            mutex.unlock()
        }
    }

    // STEP 2: AI decides whether to approach the chosen orb or move away from it
    suspend fun decideApproach(x: Float, y: Float, targetX: Float, targetY: Float): String {
        if (llmInference == null) return "APPROACH"
        if (!mutex.tryLock()) return "APPROACH"
        try {
            return withContext(Dispatchers.Default) {
                val side = if (targetX > x) "right" else "left"
                val distance = kotlin.math.hypot((targetX - x).toDouble(), (targetY - y).toDouble())
                val dist = when {
                    distance < 150 -> "very close"
                    distance < 400 -> "near"
                    else -> "far"
                }
                val needsJump = targetY < y - 50
                val heightNote = if (needsJump) "requires a jump" else "same level"

                val prompt = "Your chosen orb is $side, $dist, and $heightNote. " +
                        "Which single word describes what to do: " +
                        "\n1. APPROACH  \n2. AWAY?"

                Log.d("AiController", "Approach prompt: $prompt")
                val raw = llmInference?.generateResponse(prompt)
                val cleaned = raw?.replace(Regex("\\s+"), " ")?.trim()?.uppercase()
                Log.d("AiController", "Approach raw: '$raw' -> '$cleaned'")

                val validActions = setOf("APPROACH", "AWAY")
                cleaned?.let { c -> validActions.firstOrNull { c.contains(it) } } ?: "APPROACH"
            }
        } catch (e: Exception) {
            Log.e("AiController", "Approach inference error: ${e.message}")
            return "APPROACH"
        } finally {
            mutex.unlock()
        }
    }

    // STEP 3: AI decides to jump or skip once arrived at the target
    suspend fun decideJumpOrSkip(x: Float, y: Float, targetX: Float, targetY: Float): String {
        if (llmInference == null) return "SKIP"
        if (!mutex.tryLock()) return "SKIP"
        try {
            return withContext(Dispatchers.Default) {
                val side = if (targetX > x) "right" else "left"
                val needsJump = targetY < y - 50
                val heightNote = if (needsJump) "above you and requires a jump" else "at your level"

                val prompt = "Your chosen orb is $side and $heightNote. " +
                        "Which single word describes what to do: " +
                        "\n1. JUMP  \n2. SKIP?"

                Log.d("AiController", "JumpOrSkip prompt: $prompt")
                val raw = llmInference?.generateResponse(prompt)
                val cleaned = raw?.replace(Regex("\\s+"), " ")?.trim()?.uppercase()
                Log.d("AiController", "JumpOrSkip raw: '$raw' -> '$cleaned'")

                val validActions = setOf("JUMP", "SKIP")
                cleaned?.let { c -> validActions.firstOrNull { c.contains(it) } } ?: "SKIP"
            }
        } catch (e: Exception) {
            Log.e("AiController", "JumpOrSkip inference error: ${e.message}")
            return "SKIP"
        } finally {
            mutex.unlock()
        }
    }

    fun close() {
        val inference = llmInference
        llmInference = null
        isInitialized = false
        try {
            inference?.close()
        } catch (e: Exception) {
            Log.w("AiController", "LLM cleanup failed safely: ${e.message}")
        }
    }
}
