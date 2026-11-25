package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class WorkerHealthStatus(
    val reachable: Boolean,
    val workerUrl: String,
    val deviceType: String,
    val cudaAvailable: Boolean,
    val freeDiskSpaceGb: Double,
    val installedModels: List<String>,
    val statusMessage: String
)

data class RemoteAsrSegment(
    val startMs: Long,
    val endMs: Long,
    val japaneseText: String,
    val romajiReading: String,
    val confidenceScore: Float
)

data class RemoteSeparationResult(
    val vocalFile: File,
    val backgroundFile: File,
    val modelName: String
)

/**
 * Self-Hostable Python/PyTorch/CUDA Worker Client.
 * Honest Hybrid Architecture:
 * - Never disguises the absence of a remote GPU server as a successful remote PyTorch execution.
 * - Implements real HTTP multipart dispatch to:
 *   - GET  /v1/health      -> Worker health & GPU check
 *   - POST /v1/separate    -> HTDemucs (adefossez/HTDemucs)
 *   - POST /v1/transcribe  -> Whisper Large V3 Turbo (openai/whisper-large-v3-turbo)
 *   - POST /v1/synthesize  -> F5-Hindi-24KHz (SPRINGLab/F5-Hindi-24KHz)
 */
class RemoteWorkerClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun normalizeBaseUrl(baseUrl: String): String? {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.startsWith("http://") || clean.startsWith("https://")) clean else null
    }

    suspend fun checkWorkerHealth(baseUrl: String): WorkerHealthStatus = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeBaseUrl(baseUrl)
        if (cleanUrl == null) {
            return@withContext WorkerHealthStatus(
                reachable = false,
                workerUrl = baseUrl.trim(),
                deviceType = "Not Configured (Android Local DSP + Firebase AI + Android Speech TTS Active)",
                cudaAvailable = false,
                freeDiskSpaceGb = 0.0,
                installedModels = emptyList(),
                statusMessage = "No remote Python/GPU worker URL configured. Android Local DSP, Firebase AI Logic Gemini 3.8 Flash (ASR + Translation), and Android Speech TTS run natively."
            )
        }

        try {
            val request = Request.Builder()
                .url("$cleanUrl/v1/health")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
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
                val modelsArr = json.optJSONArray("installed_models")
                val models = mutableListOf<String>()
                if (modelsArr != null) {
                    for (i in 0 until modelsArr.length()) {
                        models.add(modelsArr.optString(i))
                    }
                }
                WorkerHealthStatus(
                    reachable = true,
                    workerUrl = cleanUrl,
                    deviceType = json.optString("device", "CUDA GPU Worker"),
                    cudaAvailable = json.optBoolean("cuda_available", true),
                    freeDiskSpaceGb = json.optDouble("free_disk_gb", 0.0),
                    installedModels = models,
                    statusMessage = "Connected to remote GPU inference worker ($cleanUrl)."
                )
            }
        } catch (e: Exception) {
            WorkerHealthStatus(
                reachable = false,
                workerUrl = cleanUrl,
                deviceType = "Offline / Unreachable",
                cudaAvailable = false,
                freeDiskSpaceGb = 0.0,
                installedModels = emptyList(),
                statusMessage = "Worker unreachable (${e.localizedMessage ?: "Connection refused"})."
            )
        }
    }

    /**
     * Dispatches `POST /v1/separate` to a configured HTDemucs worker.
     * Expects JSON `{ "vocal_url": "...", "background_url": "...", "model": "HTDemucs" }` and downloads the WAV stems.
     */
    suspend fun separateAudioRemote(
        baseUrl: String,
        masterWav: File,
        outDir: File
    ): Result<RemoteSeparationResult> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeBaseUrl(baseUrl)
            ?: return@withContext Result.failure(IllegalStateException("Remote worker URL not configured."))
        if (!masterWav.exists() || masterWav.length() <= 44L) {
            return@withContext Result.failure(IllegalArgumentException("Master WAV file is missing."))
        }
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", masterWav.name, masterWav.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", "adefossez/HTDemucs")
                .build()

            val request = Request.Builder()
                .url("$cleanUrl/v1/separate")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Remote HTDemucs HTTP ${resp.code}"))
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val vocalUrl = json.optString("vocal_url").takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(IllegalStateException("Worker response missing vocal_url"))
                val bgUrl = json.optString("background_url").takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(IllegalStateException("Worker response missing background_url"))

                outDir.mkdirs()
                val vocalOut = File(outDir, "stem_dialogue_vocal.wav")
                val bgOut = File(outDir, "stem_background_accompaniment.wav")
                downloadFile(resolveWorkerUrl(cleanUrl, vocalUrl), vocalOut)
                downloadFile(resolveWorkerUrl(cleanUrl, bgUrl), bgOut)

                Result.success(
                    RemoteSeparationResult(
                        vocalFile = vocalOut,
                        backgroundFile = bgOut,
                        modelName = json.optString("model", "HTDemucs (Remote GPU)")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Dispatches `POST /v1/transcribe` to a configured Whisper Large V3 Turbo worker.
     * Expects JSON `{ "segments": [{ "start_ms": 0, "end_ms": 2100, "text": "...", "romaji": "...", "confidence": 0.92 }] }`.
     */
    suspend fun transcribeJapaneseRemote(
        baseUrl: String,
        vocalWav: File
    ): Result<List<RemoteAsrSegment>> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeBaseUrl(baseUrl)
            ?: return@withContext Result.failure(IllegalStateException("Remote worker URL not configured."))
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

            val request = Request.Builder()
                .url("$cleanUrl/v1/transcribe")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { resp ->
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
                    if (text.isNotBlank() && endMs > startMs) {
                        segments.add(
                            RemoteAsrSegment(
                                startMs = startMs,
                                endMs = endMs,
                                japaneseText = text,
                                romajiReading = item.optString("romaji", "").trim(),
                                confidenceScore = item.optDouble("confidence", 0.85).toFloat().coerceIn(0f, 1f)
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
        outputWavFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeBaseUrl(baseUrl)
            ?: return@withContext Result.failure(IllegalStateException("Remote worker URL not configured."))
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

            val request = Request.Builder()
                .url("$cleanUrl/v1/synthesize")
                .post(builder.build())
                .build()

            httpClient.newCall(request).execute().use { resp ->
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveWorkerUrl(baseUrl: String, pathOrUrl: String): String {
        return if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            pathOrUrl
        } else {
            "$baseUrl/${pathOrUrl.trimStart('/')}"
        }
    }

    private fun downloadFile(url: String, destination: File) {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IllegalStateException("Empty body for $url")
            destination.parentFile?.mkdirs()
            destination.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    companion object {
        val SELF_HOSTED_WORKER_SPECIFICATION = """
# ==============================================================================
# AnimeDub Studio — Self-Hostable Python/GPU Inference Worker (FastAPI + PyTorch)
# Deploy on any CUDA GPU server / workstation:
#   pip install fastapi uvicorn transformers safetensors demucs onnxruntime pyannote.audio f5-tts torch torchaudio
#   uvicorn worker_server:app --host 0.0.0.0 --port 8080
# ==============================================================================
import os, shutil, torch
from fastapi import FastAPI

app = FastAPI(title="AnimeDub Studio GPU Inference Worker", version="2.1.0")

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
            "SPRINGLab/F5-Hindi-24KHz",
            "adefossez/HTDemucs"
        ]
    }

# POST /v1/separate      -> Agent 6: adefossez/HTDemucs (returns vocal_url, background_url)
# POST /v1/transcribe    -> Agent 1: openai/whisper-large-v3-turbo (returns segments[])
# POST /v1/diarize       -> Agent 3: pyannote/speaker-diarization-3.1 (gated HF token required)
# POST /v1/synthesize    -> Agent 5: SPRINGLab/F5-Hindi-24KHz (returns 24 kHz WAV bytes)
""".trimIndent()
    }
}
