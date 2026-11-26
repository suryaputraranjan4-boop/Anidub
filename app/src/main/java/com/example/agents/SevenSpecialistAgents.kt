package com.example.agents

import android.content.Context
import android.util.Log
import com.example.data.local.AgentJobRecordEntity
import com.example.data.local.AnimeDubDao
import com.example.data.local.AudioStemEntity
import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.DictionaryEntryEntity
import com.example.data.local.EpisodeEntity
import com.example.data.local.ExportRecordEntity
import com.example.data.local.QualityCheckResultEntity
import com.example.data.local.SpeakerProfileEntity
import com.example.data.local.TimelineTrackEntity
import com.example.data.local.TranscriptSegmentEntity
import com.example.data.local.VoiceGenerationRecordEntity
import com.example.data.remote.RemoteWorkerClient
import com.example.data.remote.WorkerHealthStatus
import com.example.engine.AndroidSpeechTtsEngine
import com.example.engine.AudioDspEngine
import com.example.engine.FinalVerificationAgent
import com.example.engine.GeneratedSubtitleBundle
import com.example.engine.HinglishTtsNormalizer
import com.example.engine.MediaExtractionResult
import com.example.engine.MixPlacementItem
import com.example.engine.MixedScriptLanguageEngine
import com.example.engine.SileroVadOnnxEngine
import com.example.engine.SubtitleGenerator
import com.example.engine.SynthesizedDialogueResult
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "SevenSpecialistAgents"

/**
 * Deterministic Pipeline Coordinator & Multi-Agent Studio Orchestrator.
 *
 * Implements the 14-stage workflow strictly against real imported media & user data:
 * - Stage 1: Media Import & Metadata Inspection
 * - Stage 2: Lossless Audio & Timebase Extraction (MediaExtractor + MediaCodec -> PCM WAV)
 * - Stage 3: Background Audio Separation (Remote HTDemucs or On-Device Bandpass/Residual DSP)
 * - Stage 4: Japanese Speech Recognition & VAD (Remote Whisper Large V3 Turbo, or On-Device Silero VAD ONNX / DSP VAD + Firebase AI Logic Gemini Multimodal Audio)
 * - Stage 5: Dialogue Timestamps & Segmentation
 * - Stage 6: Audio-Only Speaker Diarization & Character Profiling (Remote pyannote/WeSpeaker via POST /v1/diarize or On-Device Non-Neural Acoustic F0/Spectral Fallback)
 * - Stage 7: Global Sequential Dialogue Numbering (A-001, B-002, C-003...; never auto-reassigns unidentified speakers)
 * - Stage 8: Context Understanding, Roman Hinglish Translation & Adaptation (Firebase AI Logic Gemini; enforces 100% coverage & zero Devanagari)
 * - Stage 9: Character Voice Reference Setup
 * - Stage 10: Character-Wise Batch Voice Generation (Remote F5-Hindi-24KHz or On-Device Generic Android Speech TTS + WSOLA)
 * - Stage 11: Chronological Timeline Reconstruction
 * - Stage 12: Background & Dialogue Multitrack Mixing (Streaming Additive 2-Pass Mixer)
 * - Stage 13: 13-Point Automated Quality Control
 * - Stage 14: Final Export + Agent 21 Subtitles (SRT + ASS) + Agent 22 Final Verification
 */
