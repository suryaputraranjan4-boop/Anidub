package com.example.engine

import com.example.data.local.DictionaryEntryEntity
import java.io.File

data class TtsEngineDescriptor(
    val engineId: String,
    val displayName: String,
    val modelVersion: String,
    val licenseName: String,
    val runtimeType: String,
    val isSupportedOnAndroid: Boolean,
    val isExperimentalArchived: Boolean,
    val supportsVoiceCloning: Boolean,
    val supportsReferenceAudio: Boolean,
    val supportsNeuralEmotionControl: Boolean,
    val supportedLanguages: String,
    val outputSampleRateHz: Int,
    val supportedEmotions: List<String>,
    val statusWarning: String? = null
) {
    val supportsCrossLanguageCloning: Boolean
        get() = supportsVoiceCloning
}

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
 * Honest Pronunciation & TTS Normalization Engine (Android-Only Architecture).
 *
 * Strictly separates:
 * - Canonical display & subtitle text (Permanent Mixed-Script: Devanagari Hindi + English)
 * - Internal TTS pronunciation text (Dictionary pronunciation guidance applied with
 *   deterministic Project > Anime > Global precedence and longest-phrase boundary matching)
 *
 * Strict Honesty Rules:
 * - Only `Android-Speech-TTS` is locally runnable on Android (explicit generic system TTS fallback — NO voice cloning).
 * - `Chatterbox-Multilingual-V3` and `F5-Hindi-24KHz` are explicitly listed as `isSupportedOnAndroid = false`
 *   so the UI never disguises Android TTS as Chatterbox or F5-Hindi or claims voice cloning is available on-device.
 */
object HinglishTtsNormalizer {

    val AVAILABLE_TTS_ENGINES = listOf(
        TtsEngineDescriptor(
            engineId = "Android-Speech-TTS",
            displayName = "Android System TextToSpeech (Explicit Local Fallback)",
            modelVersion = "android.speech.tts.TextToSpeech",
            licenseName = "Android System Speech Engine",
            runtimeType = "ANDROID_LOCAL_FALLBACK",
            isSupportedOnAndroid = true,
            isExperimentalArchived = false,
            supportsVoiceCloning = false,
            supportsReferenceAudio = false,
            supportsNeuralEmotionControl = false,
            supportedLanguages = "Hindi (hi-IN) + Indian English (en-IN) via installed Android TTS voice pack",
            outputSampleRateHz = 24000,
            supportedEmotions = listOf("NEUTRAL", "CALM", "EXCITED", "ANGRY", "SAD", "FEARFUL", "HAPPY"),
            statusWarning = "Explicit on-device fallback: generic Android system voice via TextToSpeech.synthesizeToFile(). Pitch/rate adjustments only — NO voice cloning, NO reference-audio cloning, and NO neural emotion synthesis."
        ),
        TtsEngineDescriptor(
            engineId = "Chatterbox-Multilingual-V3",
            displayName = "Chatterbox Multilingual V3 (Unsupported on Android)",
            modelVersion = "ResembleAI/chatterbox (500M LLaMA + S3Gen + HiFi-GAN)",
            licenseName = "MIT License",
            runtimeType = "UNSUPPORTED_ON_ANDROID",
            isSupportedOnAndroid = false,
            isExperimentalArchived = false,
            supportsVoiceCloning = false, // False on Android because model cannot execute locally
            supportsReferenceAudio = false,
            supportsNeuralEmotionControl = false,
            supportedLanguages = "23 languages incl. Hindi & English (Desktop PyTorch GPU only)",
            outputSampleRateHz = 24000,
            supportedEmotions = emptyList(),
            statusWarning = "UNSUPPORTED ON ANDROID: Requires Python/PyTorch GPU runtime (~3.2 GB weights: T3 LLaMA + S3Gen + HiFi-GAN). No official ONNX/Android runtime exists. Android TTS is NEVER disguised as Chatterbox."
        ),
        TtsEngineDescriptor(
            engineId = "F5-Hindi-24KHz",
            displayName = "F5-Hindi-24KHz (Unsupported on Android)",
            modelVersion = "SPRINGLab/F5-Hindi-24KHz (model_2500000.safetensors)",
            licenseName = "CC-BY-4.0",
            runtimeType = "UNSUPPORTED_ON_ANDROID",
            isSupportedOnAndroid = false,
            isExperimentalArchived = false,
            supportsVoiceCloning = false, // False on Android because model cannot execute locally
            supportsReferenceAudio = false,
            supportsNeuralEmotionControl = false,
            supportedLanguages = "Hindi (hi) — Desktop PyTorch GPU only",
            outputSampleRateHz = 24000,
            supportedEmotions = emptyList(),
            statusWarning = "UNSUPPORTED ON ANDROID: Requires Python/PyTorch f5-tts + Vocos vocoder (~632 MB Safetensors). Not executable locally on Android without an ONNX/TFLite runtime export."
        )
    )

    fun descriptorFor(engineId: String): TtsEngineDescriptor {
        return AVAILABLE_TTS_ENGINES.firstOrNull { it.engineId.equals(engineId, ignoreCase = true) }
            ?: AVAILABLE_TTS_ENGINES.first()
    }

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
     * Produces the exact clean speakable text sent directly to Android Speech TTS.
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
