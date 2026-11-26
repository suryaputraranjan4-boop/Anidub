package com.example.engine

import com.example.data.local.DictionaryEntryEntity
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

data class DictionaryImportPlan(
    val toInsert: List<DictionaryEntryEntity>,
    val toUpdate: List<DictionaryEntryEntity>,
    val importedCount: Int,
    val updatedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val warnings: List<String> = emptyList()
) {
    val totalProcessed: Int
        get() = importedCount + updatedCount + skippedCount + failedCount
}

data class MixedScriptValidationResult(
    val isValid: Boolean,
    val status: String, // VALID_MIXED_SCRIPT, LEGACY_ROMAN_WARNING, INVALID_ROMAN_HINDI, EMPTY
    val hasDevanagari: Boolean,
    val hasLatin: Boolean,
    val detectedRomanHindiTokens: List<String>,
    val message: String
) {
    val issueSummary: String
        get() = if (isValid) "" else message
}

/**
 * Centralized Permanent Hindi-English Mixed-Script Language Engine.
 *
 * Permanent rules enforced everywhere (Gemini prompts, post-validation, manual edits,
 * dictionary matching, TTS pronunciation separation, QC, and SRT/ASS subtitles):
 * 1. Hindi words MUST be written in Devanagari (e.g., मुझे, तुम्हारे, साथ, जाना, है, अभी, नहीं).
 * 2. English words MUST be written in English/Roman script (e.g., but, time, ready, situation, dangerous).
 * 3. Never convert entire Hindi sentences into Roman Hinglish.
 * 4. Never unnecessarily translate natural English words, brands, technical terms, or character names into Hindi.
 * 5. Display/Subtitle text and Internal TTS pronunciation text MUST remain strictly separate.
 */
object MixedScriptLanguageEngine {

    const val STATUS_VALID = "VALID_MIXED_SCRIPT"
    const val STATUS_CORRECTED = "CORRECTED_MIXED_SCRIPT"
    const val STATUS_LEGACY_ROMAN = "LEGACY_ROMAN_WARNING"
    const val STATUS_INVALID_ROMAN = "INVALID_ROMAN_HINDI"
    const val STATUS_EMPTY = "EMPTY"

    private val DEVANAGARI_REGEX = Regex("[\\u0900-\\u097F]")
    private val LATIN_WORD_REGEX = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")

    fun containsDevanagari(text: String): Boolean = DEVANAGARI_REGEX.containsMatchIn(text)

