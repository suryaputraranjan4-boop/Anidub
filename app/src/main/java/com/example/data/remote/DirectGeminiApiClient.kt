package com.example.data.remote

import android.util.Base64
import android.util.Log
import com.example.data.preferences.GeminiApiKeyStore
import com.example.engine.MixedScriptLanguageEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class GeminiErrorKind {
    MISSING_API_KEY,
    INVALID_API_KEY,
    RATE_LIMITED,
    QUOTA_OR_BILLING,
    MODEL_NOT_FOUND,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    SAFETY_BLOCKED,
    SERVER_ERROR,
    INVALID_RESPONSE,
    CANCELLED
}

/**
 * Explicit 8-state Direct Gemini API connection & credential status for UI consistency.
 * Prevents ever showing "CONNECTED" or "API READY" merely because a key string is saved.
 */
enum class GeminiConnectionState(val pillLabel: String) {
    API_KEY_MISSING("KEY MISSING"),
    API_KEY_SAVED_UNVERIFIED("KEY SAVED (UNVERIFIED)"),
    TESTING("TESTING…"),
    VERIFIED_CONNECTED("VERIFIED CONNECTED"),
    AUTH_FAILED("AUTH FAILED"),
    QUOTA_OR_RATE_LIMITED("QUOTA / RATE LIMIT"),
    NETWORK_UNAVAILABLE("NETWORK UNAVAILABLE"),
    MODEL_UNAVAILABLE("MODEL UNAVAILABLE")
}

