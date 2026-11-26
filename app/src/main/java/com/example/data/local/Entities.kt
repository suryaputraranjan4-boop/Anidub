package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val animeSeriesName: String,
    val sourceLanguage: String = "ja-JP (Japanese)",
    val targetLanguage: String = "hi-Latn (Roman Hinglish)",
    val masterSampleRateHz: Int = 24000,
    val preferredTtsEngine: String = "F5-Hindi-24KHz",
    val preferredSeparationModel: String = "HTDemucs",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val episodeNumber: Int,
    val episodeTitle: String,
    val videoFileName: String,
    val durationMs: Long,
    val resolution: String,
    val frameRateFps: Float,
    val fileSizeBytes: Long,
    val audioCodec: String,
    val audioSampleRateHz: Int,
    val audioChannels: Int,
    val originalVideoPath: String,
    val originalMasterAudioPath: String,
    val currentStageNumber: Int = 1, // 1..14
    val currentStageName: String = "Stage 1 — Video Uploaded",
    val completedStagesMask: Int = 1, // Bitmask of completed stages 1..14 for checkpoint resume
    val pipelineStatus: String = "READY", // READY, RUNNING, PAUSED_CHECKPOINT, COMPLETED, ERROR
    val lastErrorMessage: String? = null,
    val mixedMasterAudioPath: String? = null,
    val exportedVideoPath: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "audio_stems")
data class AudioStemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val stemType: String, // ORIGINAL_MASTER, DIALOGUE_VOCAL, BACKGROUND_ACCOMPANIMENT, BGM_MUSIC, SFX_AMBIENCE
    val displayName: String,
    val separatorModelUsed: String,
    val filePath: String,
    val sampleRateHz: Int,
    val channels: Int,
    val durationMs: Long,
    val codec: String = "PCM_16BIT_WAV",
    val volumeGain: Float = 1.0f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val separationQualityScore: Float = -1f, // -1f = unmeasured (never invent SDR/SIR)
    val peakDbfs: Float = -96.0f,
    val isHonestTwoStemModel: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "transcript_segments")
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val segmentIndex: Int,
    val japaneseText: String, // Original Japanese transcript — NEVER overwritten by translation
    val romajiReading: String,
    val preliminaryStartMs: Long,
    val preliminaryEndMs: Long,
    val confidenceScore: Float = -1f, // -1f = unmeasured / manual user entry
    val asrModelUsed: String = "Unrecognized",
    val originalAudioSlicePath: String
)

@Entity(tableName = "dialogue_segments")
data class DialogueSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val globalOrderNumber: Int, // 1, 2, 3, 4, 5, 6, 7 (One global sequential counter!)
    val dialogueId: String, // A-001, B-002, C-003, A-004, B-005, A-006, C-007
    val speakerLabel: String, // A, B, C, D...
    val originalStartMs: Long,
    val originalEndMs: Long,
    val originalDurationMs: Long,
    val transcriptSegmentId: Long,
    val japaneseText: String, // Preserved Japanese source dialogue
    val romanHinglishText: String, // Natural Roman Hinglish translation (Never Devanagari)
    val ttsNormalizedText: String, // Internal pronunciation-optimized phonetic string for TTS
    val emotion: String = "NEUTRAL", // ANGRY, SAD, HAPPY, EXCITED, FEARFUL, CALM, WHISPERING, SHOUTING, NEUTRAL
    val speakingStyle: String = "Natural",
    val alignmentSource: String = "Unaligned",
    val overlapsWithDialogueId: String? = null,
    val originalAudioSlicePath: String = "",
    val generatedAudioPath: String? = null,
    val previousGeneratedAudioPath: String? = null,
    val actualGeneratedDurationMs: Long = 0L,
    val durationDeviationMs: Long = 0L,
    val timeStretchRatio: Float = 1.0f,
    val voiceReferenceId: String = "",
    val ttsEngineUsed: String = "",
    val characterBatchNumber: Int = 0, // Batch 1 (A), Batch 2 (B), Batch 3 (C)
    val orderInCharacterBatch: Int = 0, // 1st in batch, 2nd in batch...
    val generationStatus: String = "PENDING", // PENDING, TRANSLATED, GENERATED, SYNCED, FAILED
    val timelineStartMs: Long = 0L, // Must equal originalStartMs unless manually adjusted
    val peakDbfs: Float = -96.0f,
    val translationVersion: Int = 1,
    val generationVersion: Int = 0,
    val errorNote: String? = null,
    val characterId: String = "",
    val emotionConfidence: Float = -1f, // -1f = unmeasured
    val speakerConfidence: Float = -1f, // -1f = unmeasured
    val translationConfidence: Float = -1f, // -1f = unmeasured
    val sceneContextNote: String = "",
    val syncStatus: String = "PENDING", // PENDING, ALIGNED, TIME_STRETCHED, NEEDS_ADAPTATION
    val verificationStatus: String = "UNVERIFIED"
)

@Entity(tableName = "speaker_profiles")
data class SpeakerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val episodeId: Long,
    val speakerLabel: String, // A, B, C, D (Assigned by first chronological occurrence)
    val characterName: String, // e.g., "Character 1" -> user renames to "Satoru", "Ryomen", "Megumi"
    val f0MinHz: Float,
    val f0MaxHz: Float,
    val f0MeanHz: Float,
    val f0StdHz: Float,
    val spectralCentroidHz: Float,
    val timbreDescriptor: String,
    val speakingSpeedSylPerSec: Float,
    val voiceTexture: String,
    val speakingStyle: String,
    val dominantEmotion: String,
    val speakerEmbeddingJson: String, // 16-dimensional acoustic speaker embedding vector
    val voiceSimilarityScore: Float = -1f, // -1f = unmeasured (single sample or uncompared)
    val voiceReferencePath: String,
    val voiceReferenceId: String,
    val rightsConsentConfirmed: Boolean = false, // Strict default: FALSE until explicitly confirmed by user
    val useConsentedGenericVoiceFallback: Boolean = true,
    val preferredTtsEngine: String = "F5-Hindi-24KHz",
    val dialogueCount: Int = 0,
    val voiceConsistencyScore: Float = -1f, // -1f = unmeasured
    val characterId: String = "",
    val visualIdentityNote: String = "Audio-Only Mode (No visual recognition)",
    val profileConfidence: Float = -1f // -1f = unmeasured
)

