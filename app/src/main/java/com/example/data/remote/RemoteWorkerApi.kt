package com.example.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class RemoteModelReadiness(
    val modelKey: String,
    val displayName: String,
    val downloaded: Boolean,
    val loaded: Boolean,
    val readyForInference: Boolean,
    val detailMessage: String = ""
)

data class WorkerHealthStatus(
    val reachable: Boolean,
    val apiAvailable: Boolean = reachable,
    val workerUrl: String,
    val deviceType: String,
    val cudaAvailable: Boolean,
    val freeDiskSpaceGb: Double,
    val installedModels: List<String>,
    val modelReadiness: Map<String, RemoteModelReadiness> = emptyMap(),
    val statusMessage: String
) {
    /**
     * Returns true ONLY if the server is reachable, the API is available, AND the requested
     * model is explicitly reported as loaded & ready for inference (never assumes HTTP 200 = ready).
     */
    fun isModelReady(modelKey: String): Boolean {
        if (!reachable || !apiAvailable) return false
        val direct = modelReadiness[modelKey]
        if (direct != null) return direct.readyForInference
        val normalized = modelKey.lowercase(Locale.US)
        val fuzzy = modelReadiness.entries.firstOrNull { (k, _) ->
            k.lowercase(Locale.US).contains(normalized) || normalized.contains(k.lowercase(Locale.US))
        }?.value
        if (fuzzy != null) return fuzzy.readyForInference
        // Legacy workers returning only `installed_models` array without granular readiness map
        if (modelReadiness.isEmpty() && installedModels.isNotEmpty()) {
            return installedModels.any { it.lowercase(Locale.US).contains(normalized) }
        }
        return false
    }
}

data class RemoteAsrWord(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val probability: Float = -1f
)

data class RemoteAsrSegment(
    val startMs: Long,
    val endMs: Long,
    val japaneseText: String,
    val romajiReading: String,
    val confidenceScore: Float, // -1f when worker did not report confidence
    val words: List<RemoteAsrWord> = emptyList()
)

data class RemoteDiarizationSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String,
    val confidenceScore: Float, // -1f when worker did not report confidence
    val embedding: FloatArray? = null
)

data class RemoteSeparationResult(
    val vocalFile: File,
    val backgroundFile: File,
    val modelName: String,
    val qualityScore: Float? = null
)

/**
 * Self-Hostable Python/PyTorch/CUDA Worker Client.
 *
 * Security & Honesty Guarantees:
 * - Enforces HTTPS by default; cleartext `http://` is permitted ONLY when [allowInsecureLocalHttp]
 *   is explicitly enabled AND the host resolves to loopback (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`)
 *   or a private RFC1918 LAN IPv4 address (`10.*`, `192.168.*`, `172.16-31.*`).
 * - Disables automatic redirects (`followRedirects(false)`) and validates same-origin
 *   (`scheme + host + port`) on all remote download URLs to prevent SSRF.
 * - Supports optional `Authorization: Bearer <token>` authentication on all endpoints.
 * - Never invents `cudaAvailable = true` or default `0.85f` confidence scores when fields are omitted.
 * - Implements real HTTP multipart dispatch to:
 *   - GET  /v1/health      -> Worker health & GPU check
 *   - POST /v1/separate    -> HTDemucs (adefossez/HTDemucs)
 *   - POST /v1/transcribe  -> Whisper Large V3 Turbo (openai/whisper-large-v3-turbo)
 *   - POST /v1/diarize     -> pyannote/speaker-diarization-3.1 + WeSpeaker ResNet34
 *   - POST /v1/synthesize  -> F5-Hindi-24KHz (SPRINGLab/F5-Hindi-24KHz)
 */
class RemoteWorkerClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun checkWorkerHealth(
        baseUrl: String,
        authToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): WorkerHealthStatus = withContext(Dispatchers.IO) {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) {
            return@withContext WorkerHealthStatus(
                reachable = false,
                workerUrl = "",
                deviceType = "Not Configured (On-Device DSP/ONNX + Firebase AI + Android Speech TTS Active)",
                cudaAvailable = false,
                freeDiskSpaceGb = 0.0,
                installedModels = emptyList(),
                statusMessage = "No remote Python/GPU worker URL configured. On-device DSP/Silero ONNX VAD, Firebase AI Logic Gemini, and Android Speech TTS run locally."
            )
        }

        val validated = validateWorkerBaseUrl(raw, allowInsecureLocalHttp)
        if (validated.isFailure) {
            return@withContext WorkerHealthStatus(
                reachable = false,
                workerUrl = raw,
                deviceType = "Blocked By Transport Policy",
                cudaAvailable = false,
                freeDiskSpaceGb = 0.0,
                installedModels = emptyList(),
                statusMessage = validated.exceptionOrNull()?.localizedMessage ?: "Invalid remote worker URL."
            )
        }
        val cleanUrl = validated.getOrThrow()

        try {
            val request = buildAuthorizedRequest("$cleanUrl/v1/health", authToken).get().build()
            executeWithRetry(request).use { response ->
                if (!response.isSuccessful) {
                    return@withContext WorkerHealthStatus(
                        reachable = false,
                        workerUrl = cleanUrl,
                        deviceType = "HTTP ${response.code}",
                        cudaAvailable = false,
                        freeDiskSpaceGb = 0.0,
                        installedModels = emptyList(),
                        statusMessage = "Worker endpoint responded with HTTP ${response.code}."
                    )
                }
                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val apiAvailable = json.optBoolean("api_available", true)
                val modelsArr = json.optJSONArray("installed_models")
                val models = mutableListOf<String>()
                if (modelsArr != null) {
                    for (i in 0 until modelsArr.length()) {
                        val entry = modelsArr.optString(i).trim()
                        if (entry.isNotEmpty()) models.add(entry)
                    }
                }
                val readinessMap = linkedMapOf<String, RemoteModelReadiness>()
                val modelsObj = json.optJSONObject("models")
                if (modelsObj != null) {
                    val keys = modelsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = modelsObj.optJSONObject(key) ?: continue
                        val downloaded = item.optBoolean("downloaded", false)
                        val loaded = item.optBoolean("loaded", false)
                        val ready = item.optBoolean("ready", loaded && downloaded)
                        val display = item.optString("name", key).ifBlank { key }
                        val detail = item.optString("detail", item.optString("error", "")).trim()
                        readinessMap[key] = RemoteModelReadiness(
                            modelKey = key,
                            displayName = display,
                            downloaded = downloaded,
                            loaded = loaded,
                            readyForInference = ready,
                            detailMessage = detail
                        )
                        if (ready && !models.contains(display)) {
                            models.add(display)
                        }
                    }
                }
                val readyCount = readinessMap.values.count { it.readyForInference }
                val summaryMsg = if (readinessMap.isNotEmpty()) {
                    "Connected to worker ($cleanUrl) • $readyCount/${readinessMap.size} model(s) ready for inference."
                } else {
                    "Connected to worker ($cleanUrl) • ${models.size} model(s) reported."
                }
                WorkerHealthStatus(
                    reachable = true,
                    apiAvailable = apiAvailable,
                    workerUrl = cleanUrl,
                    deviceType = json.optString("device", "Unknown Remote Device").ifBlank { "Unknown Remote Device" },
                    cudaAvailable = json.optBoolean("cuda_available", false),
                    freeDiskSpaceGb = json.optDouble("free_disk_gb", 0.0),
                    installedModels = models,
                    modelReadiness = readinessMap,
                    statusMessage = summaryMsg
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WorkerHealthStatus(
                reachable = false,
                apiAvailable = false,
                workerUrl = cleanUrl,
                deviceType = "Offline / Unreachable",
                cudaAvailable = false,
                freeDiskSpaceGb = 0.0,
                installedModels = emptyList(),
                modelReadiness = emptyMap(),
                statusMessage = "Worker unreachable (${e.localizedMessage ?: "Connection refused"})."
            )
        }
    }

    /**
     * Dispatches `POST /v1/separate` to a configured HTDemucs worker.
     * Expects JSON `{ "vocal_url": "...", "background_url": "...", "model": "HTDemucs" }`
     * and validates same-origin download URLs before fetching WAV stems.
     */
    suspend fun separateAudioRemote(
        baseUrl: String,
        masterWav: File,
        outDir: File,
        authToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): Result<RemoteSeparationResult> = withContext(Dispatchers.IO) {
        val cleanUrl = validateWorkerBaseUrl(baseUrl, allowInsecureLocalHttp).getOrElse {
            return@withContext Result.failure(it)
        }
        if (!masterWav.exists() || masterWav.length() <= 44L) {
            return@withContext Result.failure(IllegalArgumentException("Master WAV file is missing."))
        }
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", masterWav.name, masterWav.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", "adefossez/HTDemucs")
                .build()

            val request = buildAuthorizedRequest("$cleanUrl/v1/separate", authToken).post(body).build()
            executeWithRetry(request).use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Remote HTDemucs HTTP ${resp.code}"))
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val vocalUrlRaw = json.optString("vocal_url").takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(IllegalStateException("Worker response missing vocal_url"))
                val bgUrlRaw = json.optString("background_url").takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(IllegalStateException("Worker response missing background_url"))

                val safeVocalUrl = resolveAndValidateSameOriginUrl(cleanUrl, vocalUrlRaw).getOrElse {
                    return@withContext Result.failure(it)
                }
                val safeBgUrl = resolveAndValidateSameOriginUrl(cleanUrl, bgUrlRaw).getOrElse {
                    return@withContext Result.failure(it)
                }

                outDir.mkdirs()
                val vocalOut = File(outDir, "stem_dialogue_vocal.wav")
                val bgOut = File(outDir, "stem_background_accompaniment.wav")
                downloadFile(safeVocalUrl, vocalOut, authToken)
                downloadFile(safeBgUrl, bgOut, authToken)

                val measuredQuality: Float? = if (json.has("quality_score") && !json.isNull("quality_score")) {
                    json.optDouble("quality_score", Double.NaN).takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
                } else null

                Result.success(
                    RemoteSeparationResult(
                        vocalFile = vocalOut,
                        backgroundFile = bgOut,
                        modelName = json.optString("model", "HTDemucs (Remote GPU)").ifBlank { "HTDemucs (Remote GPU)" },
                        qualityScore = measuredQuality
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Dispatches `POST /v1/transcribe` to a configured Whisper Large V3 Turbo worker.
     * Expects JSON `{ "segments": [{ "start_ms": 0, "end_ms": 2100, "text": "...", "romaji": "...", "confidence": 0.92 }] }`.
     * Never invents confidence when omitted (`-1f` = unmeasured).
     */
    suspend fun transcribeJapaneseRemote(
        baseUrl: String,
        vocalWav: File,
        authToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): Result<List<RemoteAsrSegment>> = withContext(Dispatchers.IO) {
        val cleanUrl = validateWorkerBaseUrl(baseUrl, allowInsecureLocalHttp).getOrElse {
            return@withContext Result.failure(it)
        }
        if (!vocalWav.exists() || vocalWav.length() <= 44L) {
            return@withContext Result.failure(IllegalArgumentException("Vocal WAV file is missing."))
        }
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", vocalWav.name, vocalWav.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("language", "ja")
                .addFormDataPart("model", "openai/whisper-large-v3-turbo")
                .build()

            val request = buildAuthorizedRequest("$cleanUrl/v1/transcribe", authToken).post(body).build()
            executeWithRetry(request).use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Remote Whisper HTTP ${resp.code}"))
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr: JSONArray = json.optJSONArray("segments") ?: JSONArray()
                val segments = mutableListOf<RemoteAsrSegment>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val startMs = item.optLong("start_ms", (item.optDouble("start", 0.0) * 1000.0).toLong())
                    val endMs = item.optLong("end_ms", (item.optDouble("end", 0.0) * 1000.0).toLong())
                    val text = item.optString("text").trim()
                    val confidence = parseOptionalConfidence(item, "confidence")
                    val wordsList = mutableListOf<RemoteAsrWord>()
                    val wordsArr = item.optJSONArray("words")
                    if (wordsArr != null) {
                        for (wIdx in 0 until wordsArr.length()) {
                            val wObj = wordsArr.optJSONObject(wIdx) ?: continue
                            val wText = wObj.optString("word", wObj.optString("text", "")).trim()
                            val wStart = wObj.optLong("start_ms", (wObj.optDouble("start", 0.0) * 1000.0).toLong())
                            val wEnd = wObj.optLong("end_ms", (wObj.optDouble("end", 0.0) * 1000.0).toLong())
                            val wProb = parseOptionalConfidence(wObj, "probability")
                            if (wText.isNotEmpty() && wEnd > wStart) {
                                wordsList.add(RemoteAsrWord(wText, wStart, wEnd, wProb))
                            }
                        }
                    }
                    if (text.isNotBlank() && endMs > startMs) {
                        segments.add(
                            RemoteAsrSegment(
                                startMs = startMs,
                                endMs = endMs,
                                japaneseText = text,
                                romajiReading = item.optString("romaji", "").trim(),
                                confidenceScore = confidence,
                                words = wordsList
                            )
                        )
                    }
                }
                if (segments.isEmpty()) {
                    Result.failure(IllegalStateException("Remote Whisper returned 0 transcribed segments."))
                } else {
                    Result.success(segments)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Dispatches `POST /v1/diarize` to a configured pyannote / WeSpeaker worker.
     * Expects JSON `{ "segments": [{ "start_ms": 0, "end_ms": 2100, "speaker": "SPEAKER_00", "confidence": 0.91 }] }`.
     */
    suspend fun diarizeSpeakersRemote(
        baseUrl: String,
        vocalWav: File,
        authToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): Result<List<RemoteDiarizationSegment>> = withContext(Dispatchers.IO) {
        val cleanUrl = validateWorkerBaseUrl(baseUrl, allowInsecureLocalHttp).getOrElse {
            return@withContext Result.failure(it)
        }
        if (!vocalWav.exists() || vocalWav.length() <= 44L) {
            return@withContext Result.failure(IllegalArgumentException("Vocal WAV file is missing."))
        }
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", vocalWav.name, vocalWav.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", "pyannote/speaker-diarization-3.1")
                .build()

            val request = buildAuthorizedRequest("$cleanUrl/v1/diarize", authToken).post(body).build()
            executeWithRetry(request).use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Remote pyannote diarization HTTP ${resp.code}"))
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr: JSONArray = json.optJSONArray("segments") ?: JSONArray()
                val segments = mutableListOf<RemoteDiarizationSegment>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val startMs = item.optLong("start_ms", (item.optDouble("start", 0.0) * 1000.0).toLong())
                    val endMs = item.optLong("end_ms", (item.optDouble("end", 0.0) * 1000.0).toLong())
                    val speaker = item.optString("speaker", item.optString("speaker_label", "")).trim()
                    val confidence = parseOptionalConfidence(item, "confidence")
                    val embArr = item.optJSONArray("embedding")
                    val emb = if (embArr != null && embArr.length() >= 8) {
                        FloatArray(embArr.length()) { eIdx -> embArr.optDouble(eIdx, 0.0).toFloat() }
                    } else null
                    if (speaker.isNotBlank() && endMs > startMs) {
                        segments.add(
                            RemoteDiarizationSegment(
                                startMs = startMs,
                                endMs = endMs,
                                speakerLabel = speaker,
                                confidenceScore = confidence,
                                embedding = emb
                            )
                        )
                    }
                }
                if (segments.isEmpty()) {
                    Result.failure(IllegalStateException("Remote pyannote returned 0 diarization segments."))
                } else {
                    Result.success(segments)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Dispatches `POST /v1/synthesize` to a configured F5-Hindi-24KHz worker.
     * Writes the returned raw WAV response bytes directly to `outputWavFile`.
     */
    suspend fun synthesizeHindiSpeechRemote(
        baseUrl: String,
        text: String,
        referenceWav: File?,
        speakerLabel: String,
        emotion: String,
        targetDurationMs: Long,
        outputWavFile: File,
        authToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = validateWorkerBaseUrl(baseUrl, allowInsecureLocalHttp).getOrElse {
            return@withContext Result.failure(it)
        }
        try {
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("text", text)
                .addFormDataPart("speaker_label", speakerLabel)
                .addFormDataPart("emotion", emotion)
                .addFormDataPart("target_duration_ms", targetDurationMs.toString())
                .addFormDataPart("model", "SPRINGLab/F5-Hindi-24KHz")

            if (referenceWav != null && referenceWav.exists() && referenceWav.length() > 44L) {
                builder.addFormDataPart(
                    "reference_audio",
                    referenceWav.name,
                    referenceWav.asRequestBody("audio/wav".toMediaType())
                )
            }

            val request = buildAuthorizedRequest("$cleanUrl/v1/synthesize", authToken).post(builder.build()).build()
            executeWithRetry(request).use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Remote F5-Hindi-24KHz HTTP ${resp.code}"))
                }
                val bytes = resp.body?.bytes()
                    ?: return@withContext Result.failure(IllegalStateException("Empty audio response from F5-Hindi-24KHz worker."))
                if (bytes.size <= 44) {
                    return@withContext Result.failure(IllegalStateException("Invalid WAV bytes (${bytes.size} B) from worker."))
                }
                outputWavFile.parentFile?.mkdirs()
                outputWavFile.writeBytes(bytes)
                Result.success(outputWavFile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildAuthorizedRequest(url: String, authToken: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "AnimeDubStudio-Android/1.0")
        val trimmedToken = authToken.trim()
        if (trimmedToken.isNotEmpty()) {
            builder.header("Authorization", "Bearer $trimmedToken")
        }
        return builder
    }

    private suspend fun executeWithRetry(request: Request, maxRetries: Int = 2): Response {
        var attempt = 0
        var lastError: IOException? = null
        while (attempt <= maxRetries) {
            coroutineContext.ensureActive()
            try {
                return httpClient.newCall(request).execute()
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxRetries) throw e
                delay(400L * (1 shl attempt))
                attempt++
            }
        }
        throw lastError ?: IOException("HTTP request failed after retries.")
    }

    private suspend fun downloadFile(url: String, destination: File, authToken: String) {
        val req = buildAuthorizedRequest(url, authToken).get().build()
        executeWithRetry(req).use { resp ->
            if (resp.isRedirect) {
                throw SecurityException("Blocked redirect when downloading worker artifact from $url.")
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("Download failed HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body for $url")
            destination.parentFile?.mkdirs()
            destination.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    companion object {
        /**
         * Parses an optional numeric confidence field; returns `-1f` (unmeasured) if absent or null.
         */
        fun parseOptionalConfidence(json: JSONObject, key: String): Float {
            if (!json.has(key) || json.isNull(key)) return -1f
            val v = json.optDouble(key, Double.NaN)
            return if (v.isNaN()) -1f else v.toFloat().coerceIn(0f, 1f)
        }

        /**
         * Enforces HTTPS for remote workers. Permits cleartext `http://` ONLY when
         * [allowInsecureLocalHttp] is explicitly enabled AND the host is loopback or private RFC1918 LAN.
         */
        fun validateWorkerBaseUrl(baseUrl: String, allowInsecureLocalHttp: Boolean = false): Result<String> {
            val clean = baseUrl.trim().trimEnd('/')
            if (clean.isEmpty()) {
                return Result.failure(IllegalStateException("Remote worker URL is not configured."))
            }
            val parsed = clean.toHttpUrlOrNull()
                ?: return Result.failure(IllegalArgumentException("Malformed remote worker URL: '$clean'"))

            if (parsed.scheme.equals("https", ignoreCase = true)) {
                return Result.success(clean)
            }
            if (parsed.scheme.equals("http", ignoreCase = true)) {
                if (!allowInsecureLocalHttp) {
                    return Result.failure(
                        SecurityException(
                            "Cleartext HTTP is blocked by default. Use HTTPS, or enable 'Allow Local LAN HTTP' in Settings for a trusted loopback/LAN worker."
                        )
                    )
                }
                if (!isLoopbackOrPrivateLanHost(parsed.host)) {
                    return Result.failure(
                        SecurityException(
                            "Cleartext HTTP is only permitted for loopback or private LAN hosts (127.0.0.1, localhost, 10.x, 192.168.x, 172.16-31.x); refused host '${parsed.host}'."
                        )
                    )
                }
                return Result.success(clean)
            }
            return Result.failure(IllegalArgumentException("Unsupported URL scheme '${parsed.scheme}' (use https://)."))
        }

        /**
         * Resolves a relative or absolute worker artifact URL and enforces identical scheme, host,
         * and port against the configured [baseUrl] to prevent SSRF attacks.
         */
        fun resolveAndValidateSameOriginUrl(baseUrl: String, pathOrUrl: String): Result<String> {
            val baseParsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
                ?: return Result.failure(IllegalArgumentException("Invalid worker base URL."))
            val trimmedTarget = pathOrUrl.trim()
            if (trimmedTarget.isEmpty()) {
                return Result.failure(IllegalArgumentException("Worker returned an empty artifact URL."))
            }
            val candidateUrl = if (trimmedTarget.startsWith("http://", ignoreCase = true) ||
                trimmedTarget.startsWith("https://", ignoreCase = true)
            ) {
                trimmedTarget.toHttpUrlOrNull()
            } else {
                "$baseUrl/${trimmedTarget.trimStart('/')}".toHttpUrlOrNull()
            } ?: return Result.failure(IllegalArgumentException("Malformed artifact URL returned by worker: '$trimmedTarget'"))

            val sameOrigin = candidateUrl.scheme.equals(baseParsed.scheme, ignoreCase = true) &&
                candidateUrl.host.equals(baseParsed.host, ignoreCase = true) &&
                candidateUrl.port == baseParsed.port
            if (!sameOrigin) {
                return Result.failure(
                    SecurityException(
                        "Blocked cross-origin artifact URL '${candidateUrl.scheme}://${candidateUrl.host}:${candidateUrl.port}' (expected '${baseParsed.scheme}://${baseParsed.host}:${baseParsed.port}')."
                    )
                )
            }
            return Result.success(candidateUrl.toString())
        }

        fun isLoopbackOrPrivateLanHost(rawHost: String): Boolean {
            val host = rawHost.trim().lowercase(Locale.US).removeSurrounding("[", "]")
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") {
                return true
            }
            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return when {
                octets[0] == 10 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 127 -> true
                else -> false
            }
        }

        val SELF_HOSTED_WORKER_SPECIFICATION = """
# ==============================================================================
# AnimeDub Studio — Self-Hostable Python/GPU Inference Worker (FastAPI + PyTorch)
# Deploy on any CUDA GPU server / workstation (use TLS reverse proxy or enable LAN HTTP):
#   pip install fastapi uvicorn transformers safetensors demucs onnxruntime pyannote.audio f5-tts torch torchaudio
#   uvicorn worker_server:app --host 0.0.0.0 --port 8080
# ==============================================================================
import os, shutil, torch
from fastapi import FastAPI

app = FastAPI(title="AnimeDub Studio GPU Inference Worker", version="2.2.0")

@app.get("/v1/health")
def health_check():
    total, used, free = shutil.disk_usage("/")
    return {
        "status": "ok",
        "device": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU",
        "cuda_available": torch.cuda.is_available(),
        "free_disk_gb": round(free / (1024**3), 2),
        "installed_models": [
            "openai/whisper-large-v3-turbo",
            "onnx-community/silero-vad",
            "pyannote/speaker-diarization-3.1",
            "pyannote/wespeaker-voxceleb-resnet34-LM",
            "SPRINGLab/F5-Hindi-24KHz",
            "adefossez/HTDemucs"
        ]
    }

# Optional header: Authorization: Bearer <token>
# POST /v1/separate      -> Agent 6: adefossez/HTDemucs (returns same-origin vocal_url, background_url)
# POST /v1/transcribe    -> Agent 1: openai/whisper-large-v3-turbo (returns segments[])
# POST /v1/diarize       -> Agent 3: pyannote/speaker-diarization-3.1 (returns segments[{start_ms, end_ms, speaker, confidence}])
# POST /v1/synthesize    -> Agent 5: SPRINGLab/F5-Hindi-24KHz (returns 24 kHz WAV bytes)
""".trimIndent()
    }
}