class GeminiApiException(
    val kind: GeminiErrorKind,
    val httpCode: Int? = null,
    override val message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

data class GeminiModelOption(
    val modelId: String,
    val displayName: String,
    val description: String,
    val supportsAudioInput: Boolean = true
)

data class GeminiConnectionTestResult(
    val isConnected: Boolean,
    val modelId: String,
    val remoteDisplayName: String,
    val latencyMs: Long,
    val message: String,
    val errorKind: GeminiErrorKind? = null,
    val connectionState: GeminiConnectionState = if (isConnected) GeminiConnectionState.VERIFIED_CONNECTED else GeminiConnectionState.API_KEY_SAVED_UNVERIFIED
)

data class GeminiDialogueTranslationItem(
    val sourceIndex: Int,
    val sentences: List<String>,
    val emotion: String
)

data class GeminiDialogueRewriteResult(
    val rewrittenMixedScript: String,
    val emotion: String,
    val reviewNotes: String,
    val issuesFound: List<String>
)

data class GeminiAudioTranscriptionResult(
    val japaneseText: String,
    val emotion: String,
    val isSpeechDetected: Boolean
)

/**
 * Single Centralized Direct Gemini REST API Client (No Firebase, No Remote Worker).
 *
 * All Gemini requests in the application (connection test, Japanese->Mixed-Script translation,
 * sentence splitting, contextual dialogue rewriting/review, script correction, and optional online
 * multimodal Japanese audio transcription) route exclusively through this client.
 *
 * Security & Reliability Guarantees:
 * - Passes the API key strictly via the `x-goog-api-key` HTTP header (never in URL query params).
 * - Never logs raw API keys or sensitive headers.
 * - Supports cooperative coroutine cancellation (cancels active OkHttp calls immediately).
 * - Implements exponential backoff retries for transient 429/5xx/network faults.
 * - Classifies invalid key, quota/billing, rate-limit, missing model, offline, and timeout errors cleanly.
 */
class DirectGeminiApiClient(
    private val keyStore: GeminiApiKeyStore
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(115, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(requestedModelName: String): Result<GeminiConnectionTestResult> = withContext(Dispatchers.IO) {
        val modelValidation = validateModelSelection(requestedModelName)
        if (modelValidation.isFailure) {
            return@withContext Result.failure(modelValidation.exceptionOrNull()!!)
        }
        val modelId = modelValidation.getOrThrow()
        val apiKey = keyStore.getEffectiveApiKey()
        if (apiKey.isBlank()) {
            val ex = GeminiApiException(
                kind = GeminiErrorKind.MISSING_API_KEY,
                message = "No Gemini API key configured. Enter your Google AI Studio API key in Settings."
            )
            return@withContext Result.failure(ex)
        }

        val startMs = System.currentTimeMillis()
        val url = "$BASE_URL/models/$modelId"
        val request = Request.Builder()
            .url(url)
            .get()
            .header(HEADER_API_KEY, apiKey)
            .header("Accept", "application/json")
            .build()

        return@withContext try {
            val bodyStr = executeWithRetry(
                request = request,
                operationName = "testConnection",
                modelId = modelId,
                maxAttempts = 2
            )
            val json = JSONObject(bodyStr)
            val displayName = json.optString("displayName", modelId).ifBlank { modelId }
            val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            Result.success(
                GeminiConnectionTestResult(
                    isConnected = true,
                    modelId = modelId,
                    remoteDisplayName = displayName,
                    latencyMs = latencyMs,
                    message = "Verified connection to $displayName ($modelId) in ${latencyMs} ms.",
                    connectionState = GeminiConnectionState.VERIFIED_CONNECTED
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                GeminiApiException(
                    kind = GeminiErrorKind.INVALID_RESPONSE,
                    message = "Gemini connection test returned unexpected data: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    cause = e
                )
            )
        }
    }

    suspend fun translateAndSegmentDialogues(
        modelName: String,
        animeSeriesName: String,
        episodeTitle: String,
        dictionaryPromptBlock: String,
        speakerContextBlock: String,
        dialogueBatchPromptBlock: String
    ): Result<List<GeminiDialogueTranslationItem>> = withContext(Dispatchers.IO) {
        val systemInstruction = """
You are a master Japanese-to-Hindi anime dubbing script writer for Indian audiences.
${MixedScriptLanguageEngine.PERMANENT_MIXED_SCRIPT_SYSTEM_RULES}

CRITICAL DUBBING & SENTENCE SEGMENTATION RULES:
1. Context awareness: Use the preceding and following dialogue lines plus character personality/style notes to maintain natural tone, continuity, honorific feel, sarcasm, humor, and emotional flow.
2. Natural Sentence Splitting: If a Japanese dialogue turn contains multiple distinct sentences, dramatic pauses, or rapid exchanges, split it into 1..N natural mixed-script sentences in `sentences[]` (at least 1 non-empty sentence per turn).
3. Dictionary Precedence (Project > Anime > Global): Follow every term preference provided in the dictionary block below without corrupting unrelated words.
4. NEVER invent or fabricate timestamps — timestamps are computed separately from acoustic audio timing.
5. Output STRICT valid JSON matching the requested schema.
        """.trimIndent()

        val userPrompt = """
Anime Series: $animeSeriesName
Episode: $episodeTitle

SPEAKER PERSONALITY & STYLE CONTEXT:
$speakerContextBlock

ACTIVE TERMINOLOGY DICTIONARY (Highest precedence first: Project > Anime > Global):
$dictionaryPromptBlock

JAPANESE DIALOGUE BATCH TO TRANSLATE (keep every sourceIndex intact):
$dialogueBatchPromptBlock
        """.trimIndent()

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("items", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("sourceIndex", JSONObject().put("type", "INTEGER"))
                            put("sentences", JSONObject().apply {
                                put("type", "ARRAY")
                                put("items", JSONObject().put("type", "STRING"))
                            })
                            put("emotion", JSONObject().put("type", "STRING"))
                        })
                        put("required", JSONArray(listOf("sourceIndex", "sentences", "emotion")))
                    })
                })
            })
            put("required", JSONArray(listOf("items")))
        }

        val rawRes = generateStructuredJson(
            modelName = modelName,
            operationName = "translateAndSegmentDialogues",
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            responseSchema = schema,
            temperature = 0.25
        )
        if (rawRes.isFailure) return@withContext Result.failure(rawRes.exceptionOrNull()!!)

        return@withContext try {
            val root = JSONObject(stripCodeFences(rawRes.getOrThrow()))
            val arr = root.optJSONArray("items")
                ?: throw GeminiApiException(GeminiErrorKind.INVALID_RESPONSE, message = "Gemini response missing 'items' array.")
            val parsed = mutableListOf<GeminiDialogueTranslationItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val srcIdx = obj.optInt("sourceIndex", -1)
                if (srcIdx < 0) continue
                val sentArr = obj.optJSONArray("sentences")
                val sentences = mutableListOf<String>()
                if (sentArr != null) {
                    for (j in 0 until sentArr.length()) {
                        val s = sentArr.optString(j, "").trim()
                        if (s.isNotEmpty()) sentences.add(s)
                    }
                }
                val emotion = normalizeEmotion(obj.optString("emotion", "NEUTRAL"))
                if (sentences.isNotEmpty()) {
                    parsed.add(GeminiDialogueTranslationItem(srcIdx, sentences, emotion))
                }
            }
            if (parsed.isEmpty()) {
                Result.failure(
                    GeminiApiException(
                        kind = GeminiErrorKind.INVALID_RESPONSE,
                        message = "Gemini returned 0 usable dialogue translations."
                    )
                )
            } else {
                Result.success(parsed)
            }
        } catch (e: GeminiApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                GeminiApiException(
                    kind = GeminiErrorKind.INVALID_RESPONSE,
                    message = "Failed to parse Gemini translation JSON: ${e.localizedMessage}",
                    cause = e
                )
            )
        }
    }

    suspend fun correctMixedScriptBatch(
        modelName: String,
        dictionaryPromptBlock: String,
        linesToFix: List<Pair<Int, String>>
    ): Result<Map<Int, String>> = withContext(Dispatchers.IO) {
        if (linesToFix.isEmpty()) return@withContext Result.success(emptyMap())

        val systemInstruction = """
You are a strict Hindi-English Mixed-Script proofreader.
${MixedScriptLanguageEngine.PERMANENT_MIXED_SCRIPT_SYSTEM_RULES}

TASK:
Convert any Romanized Hindi words into accurate Devanagari script while keeping natural English words, brands, technical terms, and proper nouns in English/Roman characters.
Preserve the exact meaning, punctuation, tone, and dictionary terms. Return STRICT JSON only.
        """.trimIndent()

        val linesText = linesToFix.joinToString("\n") { (idx, line) -> "[$idx] $line" }
        val userPrompt = """
ACTIVE TERMINOLOGY DICTIONARY:
$dictionaryPromptBlock

LINES TO CORRECT TO PERMANENT MIXED-SCRIPT (Devanagari Hindi + Roman English):
$linesText
        """.trimIndent()

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("corrections", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("index", JSONObject().put("type", "INTEGER"))
                            put("fixedText", JSONObject().put("type", "STRING"))
                        })
                        put("required", JSONArray(listOf("index", "fixedText")))
                    })
                })
            })
            put("required", JSONArray(listOf("corrections")))
        }

        val rawRes = generateStructuredJson(
            modelName = modelName,
            operationName = "correctMixedScriptBatch",
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            responseSchema = schema,
            temperature = 0.1
        )
        if (rawRes.isFailure) return@withContext Result.failure(rawRes.exceptionOrNull()!!)

        return@withContext try {
            val root = JSONObject(stripCodeFences(rawRes.getOrThrow()))
            val arr = root.optJSONArray("corrections") ?: JSONArray()
            val out = LinkedHashMap<Int, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val idx = obj.optInt("index", -1)
                val fixed = obj.optString("fixedText", "").trim()
                if (idx >= 0 && fixed.isNotEmpty()) {
                    out[idx] = fixed
                }
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(
                GeminiApiException(
                    kind = GeminiErrorKind.INVALID_RESPONSE,
                    message = "Failed to parse Gemini script correction JSON: ${e.localizedMessage}",
                    cause = e
                )
            )
        }
    }

    suspend fun rewriteOrReviewDialogue(
        modelName: String,
        animeSeriesName: String,
        speakerName: String,
        speakerPersonality: String,
        japaneseSource: String,
        currentMixedScript: String,
        prevContext: String,
        nextContext: String,
        dictionaryPromptBlock: String,
        instruction: String
    ): Result<GeminiDialogueRewriteResult> = withContext(Dispatchers.IO) {
        val systemInstruction = """
You are a senior Hindi anime dubbing director reviewing and polishing a single dialogue line.
${MixedScriptLanguageEngine.PERMANENT_MIXED_SCRIPT_SYSTEM_RULES}

Review the dialogue for:
1. Strict Devanagari Hindi + Roman English compliance (zero Romanized Hindi words).
2. Natural conversational dubbing rhythm matching the character personality and surrounding scene context.
3. Exact compliance with the provided Project > Anime > Global dictionary preferences.
4. Concise review notes listing any issues fixed.
        """.trimIndent()

        val userPrompt = """
Anime Series: $animeSeriesName
Speaker: $speakerName
Speaker Personality & Voice Style: $speakerPersonality
Previous Line Context: ${prevContext.ifBlank { "(start of scene)" }}
Next Line Context: ${nextContext.ifBlank { "(end of scene)" }}

Japanese Source: $japaneseSource
Current Line: $currentMixedScript
Director Instruction: ${instruction.ifBlank { "Polish for natural dubbing flow, verify mixed-script Devanagari Hindi + English, and check dictionary terms." }}

ACTIVE TERMINOLOGY DICTIONARY:
$dictionaryPromptBlock
        """.trimIndent()

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("rewrittenMixedScript", JSONObject().put("type", "STRING"))
                put("emotion", JSONObject().put("type", "STRING"))
                put("reviewNotes", JSONObject().put("type", "STRING"))
                put("issuesFound", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", JSONObject().put("type", "STRING"))
                })
            })
            put("required", JSONArray(listOf("rewrittenMixedScript", "emotion", "reviewNotes", "issuesFound")))
        }

        val rawRes = generateStructuredJson(
            modelName = modelName,
            operationName = "rewriteOrReviewDialogue",
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            responseSchema = schema,
            temperature = 0.25
        )
        if (rawRes.isFailure) return@withContext Result.failure(rawRes.exceptionOrNull()!!)

        return@withContext try {
            val obj = JSONObject(stripCodeFences(rawRes.getOrThrow()))
            val rewritten = obj.optString("rewrittenMixedScript", "").trim()
            if (rewritten.isEmpty()) {
                return@withContext Result.failure(
                    GeminiApiException(GeminiErrorKind.INVALID_RESPONSE, message = "Gemini rewrite returned empty dialogue text.")
                )
            }
            val emotion = normalizeEmotion(obj.optString("emotion", "NEUTRAL"))
            val notes = obj.optString("reviewNotes", "").trim()
            val issuesArr = obj.optJSONArray("issuesFound") ?: JSONArray()
            val issues = (0 until issuesArr.length()).mapNotNull { i ->
                issuesArr.optString(i, "").trim().takeIf { it.isNotEmpty() }
            }
            Result.success(
                GeminiDialogueRewriteResult(
                    rewrittenMixedScript = rewritten,
                    emotion = emotion,
                    reviewNotes = notes,
                    issuesFound = issues
                )
            )
        } catch (e: Exception) {
            Result.failure(
                GeminiApiException(
                    kind = GeminiErrorKind.INVALID_RESPONSE,
                    message = "Failed to parse Gemini dialogue review JSON: ${e.localizedMessage}",
                    cause = e
                )
            )
        }
    }

    /**
     * Online multimodal audio transcription via Direct Gemini API.
     * Honestly labeled as Direct Gemini Audio Transcription (NOT Whisper / WhisperX forced alignment).
     * Never invents word-level timestamps — only transcribes what is genuinely audible in the WAV clip.
     */
    suspend fun transcribeJapaneseAudioSlice(
        modelName: String,
        wavBytes: ByteArray,
        segmentLabel: String
    ): Result<GeminiAudioTranscriptionResult> = withContext(Dispatchers.IO) {
        if (wavBytes.size <= 44) {
            return@withContext Result.failure(IllegalArgumentException("Audio slice $segmentLabel has no PCM payload."))
        }

        val systemInstruction = """
You are an exact Japanese anime speech transcriber.
Listen ONLY to the provided audio clip and transcribe the spoken Japanese dialogue faithfully in Japanese script (Kanji/Kana).
CRITICAL HONESTY RULES:
- If the clip contains only background music, silence, or sound effects with NO spoken human dialogue, set isSpeechDetected=false and japaneseText="".
- NEVER guess, invent, or hallucinate lines that are not clearly spoken in the audio.
- Classify vocal emotion strictly from vocal delivery as one of: NEUTRAL, CALM, EXCITED, ANGRY, SAD, FEARFUL, HAPPY, WHISPERING, SHOUTING.
        """.trimIndent()

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("isSpeechDetected", JSONObject().put("type", "BOOLEAN"))
                put("japaneseText", JSONObject().put("type", "STRING"))
                put("emotion", JSONObject().put("type", "STRING"))
            })
            put("required", JSONArray(listOf("isSpeechDetected", "japaneseText", "emotion")))
        }

        val audioB64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val rawRes = generateStructuredJson(
            modelName = modelName,
            operationName = "transcribeJapaneseAudioSlice",
            systemInstruction = systemInstruction,
            userPrompt = "Transcribe spoken Japanese dialogue from audio segment $segmentLabel into strict JSON.",
            responseSchema = schema,
            temperature = 0.05,
            audioBase64 = audioB64,
            audioMimeType = "audio/wav"
        )
        if (rawRes.isFailure) return@withContext Result.failure(rawRes.exceptionOrNull()!!)

        return@withContext try {
            val obj = JSONObject(stripCodeFences(rawRes.getOrThrow()))
            val detected = obj.optBoolean("isSpeechDetected", false)
            val text = obj.optString("japaneseText", "").trim()
            val emotion = normalizeEmotion(obj.optString("emotion", "NEUTRAL"))
            Result.success(
                GeminiAudioTranscriptionResult(
                    japaneseText = if (detected) text else "",
                    emotion = emotion,
                    isSpeechDetected = detected && text.isNotEmpty()
                )
            )
        } catch (e: Exception) {
            Result.failure(
                GeminiApiException(
                    kind = GeminiErrorKind.INVALID_RESPONSE,
                    message = "Failed to parse Gemini audio transcription JSON: ${e.localizedMessage}",
                    cause = e
                )
            )
        }
    }

    suspend fun generateStructuredJson(
        modelName: String,
        operationName: String,
        systemInstruction: String,
        userPrompt: String,
        responseSchema: JSONObject? = null,
        temperature: Double = 0.25,
        audioBase64: String? = null,
        audioMimeType: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val modelValidation = validateModelSelection(modelName)
        if (modelValidation.isFailure) {
            return@withContext Result.failure(modelValidation.exceptionOrNull()!!)
        }
        val normalizedModel = modelValidation.getOrThrow()
        val apiKey = keyStore.getEffectiveApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                GeminiApiException(
                    kind = GeminiErrorKind.MISSING_API_KEY,
                    message = "No Gemini API key configured. Open Settings -> Direct Gemini API to add your Google AI Studio API key."
                )
            )
        }

        val parts = JSONArray().apply {
            if (!audioBase64.isNullOrBlank() && !audioMimeType.isNullOrBlank()) {
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", audioMimeType)
                        put("data", audioBase64)
                    })
                })
            }
            put(JSONObject().put("text", userPrompt))
        }

        val generationConfig = JSONObject().apply {
            put("temperature", temperature)
            put("responseMimeType", "application/json")
            if (responseSchema != null) {
                put("responseSchema", responseSchema)
            }
        }

        val payload = JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("generationConfig", generationConfig)
        }

        val url = "$BASE_URL/models/$normalizedModel:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header(HEADER_API_KEY, apiKey)
            .header("Accept", "application/json")
            .build()

        return@withContext try {
            val responseBody = executeWithRetry(
                request = request,
                operationName = operationName,
                modelId = normalizedModel,
                maxAttempts = 3
            )
            val text = extractCandidateText(responseBody)
            Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                GeminiApiException(
                    kind = GeminiErrorKind.INVALID_RESPONSE,
                    message = "Unexpected Gemini API error during $operationName: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    cause = e
                )
            )
        }
    }

    private suspend fun executeWithRetry(
        request: Request,
        operationName: String,
        modelId: String,
        maxAttempts: Int
    ): String {
        var attempt = 1
        var backoffMs = 1_200L
        while (true) {
            val startMs = System.currentTimeMillis()
            try {
                val (code, bodyStr, retryAfterSec) = executeCancellable(request)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.i(TAG, "Gemini $operationName model=$modelId attempt=$attempt/$maxAttempts http=$code elapsed=${elapsedMs}ms")

                if (code in 200..299) {
                    return bodyStr
                }

                val classified = classifyHttpError(code, bodyStr)
                val isRetryable = classified.kind == GeminiErrorKind.RATE_LIMITED ||
                    classified.kind == GeminiErrorKind.SERVER_ERROR
                if (isRetryable && attempt < maxAttempts) {
                    val waitMs = if (retryAfterSec != null && retryAfterSec in 1L..30L) {
                        retryAfterSec * 1000L
                    } else {
                        backoffMs
                    }
                    Log.w(TAG, "Gemini $operationName transient ${classified.kind} (HTTP $code); retrying in ${waitMs}ms")
                    delay(waitMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(12_000L)
                    attempt++
                    continue
                }
                throw classified
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiApiException) {
                throw e
            } catch (e: IOException) {
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.w(TAG, "Gemini $operationName network error model=$modelId attempt=$attempt/$maxAttempts elapsed=${elapsedMs}ms: ${e.javaClass.simpleName}")
                if (attempt < maxAttempts && e !is UnknownHostException) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
                    attempt++
                    continue
                }
                val isTimeout = e is SocketTimeoutException
                val friendly = when {
                    e is UnknownHostException -> "Device appears offline or cannot reach generativelanguage.googleapis.com. Check your internet connection."
                    isTimeout -> "Direct Gemini API request timed out after ${elapsedMs / 1000}s. Retry when network is stable."
                    else -> "Network unavailable communicating with Direct Gemini API: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
                throw GeminiApiException(
                    kind = if (isTimeout) GeminiErrorKind.TIMEOUT else GeminiErrorKind.NETWORK_UNAVAILABLE,
                    message = friendly,
                    cause = e
                )
            }
        }
    }

    private suspend fun executeCancellable(request: Request): Triple<Int, String, Long?> =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bodyStr = try {
                            resp.body?.string().orEmpty()
                        } catch (ioe: IOException) {
                            if (cont.isActive) cont.resumeWithException(ioe)
                            return
                        }
                        val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()
                        if (cont.isActive) {
                            cont.resume(Triple(resp.code, bodyStr, retryAfter))
                        }
                    }
                }
            })
        }

    private fun extractCandidateText(responseBody: String): String {
        val root = JSONObject(responseBody)
        val promptFeedback = root.optJSONObject("promptFeedback")
        val blockReason = promptFeedback?.optString("blockReason", "").orEmpty()
        if (blockReason.isNotBlank()) {
            throw GeminiApiException(
                kind = GeminiErrorKind.SAFETY_BLOCKED,
                message = "Gemini safety filter blocked this request ($blockReason). Edit the line manually or adjust context."
            )
        }
        val candidates = root.optJSONArray("candidates")
            ?: throw GeminiApiException(GeminiErrorKind.INVALID_RESPONSE, message = "Gemini response contained no candidates.")
        if (candidates.length() == 0) {
            throw GeminiApiException(GeminiErrorKind.INVALID_RESPONSE, message = "Gemini returned an empty candidates array.")
        }
        val first = candidates.getJSONObject(0)
        val finishReason = first.optString("finishReason", "").uppercase(Locale.US)
        if (finishReason in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")) {
            throw GeminiApiException(
                kind = GeminiErrorKind.SAFETY_BLOCKED,
                message = "Gemini blocked candidate output due to safety/policy filter (finishReason=$finishReason)."
            )
        }
        val content = first.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val t = p.optString("text", "")
                if (t.isNotEmpty()) sb.append(t)
            }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) {
            throw GeminiApiException(
                kind = GeminiErrorKind.INVALID_RESPONSE,
                message = "Gemini returned empty text output (finishReason=${finishReason.ifBlank { "UNKNOWN" }})."
            )
        }
        return text
    }

    companion object {
        private const val TAG = "DirectGeminiApiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val HEADER_API_KEY = "x-goog-api-key"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_MODEL_ID = "gemini-3.8-flash"

        /**
         * Verified active official Gemini Developer API models (2026).
         * Deprecated/retired legacy IDs (gemini-2.5-*, gemini-2.0-*, gemini-1.5-*) are excluded
         * from selection and flagged explicitly by [validateModelSelection].
         */
        val SUPPORTED_MODELS: List<GeminiModelOption> = listOf(
            GeminiModelOption(
                modelId = "gemini-3.8-flash",
                displayName = "Gemini 3.8 Flash (Recommended)",
                description = "Recommended fast multimodal model for Devanagari Hindi + English translation, sentence splitting, dialogue review, and Japanese audio transcription."
            ),
            GeminiModelOption(
                modelId = "gemini-3.5-flash-lite",
                displayName = "Gemini 3.5 Flash-Lite",
                description = "Ultra-low-latency, cost-efficient model for rapid batch translation and mixed-script proofreading."
            ),
            GeminiModelOption(
                modelId = "gemini-4-hydrogen",
                displayName = "Gemini 4 Hydrogen",
                description = "Fast next-generation multimodal model for high-accuracy dialogue adaptation and audio transcription."
            ),
            GeminiModelOption(
                modelId = "gemini-4-neon",
                displayName = "Gemini 4 Neon",
                description = "Frontier fast reasoning model for nuanced anime dialogue, humor, honorifics, and complex context."
            ),
            GeminiModelOption(
                modelId = "gemini-4-argon",
                displayName = "Gemini 4 Argon",
                description = "Deep reasoning model for complex localization review and subtle tone preservation."
            ),
            GeminiModelOption(
                modelId = "gemini-4-carbon",
                displayName = "Gemini 4 Carbon",
                description = "Frontier deep reasoning model for highest-precision dialogue review and script QA."
            )
        )

        val SUPPORTED_MODEL_IDS: Set<String> = SUPPORTED_MODELS.map { it.modelId }.toSet()

        val RETIRED_OR_DEPRECATED_MODELS: Set<String> = setOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-1.0-pro"
        )

        private val VALID_EMOTIONS = setOf(
            "NEUTRAL", "CALM", "EXCITED", "ANGRY", "SAD", "FEARFUL", "HAPPY", "WHISPERING", "SHOUTING"
        )

        /**
         * Preserves valid model IDs without ever rewriting newer models back to older models.
         * Strips only optional `models/` prefix and surrounding whitespace; returns [DEFAULT_MODEL_ID]
         * only when the input is null or blank.
         */
        fun normalizeModelName(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().removePrefix("models/").trim()
            if (trimmed.isEmpty()) return DEFAULT_MODEL_ID
            return trimmed
        }

        fun isRetiredOrDeprecatedModel(modelId: String?): Boolean {
            val clean = normalizeModelName(modelId).lowercase(Locale.US)
            return clean in RETIRED_OR_DEPRECATED_MODELS
        }

        fun isSupportedModel(modelId: String?): Boolean {
            val clean = normalizeModelName(modelId)
            return clean in SUPPORTED_MODEL_IDS
        }

        /**
         * Explicitly validates a requested Gemini model ID before sending a request.
         * Rejects retired/deprecated or malformed model IDs with a clear [GeminiErrorKind.MODEL_NOT_FOUND] error
         * instead of silently rewriting newer models to older ones.
         */
        fun validateModelSelection(raw: String?): Result<String> {
            val clean = normalizeModelName(raw)
            val lower = clean.lowercase(Locale.US)
            if (lower in RETIRED_OR_DEPRECATED_MODELS) {
                return Result.failure(
                    GeminiApiException(
                        kind = GeminiErrorKind.MODEL_NOT_FOUND,
                        message = "Selected model '$clean' is deprecated/retired on the Gemini API. Select an active model such as $DEFAULT_MODEL_ID in Settings."
                    )
                )
            }
            if (!Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{2,64}$").matches(clean)) {
                return Result.failure(
                    GeminiApiException(
                        kind = GeminiErrorKind.MODEL_NOT_FOUND,
                        message = "Invalid Gemini model ID '$clean'. Select a verified active model in Settings."
                    )
                )
            }
            if (clean !in SUPPORTED_MODEL_IDS) {
                return Result.failure(
                    GeminiApiException(
                        kind = GeminiErrorKind.MODEL_NOT_FOUND,
                        message = "Unsupported Gemini model '$clean'. Choose one of the verified active models (${SUPPORTED_MODEL_IDS.joinToString(", ")}) in Settings."
                    )
                )
            }
            return Result.success(clean)
        }

        fun mapErrorToConnectionState(errorKind: GeminiErrorKind?): GeminiConnectionState = when (errorKind) {
            GeminiErrorKind.MISSING_API_KEY -> GeminiConnectionState.API_KEY_MISSING
            GeminiErrorKind.INVALID_API_KEY -> GeminiConnectionState.AUTH_FAILED
            GeminiErrorKind.RATE_LIMITED,
            GeminiErrorKind.QUOTA_OR_BILLING -> GeminiConnectionState.QUOTA_OR_RATE_LIMITED
            GeminiErrorKind.NETWORK_UNAVAILABLE,
            GeminiErrorKind.TIMEOUT -> GeminiConnectionState.NETWORK_UNAVAILABLE
            GeminiErrorKind.MODEL_NOT_FOUND -> GeminiConnectionState.MODEL_UNAVAILABLE
            else -> GeminiConnectionState.API_KEY_SAVED_UNVERIFIED
        }

        fun normalizeEmotion(raw: String?): String {
            val upper = raw?.trim()?.uppercase(Locale.US).orEmpty()
            return if (upper in VALID_EMOTIONS) upper else "NEUTRAL"
        }

        fun stripCodeFences(raw: String): String {
            var s = raw.trim()
            if (s.startsWith("```")) {
                s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
                if (s.endsWith("```")) {
                    s = s.removeSuffix("```").trim()
                }
            }
            return s
        }

        /**
         * Pure-Kotlin HTTP error classifier (unit-testable on JVM without Android org.json stubs).
         */
        fun classifyHttpError(httpCode: Int, responseBody: String): GeminiApiException {
            val lower = responseBody.lowercase(Locale.US)
            val extractedMessage = Regex("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .find(responseBody)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", " ")
                ?.take(220)
                .orEmpty()

            return when {
                httpCode == 400 && (lower.contains("api_key_invalid") || lower.contains("api key not valid")) -> {
                    GeminiApiException(
                        kind = GeminiErrorKind.INVALID_API_KEY,
                        httpCode = httpCode,
                        message = "Invalid Gemini API key. Verify your key in Settings -> Direct Gemini API."
                    )
                }
                httpCode == 401 || (httpCode == 403 && (lower.contains("api key") || lower.contains("permission_denied") || lower.contains("unregistered"))) -> {
                    val isBilling = lower.contains("billing") || lower.contains("quota")
                    if (isBilling) {
                        GeminiApiException(
                            kind = GeminiErrorKind.QUOTA_OR_BILLING,
                            httpCode = httpCode,
                            message = "Gemini API billing or quota restriction (HTTP $httpCode): ${extractedMessage.ifBlank { "Enable billing or check project quota in Google AI Studio." }}"
                        )
                    } else {
                        GeminiApiException(
                            kind = GeminiErrorKind.INVALID_API_KEY,
                            httpCode = httpCode,
                            message = "Gemini API key rejected (HTTP $httpCode). Check key permissions or generate a new key in Google AI Studio."
                        )
                    }
                }
                httpCode == 404 || lower.contains("not_found") -> {
                    GeminiApiException(
                        kind = GeminiErrorKind.MODEL_NOT_FOUND,
                        httpCode = httpCode,
                        message = "Selected Gemini model was not found or is unavailable for your key (HTTP $httpCode). Switch to $DEFAULT_MODEL_ID in Settings."
                    )
                }
                httpCode == 429 -> {
                    val isBillingOrQuotaExhausted = lower.contains("billing") || lower.contains("exceeded your current quota")
                    if (isBillingOrQuotaExhausted) {
                        GeminiApiException(
                            kind = GeminiErrorKind.QUOTA_OR_BILLING,
                            httpCode = httpCode,
                            message = "Gemini API quota exhausted or billing required (HTTP 429). ${extractedMessage.ifBlank { "Check your Google AI Studio usage limits." }}"
                        )
                    } else {
                        GeminiApiException(
                            kind = GeminiErrorKind.RATE_LIMITED,
                            httpCode = httpCode,
                            message = "Gemini API rate limit reached (HTTP 429). Please wait briefly and retry."
                        )
                    }
                }
                httpCode in 500..599 -> {
                    GeminiApiException(
                        kind = GeminiErrorKind.SERVER_ERROR,
                        httpCode = httpCode,
                        message = "Gemini API temporary server error (HTTP $httpCode). ${extractedMessage.ifBlank { "Retry shortly." }}"
                    )
                }
                else -> {
                    GeminiApiException(
                        kind = GeminiErrorKind.INVALID_RESPONSE,
                        httpCode = httpCode,
                        message = "Gemini API error (HTTP $httpCode): ${extractedMessage.ifBlank { "Unexpected response from Gemini API." }}"
                    )
                }
            }
        }
    }
}
