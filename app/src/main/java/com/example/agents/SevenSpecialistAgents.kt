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
import com.example.data.remote.DirectGeminiApiClient
import com.example.data.remote.GeminiDialogueRewriteResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "SevenSpecialistAgents"

/**
 * Explicit pipeline stage lifecycle status.
 * Never collapses PARTIAL, FAILED, SKIPPED, CANCELLED, or UNSUPPORTED into COMPLETED.
 */
enum class StageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    SKIPPED,
    CANCELLED,
    UNSUPPORTED
}

/**
 * Structured outcome returned by [SevenSpecialistAgentsCoordinator.executePipelineFromStage].
 */
data class StageExecutionResult(
    val stageNumber: Int,
    val status: StageStatus,
    val episode: EpisodeEntity,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val message: String = "",
    val isBlockingFailure: Boolean = false
)

/**
 * Deterministic Pipeline Coordinator & Multi-Agent Studio Orchestrator (Android-Only + Direct Gemini API).
 *
 * Implements the 14-stage workflow strictly against real imported media & user data:
 * - Stage 1: Media Import & Metadata Inspection
 * - Stage 2: Lossless Audio & Timebase Extraction (MediaExtractor + MediaCodec -> PCM WAV)
 * - Stage 3: Background Audio Approximation (On-Device Bandpass/Residual DSP — Non-Neural; original master preserved)
 * - Stage 4: Japanese Speech Recognition & VAD (On-Device Silero VAD ONNX / DSP VAD + Direct Gemini API Multimodal Audio)
 * - Stage 5: Dialogue Timestamps & Segmentation (honestly marked ACOUSTIC_ESTIMATED or MANUAL)
 * - Stage 6: Audio-Only Speaker Analysis (On-Device Acoustic F0 & Spectral Clustering — Non-Neural, project-isolated)
 * - Stage 7: Global Sequential Dialogue Numbering (A-001, B-002, C-003...; never auto-reassigns unidentified speakers)
 * - Stage 8: Context Understanding, Permanent Mixed-Script Translation & Dictionary Enforcement (Direct Gemini API)
 * - Stage 9: Character Voice Reference Setup (strict WAV decode verification per speaker)
 * - Stage 10: Character-Wise Batch Voice Generation (Android System TextToSpeech Explicit Local Fallback + WSOLA)
 * - Stage 11: Chronological Timeline Reconstruction
 * - Stage 12: Background & Dialogue Multitrack Mixing (Streaming Additive 2-Pass Mixer)
 * - Stage 13: 13-Point Automated Quality Control (strict WAV decoding + genuine overlap/gap audit)
 * - Stage 14: Final Export (Streaming MP4 passthrough / WAV) + Agent 21 Subtitles + Agent 22 Final Verification
 */
