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
    val visibleRomanHinglish: String,
    val internalNormalizedPhonetic: String,
    val actualDurationMs: Long,
    val timeStretchRatio: Float,
    val peakDbfs: Float,
    val engineUsed: String,
    val modelVersion: String,
    val syncStatus: String = "ALIGNED"
)

/**
 * Agent 16 — Pronunciation & Roman Hinglish Normalization Engine.
 *
 * Responsibilities:
 * 1. Keeps the visible editor text strictly in Roman Hinglish (never Devanagari).
 * 2. Builds an internal pronunciation-optimized representation for TTS synthesis:
 *    - Applies 3-level Dictionary pronunciation rules (Project > Anime > Global)
 *    - Preserves English words (e.g., Goblin, Demon, King, Dungeon, Mana, Guild, Quest)
 *    - Prepares clean natural text for Android Speech TTS (`hi-IN` / `en-IN`) or Remote `F5-Hindi-24KHz`.
 *
 * NOTE: The legacy synthetic sine-wave tone oscillator has been permanently removed.
 * All audio synthesis must run through real spoken speech engines (`RemoteWorkerClient` F5-Hindi-24KHz
 * or `AndroidSpeechTtsEngine` using `android.speech.tts.TextToSpeech.synthesizeToFile()`).
 */
object HinglishTtsNormalizer {

    val AVAILABLE_TTS_ENGINES = listOf(
        TtsEngineDescriptor(
            engineId = "F5-Hindi-24KHz",
            displayName = "F5-Hindi-24KHz (Remote GPU) + Android Speech TTS",
            modelVersion = "SPRINGLab/F5-Hindi-24KHz (model_2500000.safetensors)",
            licenseName = "CC-BY-4.0",
            runtimeType = "REMOTE_PYTHON_GPU_WORKER + ANDROID_SPEECH_TTS",
            isExperimentalArchived = false,
            supportsCrossLanguageCloning = false,
            supportedEmotions = listOf("NEUTRAL", "CALM", "EXCITED", "ANGRY", "SAD", "FEARFUL", "HAPPY", "WHISPERING", "SHOUTING"),
            statusWarning = "Uses Remote GPU Worker (F5-Hindi-24KHz) when connected; automatically uses real on-device Android Speech TTS (hi-IN / en-IN) when offline."
        ),
        TtsEngineDescriptor(
            engineId = "Android-Speech-TTS",
            displayName = "Android Speech TTS (On-Device hi-IN / en-IN)",
            modelVersion = "android.speech.tts.TextToSpeech",
            licenseName = "Android System Speech Engine",
            runtimeType = "ANDROID_LOCAL",
            isExperimentalArchived = false,
            supportsCrossLanguageCloning = false,
            supportedEmotions = listOf("NEUTRAL", "ANGRY", "CALM", "EXCITED", "SAD", "FEARFUL", "HAPPY"),
            statusWarning = "Real on-device spoken TTS via Android TextToSpeech.synthesizeToFile() with character pitch & speaking-rate mapping (0 B extra download). Requires an installed Android TTS voice pack."
        )
    )

    private val HINDI_PHONETIC_LEXICON = mapOf(
        "mujhe" to "mu-jhay",
        "kam" to "kum",
        "mat" to "mut",
        "samajhna" to "sa-majh-naa",
        "main" to "main",
        "tumhe" to "tum-hay",
        "hara" to "ha-raa",
        "dunga" to "doon-gaa",
        "ye" to "yeh",
        "meri" to "may-ree",
        "mera" to "may-raa",
        "hai" to "hai",
        "nahi" to "na-hee",
        "kya" to "kyaa",
        "ab" to "ub",
        "khatam" to "kha-tum",
        "shakti" to "shak-tee",
        "maidan" to "mai-daan",
        "bhaago" to "bhaa-go",
        "ruko" to "ru-ko",
        "dekho" to "day-kho",
        "tumhari" to "tum-haa-ree",
        "taaqat" to "taa-qat",
        "bekaar" to "bay-kaar",
        "jeet" to "jeet",
        "haar" to "haar",
        "bilkul" to "bil-kul",
        "tayar" to "tai-yaar",
        "hoon" to "hoon"
    )

    private val PRESERVED_ENGLISH_WORDS = setOf(
        "book", "goblin", "demon", "king", "dungeon", "mana", "guild", "quest",
        "level", "boss", "magic", "power", "shield", "sword", "portal", "team",
        "barrier", "domain", "attack", "defense", "cursed", "energy", "technique"
    )

    /**
     * Converts visible Roman Hinglish into an internal pronunciation-optimized phonetic representation
     * without changing the visible editor string or adding extra words.
     */
    fun normalizeForTtsPronunciation(
        visibleRomanHinglish: String,
        dictionaries: List<DictionaryEntryEntity>
    ): String {
        if (visibleRomanHinglish.isBlank()) return ""

        val dictPronunciationMap = dictionaries.associate {
            it.preferredRomanHinglish.lowercase().trim() to
                it.pronunciationGuidance.ifBlank { it.preferredRomanHinglish }
        }

        val tokens = visibleRomanHinglish.trim().split(Regex("\\s+"))
        val normalizedTokens = tokens.map { rawToken ->
            val leadingPunct = rawToken.takeWhile { !it.isLetterOrDigit() }
            val trailingPunct = rawToken.takeLastWhile { !it.isLetterOrDigit() }
            val core = rawToken.removePrefix(leadingPunct).removeSuffix(trailingPunct)
            val lower = core.lowercase()

            val mappedCore = when {
                core.isEmpty() -> ""
                dictPronunciationMap.containsKey(lower) -> dictPronunciationMap[lower]!!
                PRESERVED_ENGLISH_WORDS.contains(lower) -> "[EN:$core]"
                HINDI_PHONETIC_LEXICON.containsKey(lower) -> HINDI_PHONETIC_LEXICON[lower]!!
                else -> core
            }
            "$leadingPunct$mappedCore$trailingPunct"
        }
        return normalizedTokens.joinToString(" ")
    }

    /**
     * Produces clean speakable Hinglish text for Android Speech TTS / F5-Hindi-24KHz by applying
     * explicit dictionary pronunciation overrides while keeping natural spelling (no bracket tags).
     */
    fun prepareSpeakableText(
        visibleRomanHinglish: String,
        dictionaries: List<DictionaryEntryEntity>
    ): String {
        if (visibleRomanHinglish.isBlank()) return ""
        val dictPronunciationMap = dictionaries
            .filter { it.pronunciationGuidance.isNotBlank() && it.preferredRomanHinglish.isNotBlank() }
            .associate { it.preferredRomanHinglish.lowercase().trim() to it.pronunciationGuidance.trim() }

        return visibleRomanHinglish.trim().split(Regex("\\s+")).joinToString(" ") { rawToken ->
            val leadingPunct = rawToken.takeWhile { !it.isLetterOrDigit() }
            val trailingPunct = rawToken.takeLastWhile { !it.isLetterOrDigit() }
            val core = rawToken.removePrefix(leadingPunct).removeSuffix(trailingPunct)
            val override = dictPronunciationMap[core.lowercase()]
            val cleanCore = override ?: core
            "$leadingPunct$cleanCore$trailingPunct"
        }
    }
}
