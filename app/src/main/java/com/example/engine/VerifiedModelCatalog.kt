package com.example.engine

import com.example.data.local.ModelManifestEntity

/**
 * Represents a single verified downloadable file belonging to an AI model package
 * (primary weights, tokenizer, config, vocabulary, pipeline YAML, or license).
 */
data class ModelArtifactFile(
    val filename: String,
    val relativePath: String,
    val downloadUrl: String,
    val expectedBytes: Long, // Exact bytes from Hugging Face API, or -1L when gated & unprobed
    val expectedSha256: String = "",
    val role: String = "WEIGHTS", // WEIGHTS, TOKENIZER, CONFIG, VOCAB, PIPELINE, LICENSE
    val isGatedFile: Boolean = false
)

/**
 * Real-time on-disk inspection status for a single required file in a model's directory.
 */
data class ModelArtifactDiskStatus(
    val artifact: ModelArtifactFile,
    val actualBytesOnDisk: Long,
    val partialBytesOnDisk: Long,
    val isPresentAndSized: Boolean,
    val localPath: String
)

/**
 * Verified official AI model catalog & structured multi-file manifest serializer.
 *
 * Audit Policy:
 * - Zero README.md or config-only fake download entries.
 * - Zero fake KB sizes for weight models.
 * - Exact official byte sizes & LFS SHA-256 hashes sourced from Hugging Face API.
 * - Gated models (pyannote/speaker-diarization-3.1 & segmentation-3.0) strictly report
 *   isSizeKnown = false and expectedSizeBytes = 0L until probed with a valid HF token.
 * - Single primary Hindi TTS model selected (SPRINGLab/F5-Hindi-24KHz, 632.17 MB, CC-BY-4.0,
 *   native 24 kHz) rather than duplicating Chatterbox-Multilingual-hi (3.21 GB) in downloads.
 */
object VerifiedModelCatalog {

    const val RUNTIME_ANDROID_LOCAL = "ANDROID_LOCAL"
    const val RUNTIME_ONNX_COMPATIBLE = "ONNX_RUNTIME_COMPATIBLE"
    const val RUNTIME_REMOTE_GPU_WORKER = "REMOTE_PYTHON_GPU_WORKER"
    const val RUNTIME_EXTERNAL_API = "EXTERNAL_API"

    fun isLocalOrBuiltIn(runtimeCompatibility: String): Boolean {
        return runtimeCompatibility in setOf(RUNTIME_ANDROID_LOCAL, RUNTIME_ONNX_COMPATIBLE)
    }

    fun isDownloadablePackage(model: ModelManifestEntity): Boolean {
        return model.runtimeCompatibility !in setOf(RUNTIME_ANDROID_LOCAL, RUNTIME_EXTERNAL_API) &&
            parseManifest(model.filesManifestJson).isNotEmpty()
    }

    fun providerForModel(modelId: String): String = when (modelId) {
        "whisper_large_v3_turbo" -> "OpenAI (Hugging Face)"
        "htdemucs_vocal_sep" -> "Alexandre Défossez / Demucs"
        "silero_vad_onnx" -> "Silero Team / ONNX Community"
        "f5_tts_hindi_24khz" -> "SPRINGLab (IIT Madras)"
        "pyannote_diarization_3_1" -> "pyannoteAI (Gated HF)"
        "wespeaker_voxceleb_resnet34_lm" -> "WeSpeaker / pyannoteAI"
        "android_local_acoustic_dsp" -> "Built-in Android Kotlin DSP (Non-Neural)"
        "android_local_media_mixer" -> "Android MediaCodec / MediaMuxer"
        "firebase_ai_gemini_3_8_flash" -> "Google / Firebase AI Logic"
        else -> "Verified Open Source"
    }

    fun runtimeBadgeLabel(runtimeCompatibility: String): String = when (runtimeCompatibility) {
        RUNTIME_ANDROID_LOCAL -> "Android Local Built-In"
        RUNTIME_ONNX_COMPATIBLE -> "On-Device ONNX Runtime Mobile"
        RUNTIME_REMOTE_GPU_WORKER -> "Remote GPU Worker Only (Not Local)"
        RUNTIME_EXTERNAL_API -> "Firebase AI Logic Cloud API"
        else -> runtimeCompatibility.replace('_', ' ')
    }