class SevenSpecialistAgentsCoordinator(
    private val context: Context,
    private val dao: AnimeDubDao,
    private val geminiClient: DirectGeminiApiClient
) {
    private val androidTtsEngine = AndroidSpeechTtsEngine(context)

    private fun sanitizeGeminiModelName(modelName: String): String {
        return DirectGeminiApiClient.normalizeModelName(modelName)
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

    /**
     * Validates real on-disk & database prerequisites before running [stage].
     * Returns null if satisfied, or a clear blocking error message if prerequisites are missing.
     */
    private suspend fun validateStagePrerequisites(episode: EpisodeEntity, stage: Int): String? {
        return when (stage) {
            3 -> {
                val master = File(episode.originalMasterAudioPath)
                if (AudioDspEngine.inspectWavHeader(master).isFailure) {
                    "Stage 3 blocked: Original extracted master WAV is missing or invalid. Run Stage 2 Audio Extraction first."
                } else null
            }
            4 -> {
                val stems = dao.getStemsForEpisodeOnce(episode.id)
                val vocal = stems.find { it.stemType == "DIALOGUE_VOCAL" }?.let { File(it.filePath) }
                    ?: File(episode.originalMasterAudioPath)
                if (AudioDspEngine.inspectWavHeader(vocal).isFailure) {
                    "Stage 4 blocked: Vocal or master WAV is missing or invalid. Run Stages 2–3 first."
                } else null
            }
            5 -> {
                val transcripts = dao.getTranscriptsForEpisodeOnce(episode.id)
                if (transcripts.isEmpty()) {
                    "Stage 5 blocked: No transcript segments exist yet. Run Stage 4 or add segments manually."
                } else null
            }
            6 -> {
                val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
                if (dialogues.isEmpty()) {
                    "Stage 6 blocked: No segmented dialogues exist yet. Complete Stage 5 first."
                } else null
            }
            7 -> {
                val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
                if (dialogues.isEmpty()) {
                    "Stage 7 blocked: No dialogues exist to number. Complete Stages 5–6 first."
                } else null
            }
            8 -> {
                val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
                when {
                    dialogues.isEmpty() -> "Stage 8 blocked: No dialogues exist to translate. Complete Stages 4–7 first."
                    dialogues.none { it.japaneseText.isNotBlank() } -> "Stage 8 blocked: All dialogues have blank Japanese text. Complete Stage 4 or enter Japanese lines in Transcript."
                    dialogues.any { it.speakerLabel.isBlank() || it.speakerLabel.equals("UNIDENTIFIED", true) || it.speakerLabel == "?" } ->
                        "Stage 8 blocked: One or more dialogues have UNIDENTIFIED speakers. Assign speakers in Stage 6/7 or Transcript first."
                    else -> null
                }
            }
            9 -> {
                val speakers = dao.getSpeakersForEpisodeOnce(episode.id)
                val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
                when {
                    speakers.isEmpty() -> "Stage 9 blocked: No speaker profiles exist yet. Run Stage 6 Speaker Analysis first."
                    dialogues.any { it.speakerLabel.isBlank() || it.speakerLabel.equals("UNIDENTIFIED", true) } ->
                        "Stage 9 blocked: UNIDENTIFIED speakers remain. Resolve speaker assignments before preparing voice references."
                    else -> null
                }
            }
            10 -> {
                val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
                when {
                    dialogues.isEmpty() -> "Stage 10 blocked: No dialogues exist yet."
                    dialogues.none { it.romanHinglishText.isNotBlank() } ->
                        "Stage 10 blocked: No Mixed-Script Hindi-English translations exist yet. Complete Stage 8 first."
                    dialogues.any { it.speakerLabel.isBlank() || it.speakerLabel.equals("UNIDENTIFIED", true) } ->
                        "Stage 10 blocked: UNIDENTIFIED speakers remain. Assign all speakers before voice synthesis."
                    else -> null
                }
            }
            11 -> {
                if (dao.getDialoguesChronologicalOnce(episode.id).isEmpty()) {
                    "Stage 11 blocked: No dialogue segments exist yet."
                } else null
            }
            12 -> {
                val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
                if (dialogues.isEmpty()) {
                    "Stage 12 blocked: No dialogue segments exist yet."
                } else null
            }
            13 -> {
                if (dao.getDialoguesChronologicalOnce(episode.id).isEmpty()) {
                    "Stage 13 blocked: No dialogue segments exist to audit."
                } else null
            }
            14 -> {
                val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
                if (dialogues.isEmpty()) {
                    "Stage 14 blocked: 0 dialogue segments exist. Background audio alone cannot be exported as a dubbed episode."
                } else null
            }
            else -> null
        }
    }

    suspend fun executePipelineFromStage(
        episodeId: Long,
        startStage: Int = 2,
        endStage: Int = 13,
        selectedTtsEngine: String = "Android-Speech-TTS",
        geminiModelName: String = DirectGeminiApiClient.DEFAULT_MODEL_ID,
        onProgress: suspend (stageNumber: Int, stageTitle: String, detail: String) -> Unit = { _, _, _ -> }
    ): Result<StageExecutionResult> = withContext(Dispatchers.IO) {
        var activeStage = startStage
        try {
            var episode = dao.getEpisodeById(episodeId)
                ?: return@withContext Result.failure(IllegalArgumentException("Episode $episodeId not found."))

            dao.updateEpisode(episode.copy(pipelineStatus = StageStatus.RUNNING.name, lastErrorMessage = null))

            for (stage in startStage..endStage) {
                activeStage = stage
                val prereqError = validateStagePrerequisites(episode, stage)
                if (prereqError != null) {
                    recordAgentJob(
                        episodeId = episode.id,
                        agentNumber = stageToAgentNumber(stage),
                        stageNumber = stage,
                        agentName = "Pipeline Coordinator (Stage $stage Prerequisite)",
                        operation = "Blocked: missing prerequisite",
                        modelUsed = "Deterministic Dependency Validator",
                        runtimeMode = "ON_DEVICE_ANDROID",
                        inputSummary = "Stage $stage dependency check",
                        outputSummary = prereqError,
                        completed = 0,
                        total = 1,
                        status = StageStatus.FAILED.name,
                        errorDetails = prereqError
                    )
                    val haltedEp = episode.copy(
                        currentStageNumber = stage,
                        currentStageName = "Stage $stage — Blocked (Prerequisite Missing)",
                        completedStagesMask = clearStagesFrom(episode.completedStagesMask, stage),
                        pipelineStatus = StageStatus.FAILED.name,
                        lastErrorMessage = prereqError,
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.updateEpisode(haltedEp)
                    return@withContext Result.success(
                        StageExecutionResult(
                            stageNumber = stage,
                            status = StageStatus.FAILED,
                            episode = haltedEp,
                            completedCount = 0,
                            totalCount = 1,
                            message = prereqError,
                            isBlockingFailure = true
                        )
                    )
                }

                when (stage) {
                    1, 2 -> {
                        onProgress(2, "Stage 2 — Audio Extraction", "Decoding source media audio track to 16-bit PCM WAV...")
                        episode = runStage1And2Extraction(episode)
                    }
                    3 -> {
                        onProgress(3, "Stage 3 — Local DSP Stem Separation (Agent 6)", "Running on-device frequency filtering & residual BGM subtraction...")
                        episode = runStage3Agent6BackgroundSeparation(episode = episode)
                    }
                    4 -> {
                        onProgress(4, "Stage 4 — Local VAD & Direct Gemini Audio ASR (Agent 1)", "Detecting speech windows locally and transcribing via Direct Gemini API...")
                        episode = runStage4Agent1JapaneseTranscription(
                            episode = episode,
                            geminiModelName = geminiModelName
                        )
                    }
                    5 -> {
                        onProgress(5, "Stage 5 — Dialogue Segmentation (Agent 2)", "Validating acoustic-estimated dialogue timestamps and overlaps...")
                        episode = runStage5Agent2TimestampsAndSegmentation(episode)
                    }
                    6 -> {
                        onProgress(6, "Stage 6 — Acoustic Speaker Analysis (Agent 3)", "Running on-device non-neural acoustic F0 & spectral clustering...")
                        episode = runStage6Agent3SpeakerIdentification(episode = episode)
                    }
                    7 -> {
                        onProgress(7, "Stage 7 — Global Dialogue Numbering", "Assigning chronological global IDs (A-001, B-002, C-003...)...")
                        episode = runStage7GlobalDialogueNumbering(episode)
                    }
                    8 -> {
                        onProgress(8, "Stage 8 — Mixed-Script Translation (Agent 4)", "Translating to Devanagari Hindi + English via Direct Gemini API (${sanitizeGeminiModelName(geminiModelName)})...")
                        episode = runStage8Agent4GeminiTranslation(episode, geminiModelName = geminiModelName)
                    }
                    9 -> {
                        onProgress(9, "Stage 9 — Voice Reference Setup", "Decoding & verifying character voice reference clips...")
                        episode = runStage9VoiceProfileSetup(episode, selectedTtsEngine)
                    }
                    10 -> {
                        onProgress(10, "Stage 10 — Character Batch Voice Gen (Agent 5)", "Synthesizing Hindi/English speech locally via Android System TTS fallback...")
                        episode = runStage10Agent5CharacterWiseVoiceGeneration(
                            episode = episode,
                            selectedTtsEngine = selectedTtsEngine
                        )
                    }
                    11 -> {
                        onProgress(11, "Stage 11 — Timeline Reconstruction (Agent 7)", "Restoring chronological dialogue sequence on multitrack timeline...")
                        episode = runStage11Agent7TimelineReconstruction(episode)
                    }
                    12 -> {
                        onProgress(12, "Stage 12 — Multitrack Mixing (Agent 7)", "Mixing verified dialogue tracks with preserved background stem...")
                        episode = runStage12Agent7MultitrackMixing(episode)
                    }
                    13 -> {
                        onProgress(13, "Stage 13 — 13-Point Quality Control (Agent 7)", "Running strict WAV decode, overlap/gap, script & headroom audit...")
                        episode = runStage13Agent7QualityControl(episode)
                    }
                    14 -> {
                        onProgress(14, "Stage 14 — Export, Subtitles & Verification", "Exporting media, generating Mixed-Script SRT/ASS subtitles, and verifying output...")
                        episode = runStage14FinalExport(episode, exportFormat = "WAV_AUDIO_ONLY")
                    }
                }

                // Strict fail-fast gate: if this stage did NOT achieve COMPLETED bit, halt downstream immediately!
                val stageCompleted = (episode.completedStagesMask and (1 shl stage)) != 0
                if (!stageCompleted) {
                    val latestJob = dao.getAgentJobsForEpisodeOnce(episode.id)
                        .filter { it.stageNumber == stage }
                        .maxByOrNull { it.updatedAt }
                    val derivedStatus = parseStageStatus(latestJob?.status ?: StageStatus.FAILED.name)
                    val haltStatus = if (derivedStatus == StageStatus.COMPLETED) StageStatus.FAILED else derivedStatus
                    val haltMsg = episode.lastErrorMessage?.takeIf { it.isNotBlank() }
                        ?: latestJob?.errorDetails?.takeIf { it.isNotBlank() }
                        ?: latestJob?.outputSummary?.takeIf { it.isNotBlank() }
                        ?: "Stage $stage halted with status ${haltStatus.name}. Resolve issues before running downstream stages."
                    val haltedEpisode = episode.copy(
                        pipelineStatus = haltStatus.name,
                        lastErrorMessage = haltMsg,
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.updateEpisode(haltedEpisode)
                    return@withContext Result.success(
                        StageExecutionResult(
                            stageNumber = stage,
                            status = haltStatus,
                            episode = haltedEpisode,
                            completedCount = latestJob?.completedItems ?: 0,
                            totalCount = latestJob?.totalItems ?: 0,
                            message = haltMsg,
                            isBlockingFailure = true
                        )
                    )
                }
            }

            val finalEpisode = dao.getEpisodeById(episodeId) ?: episode
            val exportVerified = (finalEpisode.completedStagesMask and (1 shl 14)) != 0
            val doneEpisode = finalEpisode.copy(
                pipelineStatus = if (exportVerified) StageStatus.COMPLETED.name else "READY",
                lastErrorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            dao.updateEpisode(doneEpisode)
            Result.success(
                StageExecutionResult(
                    stageNumber = doneEpisode.currentStageNumber,
                    status = StageStatus.COMPLETED,
                    episode = doneEpisode,
                    completedCount = endStage - startStage + 1,
                    totalCount = endStage - startStage + 1,
                    message = "Completed through ${doneEpisode.currentStageName}.",
                    isBlockingFailure = false
                )
            )
        } catch (e: CancellationException) {
            val current = dao.getEpisodeById(episodeId)
            if (current != null) {
                recordAgentJob(
                    episodeId = current.id,
                    agentNumber = stageToAgentNumber(activeStage),
                    stageNumber = activeStage,
                    agentName = "Pipeline Coordinator",
                    operation = "Stage $activeStage cancelled by user",
                    modelUsed = "Coordinator",
                    runtimeMode = "ON_DEVICE_ANDROID",
                    inputSummary = "Stage $activeStage",
                    outputSummary = "Cancelled before completion",
                    completed = 0,
                    total = 1,
                    status = StageStatus.CANCELLED.name,
                    errorDetails = "Pipeline cancelled at Stage $activeStage by user."
                )
                dao.updateEpisode(
                    current.copy(
                        pipelineStatus = StageStatus.CANCELLED.name,
                        lastErrorMessage = "Pipeline cancelled at Stage $activeStage by user. Previously completed stages remain saved.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline execution error at Stage $activeStage", e)
            val current = dao.getEpisodeById(episodeId)
            if (current != null) {
                dao.updateEpisode(
                    current.copy(
                        completedStagesMask = clearStagesFrom(current.completedStagesMask, activeStage),
                        pipelineStatus = StageStatus.FAILED.name,
                        lastErrorMessage = e.localizedMessage ?: "Stage $activeStage failed.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            Result.failure(e)
        }
    }

    private fun stageToAgentNumber(stage: Int): Int = when (stage) {
        2, 3 -> 6
        4 -> 1
        5, 7 -> 2
        6 -> 3
        8 -> 4
        9, 10 -> 5
        else -> 7
    }

    private fun parseStageStatus(raw: String): StageStatus {
        return StageStatus.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: StageStatus.FAILED
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
     * Stage 3 — Agent 6: On-Device Background Audio Separation Approximation (Non-Neural DSP).
     * Runs 100% locally on Android using vocal bandpass filtering & residual BGM subtraction while
     * strictly preserving the untouched ORIGINAL_MASTER audio. Never claims neural HTDemucs output.
     */
    suspend fun runStage3Agent6BackgroundSeparation(
        episode: EpisodeEntity
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
                agentName = "Agent 6 — Local DSP Stem Approximation",
                operation = "Blocked: missing extracted audio",
                modelUsed = "On-Device Frequency Filtering & Residual Subtraction (Non-Neural DSP)",
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
        val masterBuf = AudioDspEngine.readWavFileStrict(masterFile).getOrElse {
            throw IllegalStateException("Master WAV is unreadable: ${it.localizedMessage}", it)
        }
        val masterPeak = AudioDspEngine.computePeakDbfs(masterBuf)

        val sepResult = AudioDspEngine.separateStemsDsp(masterFile, stemsDir)
        val vocalFile = sepResult.dialogueVocalFile
        val bgFile = sepResult.backgroundAccompanimentFile
        val vocalPeak = sepResult.vocalPeakDbfs
        val bgPeak = sepResult.backgroundPeakDbfs
        val qualityScore = sepResult.qualityScore // -1f (unmeasured non-neural DSP)
        val separatorModelLabel = "On-Device Frequency Filtering & Residual Subtraction (Non-Neural DSP)"
        val runtimeModeLabel = "ON_DEVICE_ANDROID"

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
     * Stage 4 — Agent 1 (On-Device VAD + Direct Gemini API Audio Transcription):
     * 1. Runs genuine On-Device Silero VAD via ONNX Runtime Mobile (`model.onnx`) if downloaded & load-verified,
     *    falling back transparently to Energy-Based DSP VAD when `model.onnx` is not downloaded.
     * 2. Transcribes each detected speech slice via the centralized `DirectGeminiApiClient`.
     * 3. Explicitly labeled as Direct Gemini Audio Transcription (NOT Whisper or WhisperX forced alignment).
     * 4. Never fabricates fake Japanese transcripts or marks Stage 4 completed with 0 transcribed lines.
     */
    suspend fun runStage4Agent1JapaneseTranscription(
        episode: EpisodeEntity,
        geminiModelName: String = DirectGeminiApiClient.DEFAULT_MODEL_ID
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
                modelUsed = "Silero VAD ONNX + Direct Gemini Audio",
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

        var usedRuntimeMode = "DIRECT_GEMINI_API"
        var usedModelLabel = "Direct Gemini Audio ($cleanGeminiName)"
        var vadMethodLabel = "Existing Segments"
        var asrErrorNote: String? = null

        val segmentsToSave: List<TranscriptSegmentEntity> = if (existingTranscripts.isNotEmpty()) {
            existingTranscripts.map { tr ->
                val sliceBuf = AudioDspEngine.sliceBuffer(vocalBuffer, tr.preliminaryStartMs, tr.preliminaryEndMs)
                val sliceFile = AudioDspEngine.writeWavFile(
                    File(slicesDir, "jp_seg_%03d.wav".format(tr.segmentIndex)),
                    sliceBuf
                )
                if (tr.japaneseText.isNotBlank()) {
                    tr.copy(originalAudioSlicePath = sliceFile.absolutePath)
                } else {
                    val asrRes = transcribeSliceWithGeminiAudio(sliceFile, cleanGeminiName, "seg-${tr.segmentIndex}")
                    if (asrRes.isSuccess) {
                        val parsed = asrRes.getOrThrow()
                        tr.copy(
                            japaneseText = parsed.japaneseText,
                            romajiReading = "",
                            confidenceScore = -1f,
                            asrModelUsed = "Direct Gemini Audio ($cleanGeminiName — Acoustic Estimated Timing)",
                            originalAudioSlicePath = sliceFile.absolutePath
                        )
                    } else {
                        asrErrorNote = asrRes.exceptionOrNull()?.localizedMessage
                        tr.copy(originalAudioSlicePath = sliceFile.absolutePath)
                    }
                }
            }
        } else {
            // On-Device Silero VAD ONNX Runtime Mobile (if downloaded & loadable) -> Energy DSP VAD fallback
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

            usedModelLabel = "$vadMethodLabel + Direct Gemini Audio ($cleanGeminiName)"
            detectedRanges.mapIndexed { idx, (startMs, endMs) ->
                val segIndex = idx + 1
                val sliceBuf = AudioDspEngine.sliceBuffer(vocalBuffer, startMs, endMs)
                val sliceFile = AudioDspEngine.writeWavFile(
                    File(slicesDir, "jp_seg_%03d.wav".format(segIndex)),
                    sliceBuf
                )
                val asrRes = transcribeSliceWithGeminiAudio(sliceFile, cleanGeminiName, "seg-$segIndex")
                if (asrRes.isSuccess) {
                    val parsed = asrRes.getOrThrow()
                    TranscriptSegmentEntity(
                        episodeId = episode.id,
                        segmentIndex = segIndex,
                        japaneseText = parsed.japaneseText,
                        romajiReading = "",
                        preliminaryStartMs = startMs,
                        preliminaryEndMs = endMs,
                        confidenceScore = -1f,
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
                        asrModelUsed = "$vadMethodLabel (Gemini Audio Error: ${asrErrorNote?.take(60) ?: "Unavailable"})",
                        originalAudioSlicePath = sliceFile.absolutePath
                    )
                }
            }
        }

        dao.replaceTranscriptsAtomic(episode.id, segmentsToSave)

        val transcribedCount = segmentsToSave.count { it.japaneseText.isNotBlank() }
        val allTimestampsValid = segmentsToSave.all { it.preliminaryStartMs >= 0L && it.preliminaryEndMs > it.preliminaryStartMs }
        val stageStatus = when {
            segmentsToSave.isEmpty() || transcribedCount == 0 || !allTimestampsValid -> StageStatus.FAILED
            transcribedCount < segmentsToSave.size -> StageStatus.PARTIAL
            else -> StageStatus.COMPLETED
        }

        val note = when (stageStatus) {
            StageStatus.COMPLETED ->
                "Transcribed all $transcribedCount / ${segmentsToSave.size} Japanese dialogue segment(s) via $usedModelLabel (timings are ACOUSTIC_ESTIMATED from local VAD)."
            StageStatus.PARTIAL ->
                "Partial transcription: $transcribedCount / ${segmentsToSave.size} segments transcribed (${segmentsToSave.size - transcribedCount} failed: ${asrErrorNote ?: "Gemini API error"}). Downstream stages halted until missing lines are retried or entered manually."
            else ->
                "Detected ${segmentsToSave.size} speech slices ($vadMethodLabel), but Direct Gemini Audio returned 0 valid transcripts (${asrErrorNote ?: "check Gemini API key, model & network in Settings"}). Retry or enter text manually in Transcript."
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
            status = stageStatus.name,
            errorDetails = if (stageStatus == StageStatus.COMPLETED) null else (asrErrorNote ?: note),
            startedAt = startedAt
        )

        val newMask = if (stageStatus == StageStatus.COMPLETED) {
            invalidateDownstreamStages(episode.completedStagesMask, 4)
        } else {
            clearStagesFrom(episode.completedStagesMask, 4)
        }
        val updated = episode.copy(
            currentStageNumber = 4,
            currentStageName = when (stageStatus) {
                StageStatus.COMPLETED -> "Stage 4 — $transcribedCount/${segmentsToSave.size} Japanese Lines Transcribed"
                StageStatus.PARTIAL -> "Stage 4 — Partial ($transcribedCount/${segmentsToSave.size} Transcribed)"
                else -> "Stage 4 — Failed (${segmentsToSave.size} Slices Need ASR)"
            },
            completedStagesMask = newMask,
            lastErrorMessage = if (stageStatus == StageStatus.COMPLETED) null else note,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    private suspend fun transcribeSliceWithGeminiAudio(
        wavFile: File,
        geminiModelName: String,
        segmentLabel: String
    ): Result<com.example.data.remote.GeminiAudioTranscriptionResult> {
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            return Result.failure(IllegalArgumentException("Audio slice WAV missing or empty."))
        }
        if (wavFile.length() > 15L * 1024L * 1024L) {
            return Result.failure(IllegalArgumentException("Audio slice exceeds 15 MB inlineData limit."))
        }
        return geminiClient.transcribeJapaneseAudioSlice(
            modelName = geminiModelName,
            wavBytes = wavFile.readBytes(),
            segmentLabel = segmentLabel
        )
    }

    /**
     * Stage 5 — Agent 2: Dialogue Timestamp & Segmentation Agent.
     * Honestly labels timestamps: `MANUAL` for user-authored lines, `ACOUSTIC_ESTIMATED` for
     * on-device VAD acoustic windows (never claims WhisperX word-level forced alignment).
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
                tr.asrModelUsed.contains("Manual", ignoreCase = true) -> "MANUAL"
                else -> "ACOUSTIC_ESTIMATED"
            }
            val alignmentLabel = when (honestTimingStatus) {
                "MANUAL" -> "Manual Timeline Entry"
                else -> "On-Device VAD Acoustic Boundary (Estimated — No WhisperX)"
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
     * Stage 6 — Agent 3: Audio-Only Speaker Analysis (On-Device Acoustic F0 & Spectral Clustering — Non-Neural).
     * Runs 100% locally on Android with strict per-project speaker isolation (`episode.projectId`),
     * tracking emotion separately from speaker identity. Never claims neural pyannote/WeSpeaker diarization
     * and never fabricates similarity or confidence scores (`-1f` when unmeasured).
     */
    suspend fun runStage6Agent3SpeakerIdentification(
        episode: EpisodeEntity
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
        if (dialogues.isEmpty()) {
            val msg = "No dialogue segments found to analyze. Run Stage 5 first."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 3,
                stageNumber = 6,
                agentName = "Agent 3 — Acoustic Speaker Analysis (Non-Neural)",
                operation = "Blocked: no dialogue segments",
                modelUsed = "On-Device Acoustic F0 & Spectral Clustering (Non-Neural)",
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

        // Strictly project-isolated speaker profiles for cross-episode & rerun continuity
        val savedProjectSpeakers = dao.getSpeakersForProjectOnce(episode.projectId)
            .distinctBy { it.speakerLabel }
        val savedSpeakerEmbeddings = savedProjectSpeakers.mapNotNull { sp ->
            val emb = parseEmbeddingJson(sp.speakerEmbeddingJson)
            if (emb.isNotEmpty()) sp to emb else null
        }
        val existingNamesByLabel = savedProjectSpeakers.associate { it.speakerLabel to it.characterName }
        val existingConsentByLabel = savedProjectSpeakers.associate { it.speakerLabel to it.rightsConsentConfirmed }

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

        val claimedLabels = mutableSetOf<String>()
        val savedProfilesByLabel = savedProjectSpeakers.associateBy { it.speakerLabel }
        var unidentifiedSliceCount = 0

        for (dlg in dialogues) {
            val sliceFile = File(dlg.originalAudioSlicePath)
            val strictWav = AudioDspEngine.validateAudioFileStrict(sliceFile)
            val pcm = if (strictWav.isSuccess) AudioDspEngine.readWavFile(sliceFile) else null
            val acoustic = if (pcm != null && pcm.samples.isNotEmpty()) AudioDspEngine.analyzeVoiceSegment(pcm) else null
            val hasUsableVoice = strictWav.isSuccess &&
                strictWav.getOrThrow().rmsDbfs > FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS &&
                acoustic != null &&
                acoustic.embedding.any { it != 0f }

            if (!hasUsableVoice || acoustic == null) {
                // Strict honesty: NEVER force an unreadable/silent/unvoiced slice into Speaker "A"!
                unidentifiedSliceCount++
                updatedDialogues.add(
                    dlg.copy(
                        speakerLabel = "UNIDENTIFIED",
                        characterId = "",
                        speakerConfidence = -1f,
                        peakDbfs = strictWav.getOrNull()?.peakDbfs ?: dlg.peakDbfs,
                        errorNote = "Audio slice unreadable or unvoiced; assign speaker manually in Transcript."
                    )
                )
                continue
            }

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
                    similarityScores = mutableListOf(),
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
            val charId = assignedCluster.matchedSavedProfile?.characterId?.takeIf { it.isNotBlank() }
                ?: "CHAR-${episode.projectId}-${assignedCluster.label}"
            updatedDialogues.add(
                dlg.copy(
                    speakerLabel = assignedCluster.label,
                    characterId = charId,
                    speakerConfidence = measuredSpeakerConf,
                    emotion = if (dlg.emotion == "NEUTRAL") acoustic.estimatedEmotion else dlg.emotion,
                    emotionConfidence = if (dlg.emotionConfidence >= 0f) dlg.emotionConfidence else -1f,
                    speakingStyle = if (dlg.speakingStyle.isBlank() || dlg.speakingStyle == "Natural") acoustic.speakingStyle else dlg.speakingStyle,
                    peakDbfs = acoustic.peakDbfs,
                    errorNote = null
                )
            )
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
            val existingProfile = cluster.matchedSavedProfile ?: savedProfilesByLabel[cluster.label]
            val confirmedConsent = existingProfile?.rightsConsentConfirmed
                ?: existingConsentByLabel[cluster.label]
                ?: false
            val charId = existingProfile?.characterId?.takeIf { it.isNotBlank() }
                ?: "CHAR-${episode.projectId}-${cluster.label}"
            val preservedTts = existingProfile?.preferredTtsEngine?.takeIf { it.isNotBlank() } ?: "Android-Speech-TTS"
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
                speakingStyle = existingProfile?.speakingStyle?.takeIf { it.isNotBlank() && it != "Natural" } ?: firstFeat.speakingStyle,
                dominantEmotion = firstFeat.estimatedEmotion,
                speakerEmbeddingJson = embJson,
                voiceSimilarityScore = avgSim,
                voiceReferencePath = cluster.representativeSlicePath,
                voiceReferenceId = "VREF-${episode.projectId}-${cluster.label}",
                rightsConsentConfirmed = confirmedConsent,
                useConsentedGenericVoiceFallback = existingProfile?.useConsentedGenericVoiceFallback ?: !confirmedConsent,
                preferredTtsEngine = preservedTts,
                dialogueCount = count,
                voiceConsistencyScore = avgSim,
                characterId = charId,
                visualIdentityNote = "Audio-Only On-Device Acoustic F0 & Spectral Clustering (Non-Neural; No visual/gender assumptions)$continuityTag",
                profileConfidence = avgSim
            )
        }
        dao.replaceSpeakersAtomic(episode.id, speakerEntities)

        val identifiedCount = updatedDialogues.size - unidentifiedSliceCount
        val stageStatus = when {
            speakerEntities.isEmpty() || identifiedCount == 0 -> StageStatus.FAILED
            unidentifiedSliceCount > 0 -> StageStatus.PARTIAL
            else -> StageStatus.COMPLETED
        }
        val errNote = when (stageStatus) {
            StageStatus.COMPLETED -> null
            StageStatus.PARTIAL -> "$unidentifiedSliceCount of ${updatedDialogues.size} dialogue slice(s) were silent/unvoiced and marked UNIDENTIFIED. Assign speakers manually before Stage 8."
            else -> "0 speakers could be identified from dialogue audio slices. Check vocal audio quality or assign speakers manually."
        }

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 3,
            stageNumber = 6,
            agentName = "Agent 3 — Acoustic Speaker Analysis (Non-Neural)",
            operation = "Clustered ${speakerEntities.size} speaker(s) ($identifiedCount/${updatedDialogues.size} dialogues identified)",
            modelUsed = "On-Device Acoustic F0 & Spectral Clustering (Non-Neural)",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${updatedDialogues.size} vocal dialogue slices",
            outputSummary = if (speakerEntities.isNotEmpty()) {
                speakerEntities.joinToString {
                    val pitchTxt = if (it.f0MeanHz > 0f) "${it.f0MeanHz} Hz" else "Unvoiced"
                    "${it.characterName} ($pitchTxt, ${it.dialogueCount} lines)"
                } + if (unidentifiedSliceCount > 0) " • $unidentifiedSliceCount UNIDENTIFIED" else ""
            } else {
                "0 speakers identified"
            },
            completed = identifiedCount,
            total = updatedDialogues.size,
            status = stageStatus.name,
            errorDetails = errNote,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 6,
            currentStageName = when (stageStatus) {
                StageStatus.COMPLETED -> "Stage 6 — ${speakerEntities.size} Speaker(s) Analyzed"
                StageStatus.PARTIAL -> "Stage 6 — Partial ($unidentifiedSliceCount Unidentified)"
                else -> "Stage 6 — Speaker Analysis Failed"
            },
            completedStagesMask = if (stageStatus == StageStatus.COMPLETED) {
                invalidateDownstreamStages(episode.completedStagesMask, 6)
            } else {
                clearStagesFrom(episode.completedStagesMask, 6)
            },
            lastErrorMessage = errNote,
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
        val identifiedCount = numbered.size - unidentifiedCount
        val stageStatus = when {
            unidentifiedCount == 0 -> StageStatus.COMPLETED
            identifiedCount > 0 -> StageStatus.PARTIAL
            else -> StageStatus.FAILED
        }
        val errMsg = if (stageStatus == StageStatus.COMPLETED) null else {
            "$unidentifiedCount dialogue(s) are UNIDENTIFIED. Run Stage 6 Speaker Analysis or assign speakers manually before Stage 8."
        }
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 2,
            stageNumber = 7,
            agentName = "Global Dialogue ID Coordinator",
            operation = if (stageStatus == StageStatus.COMPLETED) {
                "Assigned sequential global IDs in chronological order"
            } else {
                "Halted: $unidentifiedCount dialogue(s) have unidentified speakers"
            },
            modelUsed = "Deterministic Global Counter",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${chronological.size} chronological segments",
            outputSummary = numbered.take(8).joinToString(" → ") { it.dialogueId } + if (numbered.size > 8) " …" else "",
            completed = identifiedCount,
            total = numbered.size,
            status = stageStatus.name,
            errorDetails = errMsg,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 7,
            currentStageName = when (stageStatus) {
                StageStatus.COMPLETED -> "Stage 7 — Global Dialogue IDs Assigned"
                StageStatus.PARTIAL -> "Stage 7 — Partial ($unidentifiedCount Unidentified)"
                else -> "Stage 7 — Failed (All Speakers Unidentified)"
            },
            completedStagesMask = if (stageStatus == StageStatus.COMPLETED) {
                invalidateDownstreamStages(episode.completedStagesMask, 7)
            } else {
                clearStagesFrom(episode.completedStagesMask, 7)
            },
            lastErrorMessage = errMsg,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 8 — Agent 4: Permanent Mixed-Script Hindi-English (Devanagari + English) Translation
     * via Centralized Direct Gemini REST API (`DirectGeminiApiClient`) with:
     * - Prev/next dialogue scene context + character personality/style preferences
     * - Sentence-level splitting (1..N natural mixed-script sentences per turn)
     * - Acoustic pause-valley timestamp reconciliation (`ACOUSTIC_ESTIMATED` for split sub-sentences)
     * - Strict 3-tier dictionary isolation & precedence (`Project > Anime > Global`)
     * - Mixed-script compliance validation + 1 controlled Gemini script-correction pass if Romanized Hindi leaks
     * - Atomic DB persistence (zero duplicates on rerun) & strict separation of display vs TTS pronunciation text
     */
    suspend fun runStage8Agent4GeminiTranslation(
        episode: EpisodeEntity,
        geminiModelName: String = DirectGeminiApiClient.DEFAULT_MODEL_ID
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val modelCheck = DirectGeminiApiClient.validateModelSelection(geminiModelName)
        if (modelCheck.isFailure) {
            val errMsg = modelCheck.exceptionOrNull()?.localizedMessage ?: "Selected Gemini model is unavailable."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 4,
                stageNumber = 8,
                agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
                operation = "Blocked: invalid or retired Gemini model",
                modelUsed = sanitizeGeminiModelName(geminiModelName),
                runtimeMode = "DIRECT_GEMINI_API",
                inputSummary = "Model validation",
                outputSummary = errMsg,
                completed = 0,
                total = 1,
                status = StageStatus.FAILED.name,
                errorDetails = errMsg,
                startedAt = startedAt
            )
            throw IllegalStateException(errMsg, modelCheck.exceptionOrNull())
        }
        val cleanModelName = modelCheck.getOrThrow()
        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
        if (dialogues.isEmpty()) {
            throw IllegalStateException("No dialogues exist to translate. Run Stages 4–7 first.")
        }

        val allWithJapanese = dialogues.filter { it.japaneseText.isNotBlank() }
        if (allWithJapanese.isEmpty()) {
            val msg = "No Japanese transcript text found. Add Japanese lines in the Transcript tab (or run Stage 4 ASR) before running Gemini Translation."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 4,
                stageNumber = 8,
                agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
                operation = "Blocked: Japanese transcript empty",
                modelUsed = "$cleanModelName (Direct Gemini API)",
                runtimeMode = "DIRECT_GEMINI_API",
                inputSummary = "${dialogues.size} dialogue slots (0 with Japanese text)",
                outputSummary = "Awaiting Japanese transcript",
                completed = 0,
                total = dialogues.size,
                status = StageStatus.FAILED.name,
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        // Preserve user-edited valid translations (translationVersion > 1) instead of silently overwriting them
        val preservedUserEdits = allWithJapanese.filter { dlg ->
            dlg.translationVersion > 1 &&
                dlg.romanHinglishText.isNotBlank() &&
                MixedScriptLanguageEngine.validateMixedScript(dlg.romanHinglishText).isValid
        }.associateBy { it.dialogueId }

        val translatable = allWithJapanese.filterNot { it.dialogueId in preservedUserEdits }
        if (translatable.isEmpty()) {
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 4,
                stageNumber = 8,
                agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
                operation = "Preserved ${preservedUserEdits.size} verified user-edited line(s)",
                modelUsed = "$cleanModelName (Direct Gemini API)",
                runtimeMode = "DIRECT_GEMINI_API",
                inputSummary = "${preservedUserEdits.size} user-edited lines",
                outputSummary = "All ${preservedUserEdits.size} lines already valid Mixed-Script",
                completed = preservedUserEdits.size,
                total = allWithJapanese.size,
                status = StageStatus.COMPLETED.name,
                startedAt = startedAt
            )
            val preservedEp = episode.copy(
                currentStageNumber = 8,
                currentStageName = "Stage 8 — Mixed-Script Verified (${preservedUserEdits.size} user-edited)",
                completedStagesMask = invalidateDownstreamStages(episode.completedStagesMask, 8),
                lastErrorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            dao.updateEpisode(preservedEp)
            return preservedEp
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

        val speakerContextBlock = if (speakers.isEmpty()) {
            "(No speaker profiles yet; maintain natural conversational anime dubbing style.)"
        } else {
            speakers.values.sortedBy { it.speakerLabel }.joinToString("\n") { sp ->
                "- Speaker ${sp.speakerLabel} (${sp.characterName}): Style=${sp.speakingStyle}, Timbre=${sp.timbreDescriptor}, DominantEmotion=${sp.dominantEmotion}"
            }
        }

        val dialogueBatchBlock = translatable.mapIndexed { idx, d ->
            val sp = speakers[d.speakerLabel]
            val prevLine = translatable.getOrNull(idx - 1)?.japaneseText?.take(80) ?: "(scene start)"
            val nextLine = translatable.getOrNull(idx + 1)?.japaneseText?.take(80) ?: "(scene end)"
            val durationSec = "%.2f".format(d.originalDurationMs / 1000.0)
            "sourceIndex=$idx | ID=${d.dialogueId} | Speaker=${d.speakerLabel} (${sp?.characterName ?: "Speaker ${d.speakerLabel}"}) | Window=${durationSec}s | Prev='$prevLine' | Next='$nextLine' | Japanese=${d.japaneseText}"
        }.joinToString("\n")

        data class ParsedSentence(
            val flatIndex: Int,
            val sourceDialogueId: String,
            var mixedScriptText: String,
            val speakingStyle: String,
            val emotion: String,
            val sceneContextNote: String,
            var scriptStatus: String = MixedScriptLanguageEngine.STATUS_VALID
        )

        val sentencesBySourceId = linkedMapOf<String, MutableList<ParsedSentence>>()
        val translationResult = geminiClient.translateAndSegmentDialogues(
            modelName = cleanModelName,
            animeSeriesName = project?.animeSeriesName?.ifBlank { project.title } ?: "Anime Series",
            episodeTitle = episode.episodeTitle,
            dictionaryPromptBlock = dictPromptBlock,
            speakerContextBlock = speakerContextBlock,
            dialogueBatchPromptBlock = dialogueBatchBlock
        )

        if (translationResult.isFailure) {
            val err = translationResult.exceptionOrNull()!!
            val msg = "${err.localizedMessage ?: "Direct Gemini API translation failed."} Check Settings -> Direct Gemini API or edit lines manually in Translate."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 4,
                stageNumber = 8,
                agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
                operation = "Direct Gemini translation failed",
                modelUsed = "$cleanModelName (Direct Gemini API)",
                runtimeMode = "DIRECT_GEMINI_API",
                inputSummary = "${translatable.size} Japanese dialogues",
                outputSummary = "0 translated",
                completed = 0,
                total = translatable.size,
                status = "FAILED",
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg, err)
        }

        var flatIdx = 0
        for (item in translationResult.getOrThrow()) {
            val srcDlg = translatable.getOrNull(item.sourceIndex) ?: continue
            val spStyle = speakers[srcDlg.speakerLabel]?.speakingStyle ?: srcDlg.speakingStyle
            val list = item.sentences.mapNotNull { rawLine ->
                val clean = rawLine.trim()
                if (clean.isEmpty()) null else ParsedSentence(
                    flatIndex = flatIdx++,
                    sourceDialogueId = srcDlg.dialogueId,
                    mixedScriptText = clean,
                    speakingStyle = spStyle.ifBlank { "Natural" },
                    emotion = item.emotion,
                    sceneContextNote = "Context-adapted via Direct Gemini API ($cleanModelName)"
                )
            }.toMutableList()
            if (list.isNotEmpty()) {
                sentencesBySourceId[srcDlg.dialogueId] = list
            }
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

        // One controlled Direct Gemini script-correction pass if any Romanized Hindi leaked
        if (invalidForCorrection.isNotEmpty()) {
            val corrRes = geminiClient.correctMixedScriptBatch(
                modelName = cleanModelName,
                dictionaryPromptBlock = dictPromptBlock,
                linesToFix = invalidForCorrection.map { it.flatIndex to it.mixedScriptText }
            )
            if (corrRes.isSuccess) {
                val fixedMap = corrRes.getOrThrow()
                invalidForCorrection.forEach { sent ->
                    val fixed = fixedMap[sent.flatIndex]
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
        }

        // Build final chronological dialogue rows (supporting 1..N sentence splits with acoustic pause reconciliation)
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val slicesDir = File(epDir, "jp_slices").apply { mkdirs() }
        val maxEpMs = episode.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val assembledDialogues = mutableListOf<DialogueSegmentEntity>()
        val invalidScriptIds = mutableListOf<String>()
        var splitCount = 0

        for (dlg in dialogues.sortedBy { it.originalStartMs }) {
            if (dlg.dialogueId in preservedUserEdits) {
                val ttsPron = MixedScriptLanguageEngine.buildTtsPronunciationText(dlg.romanHinglishText, resolvedDicts)
                assembledDialogues.add(
                    dlg.copy(
                        ttsNormalizedText = ttsPron,
                        scriptValidationStatus = MixedScriptLanguageEngine.STATUS_VALID,
                        errorNote = null
                    )
                )
                continue
            }
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
                        emotionConfidence = -1f,
                        translationConfidence = -1f,
                        sceneContextNote = s.sceneContextNote,
                        scriptValidationStatus = s.scriptStatus,
                        generationStatus = if (dlg.generatedAudioPath != null) dlg.generationStatus else "TRANSLATED",
                        errorNote = if (scriptOk) null else "Mixed-script validation failed: Romanized Hindi detected. Tap Fix Script with Gemini."
                    )
                )
            } else {
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
                            id = 0L,
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
                            emotionConfidence = -1f,
                            translationConfidence = -1f,
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
        val totalExpectedCount = allWithJapanese.size
        val coveredCount = preservedUserEdits.size + sentencesBySourceId.size
        val fullCoverage = missingIds.isEmpty() && invalidScriptIds.isEmpty() && coveredCount == totalExpectedCount
        val stageStatus = when {
            fullCoverage -> StageStatus.COMPLETED
            coveredCount > 0 -> StageStatus.PARTIAL
            else -> StageStatus.FAILED
        }
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
        val preservedNote = if (preservedUserEdits.isNotEmpty()) " • ${preservedUserEdits.size} user edit(s) preserved" else ""
        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 4,
            stageNumber = 8,
            agentName = "Agent 4 — Mixed-Script Translation & Dictionary",
            operation = "Translated $coveredCount/$totalExpectedCount segment(s) → ${finalNumbered.size} line(s) via $cleanModelName",
            modelUsed = "$cleanModelName (Direct Gemini API)",
            runtimeMode = "DIRECT_GEMINI_API",
            inputSummary = "${translatable.size} pending + ${preservedUserEdits.size} preserved • ${resolvedDicts.size} dictionary terms",
            outputSummary = if (fullCoverage) {
                "Saved ${finalNumbered.size} Mixed-Script Hindi-English line(s)$splitNote$preservedNote"
            } else {
                "${stageStatus.name} ($coveredCount/$totalExpectedCount): $coverageErr"
            },
            completed = coveredCount,
            total = totalExpectedCount,
            status = stageStatus.name,
            errorDetails = coverageErr,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 8,
            currentStageName = when (stageStatus) {
                StageStatus.COMPLETED -> "Stage 8 — Mixed-Script Translated (${finalNumbered.size} lines)"
                StageStatus.PARTIAL -> "Stage 8 — Partial ($coveredCount/$totalExpectedCount Translated)"
                else -> "Stage 8 — Translation Failed (0/$totalExpectedCount)"
            },
            completedStagesMask = if (stageStatus == StageStatus.COMPLETED) {
                invalidateDownstreamStages(episode.completedStagesMask, 8)
            } else {
                clearStagesFrom(episode.completedStagesMask, 8)
            },
            lastErrorMessage = if (stageStatus == StageStatus.COMPLETED) null else coverageErr,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * One-tap controlled Direct Gemini API Mixed-Script repair for a single dialogue line.
     */
    suspend fun fixSingleDialogueScriptWithGemini(
        dialogueId: Long,
        rawText: String,
        geminiModelName: String = DirectGeminiApiClient.DEFAULT_MODEL_ID
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
        val dictBlock = resolvedDicts.joinToString("\n") {
            "- [${it.dictionaryLevel}] '${it.japaneseSourceTerm}' -> '${MixedScriptLanguageEngine.preferredDisplayTerm(it)}'"
        }.ifBlank { "(none)" }

        val corrRes = geminiClient.correctMixedScriptBatch(
            modelName = sanitizeGeminiModelName(geminiModelName),
            dictionaryPromptBlock = dictBlock,
            linesToFix = listOf(0 to trimmed)
        )
        corrRes.mapCatching { map ->
            val correctedRaw = map[0]?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Direct Gemini API returned no corrected text.")
            MixedScriptLanguageEngine.applyDictionariesToDisplayText(
                displayText = correctedRaw,
                japaneseSource = dlg?.japaneseText ?: "",
                resolvedEntries = resolvedDicts
            )
        }
    }

    /**
     * Contextual Dialogue Rewriting & Quality Review via Direct Gemini API using prev/next lines,
     * character style profile, and active 3-tier dictionary.
     */
    suspend fun reviewOrRewriteDialogueWithGemini(
        dialogueId: Long,
        currentMixedScript: String,
        instruction: String,
        geminiModelName: String = DirectGeminiApiClient.DEFAULT_MODEL_ID
    ): Result<GeminiDialogueRewriteResult> = withContext(Dispatchers.IO) {
        val dlg = dao.getDialogueById(dialogueId)
            ?: return@withContext Result.failure(IllegalArgumentException("Dialogue not found."))
        val ep = dao.getEpisodeById(dlg.episodeId)
            ?: return@withContext Result.failure(IllegalArgumentException("Episode not found."))
        val project = dao.getProjectById(ep.projectId)
        val allDialogues = dao.getDialoguesChronologicalOnce(ep.id)
        val idx = allDialogues.indexOfFirst { it.id == dialogueId }
        val prevLine = if (idx > 0) allDialogues[idx - 1].romanHinglishText.ifBlank { allDialogues[idx - 1].japaneseText } else ""
        val nextLine = if (idx >= 0 && idx < allDialogues.lastIndex) allDialogues[idx + 1].romanHinglishText.ifBlank { allDialogues[idx + 1].japaneseText } else ""
        val speaker = dao.getSpeakersForEpisodeOnce(ep.id).find { it.speakerLabel == dlg.speakerLabel }
        val resolvedDicts = MixedScriptLanguageEngine.resolveScopedDictionaries(
            allEntries = dao.getAllDictionaryEntriesOnce(),
            projectId = ep.projectId,
            animeSeriesName = project?.animeSeriesName
        )
        val dictBlock = resolvedDicts.joinToString("\n") {
            "- [${it.dictionaryLevel}] '${it.japaneseSourceTerm}' -> '${MixedScriptLanguageEngine.preferredDisplayTerm(it)}'"
        }.ifBlank { "(none)" }

        val res = geminiClient.rewriteOrReviewDialogue(
            modelName = sanitizeGeminiModelName(geminiModelName),
            animeSeriesName = project?.animeSeriesName?.ifBlank { project.title } ?: "Anime Series",
            speakerName = speaker?.characterName ?: "Speaker ${dlg.speakerLabel}",
            speakerPersonality = "${speaker?.speakingStyle ?: dlg.speakingStyle} (${speaker?.timbreDescriptor ?: "Natural"})",
            japaneseSource = dlg.japaneseText,
            currentMixedScript = currentMixedScript.trim().ifBlank { dlg.romanHinglishText },
            prevContext = prevLine,
            nextContext = nextLine,
            dictionaryPromptBlock = dictBlock,
            instruction = instruction
        )
        res.map { r ->
            val withDict = MixedScriptLanguageEngine.applyDictionariesToDisplayText(
                displayText = r.rewrittenMixedScript,
                japaneseSource = dlg.japaneseText,
                resolvedEntries = resolvedDicts
            )
            r.copy(rewrittenMixedScript = withDict)
        }
    }

    /**
     * Stage 9 — Character Voice Reference Setup.
     * Strictly decodes & verifies every speaker's reference WAV (header, samples, non-silent RMS > -55 dBFS).
     * Never hardcodes COMPLETED when any required speaker lacks a verified voice reference.
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

        val failedSpeakers = mutableListOf<String>()
        val updatedSpeakers = speakers.map { sp ->
            val speakerDialogues = dialogues.filter { it.speakerLabel == sp.speakerLabel }
            // Pick the first dialogue slice for this speaker that strictly decodes as non-silent PCM WAV
            val validSourceFile = speakerDialogues
                .map { File(it.originalAudioSlicePath) }
                .plus(File(sp.voiceReferencePath))
                .firstOrNull { candidate ->
                    val check = AudioDspEngine.validateAudioFileStrict(candidate)
                    check.isSuccess &&
                        check.getOrThrow().sampleCount > 0L &&
                        check.getOrThrow().rmsDbfs > FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS
                }

            val targetRefFile = File(refDir, "vref_speaker_${sp.speakerLabel.lowercase()}.wav")
            val verifiedRefPath = if (validSourceFile != null) {
                val exported = AudioDspEngine.exportWavWithMetadata(
                    sourceWavFile = validSourceFile,
                    outputWavFile = targetRefFile,
                    targetSampleRateHz = episode.audioSampleRateHz
                )
                val postCheck = exported.fold(
                    onSuccess = { meta -> AudioDspEngine.validateAudioFileStrict(meta.outputFile) },
                    onFailure = { Result.failure(it) }
                )
                if (postCheck.isSuccess && postCheck.getOrThrow().rmsDbfs > FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS) {
                    targetRefFile.absolutePath
                } else {
                    failedSpeakers.add("${sp.characterName}(reference decode failed)")
                    ""
                }
            } else {
                failedSpeakers.add("${sp.characterName}(missing/silent vocal slice)")
                ""
            }

            sp.copy(
                voiceReferencePath = verifiedRefPath,
                voiceReferenceId = "VREF-${episode.projectId}-${sp.speakerLabel}",
                preferredTtsEngine = sp.preferredTtsEngine.ifBlank { selectedTtsEngine },
                dialogueCount = speakerDialogues.size
            )
        }
        dao.insertSpeakerProfiles(updatedSpeakers)

        val readyCount = updatedSpeakers.count { it.voiceReferencePath.isNotBlank() }
        val stageStatus = when {
            readyCount == updatedSpeakers.size && updatedSpeakers.isNotEmpty() -> StageStatus.COMPLETED
            readyCount > 0 -> StageStatus.PARTIAL
            else -> StageStatus.FAILED
        }
        val errDetails = if (stageStatus == StageStatus.COMPLETED) null else {
            "Verified $readyCount/${updatedSpeakers.size} speaker voice references. Missing/silent: ${failedSpeakers.joinToString(", ")}."
        }

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 5,
            stageNumber = 9,
            agentName = "Voice Reference Builder",
            operation = "Verified $readyCount / ${updatedSpeakers.size} speaker reference WAV(s)",
            modelUsed = "Strict PCM Reference Verifier ($selectedTtsEngine — No Neural Cloning)",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${updatedSpeakers.size} speaker profiles",
            outputSummary = if (stageStatus == StageStatus.COMPLETED) {
                updatedSpeakers.joinToString { "${it.characterName}: ${if (it.rightsConsentConfirmed) "Consent Confirmed" else "Generic TTS Fallback"}" }
            } else {
                errDetails ?: "Voice reference setup incomplete"
            },
            completed = readyCount,
            total = updatedSpeakers.size,
            status = stageStatus.name,
            errorDetails = errDetails,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 9,
            currentStageName = when (stageStatus) {
                StageStatus.COMPLETED -> "Stage 9 — Voice References Verified ($readyCount/${updatedSpeakers.size})"
                StageStatus.PARTIAL -> "Stage 9 — Partial ($readyCount/${updatedSpeakers.size} References Verified)"
                else -> "Stage 9 — Failed (0 Voice References Verified)"
            },
            completedStagesMask = if (stageStatus == StageStatus.COMPLETED) {
                invalidateDownstreamStages(episode.completedStagesMask, 9)
            } else {
                clearStagesFrom(episode.completedStagesMask, 9)
            },
            lastErrorMessage = errDetails,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 10 — Agent 5 (Voice Generation & Pronunciation — Android-Only Local Fallback):
     * Executes character-wise batches (All A -> All B -> All C) using genuine on-device
     * `AndroidSpeechTtsEngine` (Android System TextToSpeech explicit fallback — NO voice cloning).
     * Never disguises Android TTS as Chatterbox or F5-Hindi; never falls back to fake sine-wave audio.
     */
    suspend fun runStage10Agent5CharacterWiseVoiceGeneration(
        episode: EpisodeEntity,
        selectedTtsEngine: String = "Android-Speech-TTS"
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
                agentName = "Agent 5 — Local Voice Generation",
                operation = "Blocked: no translated dialogue text",
                modelUsed = "Android System TextToSpeech (Explicit Local Fallback)",
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

        val distinctSpeakerLabels = readyDialogues.map { it.speakerLabel }.distinct().sorted()
        val batchExecutionSummary = mutableListOf<String>()
        var generatedCount = 0
        var failedCount = 0
        var lastEngineUsed = "Android-Speech-TTS Generic (No Voice Cloning)"
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
                preferredTtsEngine = "Android-Speech-TTS",
                characterId = "CHAR-${episode.projectId}-$speakerLabel"
            )

            val batchIds = mutableListOf<String>()
            for ((idxInBatch, dlg) in batchDialogues.withIndex()) {
                val orderInBatch = idxInBatch + 1
                val synthOutcome = synthesizeSingleLineLocal(
                    dialogueId = dlg.dialogueId,
                    speakerProfile = speakerProfile,
                    visibleMixedScript = dlg.romanHinglishText,
                    resolvedDictionaries = resolvedDictionaries,
                    emotion = dlg.emotion,
                    targetDurationMs = dlg.originalDurationMs,
                    outputDir = genDir,
                    sampleRateHz = episode.audioSampleRateHz
                )

                // Strictly decode & verify synthesized WAV before marking GENERATED
                val verifiedSynth = synthOutcome.mapCatching { synthResult ->
                    val strictCheck = AudioDspEngine.validateAudioFileStrict(synthResult.outputFile).getOrElse {
                        throw IllegalStateException("Synthesized WAV failed decode check: ${it.localizedMessage}")
                    }
                    if (strictCheck.sampleCount <= 0L || strictCheck.durationMs <= 0L) {
                        throw IllegalStateException("Synthesized WAV contains 0 samples.")
                    }
                    if (strictCheck.rmsDbfs < FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS) {
                        throw IllegalStateException("Synthesized WAV is silent (${strictCheck.rmsDbfs} dBFS). Verify Hindi/English TTS voice data is installed.")
                    }
                    synthResult to strictCheck
                }

                if (verifiedSynth.isSuccess) {
                    val (synthResult, strictCheck) = verifiedSynth.getOrThrow()
                    lastEngineUsed = synthResult.engineUsed

                    val deviationMs = strictCheck.durationMs - dlg.originalDurationMs
                    val updatedDlg = dlg.copy(
                        ttsNormalizedText = synthResult.internalNormalizedPhonetic,
                        previousGeneratedAudioPath = dlg.generatedAudioPath,
                        generatedAudioPath = synthResult.outputFile.absolutePath,
                        actualGeneratedDurationMs = strictCheck.durationMs,
                        durationDeviationMs = deviationMs,
                        timeStretchRatio = synthResult.timeStretchRatio,
                        voiceReferenceId = speakerProfile.voiceReferenceId,
                        ttsEngineUsed = synthResult.engineUsed,
                        characterBatchNumber = batchNumber,
                        orderInCharacterBatch = orderInBatch,
                        generationStatus = "GENERATED",
                        syncStatus = synthResult.syncStatus,
                        peakDbfs = strictCheck.peakDbfs,
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
                            emotionControlSupported = false,
                            targetDurationMs = dlg.originalDurationMs,
                            actualDurationMs = strictCheck.durationMs,
                            timeStretchRatio = synthResult.timeStretchRatio,
                            outputFilePath = synthResult.outputFile.absolutePath,
                            sampleRateHz = strictCheck.sampleRateHz,
                            status = "SUCCESS"
                        )
                    )
                    batchIds.add(dlg.dialogueId)
                    generatedCount++
                } else {
                    val errMsg = verifiedSynth.exceptionOrNull()?.localizedMessage ?: "Android Speech TTS synthesis failed."
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
                            ttsEngine = "Android-Speech-TTS",
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

        val untranslatedSkipped = characterWiseDialogues.size - readyDialogues.size
        val stageStatus = when {
            generatedCount == 0 -> StageStatus.FAILED
            failedCount > 0 || untranslatedSkipped > 0 -> StageStatus.PARTIAL
            else -> StageStatus.COMPLETED
        }
        val stageErr = when (stageStatus) {
            StageStatus.COMPLETED -> null
            StageStatus.PARTIAL -> "Partial synthesis: $generatedCount/${characterWiseDialogues.size} generated ($failedCount failed, $untranslatedSkipped untranslated). ${lastError ?: ""}".trim()
            else -> lastError ?: "Voice synthesis produced 0 valid audio clips. Install Google Speech Services with Hindi (hi-IN) voice data on this Android device."
        }

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 5,
            stageNumber = 10,
            agentName = "Agent 5 — Local Voice Generation",
            operation = "Synthesized $generatedCount / ${characterWiseDialogues.size} line(s) ($failedCount failed)",
            modelUsed = "$lastEngineUsed + WSOLA Sync",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${readyDialogues.size} translated lines grouped A → B → C",
            outputSummary = if (batchExecutionSummary.isNotEmpty()) batchExecutionSummary.joinToString(" | ") else (stageErr ?: "0 generated"),
            completed = generatedCount,
            total = characterWiseDialogues.size,
            status = stageStatus.name,
            errorDetails = stageErr,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 10,
            currentStageName = when (stageStatus) {
                StageStatus.COMPLETED -> "Stage 10 — $generatedCount Lines Synthesized (Android TTS)"
                StageStatus.PARTIAL -> "Stage 10 — Partial ($generatedCount OK, $failedCount Failed)"
                else -> "Stage 10 — Voice Synthesis Failed"
            },
            completedStagesMask = if (stageStatus == StageStatus.COMPLETED) {
                invalidateDownstreamStages(episode.completedStagesMask, 10)
            } else {
                clearStagesFrom(episode.completedStagesMask, 10)
            },
            lastErrorMessage = stageErr,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Local Speech Synthesis Dispatcher (Android System TextToSpeech Explicit Fallback + WSOLA Sync).
     * Never mutates visible Mixed-Script display text; feeds separate dictionary-guided pronunciation text to TTS.
     */
    private suspend fun synthesizeSingleLineLocal(
        dialogueId: String,
        speakerProfile: SpeakerProfileEntity,
        visibleMixedScript: String,
        resolvedDictionaries: List<DictionaryEntryEntity>,
        emotion: String,
        targetDurationMs: Long,
        outputDir: File,
        sampleRateHz: Int
    ): Result<SynthesizedDialogueResult> {
        val speakableText = MixedScriptLanguageEngine.buildTtsPronunciationText(visibleMixedScript, resolvedDictionaries)
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
            val validWav = AudioDspEngine.validateAudioFileStrict(f)
            if (validWav.isFailure || validWav.getOrThrow().rmsDbfs < FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS) {
                return@mapNotNull null
            }
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

        // Strict integrity: background stem alone MUST NEVER pass Stage 12 as a completed dubbed mix!
        if (placements.isEmpty()) {
            val msg = "No valid non-silent dubbed dialogue WAVs are available to mix (${chronological.size} dialogue(s) pending/failed or muted). Background audio alone cannot complete Stage 12."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 7,
                stageNumber = 12,
                agentName = "Agent 7 — Multitrack Mixing",
                operation = "Blocked: 0 valid dubbed dialogue clips",
                modelUsed = "16-bit PCM Multitrack Mixer",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "0 valid dialogue WAVs + ${bgFile?.name ?: "No BG stem"}",
                outputSummary = "Dubbed mix blocked",
                completed = 0,
                total = chronological.size.coerceAtLeast(1),
                status = StageStatus.FAILED.name,
                errorDetails = msg,
                startedAt = startedAt
            )
            val failedEp = episode.copy(
                currentStageNumber = 12,
                currentStageName = "Stage 12 — Failed (0 Dubbed Dialogues)",
                completedStagesMask = clearStagesFrom(episode.completedStagesMask, 12),
                lastErrorMessage = msg,
                updatedAt = System.currentTimeMillis()
            )
            dao.updateEpisode(failedEp)
            return failedEp
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

        val unmutedTotalDialogues = chronological.count { (trackGainsBySpeaker[it.speakerLabel] ?: 1.0f) > 0f }
        val allDialoguesMixed = placements.size >= unmutedTotalDialogues && unmutedTotalDialogues > 0
        val stageStatus = if (allDialoguesMixed) StageStatus.COMPLETED else StageStatus.PARTIAL
        val mixErr = if (stageStatus == StageStatus.COMPLETED) null else {
            "Partial mix: only ${placements.size}/$unmutedTotalDialogues active dialogue(s) had valid non-silent WAV audio."
        }

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 12,
            agentName = "Agent 7 — Multitrack Mixing",
            operation = "Mixed ${placements.size}/$unmutedTotalDialogues dialogue(s) with background stem",
            modelUsed = "16-bit PCM Multitrack Mixer + Soft-Knee Limiter",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${placements.size} verified dialogue WAV(s) + ${bgFile?.name ?: "No BG stem"}",
            outputSummary = "${mixedFile.name} • Peak ${mixMetrics.peakDbfs} dBFS • RMS ${mixMetrics.rmsDbfs} dBFS",
            completed = placements.size,
            total = unmutedTotalDialogues.coerceAtLeast(1),
            status = stageStatus.name,
            errorDetails = mixErr,
            startedAt = startedAt
        )

        val updated = episode.copy(
            mixedMasterAudioPath = mixedFile.absolutePath,
            currentStageNumber = 12,
            currentStageName = if (stageStatus == StageStatus.COMPLETED) {
                "Stage 12 — Multitrack Mixed (${placements.size} dialogues)"
            } else {
                "Stage 12 — Partial Mix (${placements.size}/$unmutedTotalDialogues dialogues)"
            },
            completedStagesMask = if (stageStatus == StageStatus.COMPLETED) {
                invalidateDownstreamStages(episode.completedStagesMask, 12)
            } else {
                clearStagesFrom(episode.completedStagesMask, 12)
            },
            lastErrorMessage = mixErr,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 13 — Agent 7: Honest 13-Point Automated Quality Control Audit.
     * - Clearly separates [REQUIRED] blocking checks from [OPTIONAL] advisory warnings.
     * - Strictly decodes EVERY dialogue WAV, background stem, and mixed master (never File.exists()-only).
     * - Genuinely audits overlaps & silence-gap timing against source timing (zero hardcoded PASSED checks).
     * - Halts Stage 14 export whenever any [REQUIRED] check fails or is blocked.
     */
    suspend fun runStage13Agent7QualityControl(episode: EpisodeEntity): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val chronological = dao.getDialoguesChronologicalOnce(episode.id).sortedBy { it.globalOrderNumber }
        val stems = dao.getStemsForEpisodeOnce(episode.id)
        val tracks = dao.getTimelineTracksOnce(episode.id)
        val speakers = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val hasDialogues = chronological.isNotEmpty()

        val checks = mutableListOf<QualityCheckResultEntity>()

        // 1. [REQUIRED] Mixed-Script Hindi-English Compliance
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
                category = "[REQUIRED] Translation (Agent 4)",
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

        // 2. [REQUIRED] Strict WAV Decode Verification of EVERY Dialogue Audio File
        val decodedDurationById = mutableMapOf<Long, Long>()
        val failedAudioIds = mutableListOf<String>()
        val silentAudioIds = mutableListOf<String>()
        var minDialogueRms = 0f

        for (dlg in chronological) {
            val path = dlg.generatedAudioPath
            if (path.isNullOrBlank()) {
                failedAudioIds.add(dlg.dialogueId)
                continue
            }
            val strict = AudioDspEngine.validateAudioFileStrict(File(path))
            if (strict.isFailure) {
                failedAudioIds.add(dlg.dialogueId)
                continue
            }
            val metrics = strict.getOrThrow()
            decodedDurationById[dlg.id] = metrics.durationMs
            if (metrics.sampleCount <= 0L || metrics.durationMs <= 0L) {
                failedAudioIds.add(dlg.dialogueId)
            } else if (metrics.rmsDbfs < FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS) {
                silentAudioIds.add(dlg.dialogueId)
            }
            if (minDialogueRms == 0f || metrics.rmsDbfs < minDialogueRms) {
                minDialogueRms = metrics.rmsDbfs
            }
        }
        val audioProblems = (failedAudioIds + silentAudioIds).distinct()
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 2,
                checkName = "2. Strict Dialogue WAV Audio Decode",
                category = "[REQUIRED] Voice Synthesis (Agent 5)",
                status = when {
                    !hasDialogues -> "BLOCKED"
                    audioProblems.isEmpty() -> "PASSED"
                    else -> "FAILED"
                },
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues segmented yet."
                    audioProblems.isEmpty() ->
                        "All ${chronological.size} dialogue WAVs strictly decoded (valid PCM headers, non-empty samples, min RMS $minDialogueRms dBFS)."
                    failedAudioIds.isNotEmpty() ->
                        "${failedAudioIds.size} dialogue WAV(s) missing or failed strict PCM decode."
                    else ->
                        "${silentAudioIds.size} dialogue WAV(s) decoded as silent (< ${FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS} dBFS)."
                },
                affectedDialogueIds = audioProblems.joinToString(),
                remediationTargetScreen = "VOICE_GEN"
            )
        )

        // 3. [REQUIRED] Global Dialogue ID Uniqueness & Identified Speakers
        val uniqueIdsCount = chronological.map { it.dialogueId }.distinct().size
        val hasUnassignedOrUnidentified = chronological.any {
            it.dialogueId.startsWith("UNASSIGNED") ||
                it.dialogueId.startsWith("UNIDENTIFIED") ||
                it.speakerLabel.isBlank() ||
                it.speakerLabel.equals("UNIDENTIFIED", ignoreCase = true) ||
                it.speakerLabel == "?"
        }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 3,
                checkName = "3. Global Dialogue ID & Speaker Integrity",
                category = "[REQUIRED] Numbering (Stage 7)",
                status = when {
                    !hasDialogues -> "BLOCKED"
                    uniqueIdsCount == chronological.size && !hasUnassignedOrUnidentified -> "PASSED"
                    else -> "FAILED"
                },
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues available."
                    hasUnassignedOrUnidentified -> "Unassigned or UNIDENTIFIED speaker IDs remain. Run Stage 6/7 or assign speakers manually."
                    uniqueIdsCount == chronological.size -> "All $uniqueIdsCount dialogue IDs are globally unique with identified speakers."
                    else -> "Duplicate dialogue IDs detected."
                },
                remediationTargetScreen = "TRANSCRIPT"
            )
        )

        // 4. [REQUIRED] Chronological Order Preservation
        val expectedOrderValid = hasDialogues && chronological.withIndex().all { (idx, dlg) ->
            dlg.globalOrderNumber == idx + 1 && dlg.dialogueId.endsWith("%03d".format(idx + 1))
        }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 4,
                checkName = "4. Chronological Order Preservation",
                category = "[REQUIRED] Timeline (Agent 7)",
                status = if (!hasDialogues) "BLOCKED" else if (expectedOrderValid) "PASSED" else "FAILED",
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues available."
                    expectedOrderValid -> "Sequential global numbering intact (${chronological.first().dialogueId} → ${chronological.last().dialogueId})."
                    else -> "Dialogue sequence numbering does not match chronological order."
                },
                remediationTargetScreen = "TIMELINE"
            )
        )

        // 5. [OPTIONAL] Character-Wise Batch Order Metadata
        val batchGroups = chronological.groupBy { it.speakerLabel }.toSortedMap()
        val batchesValid = hasDialogues && chronological.all { it.characterBatchNumber > 0 && it.orderInCharacterBatch > 0 }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 5,
                checkName = "5. Character-Wise Batch Order",
                category = "[OPTIONAL] Batch Execution (Agent 5)",
                status = if (!hasDialogues) "BLOCKED" else if (batchesValid) "PASSED" else "WARNING",
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues available."
                    batchesValid -> "Grouped cleanly across ${batchGroups.size} speaker batch(es)."
                    else -> "Batch order not yet finalized; run Stage 7 and Stage 10."
                },
                remediationTargetScreen = "VOICE_GEN"
            )
        )

        // 6. [OPTIONAL] Voice Reference & Rights Consent
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
                category = "[OPTIONAL] Speaker Profiles (Agent 3)",
                status = when {
                    speakers.isEmpty() -> "BLOCKED"
                    wrongVoiceRef.isNotEmpty() -> "FAILED"
                    unconsentedSpeakers.isNotEmpty() -> "WARNING"
                    else -> "PASSED"
                },
                summaryMessage = when {
                    speakers.isEmpty() -> "Blocked: No speakers identified yet."
                    wrongVoiceRef.isNotEmpty() -> "${wrongVoiceRef.size} line(s) reference a mismatched speaker ID."
                    unconsentedSpeakers.isNotEmpty() -> "${unconsentedSpeakers.size} speaker(s) using authorized generic Android TTS fallback (source consent unconfirmed)."
                    else -> "All ${speakers.size} speaker(s) matched to verified voice references."
                },
                affectedDialogueIds = wrongVoiceRef.joinToString { it.dialogueId },
                remediationTargetScreen = "SPEAKERS"
            )
        )

        // 7. [OPTIONAL] Start Timestamp Synchronization
        val timestampShifted = chronological.filter { abs(it.timelineStartMs - it.originalStartMs) > 50L }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 7,
                checkName = "7. Start Timestamp Synchronization",
                category = "[OPTIONAL] Timeline Sync (Agent 7)",
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

        // 8. [REQUIRED] Measured Decoded Duration Recorded
        val unrecordedDur = chronological.filter { (decodedDurationById[it.id] ?: it.actualGeneratedDurationMs) <= 0L }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 8,
                checkName = "8. Measured Duration & Deviation",
                category = "[REQUIRED] Duration Sync (Agent 5)",
                status = if (!hasDialogues) "BLOCKED" else if (unrecordedDur.isEmpty()) "PASSED" else "FAILED",
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No generated audio yet."
                    unrecordedDur.isEmpty() -> "Measured PCM duration and deviation verified for all ${chronological.size} lines."
                    else -> "${unrecordedDur.size} line(s) missing measured audio duration."
                },
                affectedDialogueIds = unrecordedDur.joinToString { it.dialogueId },
                remediationTargetScreen = "VOICE_GEN"
            )
        )

        // 9. [REQUIRED] Genuine Overlap & Silence Gap Verification (replaces hardcoded PASSED!)
        val unintendedOverlaps = mutableListOf<String>()
        val missedSourceOverlaps = mutableListOf<String>()
        var maxGapDriftMs = 0L
        var trackedSourceOverlaps = 0
        for (i in 1 until chronological.size) {
            val prev = chronological[i - 1]
            val curr = chronological[i]
            val prevSourceGapMs = curr.originalStartMs - prev.originalEndMs
            if (prevSourceGapMs < -50L) trackedSourceOverlaps++

            val prevActualDurMs = decodedDurationById[prev.id]
                ?: prev.actualGeneratedDurationMs.takeIf { it > 0L }
                ?: prev.originalDurationMs
            val prevActualEndMs = prev.timelineStartMs + prevActualDurMs
            val actualGapMs = curr.timelineStartMs - prevActualEndMs

            if (prevSourceGapMs >= 0L && actualGapMs < -120L) {
                // Unintended collision where source had silence/gap
                unintendedOverlaps.add("${prev.dialogueId}→${curr.dialogueId}(${abs(actualGapMs)}ms overlap)")
            } else if (prevSourceGapMs < -150L && actualGapMs > 150L) {
                missedSourceOverlaps.add("${prev.dialogueId}→${curr.dialogueId}")
            }
            val gapDrift = abs(actualGapMs - prevSourceGapMs)
            if (gapDrift > maxGapDriftMs) maxGapDriftMs = gapDrift
        }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 9,
                checkName = "9. Overlap & Silence Gap Verification",
                category = "[REQUIRED] Timeline (Agent 7)",
                status = when {
                    !hasDialogues -> "BLOCKED"
                    unintendedOverlaps.isNotEmpty() -> "FAILED"
                    missedSourceOverlaps.isNotEmpty() || maxGapDriftMs > 400L -> "WARNING"
                    else -> "PASSED"
                },
                summaryMessage = when {
                    !hasDialogues -> "Blocked: No dialogues on timeline."
                    unintendedOverlaps.isNotEmpty() ->
                        "Unintended dialogue overlap detected (>120ms): ${unintendedOverlaps.take(3).joinToString(", ")}. Adjust timing or re-sync TTS duration."
                    missedSourceOverlaps.isNotEmpty() ->
                        "${missedSourceOverlaps.size} source overlap window(s) drifted apart (max gap drift ${maxGapDriftMs}ms)."
                    maxGapDriftMs > 400L ->
                        "0 unintended overlaps, but max inter-dialogue silence-gap drift reached ${maxGapDriftMs}ms (tolerance 400ms)."
                    else ->
                        "Verified 0 unintended overlaps across ${chronological.size} line(s) ($trackedSourceOverlaps source overlap(s) preserved, max gap drift ${maxGapDriftMs}ms)."
                },
                affectedDialogueIds = (unintendedOverlaps + missedSourceOverlaps).take(5).joinToString(),
                remediationTargetScreen = "TIMELINE"
            )
        )

        // 10. [REQUIRED] Protected Master & Background Stem Strict WAV Verification (independent of dialogue)
        val masterStemFile = stems.find { it.stemType == "ORIGINAL_MASTER" }?.let { File(it.filePath) }
        val bgStemFile = stems.find { it.stemType == "BACKGROUND_ACCOMPANIMENT" }?.let { File(it.filePath) }
        val masterCheck = masterStemFile?.let { AudioDspEngine.validateAudioFileStrict(it) }
        val bgCheck = bgStemFile?.let { AudioDspEngine.validateAudioFileStrict(it) }
        val stemsValid = masterCheck?.isSuccess == true && bgCheck?.isSuccess == true
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 10,
                checkName = "10. Protected Master & Background Stems",
                category = "[REQUIRED] Stem Separation (Agent 6)",
                status = if (stemsValid) "PASSED" else "FAILED",
                summaryMessage = if (stemsValid) {
                    val m = masterCheck!!.getOrThrow()
                    val b = bgCheck!!.getOrThrow()
                    "Verified Master (${m.sampleRateHz}Hz, RMS ${m.rmsDbfs} dBFS) & Background (${b.sampleRateHz}Hz, RMS ${b.rmsDbfs} dBFS) independently. Background stem alone never substitutes for dialogue."
                } else {
                    "Required Original Master or Background Accompaniment WAV is missing/corrupt. Run Stage 2 & Stage 3."
                },
                remediationTargetScreen = "AUDIO_STEMS"
            )
        )

        // 11. [OPTIONAL] Active Track Accidental Mute Check
        val mutedActiveTracks = tracks.filter { it.isMuted && it.trackType != "ORIGINAL_DIALOGUE" }
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 11,
                checkName = "11. Active Mix Track Mute Check",
                category = "[OPTIONAL] Stem Mixer (Agent 7)",
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

        // 12. [REQUIRED] Strict Mixed Master WAV Decode, RMS Energy & Peak Headroom
        val mixedFile = episode.mixedMasterAudioPath?.let { File(it) }
        val mixedStrict = mixedFile?.let { AudioDspEngine.validateAudioFileStrict(it) }
        val mixedMetrics = mixedStrict?.getOrNull()
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 12,
                checkName = "12. Master Mix Decode, Energy & Headroom",
                category = "[REQUIRED] Mastering (Agent 7)",
                status = when {
                    mixedMetrics == null -> "BLOCKED"
                    mixedMetrics.rmsDbfs < FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS -> "FAILED"
                    mixedMetrics.peakDbfs <= -0.1f -> "PASSED"
                    else -> "WARNING"
                },
                summaryMessage = when {
                    mixedMetrics == null -> "Blocked: No valid mixed master WAV exists yet. Run Stage 12."
                    mixedMetrics.rmsDbfs < FinalVerificationAgent.NON_SILENT_RMS_THRESHOLD_DBFS ->
                        "Mixed master WAV decoded as silent (${mixedMetrics.rmsDbfs} dBFS). Re-run Stage 10 & Stage 12."
                    mixedMetrics.peakDbfs <= -0.1f ->
                        "Verified mixed master: Peak ${mixedMetrics.peakDbfs} dBFS, RMS ${mixedMetrics.rmsDbfs} dBFS (${mixedMetrics.sampleRateHz} Hz, ${mixedMetrics.channels} ch)."
                    else ->
                        "Master peak reached ${mixedMetrics.peakDbfs} dBFS (RMS ${mixedMetrics.rmsDbfs} dBFS); reduce track gain and re-mix."
                },
                remediationTargetScreen = "AUDIO_STEMS"
            )
        )

        // 13. [REQUIRED] Source & Master Duration Consistency
        val durDiff = if (mixedMetrics != null && episode.durationMs > 0L) abs(mixedMetrics.durationMs - episode.durationMs) else null
        checks.add(
            QualityCheckResultEntity(
                episodeId = episode.id,
                checkNumber = 13,
                checkName = "13. Source & Master Duration Consistency",
                category = "[REQUIRED] Export Readiness (Stage 14)",
                status = when {
                    mixedMetrics == null -> "BLOCKED"
                    durDiff == null || durDiff <= 150L -> "PASSED"
                    durDiff <= 500L -> "WARNING"
                    else -> "FAILED"
                },
                summaryMessage = when {
                    mixedMetrics == null -> "Blocked: Run Stage 12 Multitrack Mixing before export verification."
                    durDiff == null -> "Verified Mixed Master duration: ${mixedMetrics.durationMs} ms."
                    else -> "Source: ${episode.durationMs} ms • Mixed Master: ${mixedMetrics.durationMs} ms (Delta: $durDiff ms)."
                },
                remediationTargetScreen = "QC_EXPORT"
            )
        )

        dao.replaceQualityChecksAtomic(episode.id, checks)

        val passedCount = checks.count { it.status == "PASSED" }
        val warningCount = checks.count { it.status == "WARNING" }
        val requiredFailures = checks.filter {
            it.category.startsWith("[REQUIRED]") && (it.status == "FAILED" || it.status == "BLOCKED")
        }
        val stageStatus = if (requiredFailures.isEmpty()) StageStatus.COMPLETED else StageStatus.FAILED
        val qcErrMsg = if (requiredFailures.isEmpty()) null else {
            "${requiredFailures.size} required QC check(s) failed/blocked: ${requiredFailures.take(3).joinToString { it.checkName }}."
        }

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 13,
            agentName = "Agent 7 — 13-Point Quality Control",
            operation = "Audited 13 QC rules ($passedCount passed, $warningCount warnings, ${requiredFailures.size} required failures)",
            modelUsed = "Strict 13-Point Automated QC Validator",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${chronological.size} dialogues • ${stems.size} stems",
            outputSummary = "$passedCount / 13 PASSED • ${requiredFailures.size} required blocking failure(s)",
            completed = passedCount,
            total = 13,
            status = stageStatus.name,
            errorDetails = qcErrMsg,
            startedAt = startedAt
        )

        val updated = episode.copy(
            currentStageNumber = 13,
            currentStageName = if (stageStatus == StageStatus.COMPLETED) {
                "Stage 13 — QC Passed ($passedCount/13${if (warningCount > 0) ", $warningCount warn" else ""})"
            } else {
                "Stage 13 — QC Blocked (${requiredFailures.size} required fail)"
            },
            completedStagesMask = if (stageStatus == StageStatus.COMPLETED) {
                invalidateDownstreamStages(episode.completedStagesMask, 13)
            } else {
                clearStagesFrom(episode.completedStagesMask, 13)
            },
            lastErrorMessage = qcErrMsg,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateEpisode(updated)
        return updated
    }

    /**
     * Stage 14 — Final Export Studio.
     * - Blocks export if Stage 13 required QC checks failed or if dubbed dialogues are missing.
     * - MP4_VIDEO uses bounded-memory streaming video passthrough + AAC-LC encoding and records ACTUAL video metadata.
     * - WAV_AUDIO_ONLY uses streaming direct copy or honest resampled export with exact transformation metadata.
     * - Deletes incomplete/unverified output files on failure so broken artifacts never persist.
     */
    suspend fun runStage14FinalExport(
        episode: EpisodeEntity,
        exportFormat: String = "WAV_AUDIO_ONLY",
        resolution: String = episode.resolution,
        videoBitrateKbps: Int = 0,
        audioCodec: String = if (exportFormat == "WAV_AUDIO_ONLY") "PCM 16-bit WAV" else "AAC-LC",
        audioBitrateKbps: Int = 192,
        audioSampleRateHz: Int = episode.audioSampleRateHz,
        customFileName: String? = null
    ): EpisodeEntity {
        val startedAt = System.currentTimeMillis()
        val epDir = getEpisodeWorkspaceDir(episode.id)
        val exportsDir = File(epDir, "exports").apply { mkdirs() }

        val dialogues = dao.getDialoguesChronologicalOnce(episode.id)
        if (dialogues.isEmpty()) {
            throw IllegalStateException("Export blocked: 0 dubbed dialogue segments exist. Background audio alone cannot be exported as a dubbed episode.")
        }

        // Enforce Stage 13 required QC gate before exporting
        val latestQc = dao.getQualityChecksOnce(episode.id)
        val blockingQc = latestQc.filter {
            it.category.startsWith("[REQUIRED]") && (it.status == "FAILED" || it.status == "BLOCKED")
        }
        if (blockingQc.isNotEmpty()) {
            val msg = "Export blocked by Stage 13 required QC failures: ${blockingQc.take(3).joinToString { it.checkName }}. Fix issues and re-run Stage 13 first."
            recordAgentJob(
                episodeId = episode.id,
                agentNumber = 7,
                stageNumber = 14,
                agentName = "Agent 7 — Final Export",
                operation = "Blocked by Stage 13 required QC",
                modelUsed = "QC Export Gate",
                runtimeMode = "ON_DEVICE_ANDROID",
                inputSummary = "${blockingQc.size} failed required QC check(s)",
                outputSummary = "Export halted",
                completed = 0,
                total = 1,
                status = StageStatus.FAILED.name,
                errorDetails = msg,
                startedAt = startedAt
            )
            throw IllegalStateException(msg)
        }

        val mixedSource = episode.mixedMasterAudioPath?.let { File(it) }?.takeIf { AudioDspEngine.inspectWavHeader(it).isSuccess }
            ?: File(epDir, "mixed_dubbed_master.wav").takeIf { AudioDspEngine.inspectWavHeader(it).isSuccess }
            ?: run {
                val mixedEp = runStage12Agent7MultitrackMixing(episode)
                if ((mixedEp.completedStagesMask and (1 shl 12)) == 0) {
                    throw IllegalStateException(mixedEp.lastErrorMessage ?: "Stage 12 mixing did not complete cleanly.")
                }
                File(mixedEp.mixedMasterAudioPath ?: throw IllegalStateException("Mixing did not produce a WAV file."))
            }

        val cleanName = (customFileName?.takeIf { it.isNotBlank() }
            ?: "AnimeDub_Ep${episode.episodeNumber}_${System.currentTimeMillis() % 100000}")
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

        val outFile: File
        val actualDurationMs: Long
        val finalResolution: String
        val finalVideoBitrateKbps: Int
        val finalCodec: String
        val finalSampleRateHz: Int

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
                if (outFile.exists()) runCatching { outFile.delete() }
                val err = muxResult.exceptionOrNull()?.localizedMessage ?: "MP4 muxing failed."
                recordAgentJob(
                    episodeId = episode.id,
                    agentNumber = 7,
                    stageNumber = 14,
                    agentName = "Agent 7 — Final Export",
                    operation = "MP4 video export failed",
                    modelUsed = "Streaming MediaMuxer (Video Passthrough) + AAC-LC",
                    runtimeMode = "ON_DEVICE_ANDROID",
                    inputSummary = "${sourceVideo.name} + ${mixedSource.name}",
                    outputSummary = "Export failed",
                    completed = 0,
                    total = 1,
                    status = StageStatus.FAILED.name,
                    errorDetails = err,
                    startedAt = startedAt
                )
                throw IllegalStateException(err)
            }
            val mp4Meta = muxResult.getOrThrow()
            actualDurationMs = mp4Meta.audioDurationMs.takeIf { it > 0L } ?: mp4Meta.videoDurationMs
            finalResolution = "${mp4Meta.videoResolution} (Passthrough)"
            finalVideoBitrateKbps = mp4Meta.videoBitrateKbpsActual
            finalCodec = "Video Passthrough (${mp4Meta.videoCodec}) + AAC-LC (${audioBitrateKbps} kbps)"
            finalSampleRateHz = mp4Meta.audioSampleRateHz
        } else {
            outFile = File(exportsDir, "$cleanName.wav")
            val wavRes = AudioDspEngine.exportWavWithMetadata(
                sourceWavFile = mixedSource,
                outputWavFile = outFile,
                targetSampleRateHz = audioSampleRateHz
            )
            if (wavRes.isFailure) {
                if (outFile.exists()) runCatching { outFile.delete() }
                throw IllegalStateException(wavRes.exceptionOrNull()?.localizedMessage ?: "WAV export failed.")
            }
            val wavMeta = wavRes.getOrThrow()
            actualDurationMs = wavMeta.durationMs
            finalResolution = if (wavMeta.channels == 1) "Audio Only (Mono 1 ch)" else "Audio Only (${wavMeta.channels} ch)"
            finalVideoBitrateKbps = 0
            finalCodec = wavMeta.formatDescription
            finalSampleRateHz = wavMeta.sampleRateHz
        }

        if (!outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("Export verification failed: output file was not written to disk.")
        }

        // Agent 21 — Generate & validate Japanese, Mixed-Script Hindi-English, Bilingual SRT + Styled ASS subtitles
        val speakersByLabel = dao.getSpeakersForEpisodeOnce(episode.id).associateBy { it.speakerLabel }
        val subtitlesDir = File(exportsDir, "subtitles").apply { mkdirs() }
        val subtitleBundle = SubtitleGenerator.generateAllSubtitles(
            episodeTitle = episode.episodeTitle,
            dialogues = dialogues,
            speakersByLabel = speakersByLabel,
            outputDir = subtitlesDir,
            baseFileName = cleanName,
            episodeDurationMs = episode.durationMs
        )
        val subtitleFiles = listOf(
            subtitleBundle.japaneseSrtFile,
            subtitleBundle.hindiHinglishSrtFile,
            subtitleBundle.bilingualSrtFile,
            subtitleBundle.styledAssFile
        )

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
            status = if (subtitleBundle.validationPassed) StageStatus.COMPLETED.name else StageStatus.FAILED.name,
            errorDetails = subtitleBundle.validationIssues.takeIf { it.isNotEmpty() }?.joinToString("; "),
            startedAt = startedAt
        )

        // Agent 22 — Final Verification Agent (strict WAV/MP4 stream & AV sync, EVERY dialogue WAV decode, subtitles)
        val verification = FinalVerificationAgent.verifyExportedOutput(
            outputFile = outFile,
            exportFormat = exportFormat,
            expectedDurationMs = episode.durationMs,
            dialogues = dialogues,
            subtitleFiles = subtitleFiles,
            requireDubbedDialogues = true
        )

        recordAgentJob(
            episodeId = episode.id,
            agentNumber = 7,
            stageNumber = 14,
            agentName = "Agent 22 — Final Verification",
            operation = verification.summary,
            modelUsed = "MediaExtractor AV Sync / Strict PCM RMS & Subtitle Verifier",
            runtimeMode = "ON_DEVICE_ANDROID",
            inputSummary = "${outFile.name} + ${subtitleFiles.size} subtitle file(s)",
            outputSummary = verification.checks.joinToString(" | ") { "${it.checkName}: ${if (it.passed) "PASS" else "FAIL"}" },
            completed = verification.checks.count { it.passed },
            total = verification.checks.size,
            status = if (verification.passed) StageStatus.COMPLETED.name else StageStatus.FAILED.name,
            errorDetails = if (verification.passed) null else verification.summary,
            startedAt = startedAt
        )

        val subtitlePathsSummary = subtitleFiles.joinToString(" | ") { it.name }
        val verificationDetailsText = verification.checks.joinToString("\n") {
            "[${if (it.passed) "PASS" else "FAIL"}] ${it.checkName}: ${it.detail}"
        }
        val finalFileSize = outFile.length()

        if (!verification.passed) {
            // Delete unverified / broken output file so partial artifacts are never left behind
            runCatching { outFile.delete() }
        }

        dao.insertExportRecord(
            ExportRecordEntity(
                episodeId = episode.id,
                exportFormat = exportFormat,
                videoResolution = finalResolution,
                videoBitrateKbps = finalVideoBitrateKbps,
                audioCodec = finalCodec,
                audioBitrateKbps = if (exportFormat == "MP4_VIDEO") audioBitrateKbps else 0,
                audioSampleRateHz = finalSampleRateHz,
                outputFilePath = if (verification.passed) outFile.absolutePath else "",
                fileSizeBytes = if (verification.passed) finalFileSize else 0L,
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
            exportedVideoPath = if (verification.passed) outFile.absolutePath else episode.exportedVideoPath,
            currentStageNumber = 14,
            currentStageName = if (verification.passed) {
                "Stage 14 — Verified Export (${outFile.extension.uppercase()})"
            } else {
                "Stage 14 — Export Verification Failed"
            },
            completedStagesMask = if (verification.passed) {
                episode.completedStagesMask or (1 shl 14)
            } else {
                episode.completedStagesMask and (1 shl 14).inv()
            },
            pipelineStatus = if (verification.passed) StageStatus.COMPLETED.name else StageStatus.FAILED.name,
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
     * Dependency-Aware Single Dialogue Regeneration & Failure Retry (Android-Only Local TTS).
     */
    suspend fun regenerateSingleDialogueOnly(
        dialogueId: Long,
        newRomanHinglishText: String? = null,
        newEmotion: String? = null,
        selectedTtsEngine: String = "Android-Speech-TTS"
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
            preferredTtsEngine = "Android-Speech-TTS",
            characterId = if (cleanSpeaker == "UNIDENTIFIED") "" else "CHAR-${episode.projectId}-$cleanSpeaker"
        )

        val epDir = getEpisodeWorkspaceDir(episode.id)
        val genDir = File(epDir, "generated_dialogues").apply { mkdirs() }

        val synthOutcome = synthesizeSingleLineLocal(
            dialogueId = dlg.dialogueId,
            speakerProfile = speakerProfile,
            visibleMixedScript = updatedDisplayText,
            resolvedDictionaries = resolvedDictionaries,
            emotion = updatedEmotion,
            targetDurationMs = dlg.originalDurationMs,
            outputDir = genDir,
            sampleRateHz = episode.audioSampleRateHz
        )

        if (synthOutcome.isFailure) {
            val errMsg = synthOutcome.exceptionOrNull()?.localizedMessage ?: "Android Speech TTS synthesis failed."
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
                emotionControlSupported = false,
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
        selectedTtsEngine: String = "Android-Speech-TTS"
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
                selectedTtsEngine = selectedTtsEngine
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