@Entity(tableName = "dictionary_entries")
data class DictionaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dictionaryLevel: String, // GLOBAL, ANIME, PROJECT
    val projectId: Long? = null,
    val animeSeriesName: String? = null,
    val japaneseSourceTerm: String,
    val preferredRomanHinglish: String,
    val preferredHindiWording: String,
    val preferredEnglishWording: String,
    val preserveOriginalEnglish: Boolean = true,
    val pronunciationGuidance: String,
    val contextNotes: String,
    val replacementScope: String = "GLOBAL", // GLOBAL, ANIME, PROJECT, EPISODE, DIALOGUE
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "voice_generation_records")
data class VoiceGenerationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val dialogueId: String,
    val speakerLabel: String,
    val characterBatchNumber: Int,
    val orderInCharacterBatch: Int,
    val ttsEngine: String,
    val modelVersion: String,
    val voiceReferenceId: String,
    val visibleRomanHinglish: String,
    val internalPhoneticText: String,
    val emotionRequested: String,
    val emotionControlSupported: Boolean,
    val targetDurationMs: Long,
    val actualDurationMs: Long,
    val timeStretchRatio: Float,
    val outputFilePath: String,
    val sampleRateHz: Int = 24000,
    val status: String, // SUCCESS, RETRIED, FAILED
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "timeline_tracks")
data class TimelineTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val trackIndex: Int,
    val trackType: String, // CHARACTER, BGM_MUSIC, SFX_AMBIENCE, ORIGINAL_DIALOGUE
    val speakerLabel: String? = null,
    val trackTitle: String,
    val colorHex: String,
    val volumeGain: Float = 1.0f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false
)

@Entity(tableName = "quality_check_results")
data class QualityCheckResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val checkNumber: Int, // 1..13 mandatory checks
    val checkName: String,
    val category: String,
    val status: String, // PASSED, WARNING, FAILED
    val summaryMessage: String,
    val affectedDialogueIds: String = "",
    val remediationTargetScreen: String = "Timeline",
    val checkedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "model_manifests")
data class ModelManifestEntity(
    @PrimaryKey val modelId: String,
    val modelName: String,
    val modelVersion: String = "",
    val specialistAgentNumber: Int, // 1..7
    val category: String,
    val purpose: String,
    val sourceUrl: String,
    val fileFormat: String = "",
    val supportedLanguages: String = "",
    val actualFilesNeededJson: String,
    val filesManifestJson: String = "[]", // Structured JSON array of ModelArtifactFile entries
    val downloadArtifactUrl: String, // Primary official Hugging Face file URL
    val expectedSizeBytes: Long, // Exact combined size of all required files, or 0L when isSizeKnown == false
    val isSizeKnown: Boolean = true, // False for gated models until authenticated HEAD probe resolves Content-Length
    val licenseName: String,
    val licenseNotes: String,
    val runtimeCompatibility: String, // ANDROID_LOCAL, ONNX_RUNTIME_COMPATIBLE, REMOTE_PYTHON_GPU_WORKER, EXTERNAL_API
    val compatibilitySummary: String = "",
    val requiredDependencies: String,
    val isGatedHuggingFace: Boolean = false,
    val isExperimentalArchived: Boolean = false,
    val downloadState: String = "NOT_DOWNLOADED", // NOT_DOWNLOADED, DOWNLOADING, VERIFYING, INSTALLED, CORRUPTED, FAILED, BUILT_IN
    val downloadProgressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val verifiedFilesCount: Int = 0,
    val totalFilesCount: Int = 0,
    val installedVersion: String = "",
    val localFilePath: String = "", // Directory containing actual official filenames
    val sha256Checksum: String = "", // Verified SHA-256 of primary weight file
    val statusNote: String = "",
    val verificationLevel: String = "UNVERIFIED" // VERIFIED_SHA256, VERIFIED_SIZE_ONLY, UNVERIFIED, BUILT_IN
)

@Entity(tableName = "agent_job_records")
data class AgentJobRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val agentNumber: Int, // 1..7 (or 0 for Pipeline Coordinator stage)
    val stageNumber: Int, // 1..14
    val agentName: String,
    val currentOperation: String,
    val modelBeingUsed: String,
    val runtimeMode: String, // ON_DEVICE_ANDROID, FIREBASE_AI_GEMINI, REMOTE_GPU_WORKER
    val inputSummary: String,
    val outputSummary: String,
    val completedItems: Int,
    val totalItems: Int,
    val status: String, // IDLE, RUNNING, COMPLETED, FAILED, SKIPPED_CACHED
    val errorDetails: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "export_records")
data class ExportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val exportFormat: String, // MP4_VIDEO, WAV_AUDIO_ONLY
    val videoResolution: String,
    val videoBitrateKbps: Int,
    val audioCodec: String,
    val audioBitrateKbps: Int,
    val audioSampleRateHz: Int,
    val outputFilePath: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val status: String,
    val exportedAt: Long = System.currentTimeMillis(),
    val subtitlePathsSummary: String = "",
    val verificationReport: String = ""
)
