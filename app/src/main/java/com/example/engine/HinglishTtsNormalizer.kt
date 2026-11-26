package com.example.engine

import com.example.data.local.DictionaryEntryEntity
import java.io.File

data class TtsEngineDescriptor(
    val engineId: String,
    val displayName: String,
    val modelVersion: String,
    val licenseName: String,
    val runtimeType: String,
    val isExperimentalArchived: Boolean,
    val supportsCrossLanguageCloning: Boolean,
    val supportedEmotions: List<String>,
    val statusWarning: String? = null
)

data class SynthesizedDialogueResult(
    val outputFile: File,
    val visibleRomanHinglish: String, // Canonical Mixed-Script Devanagari + English display text (never overwritten by TTS phonetic form)
    val internalNormalizedPhonetic: String, // Actual pronunciation string delivered to the TTS engine
    val actualDurationMs: Long,
    val timeStretchRatio: Float,
    val peakDbfs: Float,
    val engineUsed: String,
    val modelVersion: String,
    val syncStatus: String = "ALIGNED"
)

/**
 * Pronunciation & TTS Normalization Engine.
 *
 * Strictly separates:
 * - Canonical display & subtitle text (Permanent Mixed-Script: Devanagari Hindi + English)
 * - Internal TTS pronunciation text (Dictionary pronunciation guidance applied with
 *   deterministic Project > Anime > Global precedence and longest-phrase boundary matching)
 *
 * NOTE: Legacy synthetic sine-wave oscillators are permanently absent. All synthesis runs through
 * genuine Remote GPU `F5-Hindi-24KHz` (when model readiness is confirmed) or genuine on-device
 * Android `TextToSpeech.synthesizeToFile()` (honestly labeled generic system TTS).
 */
object HinglishTtsNormalizer {

    val AVAILABLE_TTS_ENGINES = listOf(
        TtsEngineDescriptor(
            engineId = "F5-Hindi-24KHz",
            displayName = "F5-Hindi-24KHz (Remote GPU) + Android Speech TTS Fallback",
            modelVersion = "SPRINGLab/F5-Hindi-24KHz (model_2500000.safetensors)",
            licenseName = "CC-BY-4.0",
            runtimeType = "REMOTE_PYTHON_GPU_WORKER + ANDROID_SPEECH_TTS",
            isExperimentalArchived = false,
            supportsCrossLanguageCloning = false,
            supportedEmotions = listOf("NEUTRAL", "CALM", "EXCITED", "ANGRY", "SAD", "FEARFUL", "HAPPY", "WHISPERING", "SHOUTING"),
            statusWarning = "Uses Remote GPU Worker (F5-Hindi-24KHz) ONLY when worker health confirms F5-Hindi is loaded and ready; falls back to generic on-device Android Speech TTS (no voice cloning)."
        ),
        TtsEngineDescriptor(
            engineId = "Android-Speech-TTS",
            displayName = "Android Speech TTS (Generic System hi-IN / en-IN)",
            modelVersion = "android.speech.tts.TextToSpeech",
            licenseName = "Android System Speech Engine",
            runtimeType = "ANDROID_LOCAL",
            isExperimentalArchived = false,
            supportsCrossLanguageCloning = false,
            supportedEmotions = listOf("NEUTRAL", "ANGRY", "CALM", "EXCITED", "SAD", "FEARFUL", "HAPPY"),
            statusWarning = "Generic on-device system TTS via Android TextToSpeech.synthesizeToFile() (no voice cloning). Speaks Devanagari Hindi + English mixed-script natively when a hi-IN voice pack is installed."
        )
    )

    /**
     * Generates the internal TTS pronunciation representation while keeping the canonical
     * Mixed-Script Hindi-English display text completely untouched.
     */
    fun normalizeForTtsPronunciation(
        displayMixedScriptText: String,
        dictionaries: List<DictionaryEntryEntity>,
        projectId: Long? = null,
        animeSeriesName: String? = null
    ): String {
        if (displayMixedScriptText.isBlank()) return ""
        val resolved = if (projectId != null || !animeSeriesName.isNullOrBlank()) {
            MixedScriptLanguageEngine.resolveScopedDictionaries(dictionaries, projectId, animeSeriesName)
        } else {
            dictionaries
        }
        return MixedScriptLanguageEngine.buildTtsPronunciationText(displayMixedScriptText, resolved)
    }

    /**
     * Produces the exact clean speakable text sent directly to Android Speech TTS or Remote F5-Hindi-24KHz.
     * Applies dictionary pronunciation overrides with longest-phrase boundary matching and strips
     * any bracketed markers without mutating the user-visible display text.
     */
    fun prepareSpeakableText(
        displayMixedScriptText: String,
        dictionaries: List<DictionaryEntryEntity>,
        projectId: Long? = null,
        animeSeriesName: String? = null
    ): String {
        return normalizeForTtsPronunciation(
            displayMixedScriptText = displayMixedScriptText,
            dictionaries = dictionaries,
            projectId = projectId,
            animeSeriesName = animeSeriesName
        )
    }
}