    /**
     * High-precision lexicon of unmistakable Romanized Hindi words.
     * STRICT EXCLUSION RULE: Every word that is also a valid English word or common abbreviation
     * (e.g., "main", "do", "to", "in", "on", "me", "is", "are", "he", "we", "or", "so", "no", "go",
     * "us", "as", "at", "by", "up", "if", "it", "hi", "hum", "bar", "bus", "car", "par", "ab", "lo",
     * "log", "mat", "din", "hun", "bas", "kon", "rok", "chal") is STRICTLY EXCLUDED here so valid
     * English or mixed-script sentences NEVER trigger false positives.
     */
    private val UNAMBIGUOUS_ROMAN_HINDI_WORDS = setOf(
        "mujhe", "mujko", "mujhko", "tumhe", "tumhein", "tumhen", "tumhara", "tumhari", "tumhare",
        "tumne", "tumko", "tumse", "tujhe", "tujhko", "tujhse", "tum", "aap", "aapka", "aapki", "aapke",
        "aapko", "aapne", "hamara", "hamari", "hamare", "humko", "humne", "humse", "humein", "hume",
        "mera", "meri", "mere", "maine", "tera", "teri", "tere", "uska", "uski", "uske", "usne",
        "usko", "unka", "unki", "unke", "unhone", "unko", "apna", "apni", "apne", "khud",
        "nahi", "nahin", "nhi", "nahee", "hai", "hain", "tha", "thi", "thee", "hoon",
        "kya", "kyaa", "kyun", "kyon", "kyunki", "kyonki", "kaise", "kaisa", "kaisi", "kahan", "kaha",
        "kahaan", "kaun", "kab", "kitna", "kitni", "kitne", "kidhar",
        "abhi", "phir", "fir", "bahut", "bohot", "boht", "bilkul", "sirf", "zyada", "jyada",
        "thoda", "thodi", "thode", "saath", "sath", "paas", "baad", "pehle", "pahle", "andar", "bahar",
        "upar", "neeche", "niche", "yahan", "yaha", "wahan", "waha", "idhar", "udhar",
        "jaana", "jana", "jao", "jaao", "jaa", "gaya", "gayi", "gaye", "aana", "aao", "aaya", "aayi", "aaye",
        "karna", "karni", "karne", "karo", "karta", "karti", "karte", "kiya", "kiye", "raha", "rahi", "rahe",
        "hona", "hota", "hoti", "hote", "hoga", "hogi", "honge", "hua", "hui", "hue",
        "dekhna", "dekho", "dekha", "dekhi", "sunna", "suno", "suna", "bolna", "bolo", "bola", "boli",
        "samajhna", "samjho", "samjha", "samajh", "pata", "batao", "batana", "bataya", "rukna", "ruko", "ruka",
        "bhaago", "bhago", "chalo", "chala", "chali", "chale", "milna", "milo", "mila", "mili",
        "lena", "liya", "dena", "diya", "dunga", "dungi", "denge", "rakhna", "rakho", "rakha",
        "lagna", "laga", "lagi", "lage", "lagta", "lagti", "sakta", "sakti", "sakte", "chahiye", "chahie",
        "chahta", "chahti", "chahte", "zaroor", "jaroor", "zaroori", "jaroori", "shayad",
        "lekin", "magar", "parantu", "isliye", "isiliye", "agar", "jab", "tab", "tak", "bhi", "aur",
        "yeh", "woh", "yahi", "wahi", "sabhi", "kuch", "kuchh", "koi", "kisi",
        "aisa", "aisi", "aise", "waisa", "waisi", "waise", "jaisa", "jaisi", "jaise",
        "accha", "achha", "achcha", "theek", "thik", "bura", "buri", "galat", "sahi", "sach", "jhooth", "jhoot",
        "bhai", "yaar", "dost", "haan", "haa", "shakti", "taaqat", "taqat", "bekaar", "bekar",
        "jeet", "haar", "haarunga", "harunga", "tayar", "taiyaar", "tayyar", "khatam", "khatm", "madad",
        "bachao", "roko", "maarna", "maaro", "mara", "ladna", "lado", "zinda", "marna", "duniya",
        "samay", "waqt", "raat", "subah", "shaam", "logon", "kaam", "baat", "baatein",
        "naam", "jagah", "raasta", "rasta", "ghar", "dil", "dimag", "aankh", "aankhein", "haath"
    )

    /**
     * Secondary Romanized Hindi short particles that are ONLY counted as Romanized Hindi when
     * co-occurring with at least one unambiguous Romanized Hindi word in the same sentence
     * (prevents false positives on English words like "main character", "to be", "do it").
     */
    private val CONTEXTUAL_ROMAN_HINDI_PARTICLES = setOf(
        "main", "mein", "se", "ko", "ka", "ke", "ki", "pe", "par", "ne", "ho", "na", "toh", "hi", "ek", "do",
        "ab", "lo", "ye", "wo", "sab", "mat", "bas", "din", "log", "hun", "hu"
    )

    /**
     * Permanent canonical prompt rules injected into every Gemini translation, sentence-splitting,
     * and script-correction request.
     */
    val PERMANENT_MIXED_SCRIPT_SYSTEM_RULES: String = """
PERMANENT HINDI-ENGLISH MIXED-SCRIPT WRITING SYSTEM (MANDATORY):
1. Hindi words MUST ALWAYS be written in Devanagari script (e.g., मुझे, तुम्हारे, साथ, जाना, है, अभी, नहीं, क्या, तुम, ये, बहुत).
2. English words MUST ALWAYS be written in English/Roman characters (e.g., but, time, ready, situation, dangerous, I trusted you, mission, system, boss, power, sorry, please).
3. Mix Hindi (Devanagari) and English (Roman) naturally within the same sentence exactly how urban Indian anime audiences speak.
4. NEVER write Hindi words in Roman Hinglish (FORBIDDEN: "Mujhe tumhare saath jaana hai, but abhi time nahi hai.").
5. NEVER unnecessarily translate natural English words, brands, technical terms, or anime terminology into heavy textbook Hindi.
6. Preserve character names, numbers, punctuation, emotion, sarcasm, humor, and speaking style.

CORRECT EXAMPLES:
- मुझे तुम्हारे साथ जाना है, but अभी time नहीं है।
- तुमने ऐसा क्यों किया? I trusted you.
- क्या तुम ready हो या नहीं?
- मुझे नहीं पता कि he कहाँ गया।
- ये situation बहुत dangerous है।

INCORRECT EXAMPLES (STRICTLY FORBIDDEN):
- Mujhe tumhare saath jaana hai, but abhi time nahi hai. (Romanized Hindi — FORBIDDEN)
- MUJHE TUMHARE SAATH JANA HAI. (All-caps Romanized Hindi — FORBIDDEN)
""".trimIndent()