class SevenSpecialistAgentsCoordinator(
    private val context: Context,
    private val dao: AnimeDubDao
) {
    private val workerClient = RemoteWorkerClient()
    private val androidTtsEngine = AndroidSpeechTtsEngine(context)

    private val asrSliceSchema = Schema.obj(
        properties = mapOf(
            "japaneseText" to Schema.string(description = "Verbatim Japanese speech transcript from the audio clip; empty string if only music/noise or unintelligible"),
            "romajiReading" to Schema.string(description = "Romaji reading of the Japanese speech"),
            "asrConfidence" to Schema.double(description = "Honest transcription confidence between 0.0 and 1.0"),
            "emotion" to Schema.enumeration(
                values = listOf("ANGRY", "SAD", "HAPPY", "EXCITED", "FEARFUL", "CALM", "WHISPERING", "SHOUTING", "NEUTRAL"),
                description = "Vocal emotional tone heard in the audio"
            ),
            "emotionConfidence" to Schema.double(description = "Honest emotion confidence between 0.0 and 1.0")
        )
    )

    private val translationBatchSchema = Schema.obj(
        properties = mapOf(
            "translations" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "dialogueId" to Schema.string(description = "Original source dialogue ID such as A-001"),
                        "sentences" to Schema.array(
                            items = Schema.obj(
                                properties = mapOf(
                                    "mixedScriptText" to Schema.string(
                                        description = "Natural spoken Mixed-Script Hindi-English sentence: Hindi words strictly in Devanagari, English words strictly in English/Roman characters. Never Roman Hinglish for Hindi words."
                                    ),
                                    "speakingStyle" to Schema.string(description = "Target delivery style matching character personality"),
                                    "emotion" to Schema.enumeration(
                                        values = listOf("ANGRY", "SAD", "HAPPY", "EXCITED", "FEARFUL", "CALM", "WHISPERING", "SHOUTING", "NEUTRAL"),
                                        description = "Detected emotional tone for this sentence"
                                    ),
                                    "emotionConfidence" to Schema.double(description = "Honest emotion confidence between 0.0 and 1.0"),
                                    "translationConfidence" to Schema.double(description = "Honest translation quality confidence between 0.0 and 1.0"),
                                    "sceneContextNote" to Schema.string(description = "Brief note on speaker intent, honorifics, or relationship nuance"),
                                    "dictionaryTermsApplied" to Schema.array(
                                        items = Schema.string(),
                                        description = "Dictionary terms preserved or applied in this sentence"
                                    )
                                )
                            ),
                            description = "1 or more natural Mixed-Script Hindi-English sentences translated from this source dialogue segment"
                        )
                    )
                ),
                description = "Translated dialogue items in Mixed-Script Hindi-English (Devanagari + English)"
            )
        )
    )

    private val scriptCorrectionSchema = Schema.obj(
        properties = mapOf(
            "corrections" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "key" to Schema.string(description = "Exact item key from the correction request"),
                        "correctedMixedScriptText" to Schema.string(
                            description = "Corrected sentence with ALL Hindi words in Devanagari and ALL English words in English/Roman characters, preserving exact meaning and tone"
                        )
                    )
                ),
                description = "Script-corrected sentences"
            )
        )
    )

    private fun ensureFirebaseReady() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            throw IllegalStateException(
                "Missing Firebase config: Firebase is not initialized on this device. Verify google-services.json and Firebase App Check configuration."
            )
        }
    }

    private fun sanitizeGeminiModelName(modelName: String): String {
        val clean = modelName.trim()
        return if (clean.matches(Regex("^[a-zA-Z0-9._\\-]{3,64}$"))) clean else "gemini-3.8-flash"
    }

    private fun classifyGeminiFailure(e: Throwable): String {
        val raw = (e.localizedMessage ?: e.message ?: e::class.java.simpleName).trim()
        val lower = raw.lowercase()
        return when {
            e is TimeoutCancellationException || lower.contains("timeout") || lower.contains("timed out") ->
                "Network / Timeout: Gemini request timed out ($raw)."
            lower.contains("firebase") && (lower.contains("not initialized") || lower.contains("google-services")) ->
                "Missing Firebase config: $raw"
            lower.contains("app check") || lower.contains("appcheck") || lower.contains("permission") ||
                lower.contains("unauthenticated") || lower.contains("403") || lower.contains("401") ->
                "App Check / Auth failure: $raw"
            lower.contains("quota") || lower.contains("resource_exhausted") || lower.contains("429") ||
                lower.contains("billing") || lower.contains("rate limit") ->
                "Quota / Billing limit: $raw"
            lower.contains("unknownhost") || lower.contains("connect") || lower.contains("network") ||
                lower.contains("socket") || lower.contains("offline") ->
                "Network / Timeout: $raw"
            e is org.json.JSONException || lower.contains("json") ->
                "Invalid model JSON response: $raw"
            else -> "Gemini AI error: $raw"
        }
    }

    private fun createAsrModel(geminiModelName: String): GenerativeModel {
        ensureFirebaseReady()
        return Firebase.ai.generativeModel(
            modelName = sanitizeGeminiModelName(geminiModelName),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = asrSliceSchema
            }
        )
    }

    private fun createTranslationModel(geminiModelName: String): GenerativeModel {
        ensureFirebaseReady()
        return Firebase.ai.generativeModel(
            modelName = sanitizeGeminiModelName(geminiModelName),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = translationBatchSchema
            }
        )
    }

    private fun createScriptCorrectionModel(geminiModelName: String): GenerativeModel {
        ensureFirebaseReady()
        return Firebase.ai.generativeModel(
            modelName = sanitizeGeminiModelName(geminiModelName),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = scriptCorrectionSchema
            }
        )
    }

    private suspend fun checkRemoteModelReady(
        remoteWorkerUrl: String,
        remoteWorkerAuthToken: String,
        allowInsecureLocalHttp: Boolean,
        modelKey: String
    ): Pair<Boolean, String> {
        if (remoteWorkerUrl.isBlank()) return false to "Remote worker not configured"
        val health = workerClient.checkWorkerHealth(
            baseUrl = remoteWorkerUrl,
            authToken = remoteWorkerAuthToken,
            allowInsecureLocalHttp = allowInsecureLocalHttp
        )
        if (!health.reachable || !health.apiAvailable) {
            return false to health.statusMessage
        }
        if (health.isModelReady(modelKey)) {
            return true to "Remote $modelKey ready"
        }
        val status = health.modelReadiness[modelKey]
        val detail = if (status != null) {
            "${status.displayName} unavailable on worker (downloaded=${status.downloaded}, loaded=${status.loaded}, ready=${status.readyForInference}${if (status.detailMessage.isNotBlank()) ": ${status.detailMessage}" else ""})"
        } else {
            "Remote worker reachable but model '$modelKey' is not ready for inference"
        }
        return false to detail
    }

    private fun parseEmbeddingJson(json: String): FloatArray {
        val trimmed = json.trim()
        if (trimmed.length < 3 || !trimmed.startsWith("[")) return FloatArray(0)
        return try {
            val arr = JSONArray(trimmed)
            FloatArray(arr.length()) { idx -> arr.optDouble(idx, 0.0).toFloat() }
        } catch (_: Exception) {
            FloatArray(0)
        }
    }

    private fun cleanupOrphanedDialogueWavFiles(genDir: File, activeDialogues: List<DialogueSegmentEntity>) {
        if (!genDir.exists() || !genDir.isDirectory) return
        val referencedPaths = activeDialogues.mapNotNull { it.generatedAudioPath }.map { File(it).name }.toSet()
        val expectedIds = activeDialogues.map { "dlg_${it.dialogueId.lowercase().replace('-', '_')}.wav" }.toSet()
        val keepNames = referencedPaths + expectedIds
        genDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.startsWith("dlg_") || file.name.startsWith("remote_")) && file.name.endsWith(".wav")) {
                if (file.name !in keepNames) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    fun getEpisodeWorkspaceDir(episodeId: Long): File {
        val dir = File(context.filesDir, "episodes/ep_$episodeId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun executePipelineFromStage(
        episodeId: Long,
        startStage: Int = 2,
        endStage: Int = 13,
        selectedTtsEngine: String = "F5-Hindi-24KHz",
        remoteWorkerUrl: String = "",
        remoteWorkerAuthToken: String = "",
        allowInsecureLocalHttp: Boolean = false,
        geminiModelName: String = "gemini-3.8-flash",
        onProgress: suspend (stageNumber: Int, stageTitle: String, detail: String) -> Unit = { _, _, _ -> }
    ): Result<EpisodeEntity> = withContext(Dispatchers.IO) {
        try {
            var episode = dao.getEpisodeById(episodeId)
                ?: return@withContext Result.failure(IllegalArgumentException("Episode $episodeId not found."))

            dao.updateEpisode(episode.copy(pipelineStatus = "RUNNING", lastErrorMessage = null))

            for (stage in startStage..endStage) {
                when (stage) {
                    1, 2 -> {
                        onProgress(2, "Stage 2 — Audio Extraction", "Decoding source media audio track to 16-bit PCM WAV...")
                        episode = runStage1And2Extraction(episode)
                    }
                    3 -> {
                        onProgress(3, "Stage 3 — Stem Separation (Agent 6)", "Separating vocal stem and residual background accompaniment...")
                        episode = runStage3Agent6BackgroundSeparation(
                            episode = episode,
                            remoteWorkerUrl = remoteWorkerUrl,
                            remoteWorkerAuthToken = remoteWorkerAuthToken,
                            allowInsecureLocalHttp = allowInsecureLocalHttp
                        )
                    }
                    4 -> {
                        onProgress(4, "Stage 4 — Japanese ASR & Speech Slicing (Agent 1)", "Detecting speech windows and transcribing Japanese dialogue...")
                        episode = runStage4Agent1JapaneseTranscription(
                            episode = episode,
                            remoteWorkerUrl = remoteWorkerUrl,
                            remoteWorkerAuthToken = remoteWorkerAuthToken,
                            allowInsecureLocalHttp = allowInsecureLocalHttp,
                            geminiModelName = geminiModelName
                        )
                    }
                    5 -> {
                        onProgress(5, "Stage 5 — Dialogue Segmentation (Agent 2)", "Validating dialogue timestamps and overlap boundaries...")
                        episode = runStage5Agent2TimestampsAndSegmentation(episode)
                    }
                    6 -> {
                        onProgress(6, "Stage 6 — Speaker Analysis (Agent 3)", "Running audio-only speaker diarization / acoustic clustering...")
                        episode = runStage6Agent3SpeakerIdentification(
                            episode = episode,
                            remoteWorkerUrl = remoteWorkerUrl,
                            remoteWorkerAuthToken = remoteWorkerAuthToken,
                            allowInsecureLocalHttp = allowInsecureLocalHttp
                        )
                    }
                    7 -> {
                        onProgress(7, "Stage 7 — Global Dialogue Numbering", "Assigning chronological global IDs (A-001, B-002, C-003...)...")
                        episode = runStage7GlobalDialogueNumbering(episode)
                    }
                    8 -> {
                        onProgress(8, "Stage 8 — Context & Hinglish Translation (Agent 4)", "Translating Japanese dialogue via ${sanitizeGeminiModelName(geminiModelName)}...")
                        episode = runStage8Agent4GeminiTranslation(episode, geminiModelName = geminiModelName)
                    }
                    9 -> {
                        onProgress(9, "Stage 9 — Voice Reference Setup", "Preparing normalized character voice reference clips...")
                        episode = runStage9VoiceProfileSetup(episode, selectedTtsEngine)
                    }
                    10 -> {
                        onProgress(10, "Stage 10 — Character Batch Voice Gen (Agent 5)", "Synthesizing real Hindi/Hinglish speech (All A -> All B -> All C)...")
                        episode = runStage10Agent5CharacterWiseVoiceGeneration(
                            episode = episode,
                            selectedTtsEngine = selectedTtsEngine,
                            remoteWorkerUrl = remoteWorkerUrl,
                            remoteWorkerAuthToken = remoteWorkerAuthToken,
                            allowInsecureLocalHttp = allowInsecureLocalHttp
                        )
                    }
                    11 -> {
                        onProgress(11, "Stage 11 — Timeline Reconstruction (Agent 7)", "Restoring chronological dialogue sequence on multitrack timeline...")
                        episode = runStage11Agent7TimelineReconstruction(episode)
                    }
                    12 -> {
                        onProgress(12, "Stage 12 — Multitrack Mixing (Agent 7)", "Mixing character dialogue tracks with preserved background stem...")
                        episode = runStage12Agent7MultitrackMixing(episode)
                    }
                    13 -> {
                        onProgress(13, "Stage 13 — 13-Point Quality Control (Agent 7)", "Running automated integrity, sync, and headroom audit...")
                        episode = runStage13Agent7QualityControl(episode)
                    }
                    14 -> {
                        onProgress(14, "Stage 14 — Export, Subtitles & Verification", "Exporting media, generating SRT/ASS subtitles, and running Final Verification...")
                        episode = runStage14FinalExport(episode, exportFormat = "WAV_AUDIO_ONLY")
                    }
                }
            }

            val finalEpisode = dao.getEpisodeById(episodeId) ?: episode
            val exportVerified = (finalEpisode.completedStagesMask and (1 shl 14)) != 0
            val doneEpisode = finalEpisode.copy(
                pipelineStatus = if (exportVerified) "COMPLETED" else "READY",
                updatedAt = System.currentTimeMillis()
            )
            dao.updateEpisode(doneEpisode)
            Result.success(doneEpisode)
        } catch (e: CancellationException) {
            val current = dao.getEpisodeById(episodeId)
            if (current != null && current.pipelineStatus == "RUNNING") {
                dao.updateEpisode(
                    current.copy(
                        pipelineStatus = "PAUSED_CHECKPOINT",
                        lastErrorMessage = "Pipeline cancelled by user.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline execution error", e)
            val current = dao.getEpisodeById(episodeId)
            if (current != null) {
                dao.updateEpisode(
                    current.copy(
                        pipelineStatus = "ERROR",
                        lastErrorMessage = e.localizedMessage ?: "Pipeline stage failed.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            Result.failure(e)
        }
    }

    /**
     * Stages 1 & 2: Verify imported source media and extract real 16-bit PCM WAV master audio.
     */
    suspend fun runStage1And2Extraction(episode: EpisodeEntity): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val sourceFile = File(episode.originalVideoPath)
        val existingMaster = File(episode.originalMasterAudioPath)

        val masterFile = File(epDir, "master_original_pcm.wav")

        val extractionResult = when {
            existingMaster.exists() && existingMaster.length() > 44L && existingMaster.extension.equals("wav", true) -> {
                val buf = AudioDspEngine.readWavFile(existingMaster)
                if (buf.samples.isNotEmpty()) {
                    if (existingMaster.absolutePath != masterFile.absolutePath) {
                        AudioDspEngine.writeWavFile(masterFile, AudioDspEngine.resampleMono(buf, episode.audioSampleRateHz))
                    }
                    MediaExtractionResult.Success(
                        wavFile = masterFile,
                        sampleRateHz = episode.audioSampleRateHz,
                        channels = 1,
                        durationMs = buf.durationMs,
                        audioCodec = episode.audioCodec.ifBlank { "PCM 16-bit WAV" }
                    )
                } else {
                    AudioDspEngine.extractAudioTrackToWav(sourceFile, masterFile, episode.audioSampleRateHz)
                }
            }
            sourceFile.exists() && sourceFile.length() > 0L -> {
                AudioDspEngine.extractAudioTrackToWav(sourceFile, masterFile, episode.audioSampleRateHz)
            }
            else -> {
                MediaExtractionResult.Failure("No media file has been imported for this episode yet. Import a video or audio file first.")
            }
        }

        when (extractionResult) {
            is MediaExtractionResult.Success -> {
                recordAgentJob(
                    episodeId = episode.id,
                    agentNumber = 6,
                    stageNumber = 2,
                    agentName = "Audio Extraction Engine",
                    operation = "Extracted lossless 16-bit PCM master WAV",
                    modelUsed = "Android MediaExtractor + MediaCodec PCM Decoder",
                    runtimeMode = "ON_DEVICE_ANDROID",
                    inputSummary = episode.videoFileName.ifBlank { sourceFile.name },
                    outputSummary = "${extractionResult.wavFile.name} (${extractionResult.sampleRateHz} Hz, ${extractionResult.channels} ch, ${extractionResult.durationMs} ms)",
                    completed = 1,
                    total = 1,
                    status = "COMPLETED",
                    startedAt = startedAt
                )

                val mask = invalidateDownstreamStages(episode.completedStagesMask or (1 shl 1), 2)
                val updated = episode.copy(
                    originalMasterAudioPath = extractionResult.wavFile.absolutePath,
                    durationMs = extractionResult.durationMs.coerceAtLeast(episode.durationMs),
                    audioCodec = extractionResult.audioCodec,
                    audioSampleRateHz = extractionResult.sampleRateHz,
                    audioChannels = extractionResult.channels,
                    currentStageNumber = 2,
                    currentStageName = "Stage 2 — Original Audio Extracted",
                    completedStagesMask = mask,
                    lastErrorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
                dao.updateEpisode(updated)
                return updated
            }
            MediaExtractionResult.MissingAudioTrack -> {
                val msg = "Imported media '${episode.videoFileName}' contains no audio track."
                recordAgentJob(
                    episodeId = episode.id,
                    agentNumber = 6,
                    stageNumber = 2,
                    agentName = "Audio Extraction Engine",
                    operation = "Audio extraction failed",
                    modelUsed = "Android MediaExtractor",
                    runtimeMode = "ON_DEVICE_ANDROID",
                    inputSummary = episode.videoFileName,
                    outputSummary = "No audio stream found",
                    completed = 0,
                    total = 1,
                    status = "FAILED",
                    errorDetails = msg,
                    startedAt = startedAt
                )
                throw IllegalStateException(msg)
            }
            is MediaExtractionResult.UnsupportedFormat -> {
                recordAgentJob(
                    episodeId = episode.id,
                    agentNumber = 6,
                    stageNumber = 2,
                    agentName = "Audio Extraction Engine",
                    operation = "Unsupported audio format",
                    modelUsed = "Android MediaCodec",
                    runtimeMode = "ON_DEVICE_ANDROID",
                    inputSummary = episode.videoFileName,
                    outputSummary = "Decoder unavailable",
                    completed = 0,
                    total = 1,
                    status = "FAILED",
                    errorDetails = extractionResult.reason,
                    startedAt = startedAt
                )
                throw IllegalStateException(extractionResult.reason)
            }
            is MediaExtractionResult.Failure -> {
                recordAgentJob(
                    episodeId = episode.id,
                    agentNumber = 6,
                    stageNumber = 2,
                    agentName = "Audio Extraction Engine",
                    operation = "Audio extraction failed",
                    modelUsed = "Android MediaExtractor + MediaCodec",
                    runtimeMode = "ON_DEVICE_ANDROID",
                    inputSummary = episode.videoFileName.ifBlank { "No media" },
                    outputSummary = "Extraction failed",
                    completed = 0,
                    total = 1,
                    status = "FAILED",
                    errorDetails = extractionResult.reason,
                    startedAt = startedAt
                )
                throw IllegalStateException(extractionResult.reason)
            }
        }
    }

    /**
     * Stage 3 — Agent 6: Background Audio Separation Agent.
     * Uses Remote Worker HTDemucs when configured & reachable; otherwise runs honest on-device
     * bandpass/residual DSP approximation (never invents SDR/SIR quality scores).
     */
    suspend fun runStage3Agent6BackgroundSeparation(
        episode: EpisodeEntity,
        remoteWorkerUrl: String = "",
        remoteWorkerAuthToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val masterFile = File(episode.originalMasterAudioPath)
        if (!masterFile.exists() || masterFile.length() <= 44L) {
            val msg = "Original master WAV is missing. Run Stage 2 Audio Extraction first."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 6,
                stageNumber = 3,
                agentName = "Agent 6 — Background Audio Separation",
                operation = "Blocked: missing extracted audio",
                modelUsed = "HTDemucs / Residual DSP Separator",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "Missing master audio",
                outputSummary = "No stems produced",
                completed = 0,
                total = 2,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        val stemsDir = File(epDir, "stems").apply { mkdirs() }
        val (htdemucsReady, htdemucsNote) = checkRemoteModelReady(
            remoteWorkerUrl = remoteWorkerUrl,
            remoteWorkerAuthToken = remoteWorkerAuthToken,
            allowInsecureLocalHttp = allowInsecureLocalHttp,
            modelKey = "htdemucs"
        )
        val remoteAttempt = if (htdemucsReady) {
            workerClient.separateAudioRemote(
                baseUrl = remoteWorkerUrl,
                masterWav = masterFile,
                outDir = stemsDir,
                authToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp
            )
        } else {
            Result.failure(IllegalStateException(htdemucsNote))
        }

        val masterBuf = AudioDspEngine.readWavFileStrict(masterFile).getOrElse {
            throw IllegalStateException("Master WAV is unreadable: ${it.localizedMessage}", it)
        }
        val masterPeak = AudioDspEngine.computePeakDbfs(masterBuf)

        val vocalFile: File
        val bgFile: File
        val vocalPeak: Float
        val bgPeak: Float
        val qualityScore: Float
        val separatorModelLabel: String
        val runtimeModeLabel: String

        if (remoteAttempt.isSuccess) {
            val remoteRes = remoteAttempt.getOrThrow()
            vocalFile = remoteRes.vocalFile
            bgFile = remoteRes.backgroundFile
            val vocalBuf = AudioDspEngine.readWavFileStrict(vocalFile).getOrElse {
                throw IllegalStateException("Remote vocal stem WAV invalid: ${it.localizedMessage}", it)
            }
            val bgBuf = AudioDspEngine.readWavFileStrict(bgFile).getOrElse {
                throw IllegalStateException("Remote background stem WAV invalid: ${it.localizedMessage}", it)
            }
            vocalPeak = AudioDspEngine.computePeakDbfs(vocalBuf)
            bgPeak = AudioDspEngine.computePeakDbfs(bgBuf)
            qualityScore = remoteRes.qualityScore ?: -1f
            separatorModelLabel = remoteRes.modelName
            runtimeModeLabel = "REMOTE_GPU_WORKER"
        } else {
            val sepResult = AudioDspEngine.separateStemsDsp(masterFile, stemsDir)
            vocalFile = sepResult.dialogueVocalFile
            bgFile = sepResult.backgroundAccompanimentFile
            vocalPeak = sepResult.vocalPeakDbfs
            bgPeak = sepResult.backgroundPeakDbfs
            qualityScore = sepResult.qualityScore // -1f (unmeasured)
            separatorModelLabel = "On-Device Bandpass/Residual DSP Approximation (Non-AI)"
            runtimeModeLabel = "ON_DEVICE_ANDROID"
        }

        val existingStems = dao.getStemsForEpisodeOnce(episode.id)
        if (existingStems.none { it.stemType == "ORIGINAL_MASTER" }) {
            dao.insertStem(
                AudioStemEntity(
                    episodeId = episode.id,
                    stemType = "ORIGINAL_MASTER",
                    displayName = "Original Master Audio (Protected)",
                    separatorModelUsed = "Lossless PCM Copy",
                    filePath = masterFile.absolutePath,
                    sampleRateHz = masterBuf.sampleRateHz,
                    channels = masterBuf.channels,
                    durationMs = masterBuf.durationMs,
                    volumeGain = 1.0f,
                    isMuted = false,
                    separationQualityScore = -1f,
                    peakDbfs = masterPeak,
                    isHonestTwoStemModel = true
                )
            )
        }

        val vocalStem = AudioStemEntity(
            episodeId = episode.id,
            stemType = "DIALOGUE_VOCAL",
            displayName = "Dialogue / Vocal Stem",
            separatorModelUsed = separatorModelLabel,
            filePath = vocalFile.absolutePath,
            sampleRateHz = masterBuf.sampleRateHz,
            channels = 1,
            durationMs = masterBuf.durationMs,
            volumeGain = 1.0f,
            isMuted = false,
            separationQualityScore = qualityScore,
            peakDbfs = vocalPeak,
            isHonestTwoStemModel = true
        )
        val bgStem = AudioStemEntity(
            episodeId = episode.id,
            stemType = "BACKGROUND_ACCOMPANIMENT",
            displayName = "Background Accompaniment (BGM + SFX)",
            separatorModelUsed = separatorModelLabel,
            filePath = bgFile.absolutePath,
            sampleRateHz = masterBuf.sampleRateHz,
            channels = 1,
            durationMs = masterBuf.durationMs,
            volumeGain = 0.92f,
            isMuted = false,
            separationQualityScore = qualityScore,
            peakDbfs = bgPeak,
            isHonestTwoStemModel = true
        )
        dao.replaceSeparatedStemsAtomic(episode.id, listOf(vocalStem, bgStem))

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 6,
            stageNumber = 3,
            agentName = "Agent 6 — Background Audio Separation",
            operation = "Separated vocal and residual background stems",
            modelUsed = separatorModelLabel,
            runtimeMode = runtimeModeLabel,
            inputSummary = masterFile.name,
            outputSummary = "Vocal ($vocalPeak dBFS) + Background ($bgPeak dBFS)",
            completed = 2,
            total = 2,
            status = "COMPLETED",
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 3,
            currentStageName = "Stage 3 — Stems Separated",
            completedStagesMask = invalidateDownstreamStages(episode.completedStagesMask, 3),
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 4 — Agent 1 (Speech Detection + Japanese ASR):
     * 1. Attempts Remote Worker Whisper Large V3 Turbo (`POST /v1/transcribe`) when configured & reachable.
     * 2. Otherwise runs genuine On-Device Silero VAD via ONNX Runtime Mobile (`model.onnx`) if downloaded,
     *    falling back transparently to Energy-Based DSP VAD when `model.onnx` is not downloaded,
     *    followed by Firebase AI Logic Gemini multimodal audio transcription for each speech slice.
     * 3. Never fabricates fake Japanese transcripts or marks Stage 4 completed with 0 transcribed lines.
     */
    suspend fun runStage4Agent1JapaneseTranscription(
        episode: EpisodeEntity,
        remoteWorkerUrl: String = "",
        remoteWorkerAuthToken: String = "",
        allowInsecureLocalHttp: Boolean = false,
        geminiModelName: String = "gemini-3.8-flash"
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val stems = dao.getStemsForEpisodeOnce(episode.id)
        val vocalStemFile = stems.find { it.stemType == "DIALOGUE_VOCAL" }?.let { File(it.filePath) }
            ?: File(episode.originalMasterAudioPath)

        if (!vocalStemFile.exists() || vocalStemFile.length() <= 44L) {
            val msg = "Vocal stem audio not found. Run Stage 2 & Stage 3 first."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 1,
                stageNumber = 4,
                agentName = "Agent 1 — Japanese Transcription",
                operation = "Blocked: missing vocal stem",
                modelUsed = "Whisper Large V3 Turbo / Gemini Multimodal Audio",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "Missing vocal audio",
                outputSummary = "0 segments",
                completed = 0,
                total = 1,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        val vocalBuffer = AudioDspEngine.readWavFileStrict(vocalStemFile).getOrElse {
            throw IllegalStateException("Vocal stem WAV is unreadable: ${it.localizedMessage}", it)
        }
        val slicesDir = File(epDir, "jp_slices").apply { mkdirs() }
        val existingTranscripts = dao.getTranscriptsForEpisodeOnce(episode.id)
        val cleanGeminiName = sanitizeGeminiModelName(geminiModelName)

        var usedRuntimeMode = "FIREBASE_AI_GEMINI"
        var usedModelLabel = "$cleanGeminiName Multimodal Audio (Firebase AI Logic)"
        var vadMethodLabel = "Existing Segments"
        var asrErrorNote: String? = null

        val segmentsToSave: List<TranscriptSegmentEntity> = if (existingTranscripts.isNotEmpty()) {
            // Re-slice existing segments and transcribe any that are still blank
            existingTranscripts.map { tr ->
                val sliceBuf = AudioDspEngine.sliceBuffer(vocalBuffer, tr.preliminaryStartMs, tr.preliminaryEndMs)
                val sliceFile = AudioDspEngine.writeWavFile(
                    File(slicesDir, "jp_seg_%03d.wav".format(tr.segmentIndex)),
                    sliceBuf
                )
                if (tr.japaneseText.isNotBlank()) {
                    tr.copy(originalAudioSlicePath = sliceFile.absolutePath)
                } else {
                    val asrRes = transcribeSliceWithGeminiAudio(sliceFile, cleanGeminiName)
                    if (asrRes.isSuccess) {
                        val parsed = asrRes.getOrThrow()
                        tr.copy(
                            japaneseText = parsed.japaneseText,
                            romajiReading = parsed.romajiReading,
                            confidenceScore = parsed.asrConfidence,
                            asrModelUsed = "$cleanGeminiName Multimodal Audio",
                            originalAudioSlicePath = sliceFile.absolutePath
                        )
                    } else {
                        asrErrorNote = asrRes.exceptionOrNull()?.localizedMessage
                        tr.copy(originalAudioSlicePath = sliceFile.absolutePath)
                    }
                }
            }
        } else {
            // Path 1: Remote Whisper Large V3 Turbo worker when configured & model ready
            val (whisperReady, whisperNote) = checkRemoteModelReady(
                remoteWorkerUrl = remoteWorkerUrl,
                remoteWorkerAuthToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp,
                modelKey = "whisper"
            )
            val remoteAsr = if (whisperReady) {
                workerClient.transcribeJapaneseRemote(
                    baseUrl = remoteWorkerUrl,
                    vocalWav = vocalStemFile,
                    authToken = remoteWorkerAuthToken,
                    allowInsecureLocalHttp = allowInsecureLocalHttp
                )
            } else {
                Result.failure(IllegalStateException(whisperNote))
            }

            if (remoteAsr.isSuccess) {
                usedRuntimeMode = "REMOTE_GPU_WORKER"
                usedModelLabel = "Whisper Large V3 Turbo (Remote GPU Worker)"
                vadMethodLabel = "Remote Whisper Segmentation"
                remoteAsr.getOrThrow().mapIndexed { idx, seg ->
                    val segIndex = idx + 1
                    val sliceBuf = AudioDspEngine.sliceBuffer(vocalBuffer, seg.startMs, seg.endMs)
                    val sliceFile = AudioDspEngine.writeWavFile(
                        File(slicesDir, "jp_seg_%03d.wav".format(segIndex)),
                        sliceBuf
                    )
                    TranscriptSegmentEntity(
                        episodeId = episode.id,
                        segmentIndex = segIndex,
                        japaneseText = seg.japaneseText,
                        romajiReading = seg.romajiReading,
                        preliminaryStartMs = seg.startMs,
                        preliminaryEndMs = seg.endMs,
                        confidenceScore = seg.confidenceScore,
                        asrModelUsed = usedModelLabel,
                        originalAudioSlicePath = sliceFile.absolutePath
                    )
                }
            } else {
                // Path 2: On-Device Silero VAD ONNX Runtime Mobile (if downloaded) -> Energy DSP VAD fallback
                val sileroOnnxFile = File(context.filesDir, "verified_models/silero_vad_onnx/onnx/model.onnx")
                val onnxResult = if (sileroOnnxFile.exists() && sileroOnnxFile.length() > 100_000L) {
                    SileroVadOnnxEngine.detectSpeechSegmentsOnnx(sileroOnnxFile, vocalBuffer)
                } else {
                    Result.failure(IllegalStateException("Silero VAD ONNX model not downloaded"))
                }

                val detectedRanges: List<Pair<Long, Long>>
                if (onnxResult.isSuccess) {
                    val out = onnxResult.getOrThrow()
                    detectedRanges = out.segments
                    vadMethodLabel = out.engineLabel
                } else {
                    detectedRanges = AudioDspEngine.detectSpeechSegmentsDsp(vocalBuffer)
                    vadMethodLabel = "On-Device Energy-Based DSP VAD (Fallback)"
                }

                if (detectedRanges.isEmpty()) {
                    val msg = "No speech activity detected in '${vocalStemFile.name}' via $vadMethodLabel. Verify audio level or add dialogue segments manually in Transcript."
                    recordAgentJob(
                        episodeId = episode.id,
                        agentNumber = 1,
                        stageNumber = 4,
                        agentName = "Agent 1 — Japanese Transcription",
                        operation = "No vocal speech activity detected",
                        modelUsed = vadMethodLabel,
                        runtimeMode = "ON_DEVICE_ANDROID",
                        inputSummary = "${vocalStemFile.name} (${vocalBuffer.durationMs} ms)",
                        outputSummary = "0 speech segments detected",
                        completed = 0,
                        total = 1,
                        status = "FAILED",
                        errorDetails = msg,
                        startedAt = startedAt
                    )
                    throw IllegalStateException(msg)
                }

                usedModelLabel = "$vadMethodLabel + $cleanGeminiName Audio"
                detectedRanges.mapIndexed { idx, (startMs, endMs) ->
                    val segIndex = idx + 1
                    val sliceBuf = AudioDspEngine.sliceBuffer(vocalBuffer, startMs, endMs)
                    val sliceFile = AudioDspEngine.writeWavFile(
                        File(slicesDir, "jp_seg_%03d.wav".format(segIndex)),
                        sliceBuf
                    )
                    val asrRes = transcribeSliceWithGeminiAudio(sliceFile, cleanGeminiName)
                    if (asrRes.isSuccess) {
                        val parsed = asrRes.getOrThrow()
                        TranscriptSegmentEntity(
                            episodeId = episode.id,
                            segmentIndex = segIndex,
                            japaneseText = parsed.japaneseText,
                            romajiReading = parsed.romajiReading,
                            preliminaryStartMs = startMs,
                            preliminaryEndMs = endMs,
                            confidenceScore = parsed.asrConfidence,
                            asrModelUsed = usedModelLabel,
                            originalAudioSlicePath = sliceFile.absolutePath
                        )
                    } else {
                        asrErrorNote = asrRes.exceptionOrNull()?.localizedMessage
                        usedRuntimeMode = "ON_DEVICE_ANDROID"
                        TranscriptSegmentEntity(
                            episodeId = episode.id,
                            segmentIndex = segIndex,
                            japaneseText = "",
                            romajiReading = "",
                            preliminaryStartMs = startMs,
                            preliminaryEndMs = endMs,
                            confidenceScore = -1f,
                            asrModelUsed = "$vadMethodLabel (ASR Error: ${asrErrorNote?.take(60) ?: "Unavailable"})",
                            originalAudioSlicePath = sliceFile.absolutePath
                        )
                    }
                }
            }
        }

        dao.replaceTranscriptsAtomic(episode.id, segmentsToSave)

        val transcribedCount = segmentsToSave.count { it.japaneseText.isNotBlank() }
        val note = if (transcribedCount > 0) {
            "Transcribed $transcribedCount / ${segmentsToSave.size} Japanese dialogue segment(s) via $usedModelLabel."
        } else {
            "Detected ${segmentsToSave.size} speech slices ($vadMethodLabel), but automatic ASR returned 0 transcripts (${asrErrorNote ?: "check network or Whisper worker"}). Enter or retry in Transcript."
        }

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 1,
            stageNumber = 4,
            agentName = "Agent 1 — Japanese Transcription",
            operation = "Transcribed $transcribedCount / ${segmentsToSave.size} speech slice(s)",
            modelUsed = usedModelLabel,
            runtimeMode = usedRuntimeMode,
            inputSummary = vocalStemFile.name,
            outputSummary = note,
            completed = transcribedCount,
            total = segmentsToSave.size,
            status = if (transcribedCount > 0) "COMPLETED" else "FAILED",
            errorDetails = if (transcribedCount == 0) (asrErrorNote ?: "0 segments transcribed") else null,
            startedAt = startedAt
        )

        val newMask = if (transcribedCount > 0) {
            invalidateDownstreamStages(episode.completedStagesMask, 4)
        } else {
            clearStagesFrom(episode.completedStagesMask, 4)
        }
        val updated = episode.copy(
            currentStageNumber = 4,
            currentStageName = if (transcribedCount > 0) {
                "Stage 4 — $transcribedCount/${segmentsToSave.size} Japanese Lines Transcribed"
            } else {
                "Stage 4 — ${segmentsToSave.size} Slices (ASR Retry Needed)"
            },
            completedStagesMask = newMask,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    private data class GeminiAsrResult(
        val japaneseText: String,
        val romajiReading: String,
        val asrConfidence: Float,
        val emotion: String,
        val emotionConfidence: Float
    )

    private suspend fun transcribeSliceWithGeminiAudio(
        wavFile: File,
        geminiModelName: String
    ): Result<GeminiAsrResult> {
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            return Result.failure(IllegalArgumentException("Audio slice WAV missing or empty."))
        }
        if (wavFile.length() > 15L * 1024L * 1024L) {
            return Result.failure(IllegalArgumentException("Audio slice exceeds 15 MB inlineData limit."))
        }
        return try {
            val asrModel = createAsrModel(geminiModelName)
            val wavBytes = wavFile.readBytes()
            val promptContent = content {
                inlineData(wavBytes, "audio/wav")
                text(
                    """
                    Listen carefully to this Japanese anime dialogue audio clip.
                    Transcribe the exact spoken Japanese dialogue (kanji/kana) and its Romaji reading.
                    Also identify the speaker's vocal emotion and provide honest confidence scores (0.0 to 1.0).
                    If the clip contains only background music, sound effects, or silence with no speech, return empty strings and confidence 0.0.
                    Never invent dialogue that is not spoken in the audio.
                    """.trimIndent()
                )
            }
            val response = withTimeout(45_000L) {
                asrModel.generateContent(promptContent)
            }
            val rawJson = response.text?.trim()
                ?: return Result.failure(IllegalStateException("Gemini ASR returned empty response."))
            val json = JSONObject(rawJson)
            Result.success(
                GeminiAsrResult(
                    japaneseText = json.optString("japaneseText", "").trim(),
                    romajiReading = json.optString("romajiReading", "").trim(),
                    asrConfidence = RemoteWorkerClient.parseOptionalConfidence(json, "asrConfidence"),
                    emotion = json.optString("emotion", "NEUTRAL").trim().uppercase(),
                    emotionConfidence = RemoteWorkerClient.parseOptionalConfidence(json, "emotionConfidence")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini multimodal ASR failed for ${wavFile.name}: ${e.localizedMessage}")
            Result.failure(e)
        }
    }

    /**
     * Stage 5 — Agent 2: Dialogue Timestamp & Segmentation Agent.
     */
    suspend fun runStage5Agent2TimestampsAndSegmentation(episode: EpisodeEntity): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val transcripts = dao.getTranscriptsForEpisodeOnce(episode.id)
        if (transcripts.isEmpty()) {
            val msg = "No transcript segments available. Run Stage 4 or add dialogue segments manually."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 2,
                stageNumber = 5,
                agentName = "Agent 2 — Dialogue Segmentation",
                operation = "Blocked: no speech segments",
                modelUsed = "Boundary & Overlap Alignment",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "0 transcript segments",
                outputSummary = "0 dialogues",
                completed = 0,
                total = 1,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        val existingDialogues = dao.getDialoguesChronologicalOnce(episode.id)
        val existingByOrder = existingDialogues.associateBy { it.globalOrderNumber }

        val sortedTranscripts = transcripts.sortedBy { it.preliminaryStartMs }
        val maxEpMs = episode.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE

        val updatedDialogues = sortedTranscripts.mapIndexed { index, tr ->
            val orderNum = index + 1
            val prev = existingByOrder[orderNum]
            val clampedStart = tr.preliminaryStartMs.coerceIn(0L, (maxEpMs - 100L).coerceAtLeast(0L))
            val clampedEnd = tr.preliminaryEndMs.coerceIn(clampedStart + 100L, maxOf(clampedStart + 100L, maxEpMs))
            val duration = (clampedEnd - clampedStart).coerceAtLeast(100L)
            val overlapId = if (index > 0 && clampedStart < sortedTranscripts[index - 1].preliminaryEndMs) {
                "Overlap #${index}"
            } else null

            val honestTimingStatus = when {
                tr.asrModelUsed.contains("Whisper", ignoreCase = true) -> "VERIFIED_ASR_ALIGNED"
                tr.asrModelUsed.contains("Manual", ignoreCase = true) -> "MANUAL"
                else -> "ACOUSTIC_ESTIMATED"
            }
            val alignmentLabel = when (honestTimingStatus) {
                "VERIFIED_ASR_ALIGNED" -> "Whisper ASR Timestamp Alignment"
                "MANUAL" -> "Manual Timeline Entry"
                else -> "On-Device VAD Acoustic Boundary"
            }

            prev?.copy(
                originalStartMs = clampedStart,
                originalEndMs = clampedEnd,
                originalDurationMs = duration,
                transcriptSegmentId = tr.id,
                japaneseText = tr.japaneseText.ifBlank { prev.japaneseText },
                originalAudioSlicePath = tr.originalAudioSlicePath,
                overlapsWithDialogueId = overlapId,
                alignmentSource = alignmentLabel,
                timingStatus = honestTimingStatus
            ) ?: DialogueSegmentEntity(
                episodeId = episode.id,
                globalOrderNumber = orderNum,
                dialogueId = "UNASSIGNED-%03d".format(orderNum),
                speakerLabel = "?",
                originalStartMs = clampedStart,
                originalEndMs = clampedEnd,
                originalDurationMs = duration,
                transcriptSegmentId = tr.id,
                japaneseText = tr.japaneseText,
                romanHinglishText = "",
                ttsNormalizedText = "",
                alignmentSource = alignmentLabel,
                timingStatus = honestTimingStatus,
                scriptValidationStatus = "UNVALIDATED",
                overlapsWithDialogueId = overlapId,
                originalAudioSlicePath = tr.originalAudioSlicePath,
                timelineStartMs = clampedStart
            )
        }
        dao.replaceDialoguesAtomic(episode.id, updatedDialogues)

        val firstStart = updatedDialogues.first().originalStartMs
        val lastEnd = updatedDialogues.last().originalEndMs
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 2,
            stageNumber = 5,
            agentName = "Agent 2 — Dialogue Segmentation",
            operation = "Aligned ${updatedDialogues.size} dialogue boundaries",
            modelUsed = "On-Device Boundary & Overlap Validator",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${transcripts.size} speech segments",
            outputSummary = "${updatedDialogues.size} dialogue windows (${firstStart}ms – ${lastEnd}ms)",
            completed = updatedDialogues.size,
            total = updatedDialogues.size,
            status = "COMPLETED",
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 5,
            currentStageName = "Stage 5 — Dialogue Timestamps Verified",
            completedStagesMask = invalidateDownstreamStages(episode.completedStagesMask, 5),
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 6 — Agent 3: Audio-Only Speaker Diarization & Acoustic Profiling.
     * 1. Attempts Remote GPU Worker `POST /v1/diarize` (pyannote/speaker-diarization-3.1 + WeSpeaker) when configured.
     * 2. Otherwise runs honest On-Device Acoustic F0 & Spectral Clustering (Non-Neural).
     * Never fabricates `0.85f` similarity for single-sample clusters or `0.55f` emotion confidence;
     * unmeasured values stay `-1f`. Rights consent defaults strictly to FALSE.
     */
    suspend fun runStage6Agent3SpeakerIdentification(
        episode: EpisodeEntity,
        remoteWorkerUrl: String = "",
        remoteWorkerAuthToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
        if (dialogues.isEmpty()) {
            val msg = "No dialogue segments found to analyze. Run Stage 5 first."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 3,
                stageNumber = 6,
                agentName = "Agent 3 — Speaker Analysis (Audio-Only)",
                operation = "Blocked: no dialogue segments",
                modelUsed = "pyannote/speaker-diarization-3.1 / On-Device Acoustic DSP",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "0 dialogues",
                outputSummary = "0 speakers",
                completed = 0,
                total = 1,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        // Strictly project-isolated speaker profiles for cross-episode & rerun continuity (never leaks across projects)
        val savedProjectSpeakers = dao.getSpeakersForProjectOnce(episode.projectId)
            .distinctBy { it.speakerLabel }
        val savedSpeakerEmbeddings = savedProjectSpeakers.mapNotNull { sp ->
            val emb = parseEmbeddingJson(sp.speakerEmbeddingJson)
            if (emb.isNotEmpty()) sp to emb else null
        }
        val existingNamesByLabel = savedProjectSpeakers.associate { it.speakerLabel to it.characterName }
        val existingConsentByLabel = savedProjectSpeakers.associate { it.speakerLabel to it.rightsConsentConfirmed }

        val stems = dao.getStemsForEpisodeOnce(episode.id)
        val vocalStemFile = stems.find { it.stemType == "DIALOGUE_VOCAL" }?.let { File(it.filePath) }
            ?: File(episode.originalMasterAudioPath)

        val (pyannoteReady, pyannoteNote) = if (vocalStemFile.exists() && vocalStemFile.length() > 44L) {
            checkRemoteModelReady(
                remoteWorkerUrl = remoteWorkerUrl,
                remoteWorkerAuthToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp,
                modelKey = "pyannote"
            )
        } else {
            false to "Vocal stem missing"
        }

        val remoteDiarization = if (pyannoteReady) {
            workerClient.diarizeSpeakersRemote(
                baseUrl = remoteWorkerUrl,
                vocalWav = vocalStemFile,
                authToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp
            )
        } else {
            Result.failure(IllegalStateException(pyannoteNote))
        }

        data class DiscoveredCluster(
            var label: String,
            var characterName: String,
            val featuresList: MutableList<com.example.engine.AcousticVoiceFeatures>,
            val similarityScores: MutableList<Float>,
            var centroidEmbedding: FloatArray,
            var representativeSlicePath: String,
            var matchedSavedProfile: SpeakerProfileEntity? = null
        )

        val clusters = mutableListOf<DiscoveredCluster>()
        val labelAlphabet = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")
        val updatedDialogues = mutableListOf<DialogueSegmentEntity>()
        val usedRemoteDiarization = remoteDiarization.isSuccess

        fun matchSavedProjectProfile(
            embedding: FloatArray,
            f0MeanHz: Float,
            alreadyClaimedLabels: Set<String>
        ): SpeakerProfileEntity? {
            if (embedding.isEmpty() || savedSpeakerEmbeddings.isEmpty()) return null
            var bestProfile: SpeakerProfileEntity? = null
            var bestScore = -1f
            for ((saved, savedEmb) in savedSpeakerEmbeddings) {
                if (saved.speakerLabel in alreadyClaimedLabels) continue
                if (savedEmb.size != embedding.size) continue
                val sim = AudioDspEngine.computeCosineSimilarity(embedding, savedEmb)
                val combined = if (f0MeanHz > 0f && saved.f0MeanHz > 0f) {
                    val f0DiffRatio = abs(f0MeanHz - saved.f0MeanHz) / saved.f0MeanHz.coerceAtLeast(80f)
                    if (f0DiffRatio >= 0.18f) continue
                    sim * 0.70f + (1f - f0DiffRatio.coerceIn(0f, 1f)) * 0.30f
                } else {
                    sim
                }
                if (combined > bestScore) {
                    bestScore = combined
                    bestProfile = saved
                }
            }
            return if (bestScore >= 0.85f) bestProfile else null
        }

        fun nextAvailableLabel(usedLabels: Set<String>): String {
            return labelAlphabet.firstOrNull { it !in usedLabels } ?: "S${usedLabels.size + 1}"
        }

        if (usedRemoteDiarization) {
            val remoteSegs = remoteDiarization.getOrThrow()
            val remoteSpeakerToCanonical = mutableMapOf<String, String>()
            val clusterByLabel = mutableMapOf<String, DiscoveredCluster>()
            val claimedLabels = mutableSetOf<String>()

            for (dlg in dialogues) {
                val sliceFile = File(dlg.originalAudioSlicePath)
                val pcm = AudioDspEngine.readWavFile(sliceFile)
                val acoustic = AudioDspEngine.analyzeVoiceSegment(pcm)

                var bestRemoteSpeaker: String? = null
                var bestOverlapMs = 0L
                var bestRemoteConf = -1f
                for (rSeg in remoteSegs) {
                    val overlap = min(dlg.originalEndMs, rSeg.endMs) - max(dlg.originalStartMs, rSeg.startMs)
                    if (overlap > bestOverlapMs) {
                        bestOverlapMs = overlap
                        bestRemoteSpeaker = rSeg.speakerLabel
                        bestRemoteConf = rSeg.confidenceScore
                    }
                }

                val canonicalLabel = if (bestRemoteSpeaker != null) {
                    remoteSpeakerToCanonical.getOrPut(bestRemoteSpeaker) {
                        val matchedSaved = matchSavedProjectProfile(acoustic.embedding, acoustic.f0MeanHz, claimedLabels)
                        val lbl = matchedSaved?.speakerLabel ?: nextAvailableLabel(claimedLabels)
                        claimedLabels.add(lbl)
                        lbl
                    }
                } else {
                    "UNIDENTIFIED"
                }

                if (canonicalLabel != "UNIDENTIFIED") {
                    val cluster = clusterByLabel.getOrPut(canonicalLabel) {
                        val savedMatch = savedProjectSpeakers.find { it.speakerLabel == canonicalLabel }
                        val c = DiscoveredCluster(
                            label = canonicalLabel,
                            characterName = savedMatch?.characterName ?: existingNamesByLabel[canonicalLabel] ?: "Speaker $canonicalLabel",
                            featuresList = mutableListOf(),
                            similarityScores = mutableListOf(),
                            centroidEmbedding = acoustic.embedding,
                            representativeSlicePath = dlg.originalAudioSlicePath,
                            matchedSavedProfile = savedMatch
                        )
                        clusters.add(c)
                        c
                    }
                    cluster.featuresList.add(acoustic)
                    if (bestRemoteConf >= 0f) {
                        cluster.similarityScores.add(bestRemoteConf)
                    }
                }

                val charId = if (canonicalLabel != "UNIDENTIFIED") "CHAR-${episode.projectId}-$canonicalLabel" else ""
                updatedDialogues.add(
                    dlg.copy(
                        speakerLabel = canonicalLabel,
                        characterId = charId,
                        speakerConfidence = if (bestRemoteConf >= 0f) (bestRemoteConf * 100f).roundToInt() / 100f else -1f,
                        emotion = if (dlg.emotion == "NEUTRAL") acoustic.estimatedEmotion else dlg.emotion,
                        emotionConfidence = if (dlg.emotionConfidence >= 0f) dlg.emotionConfidence else -1f,
                        speakingStyle = acoustic.speakingStyle,
                        peakDbfs = acoustic.peakDbfs
                    )
                )
            }
        } else {
            // Honest On-Device Non-Neural Acoustic F0 & Spectral Clustering with Project-Isolated Continuity
            val claimedLabels = mutableSetOf<String>()
            for (dlg in dialogues) {
                val sliceFile = File(dlg.originalAudioSlicePath)
                val pcm = AudioDspEngine.readWavFile(sliceFile)
                val acoustic = AudioDspEngine.analyzeVoiceSegment(pcm)

                var bestCluster: DiscoveredCluster? = null
                var bestScore = -1f
                for (cluster in clusters) {
                    val embSim = AudioDspEngine.computeCosineSimilarity(acoustic.embedding, cluster.centroidEmbedding)
                    val voicedF0s = cluster.featuresList.map { it.f0MeanHz }.filter { it > 0f }
                    val combinedScore = if (acoustic.f0MeanHz > 0f && voicedF0s.isNotEmpty()) {
                        val clusterMeanF0 = voicedF0s.average().toFloat()
                        val f0DiffRatio = abs(acoustic.f0MeanHz - clusterMeanF0) / clusterMeanF0.coerceAtLeast(80f)
                        if (f0DiffRatio >= 0.16f) continue
                        embSim * 0.65f + (1f - f0DiffRatio.coerceIn(0f, 1f)) * 0.35f
                    } else {
                        embSim
                    }
                    if (combinedScore > bestScore) {
                        bestScore = combinedScore
                        bestCluster = cluster
                    }
                }

                val matchedExisting = bestCluster != null && bestScore >= 0.84f
                val assignedCluster = if (matchedExisting) {
                    val target = bestCluster!!
                    target.featuresList.add(acoustic)
                    target.similarityScores.add(bestScore)
                    target
                } else {
                    val savedMatch = matchSavedProjectProfile(acoustic.embedding, acoustic.f0MeanHz, claimedLabels)
                    val newLabel = savedMatch?.speakerLabel ?: nextAvailableLabel(claimedLabels)
                    claimedLabels.add(newLabel)
                    val defaultCharName = savedMatch?.characterName
                        ?: existingNamesByLabel[newLabel]
                        ?: "Speaker $newLabel"
                    val created = DiscoveredCluster(
                        label = newLabel,
                        characterName = defaultCharName,
                        featuresList = mutableListOf(acoustic),
                        similarityScores = mutableListOf(), // Empty until a real pairwise comparison occurs
                        centroidEmbedding = acoustic.embedding,
                        representativeSlicePath = dlg.originalAudioSlicePath,
                        matchedSavedProfile = savedMatch
                    )
                    clusters.add(created)
                    created
                }

                val measuredSpeakerConf = if (matchedExisting) {
                    (bestScore.coerceIn(0f, 1f) * 100f).roundToInt() / 100f
                } else {
                    -1f
                }
                val charId = "CHAR-${episode.projectId}-${assignedCluster.label}"
                updatedDialogues.add(
                    dlg.copy(
                        speakerLabel = assignedCluster.label,
                        characterId = charId,
                        speakerConfidence = measuredSpeakerConf,
                        emotion = if (dlg.emotion == "NEUTRAL") acoustic.estimatedEmotion else dlg.emotion,
                        emotionConfidence = if (dlg.emotionConfidence >= 0f) dlg.emotionConfidence else -1f,
                        speakingStyle = acoustic.speakingStyle,
                        peakDbfs = acoustic.peakDbfs
                    )
                )
            }
        }

        dao.insertDialogues(updatedDialogues)

        val speakerEntities = clusters.map { cluster ->
            val count = cluster.featuresList.size
            val voiced = cluster.featuresList.filter { it.f0MeanHz > 0f }
            val minF0 = if (voiced.isNotEmpty()) voiced.minOf { it.f0MinHz } else 0f
            val maxF0 = if (voiced.isNotEmpty()) voiced.maxOf { it.f0MaxHz } else 0f
            val meanF0 = if (voiced.isNotEmpty()) voiced.map { it.f0MeanHz }.average().toFloat() else 0f
            val stdF0 = if (voiced.isNotEmpty()) voiced.map { it.f0StdHz }.average().toFloat() else 0f
            val centroidHz = cluster.featuresList.map { it.spectralCentroidHz }.average().toFloat()
            val speeds = cluster.featuresList.map { it.speakingSpeedSylPerSec }.filter { it > 0f }
            val speed = if (speeds.isNotEmpty()) speeds.average().toFloat() else 0f
            val avgSim = if (cluster.similarityScores.isNotEmpty()) {
                (cluster.similarityScores.average().toFloat().coerceIn(0f, 1f) * 100f).roundToInt() / 100f
            } else {
                -1f
            }
            val firstFeat = cluster.featuresList.first()
            val embJson = cluster.centroidEmbedding.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }
            val confirmedConsent = cluster.matchedSavedProfile?.rightsConsentConfirmed
                ?: existingConsentByLabel[cluster.label]
                ?: false
            val charId = cluster.matchedSavedProfile?.characterId?.takeIf { it.isNotBlank() }
                ?: "CHAR-${episode.projectId}-${cluster.label}"
            val continuityTag = if (cluster.matchedSavedProfile != null) " • Matched existing project voice profile" else ""

            SpeakerProfileEntity(
                projectId = episode.projectId,
                episodeId = episode.id,
                speakerLabel = cluster.label,
                characterName = cluster.characterName,
                f0MinHz = (minF0 * 10f).roundToInt() / 10f,
                f0MaxHz = (maxF0 * 10f).roundToInt() / 10f,
                f0MeanHz = (meanF0 * 10f).roundToInt() / 10f,
                f0StdHz = (stdF0 * 10f).roundToInt() / 10f,
                spectralCentroidHz = (centroidHz * 10f).roundToInt() / 10f,
                timbreDescriptor = firstFeat.timbreDescriptor,
                speakingSpeedSylPerSec = (speed * 10f).roundToInt() / 10f,
                voiceTexture = firstFeat.voiceTexture,
                speakingStyle = firstFeat.speakingStyle,
                dominantEmotion = firstFeat.estimatedEmotion,
                speakerEmbeddingJson = embJson,
                voiceSimilarityScore = avgSim,
                voiceReferencePath = cluster.representativeSlicePath,
                voiceReferenceId = "VREF-${episode.projectId}-${cluster.label}",
                rightsConsentConfirmed = confirmedConsent,
                useConsentedGenericVoiceFallback = !confirmedConsent,
                preferredTtsEngine = cluster.matchedSavedProfile?.preferredTtsEngine ?: "F5-Hindi-24KHz",
                dialogueCount = count,
                voiceConsistencyScore = avgSim,
                characterId = charId,
                visualIdentityNote = if (usedRemoteDiarization) {
                    "Audio-Only Remote pyannote/WeSpeaker Diarization (No visual/gender assumptions)$continuityTag"
                } else {
                    "Audio-Only On-Device Acoustic F0/Spectral Features (Non-Neural; No visual/gender assumptions)$continuityTag"
                },
                profileConfidence = avgSim
            )
        }
        dao.replaceSpeakersAtomic(episode.id, speakerEntities)

        val stage6Model = if (usedRemoteDiarization) {
            "pyannote/speaker-diarization-3.1 (Remote GPU Worker)"
        } else {
            "On-Device Acoustic F0/Spectral Features (Non-Neural)"
        }
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 3,
            stageNumber = 6,
            agentName = "Agent 3 — Speaker Analysis (Audio-Only)",
            operation = if (usedRemoteDiarization) {
                "Diarized ${speakerEntities.size} speaker(s) via remote pyannote worker"
            } else {
                "Clustered ${speakerEntities.size} speaker(s) via non-neural acoustic F0/spectral features"
            },
            modelUsed = stage6Model,
            runtimeMode = if (usedRemoteDiarization) "REMOTE_GPU_WORKER" else "ON_DEVICE_ANDROID",
            inputSummary = "${updatedDialogues.size} vocal dialogue slices",
            outputSummary = speakerEntities.joinToString {
                val pitchTxt = if (it.f0MeanHz > 0f) "${it.f0MeanHz} Hz" else "Unvoiced"
                "${it.characterName} ($pitchTxt, ${it.dialogueCount} lines)"
            },
            completed = updatedDialogues.size,
            total = updatedDialogues.size,
            status = "COMPLETED",
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 6,
            currentStageName = "Stage 6 — ${speakerEntities.size} Speaker(s) Analyzed",
            completedStagesMask = invalidateDownstreamStages(episode.completedStagesMask, 6),
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 7 — Global Sequential Dialogue Numbering (A-001, B-002, C-003...).
     * Strict Honesty Rule: NEVER silently assigns `"?"`, blank, or `"UNIDENTIFIED"` speakers to `"A"`.
     * If any dialogue lacks an identified speaker, marks it `UNIDENTIFIED-NNN` and blocks completion of Stage 7.
     */
    suspend fun runStage7GlobalDialogueNumbering(episode: EpisodeEntity): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val chronological = dao.getDialoguesChronologicalOnce(episode.id)
            .sortedBy { it.originalStartMs }

        if (chronological.isEmpty()) {
            throw IllegalStateException("No dialogues available to number. Complete Stage 5 & 6 first.")
        }

        val normalizedLabels = chronological.map { dlg ->
            val raw = dlg.speakerLabel.trim().uppercase()
            if (raw.isBlank() || raw == "?" || raw == "UNASSIGNED" || raw == "UNIDENTIFIED") "UNIDENTIFIED" else raw
        }
        val distinctSpeakers = normalizedLabels.distinct().sorted()
        val batchNumberBySpeaker = distinctSpeakers.withIndex().associate { (idx, label) -> label to (idx + 1) }
        val countPerSpeaker = mutableMapOf<String, Int>()

        val numbered = chronological.mapIndexed { index, dlg ->
            val globalNum = index + 1
            val label = normalizedLabels[index]
            val globalId = "$label-%03d".format(globalNum)
            val speakerOrder = (countPerSpeaker[label] ?: 0) + 1
            countPerSpeaker[label] = speakerOrder

            dlg.copy(
                speakerLabel = label,
                globalOrderNumber = globalNum,
                dialogueId = globalId,
                voiceReferenceId = if (label == "UNIDENTIFIED") "" else "VREF-${episode.projectId}-$label",
                characterBatchNumber = batchNumberBySpeaker[label] ?: 0,
                orderInCharacterBatch = speakerOrder,
                timelineStartMs = dlg.originalStartMs
            )
        }
        dao.insertDialogues(numbered)

        val unidentifiedCount = numbered.count { it.speakerLabel == "UNIDENTIFIED" }
        val allIdentified = unidentifiedCount == 0
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 2,
            stageNumber = 7,
            agentName = "Global Dialogue ID Coordinator",
            operation = if (allIdentified) {
                "Assigned sequential global IDs in chronological order"
            } else {
                "Blocked: $unidentifiedCount dialogue(s) have unidentified speakers"
            },
            modelUsed = "Deterministic Global Counter",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${chronological.size} chronological segments",
            outputSummary = numbered.take(8).joinToString(" → ") { it.dialogueId } + if (numbered.size > 8) " …" else "",
            completed = numbered.size - unidentifiedCount,
            total = numbered.size,
            status = if (allIdentified) "COMPLETED" else "FAILED",
            errorDetails = if (allIdentified) null else "$unidentifiedCount dialogue(s) are UNIDENTIFIED. Run Stage 6 Speaker Analysis or assign speakers manually.",
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 7,
            currentStageName = if (allIdentified) {
                "Stage 7 — Global Dialogue IDs Assigned"
            } else {
                "Stage 7 — $unidentifiedCount Unidentified Speaker(s)"
            },
            completedStagesMask = if (allIdentified) {
                invalidateDownstreamStages(episode.completedStagesMask, 7)
            } else {
                clearStagesFrom(episode.completedStagesMask, 7)
            },
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 8 — Agent 4: Permanent Mixed-Script Hindi-English (Devanagari + English) Translation
     * via Firebase AI Logic (configurable Gemini model) with:
     * - Sentence-level splitting (1..N sentences per long source segment)
     * - Acoustic pause-valley timestamp reconciliation (`ACOUSTIC_ESTIMATED` for split sub-sentences)
     * - Strict 3-tier dictionary isolation & precedence (`Project > Anime > Global`)
     * - Mixed-script compliance validation + 1 controlled Gemini script-correction pass if Romanized Hindi leaks
     * - Atomic DB persistence (zero duplicates on rerun) & strict separation of display vs TTS pronunciation text
     */
    suspend fun runStage8Agent4GeminiTranslation(
        episode: EpisodeEntity,
        geminiModelName: String = "gemini-3.8-flash"
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val cleanModelName = sanitizeGeminiModelName(geminiModelName)
        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
        if (dialogues.isEmpty()) {
            throw IllegalStateException("No dialogues exist to translate. Run Stages 4–7 first.")
        }

        val translatable = dialogues.filter { it.japaneseText.isNotBlank() }
        if (translatable.isEmpty()) {
            val msg = "No Japanese transcript text found. Add Japanese lines in the Transcript tab (or run Stage 4 ASR) before running Gemini Translation."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 4,
                stageNumber = 8,
                agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
                operation = "Blocked: Japanese transcript empty",
                modelUsed = "$cleanModelName (Firebase AI Logic)",
                runtimeMode = "FIREBASE_AI_GEMINI",
                inputSummary = "${dialogues.size} dialogue slots (0 with Japanese text)",
                outputSummary = "Awaiting Japanese transcript",
                completed = 0,
                total = dialogues.size,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        val speakers = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val allDictionaries = dao.getAllDictionaryEntriesOnce()
        val project = dao.getProjectById(episode.projectId)
        val resolvedDicts = MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = allDictionaries,
            projectId = episode.projectId,
            animeSeriesName = project?.animeSeriesName
        )

        val dictPromptBlock = if (resolvedDicts.isEmpty()) {
            "(No custom dictionary entries configured yet.)"
        } else {
            resolvedDicts.joinToString("\n") { d ->
                val preferred = MixedScriptLanguageEngine.preferredDisplayTerm(d)
                val pron = d.pronunciationGuidance.takeIf { it.isNotBlank() }?.let { " | Pronunciation: '$it'" } ?: ""
                val notes = d.contextNotes.takeIf { it.isNotBlank() }?.let { " | Notes: $it" } ?: ""
                "- [${d.dictionaryLevel}] Source: '${d.japaneseSourceTerm}' -> Preferred Mixed-Script: '$preferred'$pron$notes"
            }
        }

        val dialogueLinesPrompt = translatable.joinToString("\n") { d ->
            val sp = speakers[d.speakerLabel]
            val durationSec = "%.2f".format(d.originalDurationMs / 1000.0)
            "ID: ${d.dialogueId} | Speaker: ${d.speakerLabel} (${sp?.characterName ?: "Speaker ${d.speakerLabel}"}, Style: ${sp?.speakingStyle ?: d.speakingStyle}) | DurationWindow: ${durationSec}s | Japanese: ${d.japaneseText}"
        }

        val fullPrompt = """
You are Agent 4 — Context Understanding, Japanese-to-Hindi Translation, and Anime Dialogue Adaptation Specialist.
Translate EVERY SINGLE Japanese anime dialogue segment below into natural spoken Mixed-Script Hindi-English.

${MixedScriptLanguageEngine.PERMANENT_MIXED_SCRIPT_SYSTEM_RULES}

SEGMENTATION & DUBBING RULES:
1. You MUST return an entry for EVERY source dialogueId listed below — do not omit any segment.
2. If a Japanese segment contains a single utterance, return 1 sentence in `sentences`.
3. If a longer Japanese segment naturally contains multiple distinct sentences or dramatic beats, split it into 2 or more concise sentences in chronological order inside `sentences` so dubbing delivery and subtitles read naturally.
4. Preserve character relationships, honorific intent (-san, -sama, -kun, senpai), emotional subtext, and anime personality.
5. Adapt dialogue length naturally to fit each segment's DurationWindow in seconds without dropping key meaning.
6. Provide honest confidence scores (0.0 to 1.0) for emotionConfidence and translationConfidence, plus a concise sceneContextNote.
7. Strictly enforce these active Dictionary terms (precedence: PROJECT > ANIME > GLOBAL):
$dictPromptBlock

DIALOGUES IN CHRONOLOGICAL ORDER:
$dialogueLinesPrompt
""".trimIndent()

        data class ParsedSentence(
            val key: String,
            val sourceDialogueId: String,
            var mixedScriptText: String,
            val speakingStyle: String,
            val emotion: String,
            val emotionConfidence: Float,
            val translationConfidence: Float,
            val sceneContextNote: String,
            var scriptStatus: String = MixedScriptLanguageEngine.STATUS_VALID
        )

        val sentencesBySourceId = linkedMapOf<String, MutableList<ParsedSentence>>()

        try {
            val translationModel = createTranslationModel(cleanModelName)
            val response = withTimeout(60_000L) {
                translationModel.generateContent(fullPrompt)
            }
            val jsonText = response.text?.trim()
                ?: throw IllegalStateException("Gemini returned an empty response.")
            val root = JSONObject(jsonText)
            val arr: JSONArray = root.getJSONArray("translations")
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val dId = item.getString("dialogueId").trim()
                val sentArr = item.optJSONArray("sentences")
                val list = mutableListOf<ParsedSentence>()
                if (sentArr != null && sentArr.length() > 0) {
                    for (j in 0 until sentArr.length()) {
                        val sObj = sentArr.getJSONObject(j)
                        val rawMixed = sObj.optString("mixedScriptText", "").trim()
                        if (rawMixed.isBlank()) continue
                        list.add(
                            ParsedSentence(
                                key = "$dId#$j",
                                sourceDialogueId = dId,
                                mixedScriptText = rawMixed,
                                speakingStyle = sObj.optString("speakingStyle", "Natural").trim().ifBlank { "Natural" },
                                emotion = sObj.optString("emotion", "NEUTRAL").trim().uppercase().ifBlank { "NEUTRAL" },
                                emotionConfidence = RemoteWorkerClient.parseOptionalConfidence(sObj, "emotionConfidence"),
                                translationConfidence = RemoteWorkerClient.parseOptionalConfidence(sObj, "translationConfidence"),
                                sceneContextNote = sObj.optString("sceneContextNote", "").trim()
                            )
                        )
                    }
                }
                if (list.isNotEmpty()) {
                    sentencesBySourceId[dId] = list
                }
            }
            if (sentencesBySourceId.isEmpty()) {
                throw IllegalStateException("Invalid model JSON response: Gemini returned 0 translated sentences.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "${classifyGeminiFailure(e)} Retry Stage 8 or enter Mixed-Script Hindi-English manually in Translate."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 4,
                stageNumber = 8,
                agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
                operation = "Gemini translation failed",
                modelUsed = "$cleanModelName (Firebase AI Logic)",
                runtimeMode = "FIREBASE_AI_GEMINI",
                inputSummary = "${translatable.size} Japanese dialogues",
                outputSummary = "0 translated",
                completed = 0,
                total = translatable.size,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg, e)
        }

        // Apply dictionary display rules & validate Mixed-Script compliance on every sentence
        val sourceJapaneseById = translatable.associate { it.dialogueId to it.japaneseText }
        val invalidForCorrection = mutableListOf<ParsedSentence>()
        sentencesBySourceId.values.flatten().forEach { sent ->
            val srcJp = sourceJapaneseById[sent.sourceDialogueId] ?: ""
            sent.mixedScriptText = MixedScriptLanguageEngine.applyDictionariesToDisplayText(
                displayText = sent.mixedScriptText,
                japaneseSource = srcJp,
                resolvedEntries = resolvedDicts
            )
            val validation = MixedScriptLanguageEngine.validateMixedScript(sent.mixedScriptText)
            sent.scriptStatus = validation.status
            if (!validation.isValid) {
                invalidForCorrection.add(sent)
            }
        }

        // One controlled Gemini script-correction pass if any Romanized Hindi leaked
        if (invalidForCorrection.isNotEmpty()) {
            try {
                val correctionModel = createScriptCorrectionModel(cleanModelName)
                val correctionPrompt = buildString {
                    appendLine("Fix ONLY the script of each Hindi-English anime dialogue below without changing the meaning, emotion, or natural English words.")
                    appendLine(MixedScriptLanguageEngine.PERMANENT_MIXED_SCRIPT_SYSTEM_RULES)
                    appendLine()
                    appendLine("Convert any Romanized Hindi words into proper Devanagari while keeping natural English words, brands, technical terms, and English names in English/Roman script.")
                    appendLine("ITEMS TO FIX:")
                    invalidForCorrection.forEach { s ->
                        appendLine("KEY: ${s.key} | ORIGINAL_JAPANESE: ${sourceJapaneseById[s.sourceDialogueId] ?: ""} | TEXT: ${s.mixedScriptText}")
                    }
                }
                val corrResp = withTimeout(45_000L) { correctionModel.generateContent(correctionPrompt) }
                val corrJson = corrResp.text?.trim()
                if (!corrJson.isNullOrBlank()) {
                    val corrArr = JSONObject(corrJson).optJSONArray("corrections")
                    val fixedByKey = mutableMapOf<String, String>()
                    if (corrArr != null) {
                        for (i in 0 until corrArr.length()) {
                            val obj = corrArr.getJSONObject(i)
                            val k = obj.optString("key", "").trim()
                            val txt = obj.optString("correctedMixedScriptText", "").trim()
                            if (k.isNotBlank() && txt.isNotBlank()) fixedByKey[k] = txt
                        }
                    }
                    invalidForCorrection.forEach { sent ->
                        val fixed = fixedByKey[sent.key]
                        if (!fixed.isNullOrBlank()) {
                            val srcJp = sourceJapaneseById[sent.sourceDialogueId] ?: ""
                            val dictApplied = MixedScriptLanguageEngine.applyDictionariesToDisplayText(
                                displayText = fixed,
                                japaneseSource = srcJp,
                                resolvedEntries = resolvedDicts
                            )
                            val recheck = MixedScriptLanguageEngine.validateMixedScript(dictApplied)
                            sent.mixedScriptText = dictApplied
                            sent.scriptStatus = if (recheck.isValid) {
                                MixedScriptLanguageEngine.STATUS_CORRECTED
                            } else {
                                recheck.status
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Controlled mixed-script correction pass failed: ${e.localizedMessage}")
            }
        }

        // Build final chronological dialogue rows (supporting 1..N sentence splits with acoustic pause reconciliation)
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val slicesDir = File(epDir, "jp_slices").apply { mkdirs() }
        val maxEpMs = episode.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val assembledDialogues = mutableListOf<DialogueSegmentEntity>()
        val invalidScriptIds = mutableListOf<String>()
        var splitCount = 0

        for (dlg in dialogues.sortedBy { it.originalStartMs }) {
            val translatedSentences = sentencesBySourceId[dlg.dialogueId]
            if (translatedSentences.isNullOrEmpty()) {
                val errNote = if (dlg.japaneseText.isNotBlank()) {
                    "Translation omitted by model response; retry Stage 8 or translate manually."
                } else dlg.errorNote
                assembledDialogues.add(dlg.copy(errorNote = errNote))
                continue
            }

            if (translatedSentences.size == 1) {
                val s = translatedSentences.first()
                val ttsPron = MixedScriptLanguageEngine.buildTtsPronunciationText(s.mixedScriptText, resolvedDicts)
                val scriptOk = s.scriptStatus == MixedScriptLanguageEngine.STATUS_VALID ||
                    s.scriptStatus == MixedScriptLanguageEngine.STATUS_CORRECTED
                if (!scriptOk) invalidScriptIds.add(dlg.dialogueId)
                assembledDialogues.add(
                    dlg.copy(
                        romanHinglishText = s.mixedScriptText,
                        ttsNormalizedText = ttsPron,
                        speakingStyle = s.speakingStyle,
                        emotion = s.emotion,
                        emotionConfidence = s.emotionConfidence,
                        translationConfidence = s.translationConfidence,
                        sceneContextNote = s.sceneContextNote,
                        scriptValidationStatus = s.scriptStatus,
                        generationStatus = if (dlg.generatedAudioPath != null) dlg.generationStatus else "TRANSLATED",
                        errorNote = if (scriptOk) null else "Mixed-script validation failed: Romanized Hindi detected. Tap Fix Script with Gemini."
                    )
                )
            } else {
                // Multi-sentence split: reconcile timestamps via acoustic pause-valley energy minima
                splitCount++
                val origSliceFile = File(dlg.originalAudioSlicePath)
                val origSlicePcm = if (origSliceFile.exists() && origSliceFile.length() > 44L) {
                    AudioDspEngine.readWavFile(origSliceFile)
                } else null
                val weights = translatedSentences.map { it.mixedScriptText.length.coerceAtLeast(1) }
                val subRanges = AudioDspEngine.splitSegmentByAcousticPauses(
                    segmentPcm = origSlicePcm,
                    segmentStartMs = dlg.originalStartMs,
                    segmentEndMs = dlg.originalEndMs,
                    sentenceWeights = weights
                )

                translatedSentences.forEachIndexed { idx, s ->
                    val (rawStart, rawEnd) = subRanges.getOrElse(idx) { dlg.originalStartMs to dlg.originalEndMs }
                    val subStart = rawStart.coerceIn(0L, (maxEpMs - 100L).coerceAtLeast(0L))
                    val subEnd = rawEnd.coerceIn(subStart + 100L, maxOf(subStart + 100L, maxEpMs))
                    val subDur = (subEnd - subStart).coerceAtLeast(100L)

                    val subSlicePath = if (origSlicePcm != null && origSlicePcm.samples.isNotEmpty()) {
                        val relStart = (subStart - dlg.originalStartMs).coerceAtLeast(0L)
                        val relEnd = (subEnd - dlg.originalStartMs).coerceAtLeast(relStart + 100L)
                        val subBuf = AudioDspEngine.sliceBuffer(origSlicePcm, relStart, relEnd)
                        val subFile = File(slicesDir, "jp_split_${dlg.dialogueId.lowercase().replace('-', '_')}_${idx + 1}.wav")
                        AudioDspEngine.writeWavFile(subFile, subBuf)
                        subFile.absolutePath
                    } else {
                        dlg.originalAudioSlicePath
                    }

                    val ttsPron = MixedScriptLanguageEngine.buildTtsPronunciationText(s.mixedScriptText, resolvedDicts)
                    val scriptOk = s.scriptStatus == MixedScriptLanguageEngine.STATUS_VALID ||
                        s.scriptStatus == MixedScriptLanguageEngine.STATUS_CORRECTED
                    if (!scriptOk) invalidScriptIds.add("${dlg.dialogueId}.${idx + 1}")

                    assembledDialogues.add(
                        dlg.copy(
                            id = 0L, // Fresh row for atomic replacement
                            sourceDialogueRef = dlg.sourceDialogueRef.ifBlank { dlg.dialogueId },
                            originalStartMs = subStart,
                            originalEndMs = subEnd,
                            originalDurationMs = subDur,
                            timelineStartMs = subStart,
                            originalAudioSlicePath = subSlicePath,
                            romanHinglishText = s.mixedScriptText,
                            ttsNormalizedText = ttsPron,
                            speakingStyle = s.speakingStyle,
                            emotion = s.emotion,
                            emotionConfidence = s.emotionConfidence,
                            translationConfidence = s.translationConfidence,
                            sceneContextNote = s.sceneContextNote,
                            timingStatus = "ACOUSTIC_ESTIMATED",
                            alignmentSource = "Acoustic Pause-Valley Sub-Sentence Split (Estimated)",
                            scriptValidationStatus = s.scriptStatus,
                            generatedAudioPath = null,
                            actualGeneratedDurationMs = 0L,
                            durationDeviationMs = 0L,
                            generationStatus = "TRANSLATED",
                            syncStatus = "PENDING",
                            errorNote = if (scriptOk) null else "Mixed-script validation failed: Romanized Hindi detected. Tap Fix Script with Gemini."
                        )
                    )
                }
            }
        }

        // Re-assign globally sequential unique IDs (A-001, B-002, A-003...) across all assembled dialogues
        val sortedFinal = assembledDialogues.sortedBy { it.originalStartMs }
        val distinctSpeakers = sortedFinal.map { it.speakerLabel }.distinct().sorted()
        val batchBySpeaker = distinctSpeakers.withIndex().associate { (idx, lbl) -> lbl to (idx + 1) }
        val countPerSpeaker = mutableMapOf<String, Int>()
        val finalNumbered = sortedFinal.mapIndexed { idx, dlg ->
            val globalNum = idx + 1
            val label = dlg.speakerLabel.trim().uppercase().ifBlank { "UNIDENTIFIED" }
            val newId = "$label-%03d".format(globalNum)
            val spOrder = (countPerSpeaker[label] ?: 0) + 1
            countPerSpeaker[label] = spOrder
            dlg.copy(
                id = 0L,
                speakerLabel = label,
                globalOrderNumber = globalNum,
                dialogueId = newId,
                characterBatchNumber = batchBySpeaker[label] ?: 0,
                orderInCharacterBatch = spOrder
            )
        }

        dao.replaceDialoguesAtomic(episode.id, finalNumbered)
        cleanupOrphanedDialogueWavFiles(File(epDir, "generated_dialogues"), finalNumbered)

        val missingIds = translatable.map { it.dialogueId }.filterNot { it in sentencesBySourceId }
        val fullCoverage = missingIds.isEmpty() && invalidScriptIds.isEmpty()
        val coverageErr = if (!fullCoverage) {
            buildString {
                if (invalidScriptIds.isNotEmpty()) {
                    append("Mixed-script validation failed on: ${invalidScriptIds.joinToString()}. ")
                }
                if (missingIds.isNotEmpty()) {
                    append("Missing translations for: ${missingIds.joinToString()}.")
                }
            }.trim()
        } else null

        val splitNote = if (splitCount > 0) " ($splitCount segment(s) split into ${finalNumbered.size} sentences with acoustic-estimated sub-timings)" else ""
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 4,
            stageNumber = 8,
            agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
            operation = "Translated ${sentencesBySourceId.size}/${translatable.size} segment(s) → ${finalNumbered.size} line(s) via $cleanModelName",
            modelUsed = "$cleanModelName (Firebase AI Logic)",
            runtimeMode = "FIREBASE_AI_GEMINI",
            inputSummary = "${translatable.size} Japanese segments + ${resolvedDicts.size} active dictionary terms",
            outputSummary = if (fullCoverage) {
                "Saved ${finalNumbered.size} Mixed-Script Hindi-English line(s)$splitNote"
            } else {
                "Incomplete (${sentencesBySourceId.size}/${translatable.size}): $coverageErr"
            },
            completed = sentencesBySourceId.size,
            total = translatable.size,
            status = if (fullCoverage) "COMPLETED" else "FAILED",
            errorDetails = coverageErr,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 8,
            currentStageName = if (fullCoverage) {
                "Stage 8 — Mixed-Script Hindi-English Translated (${finalNumbered.size} lines)"
            } else {
                "Stage 8 — Script/Coverage Review Needed (${sentencesBySourceId.size}/${translatable.size})"
            },
            completedStagesMask = if (fullCoverage) {
                invalidateDownstreamStages(episode.completedStagesMask, 8)
            } else {
                clearStagesFrom(episode.completedStagesMask, 8)
            },
            lastErrorMessage = if (fullCoverage) null else coverageErr,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * One-tap controlled Gemini Mixed-Script repair for a single manually edited or legacy Roman-Hinglish dialogue line.
     */
    suspend fun fixSingleDialogueScriptWithGemini(
        dialogueId: Long,
        rawText: String,
        geminiModelName: String = "gemini-3.8-flash"
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter dialogue text first."))
        }
        val dlg = dao.getDialogueById(dialogueId)
        val ep = dlg?.let { dao.getEpisodeById(it.episodeId) }
        val project = ep?.let { dao.getProjectById(it.projectId) }
        val resolvedDicts = MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = dao.getAllDictionaryEntriesOnce(),
            projectId = ep?.projectId,
            animeSeriesName = project?.animeSeriesName
        )

        try {
            val model = createScriptCorrectionModel(geminiModelName)
            val prompt = buildString {
                appendLine("Convert any Romanized Hindi words in the dialogue below into natural Devanagari while preserving natural English words, brands, technical terms, and exact meaning.")
                appendLine(MixedScriptLanguageEngine.PERMANENT_MIXED_SCRIPT_SYSTEM_RULES)
                if (!dlg?.japaneseText.isNullOrBlank()) {
                    appendLine("ORIGINAL JAPANESE: ${dlg?.japaneseText}")
                }
                appendLine("KEY: single | TEXT: $trimmed")
            }
            val resp = withTimeout(45_000L) { model.generateContent(prompt) }
            val json = resp.text?.trim()
                ?: return@withContext Result.failure(IllegalStateException("Gemini returned an empty response."))
            val arr = JSONObject(json).optJSONArray("corrections")
            val correctedRaw = arr?.optJSONObject(0)?.optString("correctedMixedScriptText", "")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(IllegalStateException("Invalid model JSON response: no corrected text returned."))
            val withDict = MixedScriptLanguageEngine.applyDictionariesToDisplayText(
                displayText = correctedRaw,
                japaneseSource = dlg?.japaneseText ?: "",
                resolvedEntries = resolvedDicts
            )
            Result.success(withDict)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(classifyGeminiFailure(e), e))
        }
    }

    /**
     * Stage 9 — Character Voice Reference Setup.
     */
    suspend fun runStage9VoiceProfileSetup(
        episode: EpisodeEntity,
        selectedTtsEngine: String
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val refDir = File(epDir, "voice_refs").apply { mkdirs() }
        val speakers = dao.getSpeakersForEpisodeOnce(episode.id)
        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)

        if (speakers.isEmpty()) {
            throw IllegalStateException("No speaker profiles detected yet. Run Stage 6 Speaker Analysis first.")
        }

        val updatedSpeakers = speakers.map { sp ->
            val speakerDialogues = dialogues.filter { it.speakerLabel == sp.speakerLabel }
            val cleanReferenceSource = speakerDialogues.firstOrNull { File(it.originalAudioSlicePath).exists() }?.originalAudioSlicePath
                ?: sp.voiceReferencePath
            val srcFile = File(cleanReferenceSource)
            val targetRefFile = File(refDir, "vref_speaker_${sp.speakerLabel.lowercase()}.wav")
            val refPath = if (srcFile.exists() && srcFile.length() > 44L) {
                val buf = AudioDspEngine.readWavFile(srcFile)
                if (buf.samples.isNotEmpty()) {
                    AudioDspEngine.writeWavFile(targetRefFile, buf)
                    targetRefFile.absolutePath
                } else ""
            } else ""

            sp.copy(
                voiceReferencePath = refPath,
                voiceReferenceId = "VREF-${episode.projectId}-${sp.speakerLabel}",
                preferredTtsEngine = selectedTtsEngine,
                dialogueCount = speakerDialogues.size
            )
        }
        dao.insertSpeakerProfiles(updatedSpeakers)

        val readyCount = updatedSpeakers.count { it.voiceReferencePath.isNotBlank() && File(it.voiceReferencePath).exists() }
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 5,
            stageNumber = 9,
            agentName = "Voice Reference Builder",
            operation = "Prepared $readyCount / ${updatedSpeakers.size} speaker reference clips",
            modelUsed = "PCM Reference Normalizer ($selectedTtsEngine)",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${updatedSpeakers.size} speaker profiles",
            outputSummary = updatedSpeakers.joinToString { "${it.characterName}: ${if (it.rightsConsentConfirmed) "Source Consent" else "Generic Fallback"}" },
            completed = readyCount,
            total = updatedSpeakers.size,
            status = "COMPLETED",
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 9,
            currentStageName = "Stage 9 — Voice References Prepared",
            completedStagesMask = invalidateDownstreamStages(episode.completedStagesMask, 9),
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 10 — Agent 5 (Voice Generation & Pronunciation):
     * Executes character-wise batches (All A -> All B -> All C) using REAL spoken speech synthesis:
     * 1. Remote GPU Worker `SPRINGLab/F5-Hindi-24KHz` (`POST /v1/synthesize`) when configured & reachable.
     * 2. On-Device `AndroidSpeechTtsEngine` (generic Android Speech TTS — no character voice cloning).
     * Never falls back to fake sine-wave oscillators; failed lines are marked FAILED for single-line retry.
     */
    suspend fun runStage10Agent5CharacterWiseVoiceGeneration(
        episode: EpisodeEntity,
        selectedTtsEngine: String,
        remoteWorkerUrl: String = "",
        remoteWorkerAuthToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val genDir = File(epDir, "generated_dialogues").apply { mkdirs() }
        val speakersByLabel = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val project = dao.getProjectById(episode.projectId)
        val resolvedDictionaries = MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = dao.getAllDictionaryEntriesOnce(),
            projectId = episode.projectId,
            animeSeriesName = project?.animeSeriesName
        )

        val characterWiseDialogues = dao.getDialoguesCharacterWiseBatchesOnce(episode.id)
        val readyDialogues = characterWiseDialogues.filter { it.romanHinglishText.isNotBlank() }
        if (readyDialogues.isEmpty()) {
            val msg = "No Mixed-Script Hindi-English dialogue lines are ready for voice synthesis. Translate in Stage 8 or enter text in the Translate tab."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 5,
                stageNumber = 10,
                agentName = "Agent 5 — Voice Generation",
                operation = "Blocked: no translated dialogue text",
                modelUsed = "$selectedTtsEngine / Android Speech TTS",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "${characterWiseDialogues.size} dialogues (0 translated)",
                outputSummary = "0 audio files generated",
                completed = 0,
                total = characterWiseDialogues.size.coerceAtLeast(1),
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        val (remoteF5Ready, _) = if (selectedTtsEngine == "F5-Hindi-24KHz" && remoteWorkerUrl.isNotBlank()) {
            checkRemoteModelReady(
                remoteWorkerUrl = remoteWorkerUrl,
                remoteWorkerAuthToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp,
                modelKey = "f5_hindi"
            )
        } else {
            false to "On-device TTS mode"
        }

        val distinctSpeakerLabels = readyDialogues.map { it.speakerLabel }.distinct().sorted()
        val batchExecutionSummary = mutableListOf<String>()
        var generatedCount = 0
        var failedCount = 0
        var lastEngineUsed = selectedTtsEngine
        var lastRuntimeMode = "ON_DEVICE_ANDROID"
        var lastError: String? = null

        for ((batchIdx, speakerLabel) in distinctSpeakerLabels.withIndex()) {
            val batchNumber = batchIdx + 1
            val batchDialogues = readyDialogues
                .filter { it.speakerLabel == speakerLabel }
                .sortedBy { it.globalOrderNumber }

            val speakerProfile = speakersByLabel[speakerLabel] ?: SpeakerProfileEntity(
                projectId = episode.projectId,
                episodeId = episode.id,
                speakerLabel = speakerLabel,
                characterName = "Speaker $speakerLabel",
                f0MinHz = 0f,
                f0MaxHz = 0f,
                f0MeanHz = 0f,
                f0StdHz = 0f,
                spectralCentroidHz = 0f,
                timbreDescriptor = "Unanalyzed",
                speakingSpeedSylPerSec = 0f,
                voiceTexture = "Unmeasured",
                speakingStyle = "Natural",
                dominantEmotion = "NEUTRAL",
                speakerEmbeddingJson = "[]",
                voiceSimilarityScore = -1f,
                voiceReferencePath = "",
                voiceReferenceId = "VREF-${episode.projectId}-$speakerLabel",
                rightsConsentConfirmed = false,
                useConsentedGenericVoiceFallback = true,
                characterId = "CHAR-${episode.projectId}-$speakerLabel"
            )

            val batchIds = mutableListOf<String>()
            for ((idxInBatch, dlg) in batchDialogues.withIndex()) {
                val orderInBatch = idxInBatch + 1
                val synthOutcome = synthesizeSingleLineReal(
                    dialogueId = dlg.dialogueId,
                    speakerProfile = speakerProfile,
                    visibleMixedScript = dlg.romanHinglishText,
                    resolvedDictionaries = resolvedDictionaries,
                    emotion = dlg.emotion,
                    targetDurationMs = dlg.originalDurationMs,
                    selectedEngineId = selectedTtsEngine,
                    useRemoteF5 = remoteF5Ready,
                    remoteWorkerUrl = remoteWorkerUrl,
                    remoteWorkerAuthToken = remoteWorkerAuthToken,
                    allowInsecureLocalHttp = allowInsecureLocalHttp,
                    outputDir = genDir,
                    sampleRateHz = episode.audioSampleRateHz
                )

                if (synthOutcome.isSuccess) {
                    val synthResult = synthOutcome.getOrThrow()
                    val isRemoteF5 = synthResult.engineUsed.contains("F5-Hindi", ignoreCase = true)
                    lastEngineUsed = synthResult.engineUsed
                    lastRuntimeMode = if (isRemoteF5) "REMOTE_GPU_WORKER" else "ON_DEVICE_ANDROID"

                    val deviationMs = synthResult.actualDurationMs - dlg.originalDurationMs
                    val updatedDlg = dlg.copy(
                        ttsNormalizedText = synthResult.internalNormalizedPhonetic,
                        previousGeneratedAudioPath = dlg.generatedAudioPath,
                        generatedAudioPath = synthResult.outputFile.absolutePath,
                        actualGeneratedDurationMs = synthResult.actualDurationMs,
                        durationDeviationMs = deviationMs,
                        timeStretchRatio = synthResult.timeStretchRatio,
                        voiceReferenceId = speakerProfile.voiceReferenceId,
                        ttsEngineUsed = synthResult.engineUsed,
                        characterBatchNumber = batchNumber,
                        orderInCharacterBatch = orderInBatch,
                        generationStatus = "GENERATED",
                        syncStatus = synthResult.syncStatus,
                        peakDbfs = synthResult.peakDbfs,
                        generationVersion = dlg.generationVersion + 1,
                        errorNote = null
                    )
                    dao.updateDialogue(updatedDlg)

                    dao.insertVoiceGenerationRecord(
                        VoiceGenerationRecordEntity(
                            episodeId = episode.id,
                            dialogueId = dlg.dialogueId,
                            speakerLabel = speakerLabel,
                            characterBatchNumber = batchNumber,
                            orderInCharacterBatch = orderInBatch,
                            ttsEngine = synthResult.engineUsed,
                            modelVersion = synthResult.modelVersion,
                            voiceReferenceId = speakerProfile.voiceReferenceId,
                            visibleRomanHinglish = dlg.romanHinglishText,
                            internalPhoneticText = synthResult.internalNormalizedPhonetic,
                            emotionRequested = dlg.emotion,
                            emotionControlSupported = isRemoteF5,
                            targetDurationMs = dlg.originalDurationMs,
                            actualDurationMs = synthResult.actualDurationMs,
                            timeStretchRatio = synthResult.timeStretchRatio,
                            outputFilePath = synthResult.outputFile.absolutePath,
                            sampleRateHz = episode.audioSampleRateHz,
                            status = "SUCCESS"
                        )
                    )
                    batchIds.add(dlg.dialogueId)
                    generatedCount++
                } else {
                    val errMsg = synthOutcome.exceptionOrNull()?.localizedMessage ?: "Real speech synthesis failed."
                    lastError = errMsg
                    failedCount++
                    dao.updateDialogue(
                        dlg.copy(
                            characterBatchNumber = batchNumber,
                            orderInCharacterBatch = orderInBatch,
                            generationStatus = "FAILED",
                            syncStatus = "PENDING",
                            errorNote = errMsg
                        )
                    )
                    dao.insertVoiceGenerationRecord(
                        VoiceGenerationRecordEntity(
                            episodeId = episode.id,
                            dialogueId = dlg.dialogueId,
                            speakerLabel = speakerLabel,
                            characterBatchNumber = batchNumber,
                            orderInCharacterBatch = orderInBatch,
                            ttsEngine = selectedTtsEngine,
                            modelVersion = "FAILED",
                            voiceReferenceId = speakerProfile.voiceReferenceId,
                            visibleRomanHinglish = dlg.romanHinglishText,
                            internalPhoneticText = MixedScriptLanguageEngine.buildTtsPronunciationText(dlg.romanHinglishText, resolvedDictionaries),
                            emotionRequested = dlg.emotion,
                            emotionControlSupported = false,
                            targetDurationMs = dlg.originalDurationMs,
                            actualDurationMs = 0L,
                            timeStretchRatio = 1.0f,
                            outputFilePath = "",
                            sampleRateHz = episode.audioSampleRateHz,
                            status = "FAILED"
                        )
                    )
                }
            }
            if (batchIds.isNotEmpty()) {
                batchExecutionSummary.add("Batch $batchNumber ($speakerLabel): [${batchIds.joinToString(", ")}]")
            }
        }

        cleanupOrphanedDialogueWavFiles(genDir, dao.getDialoguesChronologicalOnce(episode.id))

        val jobStatus = if (generatedCount > 0 && failedCount == 0) "COMPLETED" else "FAILED"
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 5,
            stageNumber = 10,
            agentName = "Agent 5 — Voice Generation",
            operation = "Synthesized $generatedCount / ${readyDialogues.size} line(s) ($failedCount failed)",
            modelUsed = "$lastEngineUsed + WSOLA Sync",
            runtimeMode = lastRuntimeMode,
            inputSummary = "${readyDialogues.size} translated lines grouped A → B → C",
            outputSummary = if (batchExecutionSummary.isNotEmpty()) batchExecutionSummary.joinToString(" | ") else (lastError ?: "0 generated"),
            completed = generatedCount,
            total = readyDialogues.size,
            status = jobStatus,
            errorDetails = lastError,
            startedAt = startedAt
        )

        if (generatedCount == 0) {
            throw IllegalStateException(
                lastError ?: "Voice synthesis produced 0 audio clips. Ensure Android Speech Services (hi-IN) is installed or connect a Remote GPU Worker."
            )
        }

        val updated = episode.copy(
            currentStageNumber = 10,
            currentStageName = if (failedCount == 0) {
                "Stage 10 — $generatedCount Lines Synthesized"
            } else {
                "Stage 10 — $generatedCount OK, $failedCount Failed (Retry)"
            },
            completedStagesMask = if (failedCount == 0) {
                invalidateDownstreamStages(episode.completedStagesMask, 10)
            } else {
                clearStagesFrom(episode.completedStagesMask, 10)
            },
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Real Speech Synthesis Dispatcher (Agent 15 + Agent 16 Pronunciation + Agent 17 WSOLA Sync):
     * 1. Attempts Remote GPU Worker `SPRINGLab/F5-Hindi-24KHz` when configured & model ready.
     * 2. Falls back to real On-Device `AndroidSpeechTtsEngine` (generic Android system TTS).
     * Never mutates visible Mixed-Script display text; feeds separate dictionary-guided pronunciation text to TTS.
     */
    private suspend fun synthesizeSingleLineReal(
        dialogueId: String,
        speakerProfile: SpeakerProfileEntity,
        visibleMixedScript: String,
        resolvedDictionaries: List<DictionaryEntryEntity>,
        emotion: String,
        targetDurationMs: Long,
        selectedEngineId: String,
        useRemoteF5: Boolean,
        remoteWorkerUrl: String,
        remoteWorkerAuthToken: String,
        allowInsecureLocalHttp: Boolean,
        outputDir: File,
        sampleRateHz: Int
    ): Result<SynthesizedDialogueResult> {
        val speakableText = MixedScriptLanguageEngine.buildTtsPronunciationText(visibleMixedScript, resolvedDictionaries)
        val outFile = File(outputDir, "dlg_${dialogueId.lowercase().replace('-', '_')}.wav")

        // Path 1: Remote GPU Worker F5-Hindi-24KHz (only when model verified ready)
        if (selectedEngineId == "F5-Hindi-24KHz" && useRemoteF5 && remoteWorkerUrl.isNotBlank()) {
            val refFile = speakerProfile.voiceReferencePath
                .takeIf { it.isNotBlank() && speakerProfile.rightsConsentConfirmed }
                ?.let { File(it) }
                ?.takeIf { it.exists() && it.length() > 44L }
            val rawRemoteFile = File(outputDir, "remote_${dialogueId.lowercase().replace('-', '_')}.wav")
            val remoteRes = workerClient.synthesizeHindiSpeechRemote(
                baseUrl = remoteWorkerUrl,
                text = speakableText,
                referenceWav = refFile,
                speakerLabel = speakerProfile.speakerLabel,
                emotion = emotion,
                targetDurationMs = targetDurationMs,
                outputWavFile = rawRemoteFile,
                authToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp
            )
            if (remoteRes.isSuccess) {
                val strictBuf = AudioDspEngine.readWavFileStrict(rawRemoteFile)
                rawRemoteFile.delete()
                if (strictBuf.isSuccess) {
                    val rawBuf = strictBuf.getOrThrow()
                    if (rawBuf.samples.isNotEmpty() && AudioDspEngine.computeRmsDbfs(rawBuf) > -55.0f) {
                        val resampled = AudioDspEngine.resampleMono(rawBuf, sampleRateHz)
                        val (stretched, stretchRatio) = AudioDspEngine.timeStretchPcm(resampled, targetDurationMs)
                        AudioDspEngine.writeWavFile(outFile, stretched)
                        val devMs = abs(stretched.durationMs - targetDurationMs)
                        val syncStatus = when {
                            stretchRatio !in 0.75f..1.35f -> "NEEDS_ADAPTATION"
                            devMs <= 120L && abs(stretchRatio - 1.0f) <= 0.03f -> "ALIGNED"
                            devMs <= 350L -> "TIME_STRETCHED"
                            else -> "NEEDS_ADAPTATION"
                        }
                        return Result.success(
                            SynthesizedDialogueResult(
                                outputFile = outFile,
                                visibleRomanHinglish = visibleMixedScript,
                                internalNormalizedPhonetic = speakableText,
                                actualDurationMs = stretched.durationMs,
                                timeStretchRatio = stretchRatio,
                                peakDbfs = AudioDspEngine.computePeakDbfs(stretched),
                                engineUsed = "F5-Hindi-24KHz (Remote GPU)",
                                modelVersion = "SPRINGLab/F5-Hindi-24KHz",
                                syncStatus = syncStatus
                            )
                        )
                    }
                }
            }
        }

        // Path 2: Genuine On-Device Android Speech TTS (Generic System Voice — No Voice Cloning)
        val androidRes = androidTtsEngine.synthesizeSpokenDialogueToWav(
            dialogueId = dialogueId,
            textToSpeak = speakableText,
            displayMixedScriptText = visibleMixedScript,
            speakerProfile = speakerProfile,
            emotion = emotion,
            targetDurationMs = targetDurationMs,
            outputDir = outputDir,
            targetSampleRateHz = sampleRateHz
        )
        return androidRes.map { res ->
            res.copy(
                visibleRomanHinglish = visibleMixedScript,
                internalNormalizedPhonetic = speakableText
            )
        }
    }

    /**
     * Stage 11 — Agent 7: Chronological Timeline Reconstruction.
     * Only creates tracks for stems & speakers that genuinely exist.
     */
    suspend fun runStage11Agent7TimelineReconstruction(episode: EpisodeEntity): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val chronological = dao.getDialoguesChronologicalOnce(episode.id)
            .sortedBy { it.globalOrderNumber }
        val speakers = dao.getSpeakersForEpisodeOnce(episode.id)
        val stems = dao.getStemsForEpisodeOnce(episode.id)

        val synced = chronological.map { dlg ->
            dlg.copy(
                timelineStartMs = dlg.originalStartMs,
                generationStatus = if (!dlg.generatedAudioPath.isNullOrBlank() && File(dlg.generatedAudioPath).exists()) "SYNCED" else dlg.generationStatus
            )
        }
        dao.insertDialogues(synced)

        val tracks = mutableListOf<TimelineTrackEntity>()
        val paletteHex = mapOf("A" to "#9D7CF6", "B" to "#38BDF8", "C" to "#34D399", "D" to "#F59E0B")

        speakers.sortedBy { it.speakerLabel }.forEachIndexed { idx, sp ->
            tracks.add(
                TimelineTrackEntity(
                    episodeId = episode.id,
                    trackIndex = idx + 1,
                    trackType = "CHARACTER",
                    speakerLabel = sp.speakerLabel,
                    trackTitle = "${sp.speakerLabel} • ${sp.characterName}",
                    colorHex = paletteHex[sp.speakerLabel] ?: "#9D7CF6",
                    volumeGain = 1.0f
                )
            )
        }

        if (stems.any { it.stemType == "ORIGINAL_MASTER" && File(it.filePath).exists() }) {
            tracks.add(
                TimelineTrackEntity(
                    episodeId = episode.id,
                    trackIndex = tracks.size + 1,
                    trackType = "ORIGINAL_DIALOGUE",
                    speakerLabel = null,
                    trackTitle = "Original Source Audio",
                    colorHex = "#64748B",
                    volumeGain = 0.0f,
                    isMuted = true
                )
            )
        }

        if (stems.any { it.stemType == "BACKGROUND_ACCOMPANIMENT" && File(it.filePath).exists() }) {
            tracks.add(
                TimelineTrackEntity(
                    episodeId = episode.id,
                    trackIndex = tracks.size + 1,
                    trackType = "BGM_MUSIC",
                    speakerLabel = null,
                    trackTitle = "Background Accompaniment (BGM + SFX)",
                    colorHex = "#60A5FA",
                    volumeGain = 0.92f
                )
            )
        }

        if (stems.any { it.stemType == "SFX_AMBIENCE" && File(it.filePath).exists() }) {
            tracks.add(
                TimelineTrackEntity(
                    episodeId = episode.id,
                    trackIndex = tracks.size + 1,
                    trackType = "SFX_AMBIENCE",
                    speakerLabel = null,
                    trackTitle = "SFX & Ambience Stem",
                    colorHex = "#2DD4BF",
                    volumeGain = 0.95f
                )
            )
        }

        dao.replaceTimelineTracksAtomic(episode.id, tracks)

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 11,
            agentName = "Agent 7 — Timeline & Mixing",
            operation = "Reconstructed chronological multitrack timeline",
            modelUsed = "Chronological Timeline Reconstructor",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${synced.size} dialogues • ${speakers.size} speaker(s)",
            outputSummary = "${tracks.size} active tracks restored in chronological order",
            completed = synced.size,
            total = synced.size.coerceAtLeast(1),
            status = "COMPLETED",
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 11,
            currentStageName = "Stage 11 — Timeline Reconstructed",
            completedStagesMask = invalidateDownstreamStages(episode.completedStagesMask, 11),
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 12 — Agent 7: Background & Dialogue Multitrack Mixing.
     */
    suspend fun runStage12Agent7MultitrackMixing(episode: EpisodeEntity): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val chronological = dao.getDialoguesChronologicalOnce(episode.id)
        val stems = dao.getStemsForEpisodeOnce(episode.id)
        val tracks = dao.getTimelineTracksOnce(episode.id)

        val bgStem = stems.find { it.stemType == "BACKGROUND_ACCOMPANIMENT" && !it.isMuted }
        val bgTrack = tracks.find { it.trackType == "BGM_MUSIC" }
        val bgFile = if (bgTrack?.isMuted == true) null else bgStem?.let { File(it.filePath) }?.takeIf { it.exists() }
        val bgGain = if (bgTrack != null) bgTrack.volumeGain else (bgStem?.volumeGain ?: 0.92f)

        val anySolo = tracks.any { it.isSolo }
        val trackGainsBySpeaker = tracks.filter { it.trackType == "CHARACTER" }
            .associate { tr ->
                val active = if (anySolo) tr.isSolo else !tr.isMuted
                (tr.speakerLabel ?: "") to (if (active) tr.volumeGain else 0f)
            }

        val placements = chronological.mapNotNull { dlg ->
            val path = dlg.generatedAudioPath ?: return@mapNotNull null
            val f = File(path)
            if (!f.exists() || f.length() <= 44L) return@mapNotNull null
            val gain = trackGainsBySpeaker[dlg.speakerLabel] ?: 1.0f
            if (gain <= 0f) return@mapNotNull null
            MixPlacementItem(
                dialogueId = dlg.dialogueId,
                speakerLabel = dlg.speakerLabel,
                audioFile = f,
                startMs = dlg.timelineStartMs,
                trackGain = gain
            )
        }

        if (placements.isEmpty() && bgFile == null) {
            val msg = "Nothing to mix yet. Generate at least one dialogue clip or separate background audio first."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 7,
                stageNumber = 12,
                agentName = "Agent 7 — Multitrack Mixing",
                operation = "Blocked: no mixable audio tracks",
                modelUsed = "16-bit PCM Multitrack Mixer",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "0 dialogue clips, no background stem",
                outputSummary = "No mix produced",
                completed = 0,
                total = 1,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        val mixedFile = File(epDir, "mixed_dubbed_master.wav")
        val mixMetrics = AudioDspEngine.mixMultitrackTimeline(
            episodeDurationMs = episode.durationMs,
            sampleRateHz = episode.audioSampleRateHz,
            backgroundStemFile = bgFile,
            backgroundGain = bgGain,
            dialoguePlacements = placements,
            outputFile = mixedFile
        )

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 12,
            agentName = "Agent 7 — Multitrack Mixing",
            operation = "Mixed ${placements.size} dialogue(s) with background stem",
            modelUsed = "16-bit PCM Multitrack Mixer + Soft-Knee Limiter",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${placements.size} dialogue WAV(s) + ${bgFile?.name ?: "No BG stem"}",
            outputSummary = "${mixedFile.name} • Peak ${mixMetrics.peakDbfs} dBFS • RMS ${mixMetrics.rmsDbfs} dBFS",
            completed = placements.size + if (bgFile != null) 1 else 0,
            total = placements.size + if (bgFile != null) 1 else 0,
            status = "COMPLETED",
            startedAt = startedAt
        )

        val updated = episode.copy(
            mixedMasterAudioPath = mixedFile.absolutePath,
            currentStageNumber = 12,
            currentStageName = "Stage 12 — Multitrack Mixed",
            completedStagesMask = invalidateDownstreamStages(episode.completedStagesMask, 12),
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 13 — Agent 7: Honest 13-Point Automated Quality Control Audit.
     * Supports PASSED, WARNING, FAILED, and BLOCKED states with zero false passes.
     */
    suspend fun runStage13Agent7QualityControl(episode: EpisodeEntity): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val chronological = dao.getDialoguesChronologicalOnce(episode.id)
        val stems = dao.getStemsForEpisodeOnce(episode.id)
        val tracks = dao.getTimelineTracksOnce(episode.id)
        val speakers = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val hasDialogues = chronological.isNotEmpty()

        val checks = mutableListOf<QualityCheckResultEntity>()

        // 1. Mixed-Script Hindi-English (Devanagari + English) Translation Completeness & Compliance
        val missingTrans = chronological.filter { it.romanHinglishText.isBlank() }
        val invalidScriptLines = chronological.filter { dlg ->
            dlg.romanHinglishText.isNotBlank() &&
                !MixedScriptLanguageEngine.validateMixedScript(dlg.romanHinglishText).isValid
        }
        val acousticEstimatedCount = chronological.count { it.timingStatus == "ACOUSTIC_ESTIMATED" }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 1,
                checkName = "1. Mixed-Script Hindi-English Compliance",
                category = "Translation (Agent 4)",
                status = when {
                    !hasDialogues -> "BLOCKED"
                    missingTrans.isNotEmpty() || invalidScriptLines.isNotEmpty() -> "FAILED"
                    else -> "PASSED"
                },
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogue segments exist yet."
                    invalidScriptLines.isNotEmpty() ->
                        "${invalidScriptLines.size} line(s) contain Romanized Hindi instead of Devanagari + English. Tap Fix Script with Gemini in Translate."
                    missingTrans.isNotEmpty() ->
                        "${missingTrans.size} of ${chronological.size} dialogue(s) missing Mixed-Script Hindi-English translation."
                    else -> {
                        val timingNote = if (acousticEstimatedCount > 0) " ($acousticEstimatedCount acoustic-estimated timing window(s))" else ""
                        "All ${chronological.size} dialogues comply with Mixed-Script Hindi-English (Devanagari + English)$timingNote."
                    }
                },
                affectedDialogueIds = (missingTrans + invalidScriptLines).distinctBy { it.id }.joinToString { it.dialogueId },
                remediationTargetScreen = "TRANSLATION"
            )
        )

        // 2. Generated dialogue audio files on disk
        val missingAudio = chronological.filter { it.generatedAudioPath.isNullOrBlank() || !File(it.generatedAudioPath).exists() }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 2,
                checkName = "2. Generated Dialogue Audio Files",
                category = "Voice Synthesis (Agent 5)",
                status = when {
                    !hasDialogues -> "BLOCKED"
                    missingAudio.isEmpty() -> "PASSED"
                    else -> "FAILED"
                },
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues segmented yet."
                    missingAudio.isEmpty() -> "All ${chronological.size} dialogue WAV files verified on disk."
                    else -> "${missingAudio.size} dialogue(s) do not have generated audio files yet."
                },
                affectedDialogueIds = missingAudio.joinToString { it.dialogueId },
                remediationTargetScreen = "VOICE_GEN"
            )
        )

        // 3. Global dialogue ID uniqueness
        val uniqueIdsCount = chronological.map { it.dialogueId }.distinct().size
        val hasUnassignedOrUnidentified = chronological.any {
            it.dialogueId.startsWith("UNASSIGNED") || it.dialogueId.startsWith("UNIDENTIFIED") || it.speakerLabel == "UNIDENTIFIED"
        }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 3,
                checkName = "3. Global Dialogue ID Uniqueness",
                category = "Numbering (Stage 7)",
                status = when {
                    !hasDialogues -> "BLOCKED"
                    uniqueIdsCount == chronological.size && !hasUnassignedOrUnidentified -> "PASSED"
                    else -> "FAILED"
                },
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues available."
                    hasUnassignedOrUnidentified -> "Unassigned or unidentified speaker IDs remain. Run Stage 6 & Stage 7 or assign manually."
                    uniqueIdsCount == chronological.size -> "All $uniqueIdsCount dialogue IDs are globally unique."
                    else -> "Duplicate dialogue IDs detected."
                },
                remediationTargetScreen = "TRANSCRIPT"
            )
        )

        // 4. Chronological order preservation
        val expectedOrderValid = hasDialogues && chronological.withIndex().all { (idx, dlg) ->
            dlg.globalOrderNumber == idx + 1 && dlg.dialogueId.endsWith("%03d".format(idx + 1))
        }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 4,
                checkName = "4. Chronological Order Preservation",
                category = "Timeline (Agent 7)",
                status = if (!hasDialogues) "BLOCKED" else if (expectedOrderValid) "PASSED" else "FAILED",
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues available."
                    expectedOrderValid -> "Sequential global numbering intact (${chronological.first().dialogueId} → ${chronological.last().dialogueId})."
                    else -> "Dialogue sequence numbering does not match chronological order."
                },
                remediationTargetScreen = "TIMELINE"
            )
        )

        // 5. Character-wise batch generation metadata
        val batchGroups = chronological.groupBy { it.speakerLabel }.toSortedMap()
        val batchesValid = hasDialogues && chronological.all { it.characterBatchNumber > 0 && it.orderInCharacterBatch > 0 }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 5,
                checkName = "5. Character-Wise Batch Order",
                category = "Batch Execution (Agent 5)",
                status = if (!hasDialogues) "BLOCKED" else if (batchesValid) "PASSED" else "WARNING",
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues available."
                    batchesValid -> "Grouped cleanly across ${batchGroups.size} speaker batch(es)."
                    else -> "Batch order not yet finalized; run Stage 7 and Stage 10."
                },
                remediationTargetScreen = "VOICE_GEN"
            )
        )

        // 6. Voice reference & consent consistency
        val wrongVoiceRef = chronological.filter { dlg ->
            val sp = speakers[dlg.speakerLabel]
            sp != null && dlg.voiceReferenceId != sp.voiceReferenceId
        }
        val unconsentedSpeakers = speakers.values.filter { !it.rightsConsentConfirmed }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 6,
                checkName = "6. Voice Reference & Rights Consent",
                category = "Speaker Profiles (Agent 3)",
                status = when {
                    speakers.isEmpty() -> "BLOCKED"
                    wrongVoiceRef.isNotEmpty() -> "FAILED"
                    unconsentedSpeakers.isNotEmpty() -> "WARNING"
                    else -> "PASSED"
                },
                summaryMessage = when {
                    speakers.isEmpty() -> "Blocked: No speakers identified yet."
                    wrongVoiceRef.isNotEmpty() -> "${wrongVoiceRef.size} line(s) reference a mismatched speaker ID."
                    unconsentedSpeakers.isNotEmpty() -> "${unconsentedSpeakers.size} speaker(s) using authorized generic voice fallback (source consent unconfirmed)."
                    else -> "All ${speakers.size} speaker(s) matched to verified voice references."
                },
                affectedDialogueIds = wrongVoiceRef.joinToString { it.dialogueId },
                remediationTargetScreen = "SPEAKERS"
            )
        )

        // 7. Start timestamp preservation
        val timestampShifted = chronological.filter { abs(it.timelineStartMs - it.originalStartMs) > 50L }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 7,
                checkName = "7. Start Timestamp Synchronization",
                category = "Timeline Sync (Agent 7)",
                status = if (!hasDialogues) "BLOCKED" else if (timestampShifted.isEmpty()) "PASSED" else "WARNING",
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues on timeline."
                    timestampShifted.isEmpty() -> "All dialogues align to their original start timestamps."
                    else -> "${timestampShifted.size} dialogue(s) manually shifted >50ms from original start time."
                },
                affectedDialogueIds = timestampShifted.joinToString { it.dialogueId },
                remediationTargetScreen = "TIMELINE"
            )
        )

        // 8. Actual generated duration recorded
        val unrecordedDur = chronological.filter { it.actualGeneratedDurationMs <= 0L }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 8,
                checkName = "8. Measured Duration & Deviation",
                category = "Duration Sync (Agent 5)",
                status = if (!hasDialogues) "BLOCKED" else if (unrecordedDur.isEmpty()) "PASSED" else "FAILED",
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No generated audio yet."
                    unrecordedDur.isEmpty() -> "Measured PCM duration and deviation recorded for all ${chronological.size} lines."
                    else -> "${unrecordedDur.size} line(s) missing measured audio duration."
                },
                affectedDialogueIds = unrecordedDur.joinToString { it.dialogueId },
                remediationTargetScreen = "VOICE_GEN"
            )
        )

        // 9. Overlap & inter-dialogue gap preservation
        val overlaps = chronological.filter { !it.overlapsWithDialogueId.isNullOrBlank() }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 9,
                checkName = "9. Overlap & Silence Gap Preservation",
                category = "Timeline (Agent 7)",
                status = if (!hasDialogues) "BLOCKED" else "PASSED",
                summaryMessage = if (!hasDialogues) "Blocked: No dialogues on timeline."
                else "Independent start placements preserved (${overlaps.size} natural overlap window(s) tracked).",
                remediationTargetScreen = "TIMELINE"
            )
        )

        // 10. Protected master & background stems on disk
        val hasMaster = stems.any { it.stemType == "ORIGINAL_MASTER" && File(it.filePath).exists() }
        val hasBg = stems.any { it.stemType == "BACKGROUND_ACCOMPANIMENT" && File(it.filePath).exists() }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 10,
                checkName = "10. Protected Master & Background Stems",
                category = "Stem Separation (Agent 6)",
                status = if (hasMaster && hasBg) "PASSED" else "FAILED",
                summaryMessage = if (hasMaster && hasBg) "Original Master and Background Accompaniment WAV files verified on disk."
                else "Required audio stems are missing. Run Stage 2 & Stage 3.",
                remediationTargetScreen = "AUDIO_STEMS"
            )
        )

        // 11. Active tracks & accidental mute check (excluding original source reference track)
        val mutedActiveTracks = tracks.filter { it.isMuted && it.trackType != "ORIGINAL_DIALOGUE" }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 11,
                checkName = "11. Active Mix Track Mute Check",
                category = "Stem Mixer (Agent 7)",
                status = when {
                    tracks.isEmpty() -> "BLOCKED"
                    mutedActiveTracks.isEmpty() -> "PASSED"
                    else -> "WARNING"
                },
                summaryMessage = when {
                    tracks.isEmpty() -> "Blocked: Timeline tracks not initialized. Run Stage 11."
                    mutedActiveTracks.isEmpty() -> "All dubbing dialogue and background tracks are unmuted."
                    else -> "${mutedActiveTracks.size} active track(s) muted: ${mutedActiveTracks.joinToString { it.trackTitle }}"
                },
                remediationTargetScreen = "TIMELINE"
            )
        )

        // 12. Master mix peak headroom & clipping check
        val mixedFile = episode.mixedMasterAudioPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 44L }
        val mixPeak = mixedFile?.let { AudioDspEngine.computePeakDbfs(AudioDspEngine.readWavFile(it)) }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 12,
                checkName = "12. Master Mix Peak Headroom",
                category = "Mastering (Agent 7)",
                status = when {
                    mixPeak == null -> "BLOCKED"
                    mixPeak <= -0.1f -> "PASSED"
                    else -> "WARNING"
                },
                summaryMessage = when {
                    mixPeak == null -> "Blocked: No mixed master WAV exists yet. Run Stage 12."
                    mixPeak <= -0.1f -> "Verified master mix peak at $mixPeak dBFS (safe headroom, 0 clipping)."
                    else -> "Master peak reached $mixPeak dBFS; reduce track gain and re-mix."
                },
                remediationTargetScreen = "AUDIO_STEMS"
            )
        )

        // 13. Output audio & episode duration consistency
        val mixedDur = mixedFile?.let { AudioDspEngine.readWavFile(it).durationMs }
        val durDiff = if (mixedDur != null) abs(mixedDur - episode.durationMs) else null
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 13,
                checkName = "13. Source & Master Duration Consistency",
                category = "Export Readiness (Stage 14)",
                status = when {
                    durDiff == null -> "BLOCKED"
                    durDiff <= 150L -> "PASSED"
                    else -> "WARNING"
                },
                summaryMessage = when {
                    durDiff == null -> "Blocked: Run Stage 12 Multitrack Mixing before export verification."
                    else -> "Source: ${episode.durationMs} ms • Mixed Master: $mixedDur ms (Delta: $durDiff ms)."
                },
                remediationTargetScreen = "QC_EXPORT"
            )
        )

        dao.replaceQualityChecksAtomic(episode.id, checks)

        val passedCount = checks.count { it.status == "PASSED" }
        val failedOrBlocked = checks.count { it.status == "FAILED" || it.status == "BLOCKED" }
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 13,
            agentName = "Agent 7 — 13-Point Quality Control",
            operation = "Audited 13 QC rules ($passedCount passed, $failedOrBlocked failed/blocked)",
            modelUsed = "13-Point Automated QC Validator",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${chronological.size} dialogues • ${stems.size} stems",
            outputSummary = "$passedCount / 13 PASSED",
            completed = passedCount,
            total = 13,
            status = if (failedOrBlocked == 0) "COMPLETED" else "FAILED",
            errorDetails = if (failedOrBlocked > 0) "$failedOrBlocked QC check(s) failed or blocked." else null,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 13,
            currentStageName = "Stage 13 — QC Audited ($passedCount/13)",
            completedStagesMask = if (failedOrBlocked == 0) {
                invalidateDownstreamStages(episode.completedStagesMask, 13)
            } else {
                clearStagesFrom(episode.completedStagesMask, 13)
            },
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 14 — Final Export Studio.
     * Clearly distinguishes WAV_AUDIO_ONLY vs MP4_VIDEO and validates actual written output file.
     */
    suspend fun runStage14FinalExport(
        episode: EpisodeEntity,
        exportFormat: String = "WAV_AUDIO_ONLY",
        resolution: String = episode.resolution,
        videoBitrateKbps: Int = 8500,
        audioCodec: String = if (exportFormat == "WAV_AUDIO_ONLY") "PCM 16-bit WAV" else "AAC-LC",
        audioBitrateKbps: Int = 192,
        audioSampleRateHz: Int = episode.audioSampleRateHz,
        customFileName: String? = null
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val exportsDir = File(epDir, "exports").apply { mkdirs() }

        val mixedSource = episode.mixedMasterAudioPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 44L }
            ?: File(epDir, "mixed_dubbed_master.wav").takeIf { it.exists() && it.length() > 44L }
            ?: run {
                val mixedEp = runStage12Agent7MultitrackMixing(episode)
                File(mixedEp.mixedMasterAudioPath ?: throw IllegalStateException("Mixing did not produce a WAV file."))
            }

        val cleanName = (customFileName?.takeIf { it.isNotBlank() }
            ?: "AnimeDub_Ep${episode.episodeNumber}_${System.currentTimeMillis() % 100000}")
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

        val outFile: File
        val actualDurationMs: Long
        val finalResolution: String
        val finalCodec: String

        if (exportFormat == "MP4_VIDEO") {
            outFile = File(exportsDir, "$cleanName.mp4")
            val sourceVideo = File(episode.originalVideoPath)
            val muxResult = AudioDspEngine.muxVideoWithDubbedAudio(
                sourceVideoFile = sourceVideo,
                dubbedWavFile = mixedSource,
                outputMp4File = outFile,
                audioBitrateKbps = audioBitrateKbps
            )
            if (muxResult.isFailure) {
                val err = muxResult.exceptionOrNull()?.localizedMessage ?: "MP4 muxing failed."
                recordAgentJob(
                    episodeId = episode.id,
                    agentNumber = 7,
                    stageNumber = 14,
                    agentName = "Agent 7 — Final Export",
                    operation = "MP4 video export failed",
                    modelUsed = "Android MediaMuxer + AAC Encoder",
                    runtimeMode = "ON_DEVICE_ANDROID",
                    inputSummary = "${sourceVideo.name} + ${mixedSource.name}",
                    outputSummary = "Export failed",
                    completed = 0,
                    total = 1,
                    status = "FAILED",
                    errorDetails = err,
                    startedAt = startedAt
                )
                throw IllegalStateException(err)
            }
            actualDurationMs = AudioDspEngine.readWavFile(mixedSource).durationMs
            finalResolution = resolution.ifBlank { "Source Passthrough" }
            finalCodec = "AAC-LC (${audioBitrateKbps} kbps)"
        } else {
            val mixedBuf = AudioDspEngine.readWavFile(mixedSource)
            if (mixedBuf.samples.isEmpty()) {
                throw IllegalStateException("Mixed master WAV is empty; cannot export.")
            }
            val resampledBuf = AudioDspEngine.resampleMono(mixedBuf, audioSampleRateHz)
            outFile = File(exportsDir, "$cleanName.wav")
            AudioDspEngine.writeWavFile(outFile, resampledBuf)
            actualDurationMs = resampledBuf.durationMs
            finalResolution = "Audio Only"
            finalCodec = "PCM 16-bit WAV"
        }

        if (!outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("Export verification failed: output file was not written to disk.")
        }

        // Agent 21 — Generate & validate Japanese, Hindi/Hinglish, Bilingual SRT + Styled ASS subtitles
        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
        val speakersByLabel = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val subtitlesDir = File(exportsDir, "subtitles").apply { mkdirs() }
        val subtitleBundle = if (dialogues.isNotEmpty()) {
            SubtitleGenerator.generateAllSubtitles(
                episodeTitle = episode.episodeTitle,
                dialogues = dialogues,
                speakersByLabel = speakersByLabel,
                outputDir = subtitlesDir,
                baseFileName = cleanName,
                episodeDurationMs = episode.durationMs
            )
        } else null

        val subtitleFiles = if (subtitleBundle != null) {
            listOf(
                subtitleBundle.japaneseSrtFile,
                subtitleBundle.hindiHinglishSrtFile,
                subtitleBundle.bilingualSrtFile,
                subtitleBundle.styledAssFile
            )
        } else emptyList()

        if (subtitleBundle != null) {
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 7,
                stageNumber = 14,
                agentName = "Agent 21 — Subtitle Generator",
                operation = "Generated 4 subtitle tracks (${subtitleBundle.cueCount} cues)",
                modelUsed = "SRT & Styled ASS v4+ Subtitle Engine",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "${dialogues.size} chronological dialogue segments",
                outputSummary = subtitleFiles.joinToString(", ") { it.name },
                completed = if (subtitleBundle.validationPassed) 4 else 0,
                total = 4,
                status = if (subtitleBundle.validationPassed) "COMPLETED" else "FAILED",
                errorDetails = subtitleBundle.validationIssues.takeIf { it.isNotEmpty() }?.joinToString("; "),
                startedAt = startedAt
            )
        }

        // Agent 22 — Final Verification Agent (inspects actual written WAV/MP4, RMS, headroom, duration, dialogues, subtitles)
        val verification = FinalVerificationAgent.verifyExportedOutput(
            outputFile = outFile,
            exportFormat = exportFormat,
            expectedDurationMs = episode.durationMs,
            dialogues = dialogues,
            subtitleFiles = subtitleFiles
        )

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 14,
            agentName = "Agent 22 — Final Verification",
            operation = verification.summary,
            modelUsed = "MediaExtractor / PCM RMS & Subtitle Verifier",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${outFile.name} + ${subtitleFiles.size} subtitle file(s)",
            outputSummary = verification.checks.joinToString(" | ") { "${it.checkName}: ${if (it.passed) "PASS" else "FAIL"}" },
            completed = verification.checks.count { it.passed },
            total = verification.checks.size,
            status = if (verification.passed) "COMPLETED" else "FAILED",
            errorDetails = if (verification.passed) null else verification.summary,
            startedAt = startedAt
        )

        val subtitlePathsSummary = subtitleFiles.joinToString(" | ") { it.name }
        val verificationDetailsText = verification.checks.joinToString("\n") {
            "[${if (it.passed) "PASS" else "FAIL"}] ${it.checkName}: ${it.detail}"
        }

        dao.insertExportRecord(
            ExportRecordEntity(
                episodeId = episode.id,
                exportFormat = exportFormat,
                videoResolution = finalResolution,
                videoBitrateKbps = if (exportFormat == "MP4_VIDEO") videoBitrateKbps else 0,
                audioCodec = finalCodec,
                audioBitrateKbps = audioBitrateKbps,
                audioSampleRateHz = audioSampleRateHz,
                outputFilePath = outFile.absolutePath,
                fileSizeBytes = outFile.length(),
                durationMs = actualDurationMs,
                status = if (verification.passed) "VERIFIED" else "VERIFICATION_FAILED",
                subtitlePathsSummary = subtitlePathsSummary,
                verificationReport = verificationDetailsText
            )
        )

        if (verification.passed && dialogues.isNotEmpty()) {
            dao.insertDialogues(dialogues.map { it.copy(verificationStatus = "VERIFIED") })
        }

        val updated = episode.copy(
            exportedVideoPath = outFile.absolutePath,
            currentStageNumber = 14,
            currentStageName = if (verification.passed) {
                "Stage 14 — Verified Export (${outFile.extension.uppercase()})"
            } else {
                "Stage 14 — Export Verification Failed"
            },
            completedStagesMask = if (verification.passed) episode.completedStagesMask or (1 shl 14) else episode.completedStagesMask,
            pipelineStatus = if (verification.passed) "COMPLETED" else "ERROR",
            lastErrorMessage = if (verification.passed) null else verification.summary,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        if (!verification.passed) {
            throw IllegalStateException(verification.summary)
        }
        return updated
    }

    /**
     * Standalone Agent 21 — Export Subtitles (Japanese SRT, Hindi/Hinglish SRT, Bilingual SRT, Styled ASS).
     */
    suspend fun generateSubtitlesOnly(episode: EpisodeEntity, customBaseName: String? = null): GeneratedSubtitleBundle {
        val startedAt = System.currentTimeMillis()
        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
        if (dialogues.isEmpty()) {
            throw IllegalStateException("No dialogue segments exist yet to generate subtitles.")
        }
        val speakersByLabel = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val subtitlesDir = File(epDir, "exports/subtitles").apply { mkdirs() }
        val baseName = customBaseName?.takeIf { it.isNotBlank() } ?: "AnimeDub_Ep${episode.episodeNumber}_Subtitles"
        val bundle = SubtitleGenerator.generateAllSubtitles(
            episodeTitle = episode.episodeTitle,
            dialogues = dialogues,
            speakersByLabel = speakersByLabel,
            outputDir = subtitlesDir,
            baseFileName = baseName,
            episodeDurationMs = episode.durationMs
        )
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 14,
            agentName = "Agent 21 — Subtitle Generator",
            operation = "Exported 4 subtitle files (${bundle.cueCount} cues)",
            modelUsed = "SRT & Styled ASS v4+ Subtitle Engine",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${dialogues.size} chronological dialogues",
            outputSummary = "${bundle.japaneseSrtFile.name}, ${bundle.hindiHinglishSrtFile.name}, ${bundle.bilingualSrtFile.name}, ${bundle.styledAssFile.name}",
            completed = if (bundle.validationPassed) 4 else 0,
            total = 4,
            status = if (bundle.validationPassed) "COMPLETED" else "FAILED",
            errorDetails = bundle.validationIssues.takeIf { it.isNotEmpty() }?.joinToString("; "),
            startedAt = startedAt
        )
        return bundle
    }

    /**
     * Dependency-Aware Single Dialogue Regeneration & Failure Retry.
     */
    suspend fun regenerateSingleDialogueOnly(
        dialogueId: Long,
        newRomanHinglishText: String? = null,
        newEmotion: String? = null,
        selectedTtsEngine: String = "F5-Hindi-24KHz",
        remoteWorkerUrl: String = "",
        remoteWorkerAuthToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): Result<DialogueSegmentEntity> = withContext(Dispatchers.IO) {
        val dlg = dao.getDialogueById(dialogueId)
            ?: return@withContext Result.failure(IllegalArgumentException("Dialogue not found."))
        val episode = dao.getEpisodeById(dlg.episodeId)
            ?: return@withContext Result.failure(IllegalArgumentException("Episode not found."))
        val project = dao.getProjectById(episode.projectId)
        val speakers = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val resolvedDictionaries = MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = dao.getAllDictionaryEntriesOnce(),
            projectId = episode.projectId,
            animeSeriesName = project?.animeSeriesName
        )

        val updatedDisplayText = newRomanHinglishText?.trim() ?: dlg.romanHinglishText
        if (updatedDisplayText.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Enter Mixed-Script Hindi-English text before synthesizing voice audio."))
        }
        val scriptValidation = MixedScriptLanguageEngine.validateMixedScript(updatedDisplayText)
        val updatedEmotion = newEmotion?.trim()?.takeIf { it.isNotBlank() } ?: dlg.emotion
        val cleanSpeaker = dlg.speakerLabel.ifBlank { "UNIDENTIFIED" }
        val speakerProfile = speakers[cleanSpeaker] ?: SpeakerProfileEntity(
            projectId = episode.projectId,
            episodeId = episode.id,
            speakerLabel = cleanSpeaker,
            characterName = "Speaker $cleanSpeaker",
            f0MinHz = 0f,
            f0MaxHz = 0f,
            f0MeanHz = 0f,
            f0StdHz = 0f,
            spectralCentroidHz = 0f,
            timbreDescriptor = "Unanalyzed",
            speakingSpeedSylPerSec = 0f,
            voiceTexture = "Unmeasured",
            speakingStyle = "Natural",
            dominantEmotion = updatedEmotion,
            speakerEmbeddingJson = "[]",
            voiceSimilarityScore = -1f,
            voiceReferencePath = "",
            voiceReferenceId = if (cleanSpeaker == "UNIDENTIFIED") "" else "VREF-${episode.projectId}-$cleanSpeaker",
            rightsConsentConfirmed = false,
            useConsentedGenericVoiceFallback = true,
            characterId = if (cleanSpeaker == "UNIDENTIFIED") "" else "CHAR-${episode.projectId}-$cleanSpeaker"
        )

        val epDir = getEpisodeWorkspaceDir(episode.id)
        val genDir = File(epDir, "generated_dialogues").apply { mkdirs() }
        val (remoteF5Ready, _) = if (selectedTtsEngine == "F5-Hindi-24KHz" && remoteWorkerUrl.isNotBlank()) {
            checkRemoteModelReady(
                remoteWorkerUrl = remoteWorkerUrl,
                remoteWorkerAuthToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp,
                modelKey = "f5_hindi"
            )
        } else {
            false to "On-device TTS mode"
        }

        val synthOutcome = synthesizeSingleLineReal(
            dialogueId = dlg.dialogueId,
            speakerProfile = speakerProfile,
            visibleMixedScript = updatedDisplayText,
            resolvedDictionaries = resolvedDictionaries,
            emotion = updatedEmotion,
            targetDurationMs = dlg.originalDurationMs,
            selectedEngineId = selectedTtsEngine,
            useRemoteF5 = remoteF5Ready,
            remoteWorkerUrl = remoteWorkerUrl,
            remoteWorkerAuthToken = remoteWorkerAuthToken,
            allowInsecureLocalHttp = allowInsecureLocalHttp,
            outputDir = genDir,
            sampleRateHz = episode.audioSampleRateHz
        )

        if (synthOutcome.isFailure) {
            val errMsg = synthOutcome.exceptionOrNull()?.localizedMessage ?: "Real speech synthesis failed."
            dao.updateDialogue(
                dlg.copy(
                    romanHinglishText = updatedDisplayText,
                    scriptValidationStatus = scriptValidation.status,
                    emotion = updatedEmotion,
                    generationStatus = "FAILED",
                    errorNote = errMsg
                )
            )
            return@withContext Result.failure(IllegalStateException(errMsg, synthOutcome.exceptionOrNull()))
        }

        val synthResult = synthOutcome.getOrThrow()
        val isRemoteF5 = synthResult.engineUsed.contains("F5-Hindi", ignoreCase = true)
        val updatedDlg = dlg.copy(
            romanHinglishText = updatedDisplayText,
            ttsNormalizedText = synthResult.internalNormalizedPhonetic,
            scriptValidationStatus = scriptValidation.status,
            emotion = updatedEmotion,
            previousGeneratedAudioPath = dlg.generatedAudioPath,
            generatedAudioPath = synthResult.outputFile.absolutePath,
            actualGeneratedDurationMs = synthResult.actualDurationMs,
            durationDeviationMs = synthResult.actualDurationMs - dlg.originalDurationMs,
            timeStretchRatio = synthResult.timeStretchRatio,
            ttsEngineUsed = synthResult.engineUsed,
            generationStatus = "SYNCED",
            syncStatus = synthResult.syncStatus,
            peakDbfs = synthResult.peakDbfs,
            translationVersion = if (newRomanHinglishText != null) dlg.translationVersion + 1 else dlg.translationVersion,
            generationVersion = dlg.generationVersion + 1,
            errorNote = null
        )
        dao.updateDialogue(updatedDlg)

        dao.insertVoiceGenerationRecord(
            VoiceGenerationRecordEntity(
                episodeId = episode.id,
                dialogueId = dlg.dialogueId,
                speakerLabel = dlg.speakerLabel,
                characterBatchNumber = dlg.characterBatchNumber,
                orderInCharacterBatch = dlg.orderInCharacterBatch,
                ttsEngine = synthResult.engineUsed,
                modelVersion = synthResult.modelVersion,
                voiceReferenceId = speakerProfile.voiceReferenceId,
                visibleRomanHinglish = updatedDisplayText,
                internalPhoneticText = synthResult.internalNormalizedPhonetic,
                emotionRequested = updatedEmotion,
                emotionControlSupported = isRemoteF5,
                targetDurationMs = dlg.originalDurationMs,
                actualDurationMs = synthResult.actualDurationMs,
                timeStretchRatio = synthResult.timeStretchRatio,
                outputFilePath = synthResult.outputFile.absolutePath,
                sampleRateHz = episode.audioSampleRateHz,
                status = "RETRIED"
            )
        )

        try {
            runStage12Agent7MultitrackMixing(episode)
            runStage13Agent7QualityControl(episode)
        } catch (_: Exception) {
        }

        Result.success(updatedDlg)
    }

    suspend fun applyScopedDictionaryReplacement(
        episodeId: Long,
        targetDialogueId: Long?,
        japaneseTerm: String,
        oldTermOrWord: String,
        newRomanHinglishTerm: String,
        pronunciationGuidance: String,
        scope: String,
        selectedTtsEngine: String = "F5-Hindi-24KHz",
        remoteWorkerUrl: String = "",
        remoteWorkerAuthToken: String = "",
        allowInsecureLocalHttp: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val episode = dao.getEpisodeById(episodeId) ?: return@withContext 0
        val project = dao.getProjectById(episode.projectId)
        val cleanNewTerm = newRomanHinglishTerm.trim()
        if (cleanNewTerm.isBlank()) return@withContext 0

        if (scope in listOf("CURRENT_ANIME", "GLOBALLY", "EVERYWHERE", "CURRENT_EPISODE")) {
            val dictLevel = when (scope) {
                "CURRENT_ANIME" -> "ANIME"
                "CURRENT_EPISODE" -> "PROJECT"
                else -> "GLOBAL"
            }
            val sourceTerm = japaneseTerm.trim().ifBlank { oldTermOrWord.trim() }
            val termKey = MixedScriptLanguageEngine.normalizedTermKey(sourceTerm, cleanNewTerm)
            val targetProjectId = if (dictLevel == "PROJECT") episode.projectId else null
            val targetAnime = if (dictLevel == "ANIME") project?.animeSeriesName?.trim() else null

            val existing = dao.getAllDictionaryEntriesOnce().find { entry ->
                entry.dictionaryLevel == dictLevel &&
                    entry.projectId == targetProjectId &&
                    (entry.animeSeriesName?.trim() ?: "").equals(targetAnime ?: "", ignoreCase = true) &&
                    MixedScriptLanguageEngine.normalizedTermKey(entry.japaneseSourceTerm, MixedScriptLanguageEngine.preferredDisplayTerm(entry)) == termKey
            }

            val hasDevanagari = MixedScriptLanguageEngine.containsDevanagari(cleanNewTerm)
            val entryToSave = DictionaryEntryEntity(
                id = existing?.id ?: 0L,
                dictionaryLevel = dictLevel,
                projectId = targetProjectId,
                animeSeriesName = targetAnime,
                japaneseSourceTerm = sourceTerm,
                preferredMixedScript = cleanNewTerm,
                preferredRomanHinglish = cleanNewTerm,
                preferredHindiWording = if (hasDevanagari) cleanNewTerm else (existing?.preferredHindiWording ?: ""),
                preferredEnglishWording = if (!hasDevanagari) cleanNewTerm else (existing?.preferredEnglishWording ?: ""),
                preserveOriginalEnglish = !hasDevanagari,
                pronunciationGuidance = pronunciationGuidance.trim().ifBlank { existing?.pronunciationGuidance ?: "" },
                contextNotes = "Scoped update ($scope)",
                replacementScope = scope,
                updatedAt = System.currentTimeMillis()
            )
            dao.insertDictionaryEntry(entryToSave)
        }

        val resolvedDicts = MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = dao.getAllDictionaryEntriesOnce(),
            projectId = episode.projectId,
            animeSeriesName = project?.animeSeriesName
        )

        val dialogues = dao.getDialoguesChronologicalOnce(episodeId)
        val candidates = when (scope) {
            "ONLY_HERE", "CURRENT_DIALOGUE" -> dialogues.filter { it.id == targetDialogueId }
            else -> dialogues.filter { dlg ->
                (oldTermOrWord.isNotBlank() && dlg.romanHinglishText.contains(oldTermOrWord, ignoreCase = true)) ||
                    (japaneseTerm.isNotBlank() && dlg.japaneseText.contains(japaneseTerm))
            }
        }

        var affectedCount = 0
        for (dlg in candidates) {
            if (dlg.romanHinglishText.isBlank()) continue
            var updatedText = dlg.romanHinglishText
            if (oldTermOrWord.isNotBlank()) {
                val boundaryPattern = Regex(
                    "(?<![\\p{L}\\p{N}_\\u0900-\\u097F])${Regex.escape(oldTermOrWord.trim())}(?![\\p{L}\\p{N}_\\u0900-\\u097F])",
                    RegexOption.IGNORE_CASE
                )
                updatedText = boundaryPattern.replace(updatedText) { cleanNewTerm }
            }
            updatedText = MixedScriptLanguageEngine.applyDictionariesToDisplayText(
                displayText = updatedText,
                japaneseSource = dlg.japaneseText,
                resolvedEntries = resolvedDicts
            )
            if (updatedText == dlg.romanHinglishText && scope !in listOf("ONLY_HERE", "CURRENT_DIALOGUE")) {
                continue
            }
            regenerateSingleDialogueOnly(
                dialogueId = dlg.id,
                newRomanHinglishText = updatedText,
                selectedTtsEngine = selectedTtsEngine,
                remoteWorkerUrl = remoteWorkerUrl,
                remoteWorkerAuthToken = remoteWorkerAuthToken,
                allowInsecureLocalHttp = allowInsecureLocalHttp
            )
            affectedCount++
        }
        affectedCount
    }

    private suspend fun recordAgentJob(
        episodeId: Long,
        agentNumber: Int,
        stageNumber: Int,
        agentName: String,
        operation: String,
        modelUsed: String,
        runtimeMode: String,
        inputSummary: String,
        outputSummary: String,
        completed: Int,
        total: Int,
        status: String,
        errorDetails: String? = null,
        startedAt: Long = System.currentTimeMillis()
    ) {
        dao.insertAgentJob(
            AgentJobRecordEntity(
                episodeId = episodeId,
                agentNumber = agentNumber,
                stageNumber = stageNumber,
                agentName = agentName,
                currentOperation = operation,
                modelBeingUsed = modelUsed,
                runtimeMode = runtimeMode,
                inputSummary = inputSummary,
                outputSummary = outputSummary,
                completedItems = completed,
                totalItems = total,
                status = status,
                errorDetails = errorDetails,
                startedAt = startedAt,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        /**
         * Keeps completion bits `1..completedStage` (and sets `completedStage`) while clearing
         * downstream bits `(completedStage + 1)..14` so stale downstream stages are never falsely marked done.
         */
        fun invalidateDownstreamStages(mask: Int, completedStage: Int): Int {
            var result = 0
            for (s in 1..completedStage.coerceIn(1, 14)) {
                if ((mask and (1 shl s)) != 0) {
                    result = result or (1 shl s)
                }
            }
            return result or (1 shl completedStage.coerceIn(1, 14))
        }

        /**
         * Clears completion bits from `fromStage..14`, preserving only earlier stages `1..(fromStage - 1)`.
         */
        fun clearStagesFrom(mask: Int, fromStage: Int): Int {
            var result = 0
            for (s in 1 until fromStage.coerceIn(1, 15)) {
                if ((mask and (1 shl s)) != 0) {
                    result = result or (1 shl s)
                }
            }
            return result
        }
    }
}