    /**
     * Pure-Kotlin JSON serializer for ModelArtifactFile lists (safe on both Android & JVM unit tests).
     */
    fun serializeManifest(files: List<ModelArtifactFile>): String {
        if (files.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        files.forEachIndexed { idx, f ->
            if (idx > 0) sb.append(',')
            sb.append('{')
            sb.append("\"filename\":\"").append(escapeJson(f.filename)).append("\",")
            sb.append("\"relativePath\":\"").append(escapeJson(f.relativePath)).append("\",")
            sb.append("\"downloadUrl\":\"").append(escapeJson(f.downloadUrl)).append("\",")
            sb.append("\"expectedBytes\":").append(f.expectedBytes).append(',')
            sb.append("\"expectedSha256\":\"").append(escapeJson(f.expectedSha256)).append("\",")
            sb.append("\"role\":\"").append(escapeJson(f.role)).append("\",")
            sb.append("\"isGatedFile\":").append(f.isGatedFile)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Pure-Kotlin JSON parser for ModelArtifactFile lists (safe on both Android & JVM unit tests).
     */
    fun parseManifest(json: String): List<ModelArtifactFile> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]" || !trimmed.startsWith("[")) return emptyList()
        val results = mutableListOf<ModelArtifactFile>()
        val objRegex = Regex("\\{[^{}]*\\}")
        for (match in objRegex.findAll(trimmed)) {
            val obj = match.value
            val filename = extractJsonString(obj, "filename")
            val relativePath = extractJsonString(obj, "relativePath")
            val downloadUrl = extractJsonString(obj, "downloadUrl")
            if (filename.isEmpty() || downloadUrl.isEmpty()) continue
            val expectedBytes = extractJsonLong(obj, "expectedBytes", -1L)
            val expectedSha256 = extractJsonString(obj, "expectedSha256")
            val role = extractJsonString(obj, "role").ifEmpty { "WEIGHTS" }
            val isGated = extractJsonBoolean(obj, "isGatedFile", false)
            results.add(
                ModelArtifactFile(
                    filename = filename,
                    relativePath = relativePath.ifEmpty { filename },
                    downloadUrl = downloadUrl,
                    expectedBytes = expectedBytes,
                    expectedSha256 = expectedSha256,
                    role = role,
                    isGatedFile = isGated
                )
            )
        }
        return results
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun unescapeJson(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
    }

    private fun extractJsonString(obj: String, key: String): String {
        val regex = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val raw = regex.find(obj)?.groupValues?.getOrNull(1) ?: return ""
        return unescapeJson(raw)
    }

    private fun extractJsonLong(obj: String, key: String, default: Long): Long {
        val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return regex.find(obj)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: default
    }

    private fun extractJsonBoolean(obj: String, key: String, default: Boolean): Boolean {
        val regex = Regex("\"$key\"\\s*:\\s*(true|false)")
        return regex.find(obj)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull() ?: default
    }

    /**
     * Verified official model catalog with exact Hugging Face metadata, companion files,
     * SHA-256 checksums, licenses, and honest runtime/hardware compatibility.
     */
    fun buildVerifiedModelCatalog(): List<ModelManifestEntity> {
        // A. Whisper Large V3 Turbo (openai/whisper-large-v3-turbo) — 11 required files, 1,622,443,339 bytes (1.62 GB)
        val whisperFiles = listOf(
            ModelArtifactFile(
                filename = "model.safetensors",
                relativePath = "model.safetensors",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/model.safetensors",
                expectedBytes = 1_617_824_864L,
                expectedSha256 = "542566a422ae4f3fd23f1ba11add198fca01bbf82e66e6a2857b3f608b1eb9d1",
                role = "WEIGHTS"
            ),
            ModelArtifactFile(
                filename = "config.json",
                relativePath = "config.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/config.json",
                expectedBytes = 1_256L,
                role = "CONFIG"
            ),
            ModelArtifactFile(
                filename = "generation_config.json",
                relativePath = "generation_config.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/generation_config.json",
                expectedBytes = 3_772L,
                role = "CONFIG"
            ),
            ModelArtifactFile(
                filename = "preprocessor_config.json",
                relativePath = "preprocessor_config.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/preprocessor_config.json",
                expectedBytes = 340L,
                role = "CONFIG"
            ),
            ModelArtifactFile(
                filename = "tokenizer.json",
                relativePath = "tokenizer.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/tokenizer.json",
                expectedBytes = 2_710_337L,
                role = "TOKENIZER"
            ),
            ModelArtifactFile(
                filename = "tokenizer_config.json",
                relativePath = "tokenizer_config.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/tokenizer_config.json",
                expectedBytes = 282_843L,
                role = "TOKENIZER"
            ),
            ModelArtifactFile(
                filename = "vocab.json",
                relativePath = "vocab.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/vocab.json",
                expectedBytes = 1_036_558L,
                role = "VOCAB"
            ),
            ModelArtifactFile(
                filename = "merges.txt",
                relativePath = "merges.txt",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/merges.txt",
                expectedBytes = 493_869L,
                role = "TOKENIZER"
            ),
            ModelArtifactFile(
                filename = "normalizer.json",
                relativePath = "normalizer.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/normalizer.json",
                expectedBytes = 52_666L,
                role = "TOKENIZER"
            ),
            ModelArtifactFile(
                filename = "added_tokens.json",
                relativePath = "added_tokens.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/added_tokens.json",
                expectedBytes = 34_648L,
                role = "TOKENIZER"
            ),
            ModelArtifactFile(
                filename = "special_tokens_map.json",
                relativePath = "special_tokens_map.json",
                downloadUrl = "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/special_tokens_map.json",
                expectedBytes = 2_186L,
                role = "TOKENIZER"
            )
        )

        // B. Vocal Separation — HTDemucs (adefossez/HTDemucs) — 3 required files, 84,037,548 bytes (84.04 MB)
        val htdemucsFiles = listOf(
            ModelArtifactFile(
                filename = "955717e8.safetensors",
                relativePath = "955717e8.safetensors",
                downloadUrl = "https://huggingface.co/adefossez/HTDemucs/resolve/main/955717e8.safetensors",
                expectedBytes = 84_025_440L,
                expectedSha256 = "d9fa14133cfcc034a6758923bb3a8ca9f8dfd0b582134643bbf83f72c17576dd",
                role = "WEIGHTS"
            ),
            ModelArtifactFile(
                filename = "955717e8.json",
                relativePath = "955717e8.json",
                downloadUrl = "https://huggingface.co/adefossez/HTDemucs/resolve/main/955717e8.json",
                expectedBytes = 12_087L,
                role = "CONFIG"
            ),
            ModelArtifactFile(
                filename = "htdemucs.yaml",
                relativePath = "htdemucs.yaml",
                downloadUrl = "https://huggingface.co/adefossez/HTDemucs/resolve/main/htdemucs.yaml",
                expectedBytes = 21L,
                role = "PIPELINE"
            )
        )

        // C. Voice Activity Detection — Silero VAD (onnx-community/silero-vad) — 2 required files, 2,244,097 bytes (2.24 MB)
        val sileroFiles = listOf(
            ModelArtifactFile(
                filename = "model.onnx",
                relativePath = "onnx/model.onnx",
                downloadUrl = "https://huggingface.co/onnx-community/silero-vad/resolve/main/onnx/model.onnx",
                expectedBytes = 2_243_022L,
                expectedSha256 = "a4a068cd6cf1ea8355b84327595838ca748ec29a25bc91fc82e6c299ccdc5808",
                role = "WEIGHTS"
            ),
            ModelArtifactFile(
                filename = "LICENSE",
                relativePath = "LICENSE",
                downloadUrl = "https://huggingface.co/onnx-community/silero-vad/resolve/main/LICENSE",
                expectedBytes = 1_075L,
                role = "LICENSE"
            )
        )

        // E. Selected Hindi TTS — F5-Hindi-24KHz (SPRINGLab/F5-Hindi-24KHz) — 2 required files, 632,168,396 bytes (632.17 MB)
        val f5HindiFiles = listOf(
            ModelArtifactFile(
                filename = "model_2500000.safetensors",
                relativePath = "model_2500000.safetensors",
                downloadUrl = "https://huggingface.co/SPRINGLab/F5-Hindi-24KHz/resolve/main/model_2500000.safetensors",
                expectedBytes = 632_168_041L,
                expectedSha256 = "011d5b81535d21b91b22d78a06905c5253c0a360b873fd5a8b55eb21c134fd2f",
                role = "WEIGHTS"
            ),
            ModelArtifactFile(
                filename = "vocab.txt",
                relativePath = "vocab.txt",
                downloadUrl = "https://huggingface.co/SPRINGLab/F5-Hindi-24KHz/resolve/main/vocab.txt",
                expectedBytes = 355L,
                role = "VOCAB"
            )
        )

        // F. Speaker Diarization — pyannote/speaker-diarization-3.1 (GATED: sizes MUST NOT be invented!)
        // Dependencies: pyannote/segmentation-3.0 (gated=auto) + pyannote/wespeaker-voxceleb-resnet34-LM (open)
        val pyannoteFiles = listOf(
            ModelArtifactFile(
                filename = "config.yaml",
                relativePath = "speaker-diarization-3.1/config.yaml",
                downloadUrl = "https://huggingface.co/pyannote/speaker-diarization-3.1/resolve/main/config.yaml",
                expectedBytes = -1L,
                role = "PIPELINE",
                isGatedFile = true
            ),
            ModelArtifactFile(
                filename = "config.yaml",
                relativePath = "segmentation-3.0/config.yaml",
                downloadUrl = "https://huggingface.co/pyannote/segmentation-3.0/resolve/main/config.yaml",
                expectedBytes = -1L,
                role = "CONFIG",
                isGatedFile = true
            ),
            ModelArtifactFile(
                filename = "pytorch_model.bin",
                relativePath = "segmentation-3.0/pytorch_model.bin",
                downloadUrl = "https://huggingface.co/pyannote/segmentation-3.0/resolve/main/pytorch_model.bin",
                expectedBytes = -1L,
                role = "WEIGHTS",
                isGatedFile = true
            ),
            ModelArtifactFile(
                filename = "config.yaml",
                relativePath = "wespeaker-voxceleb-resnet34-LM/config.yaml",
                downloadUrl = "https://huggingface.co/pyannote/wespeaker-voxceleb-resnet34-LM/resolve/main/config.yaml",
                expectedBytes = 221L,
                role = "CONFIG",
                isGatedFile = false
            ),
            ModelArtifactFile(
                filename = "pytorch_model.bin",
                relativePath = "wespeaker-voxceleb-resnet34-LM/pytorch_model.bin",
                downloadUrl = "https://huggingface.co/pyannote/wespeaker-voxceleb-resnet34-LM/resolve/main/pytorch_model.bin",
                expectedBytes = 26_645_418L,
                expectedSha256 = "366edf44f4c80889a3eb7a9d7bdf02c4aede3127f7dd15e274dcdb826b143c56",
                role = "WEIGHTS",
                isGatedFile = false
            )
        )

        // G. Speaker Embedding — WeSpeaker VoxCeleb ResNet34-LM (Open CC-BY-4.0 weights, Remote Worker Only)
        val wespeakerFiles = listOf(
            ModelArtifactFile(
                filename = "pytorch_model.bin",
                relativePath = "pytorch_model.bin",
                downloadUrl = "https://huggingface.co/pyannote/wespeaker-voxceleb-resnet34-LM/resolve/main/pytorch_model.bin",
                expectedBytes = 26_645_418L,
                expectedSha256 = "366edf44f4c80889a3eb7a9d7bdf02c4aede3127f7dd15e274dcdb826b143c56",
                role = "WEIGHTS"
            ),
            ModelArtifactFile(
                filename = "config.yaml",
                relativePath = "config.yaml",
                downloadUrl = "https://huggingface.co/pyannote/wespeaker-voxceleb-resnet34-LM/resolve/main/config.yaml",
                expectedBytes = 221L,
                role = "CONFIG"
            )
        )

        return listOf(
            // 1. Japanese Speech Recognition — Whisper Large V3 Turbo
            ModelManifestEntity(
                modelId = "whisper_large_v3_turbo",
                modelName = "Whisper Large V3 Turbo",
                modelVersion = "openai/whisper-large-v3-turbo (main)",
                specialistAgentNumber = 1,
                category = "Japanese Speech Recognition (Agent 1)",
                purpose = "Multilingual Japanese anime dialogue recognition, transcription, and segment timestamps.",
                sourceUrl = "https://huggingface.co/openai/whisper-large-v3-turbo",
                fileFormat = "Safetensors (.safetensors) + Transformers Tokenizer JSON",
                supportedLanguages = "99 languages (Japanese ja, Hindi hi, English en)",
                actualFilesNeededJson = whisperFiles.joinToString(", ") { it.relativePath },
                filesManifestJson = serializeManifest(whisperFiles),
                downloadArtifactUrl = whisperFiles.first().downloadUrl,
                expectedSizeBytes = whisperFiles.sumOf { it.expectedBytes },
                isSizeKnown = true,
                licenseName = "MIT License",
                licenseNotes = "Official OpenAI Whisper Large V3 Turbo MIT release (809M params, 4-layer pruned decoder).",
                runtimeCompatibility = RUNTIME_REMOTE_GPU_WORKER,
                compatibilitySummary = "Remote GPU Worker Only (Not natively runnable on Android — PyTorch/Safetensors weights). Requires Self-Hosted Python/PyTorch GPU Worker (~4 GB VRAM FP16). Can be downloaded & SHA-256 verified to app storage for staging.",
                requiredDependencies = "Python 3.10+, torch, transformers >= 4.45, safetensors, accelerate",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "NOT_DOWNLOADED",
                verifiedFilesCount = 0,
                totalFilesCount = whisperFiles.size,
                sha256Checksum = "",
                verificationLevel = "UNVERIFIED",
                statusNote = "11 verified files (1.62 GB total). Remote GPU Worker Only — not executed locally on Android."
            ),

            // 2. Voice Activity Detection — Silero VAD ONNX (Runs Natively via ONNX Runtime Mobile!)
            ModelManifestEntity(
                modelId = "silero_vad_onnx",
                modelName = "Silero VAD (ONNX Runtime Mobile)",
                modelVersion = "onnx-community/silero-vad (main)",
                specialistAgentNumber = 2,
                category = "Voice Activity Detection (Agent 2)",
                purpose = "Neural voice activity detection identifying exact Japanese speech boundaries and silence gaps on-device.",
                sourceUrl = "https://huggingface.co/onnx-community/silero-vad",
                fileFormat = "ONNX (.onnx FP32)",
                supportedLanguages = "Language-agnostic (6,000+ languages incl. Japanese & Hindi)",
                actualFilesNeededJson = sileroFiles.joinToString(", ") { it.relativePath },
                filesManifestJson = serializeManifest(sileroFiles),
                downloadArtifactUrl = sileroFiles.first().downloadUrl,
                expectedSizeBytes = sileroFiles.sumOf { it.expectedBytes },
                isSizeKnown = true,
                licenseName = "MIT License",
                licenseNotes = "Official Silero VAD ONNX release redistributed by onnx-community under MIT License.",
                runtimeCompatibility = RUNTIME_ONNX_COMPATIBLE,
                compatibilitySummary = "Runs natively on Android via bundled ONNX Runtime Mobile (com.microsoft.onnxruntime:onnxruntime-android) as soon as model.onnx is downloaded & SHA-256 verified. Falls back to energy-based DSP VAD only when model.onnx is not downloaded.",
                requiredDependencies = "Bundled ONNX Runtime Mobile (onnxruntime-android 1.20.0)",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "NOT_DOWNLOADED",
                verifiedFilesCount = 0,
                totalFilesCount = sileroFiles.size,
                sha256Checksum = "",
                verificationLevel = "UNVERIFIED",
                statusNote = "2 verified files (2.24 MB total). Download to activate genuine on-device neural Silero VAD inference."
            ),

            // 3. Speaker Diarization — pyannote/speaker-diarization-3.1 (GATED)
            ModelManifestEntity(
                modelId = "pyannote_diarization_3_1",
                modelName = "pyannote Speaker Diarization 3.1",
                modelVersion = "pyannote/speaker-diarization-3.1 (Gated)",
                specialistAgentNumber = 3,
                category = "Audio-Only Speaker Diarization (Agent 3)",
                purpose = "Neural audio-only speaker diarization and voice clustering without visual or face recognition.",
                sourceUrl = "https://huggingface.co/pyannote/speaker-diarization-3.1",
                fileFormat = "PyTorch (.bin) + pyannote YAML Pipeline",
                supportedLanguages = "Language-agnostic audio speaker clustering",
                actualFilesNeededJson = pyannoteFiles.joinToString(", ") { it.relativePath },
                filesManifestJson = serializeManifest(pyannoteFiles),
                downloadArtifactUrl = pyannoteFiles.first().downloadUrl,
                expectedSizeBytes = 0L, // NEVER invent size for gated/unavailable files!
                isSizeKnown = false,
                licenseName = "MIT (Gated) + CC-BY-4.0 (WeSpeaker)",
                licenseNotes = "Gated Hugging Face repositories: requires accepting user conditions on both pyannote/speaker-diarization-3.1 AND pyannote/segmentation-3.0, plus a read-only HF Access Token.",
                runtimeCompatibility = RUNTIME_REMOTE_GPU_WORKER,
                compatibilitySummary = "Remote GPU Worker Only (Gated PyTorch model — not natively runnable on Android). Invoked via POST /v1/diarize when a Remote GPU Worker is configured. Total file size is withheld until queried with your authorized HF token.",
                requiredDependencies = "pyannote.audio >= 3.1, torch, torchaudio, HF Access Token (accepted terms on speaker-diarization-3.1 & segmentation-3.0)",
                isGatedHuggingFace = true,
                isExperimentalArchived = false,
                downloadState = "NOT_DOWNLOADED",
                verifiedFilesCount = 0,
                totalFilesCount = pyannoteFiles.size,
                sha256Checksum = "",
                verificationLevel = "UNVERIFIED",
                statusNote = "Gated Hugging Face model (Remote Worker Only): Accept terms on speaker-diarization-3.1 & segmentation-3.0, then enter an HF read token to inspect exact server size or stage files."
            ),

            // 3b. Speaker Embeddings — WeSpeaker VoxCeleb ResNet34-LM (Open Weights, Remote Worker Only)
            ModelManifestEntity(
                modelId = "wespeaker_voxceleb_resnet34_lm",
                modelName = "WeSpeaker VoxCeleb ResNet34-LM",
                modelVersion = "pyannote/wespeaker-voxceleb-resnet34-LM (main)",
                specialistAgentNumber = 3,
                category = "Neural Speaker Embeddings (Agent 3)",
                purpose = "256-D neural speaker embedding backbone used by pyannote/speaker-diarization-3.1 on the remote GPU worker.",
                sourceUrl = "https://huggingface.co/pyannote/wespeaker-voxceleb-resnet34-LM",
                fileFormat = "PyTorch (.bin) + YAML Config",
                supportedLanguages = "Language-agnostic audio speaker embeddings",
                actualFilesNeededJson = wespeakerFiles.joinToString(", ") { it.relativePath },
                filesManifestJson = serializeManifest(wespeakerFiles),
                downloadArtifactUrl = wespeakerFiles.first().downloadUrl,
                expectedSizeBytes = wespeakerFiles.sumOf { it.expectedBytes },
                isSizeKnown = true,
                licenseName = "CC-BY-4.0",
                licenseNotes = "Open WeSpeaker ResNet34-LM speaker embedding checkpoint used alongside pyannote diarization.",
                runtimeCompatibility = RUNTIME_REMOTE_GPU_WORKER,
                compatibilitySummary = "Remote GPU Worker Only (PyTorch .bin checkpoint — not executed on Android). Stage locally with SHA-256 verification or deploy on your Python worker.",
                requiredDependencies = "Python 3.10+, torch, torchaudio, pyannote.audio",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "NOT_DOWNLOADED",
                verifiedFilesCount = 0,
                totalFilesCount = wespeakerFiles.size,
                sha256Checksum = "",
                verificationLevel = "UNVERIFIED",
                statusNote = "2 verified files (26.65 MB total). Remote GPU Worker Only — PyTorch neural speaker embedding weights."
            ),

            // 4. Built-In Android Local Acoustic & Pitch Feature Analyzer (0 B download, Non-Neural)
            ModelManifestEntity(
                modelId = "android_local_acoustic_dsp",
                modelName = "On-Device Acoustic F0 & Spectral Features (Non-Neural)",
                modelVersion = "v1.1-BuiltIn-Kotlin-DSP",
                specialistAgentNumber = 3,
                category = "Voice & Pitch Analysis (Agent 3 Fallback)",
                purpose = "Non-neural built-in Android DSP extracting autocorrelation F0 pitch, spectral centroid, and harmonic features offline when no remote pyannote/WeSpeaker worker is connected.",
                sourceUrl = "Built-in Android Application Module (AudioDspEngine.kt)",
                fileFormat = "Native Kotlin JVM / ART Bytecode (Built-in)",
                supportedLanguages = "Language-agnostic PCM audio waveform",
                actualFilesNeededJson = "No external model weights required (compiled into app)",
                filesManifestJson = "[]",
                downloadArtifactUrl = "",
                expectedSizeBytes = 0L,
                isSizeKnown = true,
                licenseName = "App Built-in Module",
                licenseNotes = "Runs 100% locally on Android CPU with zero external downloads. Does not use trained neural speaker embeddings.",
                runtimeCompatibility = RUNTIME_ANDROID_LOCAL,
                compatibilitySummary = "Runs natively on Android device CPU (Non-Neural heuristic fallback) — always available offline with 0 B download.",
                requiredDependencies = "Android Runtime (Pre-installed)",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "BUILT_IN",
                downloadProgressPercent = 100,
                downloadedBytes = 0L,
                verifiedFilesCount = 0,
                totalFilesCount = 0,
                installedVersion = "v1.1-BuiltIn",
                verificationLevel = "BUILT_IN",
                statusNote = "Built-in Non-Neural Android DSP fallback active locally (0 B download required)."
            ),

            // 5. Gemini 3.8 Flash via Firebase AI Logic (Cloud API, 0 B download)
            ModelManifestEntity(
                modelId = "firebase_ai_gemini_3_8_flash",
                modelName = "Gemini 3.8 Flash (Firebase AI Logic)",
                modelVersion = "gemini-3.8-flash",
                specialistAgentNumber = 4,
                category = "Roman Hinglish Translation & Dictionary (Agent 4)",
                purpose = "Translates Japanese dialogue into natural Roman Hinglish (never Devanagari) enforcing 3-level glossaries and duration budgets.",
                sourceUrl = "https://firebase.google.com/docs/ai-logic",
                fileFormat = "Managed Cloud Inference API (Firebase AI Logic)",
                supportedLanguages = "Japanese (ja) -> Roman Hinglish (hi-Latn) & English",
                actualFilesNeededJson = "Provisioned via Firebase AI Logic + App Check Attestation (No local weights)",
                filesManifestJson = "[]",
                downloadArtifactUrl = "",
                expectedSizeBytes = 0L,
                isSizeKnown = true,
                licenseName = "Google APIs / Firebase Terms of Service",
                licenseNotes = "Secured by Firebase App Check attestation without embedding raw API keys.",
                runtimeCompatibility = RUNTIME_EXTERNAL_API,
                compatibilitySummary = "Managed cloud service via Firebase AI Logic + App Check — 0 B local weight storage.",
                requiredDependencies = "com.google.firebase:firebase-ai, firebase-appcheck",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "BUILT_IN",
                downloadProgressPercent = 100,
                downloadedBytes = 0L,
                verifiedFilesCount = 0,
                totalFilesCount = 0,
                installedVersion = "gemini-3.8-flash",
                verificationLevel = "BUILT_IN",
                statusNote = "Active via Firebase AI Logic + App Check (0 B local storage)."
            ),

            // 6. Selected Primary Hindi TTS — F5-Hindi-24KHz (SPRINGLab/F5-Hindi-24KHz)
            // Selected over Chatterbox-Multilingual-hi (3.21 GB) to avoid redundant multi-GB duplication:
            // F5-Hindi-24KHz is 5x smaller (632.17 MB), CC-BY-4.0 licensed, and native 24 kHz matching studio master rate.
            ModelManifestEntity(
                modelId = "f5_tts_hindi_24khz",
                modelName = "F5-Hindi-24KHz (Selected Hindi TTS)",
                modelVersion = "SPRINGLab/F5-Hindi-24KHz (step 2,500,000)",
                specialistAgentNumber = 5,
                category = "Hindi Voice Synthesis (Agent 5)",
                purpose = "Selected Hindi 24 kHz flow-matching TTS model (chosen over 3.21 GB Chatterbox Hindi for 5x smaller footprint, native 24 kHz output, and CC-BY-4.0 license).",
                sourceUrl = "https://huggingface.co/SPRINGLab/F5-Hindi-24KHz",
                fileFormat = "Safetensors (.safetensors) + Vocab TXT",
                supportedLanguages = "Hindi (hi) / Romanized Hinglish via phonetic normalizer",
                actualFilesNeededJson = f5HindiFiles.joinToString(", ") { it.relativePath },
                filesManifestJson = serializeManifest(f5HindiFiles),
                downloadArtifactUrl = f5HindiFiles.first().downloadUrl,
                expectedSizeBytes = f5HindiFiles.sumOf { it.expectedBytes },
                isSizeKnown = true,
                licenseName = "CC-BY-4.0",
                licenseNotes = "Verified CC-BY-4.0 license from SPRINGLab/F5-Hindi-24KHz model card. Attribution required.",
                runtimeCompatibility = RUNTIME_REMOTE_GPU_WORKER,
                compatibilitySummary = "Remote GPU Worker Only (Safetensors PyTorch checkpoint — not natively runnable on Android). Requires Remote Python/PyTorch GPU Worker (4-6 GB VRAM). On-device generic Android Speech TTS (no voice cloning) runs when remote worker is offline.",
                requiredDependencies = "Python 3.10+, f5-tts, torch, torchaudio, safetensors",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "NOT_DOWNLOADED",
                verifiedFilesCount = 0,
                totalFilesCount = f5HindiFiles.size,
                sha256Checksum = "",
                verificationLevel = "UNVERIFIED",
                statusNote = "2 verified files (632.17 MB total). Remote GPU Worker Only — not executed locally on Android."
            ),

            // 7. Vocal Separation — HTDemucs (adefossez/HTDemucs)
            ModelManifestEntity(
                modelId = "htdemucs_vocal_sep",
                modelName = "HTDemucs (Hybrid Transformer Demucs)",
                modelVersion = "adefossez/HTDemucs (955717e8)",
                specialistAgentNumber = 6,
                category = "Vocal & Background Separation (Agent 6)",
                purpose = "Separates anime vocals/dialogue from instrumental BGM and sound effects while preserving original master.",
                sourceUrl = "https://huggingface.co/adefossez/HTDemucs",
                fileFormat = "Safetensors (.safetensors) + Demucs JSON/YAML",
                supportedLanguages = "Language-agnostic audio stem separation (vocals / drums / bass / other)",
                actualFilesNeededJson = htdemucsFiles.joinToString(", ") { it.relativePath },
                filesManifestJson = serializeManifest(htdemucsFiles),
                downloadArtifactUrl = htdemucsFiles.first().downloadUrl,
                expectedSizeBytes = htdemucsFiles.sumOf { it.expectedBytes },
                isSizeKnown = true,
                licenseName = "MIT License (Demucs)",
                licenseNotes = "Official Hybrid Transformer Demucs weights (955717e8) with JSON architecture sidecar and htdemucs.yaml bag manifest.",
                runtimeCompatibility = RUNTIME_REMOTE_GPU_WORKER,
                compatibilitySummary = "Remote GPU Worker Only (Safetensors PyTorch Demucs checkpoint — not natively runnable on Android). Requires Remote Python/PyTorch GPU Worker; built-in Android bandpass/residual DSP approximation handles offline fallback.",
                requiredDependencies = "Python 3.10+, demucs, torch, torchaudio, safetensors",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "NOT_DOWNLOADED",
                verifiedFilesCount = 0,
                totalFilesCount = htdemucsFiles.size,
                sha256Checksum = "",
                verificationLevel = "UNVERIFIED",
                statusNote = "3 verified files (84.04 MB total). Remote GPU Worker Only — includes 955717e8.safetensors, 955717e8.json & htdemucs.yaml."
            ),

            // 8. Built-In Android MediaCodec / MediaMuxer & PCM Timeline Mixer (0 B download)
            ModelManifestEntity(
                modelId = "android_local_media_mixer",
                modelName = "Android MediaCodec / MediaMuxer & PCM Mixer",
                modelVersion = "v1.1-Android-MediaFramework",
                specialistAgentNumber = 7,
                category = "Timeline, Mixing & Export (Agent 7)",
                purpose = "Lossless 16-bit PCM WSOLA time-stretching, streaming additive multitrack mixer, headroom limiter, and MP4/WAV export.",
                sourceUrl = "Built-in Android Media Framework (MediaExtractor / MediaCodec / MediaMuxer)",
                fileFormat = "Android System Media APIs + Native Kotlin PCM",
                supportedLanguages = "Language-agnostic PCM / AAC / MP4 container",
                actualFilesNeededJson = "No external model weights required (Android OS Media Framework)",
                filesManifestJson = "[]",
                downloadArtifactUrl = "",
                expectedSizeBytes = 0L,
                isSizeKnown = true,
                licenseName = "Android Open Source Project (Apache-2.0)",
                licenseNotes = "Built-in Android hardware/software codecs and lossless 16-bit PCM WAV pipeline.",
                runtimeCompatibility = RUNTIME_ANDROID_LOCAL,
                compatibilitySummary = "Runs natively on Android — always available offline with 0 B download.",
                requiredDependencies = "Android MediaExtractor, MediaCodec, MediaMuxer (Pre-installed)",
                isGatedHuggingFace = false,
                isExperimentalArchived = false,
                downloadState = "BUILT_IN",
                downloadProgressPercent = 100,
                downloadedBytes = 0L,
                verifiedFilesCount = 0,
                totalFilesCount = 0,
                installedVersion = "v1.1-BuiltIn",
                verificationLevel = "BUILT_IN",
                statusNote = "Built-in Android PCM WSOLA + Streaming Mixer & MediaMuxer active locally (0 B download required)."
            )
        )
    }
}