    /**
     * Validates whether a dialogue or subtitle string conforms to the permanent Mixed-Script
     * Hindi-English rule without blindly flagging legitimate English words or proper nouns.
     */
    fun validateMixedScript(text: String): MixedScriptValidationResult {
        val clean = text.trim()
        if (clean.isEmpty()) {
            return MixedScriptValidationResult(
                isValid = false,
                status = STATUS_EMPTY,
                hasDevanagari = false,
                hasLatin = false,
                detectedRomanHindiTokens = emptyList(),
                message = "Dialogue text is empty."
            )
        }

        val hasDevanagari = DEVANAGARI_REGEX.containsMatchIn(clean)
        val latinWords = LATIN_WORD_REGEX.findAll(clean).map { it.value }.toList()
        val hasLatin = latinWords.isNotEmpty()

        val unambiguousLeaks = latinWords
            .filter { word ->
                // Skip pure acronyms (e.g., HQ, AI, VIP, S-Rank)
                if (word.length >= 2 && word.all { it.isUpperCase() } && word.lowercase(Locale.US) !in UNAMBIGUOUS_ROMAN_HINDI_WORDS) {
                    false
                } else {
                    word.lowercase(Locale.US) in UNAMBIGUOUS_ROMAN_HINDI_WORDS
                }
            }
            .distinctBy { it.lowercase(Locale.US) }

        val contextualLeaks = if (unambiguousLeaks.isNotEmpty()) {
            latinWords
                .filter { it.lowercase(Locale.US) in CONTEXTUAL_ROMAN_HINDI_PARTICLES }
                .distinctBy { it.lowercase(Locale.US) }
        } else {
            emptyList()
        }

        val allLeaks = (unambiguousLeaks + contextualLeaks).distinctBy { it.lowercase(Locale.US) }

        return when {
            // Purely Romanized Hindi sentence (no Devanagari + Roman Hindi words detected)
            !hasDevanagari && unambiguousLeaks.isNotEmpty() -> {
                MixedScriptValidationResult(
                    isValid = false,
                    status = STATUS_LEGACY_ROMAN,
                    hasDevanagari = false,
                    hasLatin = true,
                    detectedRomanHindiTokens = allLeaks,
                    message = "Romanized Hindi detected (${allLeaks.take(5).joinToString(", ")}). Hindi words must be written in Devanagari."
                )
            }
            // Mixed sentence that still leaked Romanized Hindi words alongside Devanagari
            hasDevanagari && unambiguousLeaks.isNotEmpty() -> {
                MixedScriptValidationResult(
                    isValid = false,
                    status = STATUS_INVALID_ROMAN,
                    hasDevanagari = true,
                    hasLatin = true,
                    detectedRomanHindiTokens = allLeaks,
                    message = "Romanized Hindi words leaked into mixed-script line (${allLeaks.take(5).joinToString(", ")})."
                )
            }
            // Valid Devanagari + English mixed-script OR intentional pure English line (e.g., "I trusted you.")
            else -> {
                MixedScriptValidationResult(
                    isValid = true,
                    status = STATUS_VALID,
                    hasDevanagari = hasDevanagari,
                    hasLatin = hasLatin,
                    detectedRomanHindiTokens = emptyList(),
                    message = if (hasDevanagari && hasLatin) {
                        "Valid Devanagari + English mixed-script."
                    } else if (hasDevanagari) {
                        "Valid Devanagari Hindi."
                    } else {
                        "Valid English expression preserved."
                    }
                )
            }
        }
    }

    /**
     * Returns the canonical display preference of a dictionary entry:
     * preferredMixedScript > preferredHindiWording (if Devanagari) > preferredEnglishWording (if preserveOriginalEnglish) > preferredRomanHinglish.
     */
    fun preferredDisplayTerm(entry: DictionaryEntryEntity): String {
        return when {
            entry.preferredMixedScript.isNotBlank() -> entry.preferredMixedScript.trim()
            entry.preserveOriginalEnglish && entry.preferredEnglishWording.isNotBlank() -> entry.preferredEnglishWording.trim()
            entry.preferredHindiWording.isNotBlank() -> entry.preferredHindiWording.trim()
            entry.preferredEnglishWording.isNotBlank() -> entry.preferredEnglishWording.trim()
            else -> entry.preferredRomanHinglish.trim()
        }
    }

    private fun scopePrecedence(level: String): Int = when (level.uppercase(Locale.US)) {
        "PROJECT" -> 3
        "ANIME" -> 2
        else -> 1 // GLOBAL
    }

    /**
     * Strictly isolates dictionary entries to the active project & anime series and resolves
     * conflicts deterministically with precedence: Project > Anime > Global.
     * Unrelated projects or unrelated anime series NEVER leak into the resolved list.
     */
    fun resolveScopedDictionaries(
        allEntries: List<DictionaryEntryEntity>,
        projectId: Long?,
        animeSeriesName: String?
    ): List<DictionaryEntryEntity> {
        val cleanSeries = animeSeriesName?.trim().orEmpty()
        val eligible = allEntries.filter { entry ->
            when (entry.dictionaryLevel.uppercase(Locale.US)) {
                "PROJECT" -> projectId != null && projectId > 0L && entry.projectId == projectId
                "ANIME" -> cleanSeries.isNotEmpty() && entry.animeSeriesName.equals(cleanSeries, ignoreCase = true)
                else -> true // GLOBAL
            }
        }

        // Deduplicate by normalized Japanese source term (or display key if source is blank),
        // keeping highest precedence (Project > Anime > Global), then most recently updated.
        val resolvedByKey = LinkedHashMap<String, DictionaryEntryEntity>()
        val sortedByPriority = eligible.sortedWith(
            compareByDescending<DictionaryEntryEntity> { scopePrecedence(it.dictionaryLevel) }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.id }
        )
        for (entry in sortedByPriority) {
            val key = entry.japaneseSourceTerm.trim().lowercase(Locale.US)
                .ifBlank { preferredDisplayTerm(entry).lowercase(Locale.US) }
            if (key.isNotEmpty() && !resolvedByKey.containsKey(key)) {
                resolvedByKey[key] = entry
            }
        }
        return resolvedByKey.values.toList()
    }

    /**
     * Boundary-aware phrase matcher supporting both Latin/English and Devanagari/Unicode scripts
     * without corrupting substrings inside longer words.
     */
    private fun buildUnicodeBoundaryRegex(term: String, ignoreCase: Boolean): Regex {
        val escaped = Regex.escape(term.trim())
        // Ensure the match is not immediately preceded or followed by a letter, digit, or Devanagari combining mark
        val pattern = "(?<![\\p{L}\\p{N}\\p{M}])$escaped(?![\\p{L}\\p{N}\\p{M}])"
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        return Pattern.compile(pattern, flags).toRegex()
    }

    /**
     * Applies resolved dictionary terminology deterministically to mixed-script display text.
     *
     * Rules enforced:
     * - Uses longest matching phrases first to avoid partial-phrase corruption.
     * - Enforces Unicode word boundaries so unrelated words containing the substring are never corrupted.
     * - Never blindly appends dictionary terms in brackets.
     * - Only replaces when an alternate wording (old wording / non-preferred Hindi/English/Roman variant)
     *   actually matches a boundary-delimited phrase in the text (or when the Japanese source contains the term).
     */
    fun applyDictionariesToDisplayText(
        displayText: String,
        japaneseSource: String,
        resolvedEntries: List<DictionaryEntryEntity>
    ): String {
        if (displayText.isBlank() || resolvedEntries.isEmpty()) return displayText

        // Build replacement pairs (candidateVariant -> canonicalTarget), ordered longest phrase first
        data class ReplacementRule(
            val variant: String,
            val target: String,
            val requiresJapaneseSourceMatch: Boolean,
            val japaneseSourceTerm: String
        )

        val rules = mutableListOf<ReplacementRule>()
        for (entry in resolvedEntries) {
            val target = preferredDisplayTerm(entry)
            if (target.isBlank()) continue

            val variants = linkedSetOf<String>()
            if (entry.preferredHindiWording.isNotBlank() && !entry.preferredHindiWording.trim().equals(target, ignoreCase = false)) {
                variants.add(entry.preferredHindiWording.trim())
            }
            if (entry.preferredEnglishWording.isNotBlank() && !entry.preferredEnglishWording.trim().equals(target, ignoreCase = false)) {
                variants.add(entry.preferredEnglishWording.trim())
            }
            if (entry.preferredRomanHinglish.isNotBlank() && !entry.preferredRomanHinglish.trim().equals(target, ignoreCase = false)) {
                variants.add(entry.preferredRomanHinglish.trim())
            }
            // Also normalize casing when preserveOriginalEnglish is enabled and target has specific casing (e.g. "Domain Expansion")
            if (entry.preserveOriginalEnglish && target.any { it.isUpperCase() } && target.all { it.code < 128 }) {
                variants.add(target)
            }

            for (v in variants) {
                if (v.length >= 2) {
                    rules.add(
                        ReplacementRule(
                            variant = v,
                            target = target,
                            requiresJapaneseSourceMatch = entry.japaneseSourceTerm.isNotBlank() && japaneseSource.isNotBlank(),
                            japaneseSourceTerm = entry.japaneseSourceTerm.trim()
                        )
                    )
                }
            }
        }

        // Longest matching phrases first
        val orderedRules = rules.sortedByDescending { it.variant.length }
        var result = displayText
        for (rule in orderedRules) {
            if (rule.requiresJapaneseSourceMatch && !japaneseSource.contains(rule.japaneseSourceTerm)) {
                // Only apply Japanese-scoped alternate replacements when the Japanese source contains the term
                // OR when it's an exact case-normalization rule for a preserved English term.
                if (!rule.variant.equals(rule.target, ignoreCase = true)) {
                    continue
                }
            }
            val regex = buildUnicodeBoundaryRegex(rule.variant, ignoreCase = true)
            if (regex.containsMatchIn(result)) {
                result = regex.replace(result, Regex.escapeReplacement(rule.target))
            }
        }
        return result
    }

    /**
     * Builds the separate internal TTS pronunciation string from the canonical Mixed-Script display text.
     *
     * Guarantees:
     * - NEVER mutates or overwrites the canonical mixed-script display or subtitle text.
     * - Applies `Project > Anime > Global` dictionary `pronunciationGuidance` overrides using
     *   longest-phrase boundary-aware matching across mixed-script, Hindi, English, and Roman keys.
     * - Strips bracketed internal annotation markers (e.g., `[EN:...]`) so clean natural speech text
     *   reaches Android Speech TTS (`hi-IN`) and Remote `F5-Hindi-24KHz`.
     */
    fun buildTtsPronunciationText(
        mixedScriptDisplayText: String,
        resolvedEntries: List<DictionaryEntryEntity>
    ): String {
        if (mixedScriptDisplayText.isBlank()) return ""

        data class PronRule(val matchPhrase: String, val spokenGuide: String)

        val rules = mutableListOf<PronRule>()
        for (entry in resolvedEntries) {
            val guide = entry.pronunciationGuidance.trim()
                .replace(Regex("""\[EN:([^\]]+)\]"""), "$1")
                .trim()
            if (guide.isBlank()) continue

            val matchKeys = linkedSetOf<String>()
            if (entry.preferredMixedScript.isNotBlank()) matchKeys.add(entry.preferredMixedScript.trim())
            if (entry.preferredHindiWording.isNotBlank()) matchKeys.add(entry.preferredHindiWording.trim())
            if (entry.preferredEnglishWording.isNotBlank()) matchKeys.add(entry.preferredEnglishWording.trim())
            if (entry.preferredRomanHinglish.isNotBlank()) matchKeys.add(entry.preferredRomanHinglish.trim())

            for (key in matchKeys) {
                if (key.isNotBlank() && !key.equals(guide, ignoreCase = false)) {
                    rules.add(PronRule(key, guide))
                }
            }
        }

        // Longest phrases first to preserve multi-word terms like "Domain Expansion" before single words
        val orderedRules = rules
            .distinctBy { it.matchPhrase.lowercase(Locale.US) }
            .sortedByDescending { it.matchPhrase.length }

        var spoken = mixedScriptDisplayText.trim()
            .replace(Regex("""\[EN:([^\]]+)\]"""), "$1")

        for (rule in orderedRules) {
            val regex = buildUnicodeBoundaryRegex(rule.matchPhrase, ignoreCase = true)
            if (regex.containsMatchIn(spoken)) {
                spoken = regex.replace(spoken, Regex.escapeReplacement(rule.spokenGuide))
            }
        }

        // Clean Devanagari danda/whitespace for consistent pauses in TTS engines
        return spoken.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Normalizes a dictionary term key with Unicode NFC, whitespace collapsing,
     * edge punctuation stripping, and lowercase folding for deterministic duplicate prevention.
     */
    fun normalizeForComparison(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        return nfc
            .trim()
            .replace(Regex("\\s+"), " ")
            .trim { it in setOf('.', ',', '!', '?', ':', ';', '"', '\'', '「', '」', '『', '』', '।', '。', '、', '！', '？', '：', '；', '（', '）') }
            .trim()
            .lowercase(Locale.US)
    }

    fun normalizedTermKey(japaneseSourceTerm: String, preferredDisplay: String): String {
        val jp = normalizeForComparison(japaneseSourceTerm)
        return if (jp.isNotEmpty()) jp else normalizeForComparison(preferredDisplay)
    }

    fun normalizedCompositeKey(entry: DictionaryEntryEntity): String {
        val level = entry.dictionaryLevel.trim().uppercase(Locale.US).let {
            if (it in setOf("PROJECT", "ANIME", "GLOBAL")) it else "GLOBAL"
        }
        val scopeBinding = when (level) {
            "PROJECT" -> "P:${entry.projectId ?: -1L}"
            "ANIME" -> "A:${normalizeForComparison(entry.animeSeriesName.orEmpty())}"
            else -> "G"
        }
        val termKey = normalizedTermKey(entry.japaneseSourceTerm, preferredDisplayTerm(entry))
        return "$level|$scopeBinding|$termKey"
    }

    /**
     * Deterministic, pure-Kotlin dictionary import planner:
     * - Normalizes case, whitespace, punctuation, and Unicode NFC.
     * - Deduplicates repeated entries WITHIN the same import batch AND against existing DB entries
     *   by updating the in-memory lookup map after every processed entry (fixing stale comparison list bugs).
     * - Preserves existing DB entry IDs on update, skips identical no-op entries cleanly,
     *   counts malformed/unscoped entries under `failedCount`, and NEVER deletes existing entries.
     */
    fun planDictionaryImport(
        incomingEntries: List<DictionaryEntryEntity>,
        existingEntries: List<DictionaryEntryEntity>,
        malformedObjectCount: Int = 0
    ): DictionaryImportPlan {
        val existingByKey = LinkedHashMap<String, DictionaryEntryEntity>()
        for (ex in existingEntries) {
            existingByKey[normalizedCompositeKey(ex)] = ex
        }

        val insertsByKey = LinkedHashMap<String, DictionaryEntryEntity>()
        val updatesByKey = LinkedHashMap<String, DictionaryEntryEntity>()
        val warnings = mutableListOf<String>()

        var importedCount = 0
        var updatedCount = 0
        var skippedCount = 0
        var failedCount = malformedObjectCount.coerceAtLeast(0)

        for ((idx, raw) in incomingEntries.withIndex()) {
            val level = raw.dictionaryLevel.trim().uppercase(Locale.US).let {
                if (it in setOf("PROJECT", "ANIME", "GLOBAL")) it else "GLOBAL"
            }
            val cleanJp = Normalizer.normalize(raw.japaneseSourceTerm, Normalizer.Form.NFC).trim()
            val cleanMixed = Normalizer.normalize(raw.preferredMixedScript, Normalizer.Form.NFC).trim()
            val cleanHindi = Normalizer.normalize(raw.preferredHindiWording, Normalizer.Form.NFC).trim()
            val cleanEnglish = Normalizer.normalize(raw.preferredEnglishWording, Normalizer.Form.NFC).trim()
            val cleanRoman = Normalizer.normalize(raw.preferredRomanHinglish, Normalizer.Form.NFC).trim()
            val effectiveDisplay = cleanMixed.ifBlank { cleanHindi.ifBlank { cleanEnglish.ifBlank { cleanRoman } } }

            if (cleanJp.isBlank() && effectiveDisplay.isBlank()) {
                failedCount++
                warnings.add("Entry #${idx + 1}: Empty source and preferred wording.")
                continue
            }
            if (level == "PROJECT" && (raw.projectId == null || raw.projectId <= 0L)) {
                failedCount++
                warnings.add("Entry #${idx + 1} ('${cleanJp.ifBlank { effectiveDisplay }}'): PROJECT scope requires an active project.")
                continue
            }
            if (level == "ANIME" && raw.animeSeriesName.isNullOrBlank()) {
                failedCount++
                warnings.add("Entry #${idx + 1} ('${cleanJp.ifBlank { effectiveDisplay }}'): ANIME scope requires an animeSeriesName.")
                continue
            }

            val sanitized = raw.copy(
                id = 0L,
                dictionaryLevel = level,
                projectId = if (level == "PROJECT") raw.projectId else null,
                animeSeriesName = if (level == "ANIME") Normalizer.normalize(raw.animeSeriesName.orEmpty(), Normalizer.Form.NFC).trim() else null,
                japaneseSourceTerm = cleanJp,
                preferredMixedScript = effectiveDisplay,
                preferredRomanHinglish = cleanRoman.ifBlank { effectiveDisplay },
                preferredHindiWording = cleanHindi,
                preferredEnglishWording = cleanEnglish,
                pronunciationGuidance = Normalizer.normalize(raw.pronunciationGuidance, Normalizer.Form.NFC).trim(),
                contextNotes = Normalizer.normalize(raw.contextNotes, Normalizer.Form.NFC).trim(),
                replacementScope = raw.replacementScope.trim().ifBlank { level }
            )

            val compositeKey = normalizedCompositeKey(sanitized)
            val existingMatch = existingByKey[compositeKey]

            if (existingMatch != null) {
                val isIdentical = isSemanticallyEquivalent(existingMatch, sanitized)
                if (isIdentical) {
                    skippedCount++
                } else {
                    val updatedEntity = sanitized.copy(id = existingMatch.id)
                    if (existingMatch.id > 0L) {
                        // Updating an already-persisted DB row (preserve its original ID!)
                        if (!updatesByKey.containsKey(compositeKey)) {
                            updatedCount++
                        } else {
                            // Repeated entry within the same JSON batch updating the same existing row
                            skippedCount++
                        }
                        updatesByKey[compositeKey] = updatedEntity
                    } else {
                        // Repeated entry within the same JSON batch colliding with an earlier new entry
                        insertsByKey[compositeKey] = updatedEntity
                        skippedCount++
                    }
                    // Immediately update in-memory comparison map so later items compare against latest state!
                    existingByKey[compositeKey] = updatedEntity
                }
            } else {
                insertsByKey[compositeKey] = sanitized
                existingByKey[compositeKey] = sanitized
                importedCount++
            }
        }

        return DictionaryImportPlan(
            toInsert = insertsByKey.values.toList(),
            toUpdate = updatesByKey.values.toList(),
            importedCount = importedCount,
            updatedCount = updatedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            warnings = warnings
        )
    }

    private fun isSemanticallyEquivalent(a: DictionaryEntryEntity, b: DictionaryEntryEntity): Boolean {
        return normalizeForComparison(a.japaneseSourceTerm) == normalizeForComparison(b.japaneseSourceTerm) &&
            normalizeForComparison(preferredDisplayTerm(a)) == normalizeForComparison(preferredDisplayTerm(b)) &&
            normalizeForComparison(a.preferredHindiWording) == normalizeForComparison(b.preferredHindiWording) &&
            normalizeForComparison(a.preferredEnglishWording) == normalizeForComparison(b.preferredEnglishWording) &&
            normalizeForComparison(a.pronunciationGuidance) == normalizeForComparison(b.pronunciationGuidance) &&
            a.preserveOriginalEnglish == b.preserveOriginalEnglish
    }

    /**
     * Pure-Kotlin JSON exporter for the 3-tier dictionary (`Project > Anime > Global`).
     */
    fun exportDictionaryEntriesToJson(entries: List<DictionaryEntryEntity>): String {
        val sb = StringBuilder("[\n")
        entries.forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",\n")
            sb.append("  {")
            sb.append("\"dictionaryLevel\":\"").append(escapeJsonString(e.dictionaryLevel)).append("\",")
            sb.append("\"animeSeriesName\":\"").append(escapeJsonString(e.animeSeriesName.orEmpty())).append("\",")
            sb.append("\"japaneseSourceTerm\":\"").append(escapeJsonString(e.japaneseSourceTerm)).append("\",")
            sb.append("\"preferredMixedScript\":\"").append(escapeJsonString(e.preferredMixedScript)).append("\",")
            sb.append("\"preferredHindiWording\":\"").append(escapeJsonString(e.preferredHindiWording)).append("\",")
            sb.append("\"preferredEnglishWording\":\"").append(escapeJsonString(e.preferredEnglishWording)).append("\",")
            sb.append("\"preferredRomanHinglish\":\"").append(escapeJsonString(e.preferredRomanHinglish)).append("\",")
            sb.append("\"pronunciationGuidance\":\"").append(escapeJsonString(e.pronunciationGuidance)).append("\",")
            sb.append("\"replacementScope\":\"").append(escapeJsonString(e.replacementScope)).append("\",")
            sb.append("\"contextNotes\":\"").append(escapeJsonString(e.contextNotes)).append("\",")
            sb.append("\"preserveOriginalEnglish\":").append(e.preserveOriginalEnglish)
            sb.append("}")
        }
        sb.append("\n]")
        return sb.toString()
    }

    /**
     * Pure-Kotlin JSON parser for importing dictionary entries.
     * Validates scope (`PROJECT`, `ANIME`, `GLOBAL`) and binds `PROJECT` / `ANIME` entries safely
     * to the active project / anime series when omitted.
     */
    fun parseDictionaryEntriesFromJson(
        json: String,
        fallbackProjectId: Long?,
        fallbackAnimeSeriesName: String
    ): Result<List<DictionaryEntryEntity>> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return Result.failure(IllegalArgumentException("Dictionary import must be a valid JSON array `[...]`."))
        }
        val objRegex = Regex("\\{[^{}]*\\}")
        val parsed = mutableListOf<DictionaryEntryEntity>()
        val now = System.currentTimeMillis()
        for (match in objRegex.findAll(trimmed)) {
            val obj = match.value
            val rawLevel = extractJsonField(obj, "dictionaryLevel").uppercase(Locale.US)
            val level = when (rawLevel) {
                "PROJECT", "ANIME", "GLOBAL" -> rawLevel
                else -> "GLOBAL"
            }
            val jp = extractJsonField(obj, "japaneseSourceTerm").trim()
            val mixed = extractJsonField(obj, "preferredMixedScript").trim()
            val hindi = extractJsonField(obj, "preferredHindiWording").trim()
            val english = extractJsonField(obj, "preferredEnglishWording").trim()
            val roman = extractJsonField(obj, "preferredRomanHinglish").trim()
            val pron = extractJsonField(obj, "pronunciationGuidance").trim()
            val scopeStr = extractJsonField(obj, "replacementScope").trim().ifBlank { level }
            val notes = extractJsonField(obj, "contextNotes").trim()
            val preserveEn = extractJsonBool(obj, "preserveOriginalEnglish", true)
            val animeFromJson = extractJsonField(obj, "animeSeriesName").trim()

            val effectiveMixed = mixed.ifBlank { hindi.ifBlank { english.ifBlank { roman } } }
            if (jp.isBlank() && effectiveMixed.isBlank()) continue

            val effectiveProjectId = if (level == "PROJECT") fallbackProjectId else null
            val effectiveAnime = if (level == "ANIME") {
                animeFromJson.ifBlank { fallbackAnimeSeriesName }.takeIf { it.isNotBlank() }
            } else {
                null
            }
            parsed.add(
                DictionaryEntryEntity(
                    dictionaryLevel = level,
                    projectId = effectiveProjectId,
                    animeSeriesName = effectiveAnime,
                    japaneseSourceTerm = jp,
                    preferredRomanHinglish = roman.ifBlank { effectiveMixed },
                    preferredMixedScript = effectiveMixed,
                    preferredHindiWording = hindi,
                    preferredEnglishWording = english,
                    pronunciationGuidance = pron,
                    replacementScope = scopeStr,
                    contextNotes = notes,
                    preserveOriginalEnglish = preserveEn,
                    updatedAt = now
                )
            )
        }
        return if (parsed.isEmpty()) {
            Result.failure(IllegalArgumentException("No valid dictionary entries were found in the provided JSON."))
        } else {
            Result.success(parsed)
        }
    }

    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun unescapeJsonString(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
    }

    private fun extractJsonField(obj: String, key: String): String {
        val regex = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val raw = regex.find(obj)?.groupValues?.getOrNull(1) ?: return ""
        return unescapeJsonString(raw)
    }

    private fun extractJsonBool(obj: String, key: String, default: Boolean): Boolean {
        val regex = Regex("\"$key\"\\s*:\\s*(true|false)")
        return regex.find(obj)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull() ?: default
    }
}
