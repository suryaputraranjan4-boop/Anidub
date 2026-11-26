package com.example.engine

import com.example.data.local.DialogueSegmentEntity
import com.example.data.local.SpeakerProfileEntity
import java.io.File
import java.util.Locale

data class GeneratedSubtitleBundle(
    val japaneseSrtFile: File,
    val hindiHinglishSrtFile: File,
    val bilingualSrtFile: File,
    val styledAssFile: File,
    val cueCount: Int,
    val validationPassed: Boolean,
    val validationIssues: List<String>
)

data class SubtitleValidationResult(
    val file: File,
    val format: String,
    val cueCount: Int,
    val isValid: Boolean,
    val errorSummary: String
)

/**
 * Agent 21 — Subtitle Generation & Strict Validation Engine.
 *
 * Generates genuine, standards-compliant subtitle files synchronized to the final
 * chronological dialogue timestamps:
 *  - Japanese source SRT (`*_ja.srt`)
 *  - Roman Hinglish dubbed SRT (`*_hi_hinglish.srt`)
 *  - Bilingual Japanese + Hinglish SRT (`*_bilingual.srt`)
 *  - Styled Advanced SubStation Alpha ASS (`*_styled.ass`) with per-speaker color styling
 *
 * Strict Honesty Rules:
 * - Never injects fake placeholder strings like "(Pending Hinglish)" or "(No Japanese transcript)".
 * - `validateSubtitleFile` strictly rejects empty cues, placeholder strings, invalid timestamps,
 *   out-of-order cues, cues exceeding episode duration bounds, and cue count mismatches.
 */
object SubtitleGenerator {

    private val PLACEHOLDER_MARKERS = listOf(
        "(No Japanese transcript)",
        "(Pending Hinglish)",
        "(Pending Japanese)",
        "[Unrecognized]",
        "[Pending]",
        "[TODO]",
        "UNASSIGNED-",
        "UNIDENTIFIED-"
    )
    private val BARE_ID_REGEX = Regex("""^\[?[A-Z]{1,3}-\d{3,4}\]?$""")

    fun generateAllSubtitles(
        episodeTitle: String,
        dialogues: List<DialogueSegmentEntity>,
        speakersByLabel: Map<String, SpeakerProfileEntity>,
        outputDir: File,
        baseFileName: String,
        episodeDurationMs: Long = 0L
    ): GeneratedSubtitleBundle {
        outputDir.mkdirs()
        val sorted = dialogues.sortedBy { it.timelineStartMs }
        val safeBase = baseFileName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "episode_subtitles" }

        val jpFile = File(outputDir, "${safeBase}_ja.srt")
        val hiFile = File(outputDir, "${safeBase}_hi_hinglish.srt")
        val biFile = File(outputDir, "${safeBase}_bilingual.srt")
        val assFile = File(outputDir, "${safeBase}_styled.ass")

        jpFile.writeText(buildSrtContent(sorted) { d -> d.japaneseText.trim() })
        hiFile.writeText(buildSrtContent(sorted) { d -> d.romanHinglishText.trim() })
        biFile.writeText(buildSrtContent(sorted) { d ->
            val hi = d.romanHinglishText.trim()
            val jp = d.japaneseText.trim()
            when {
                hi.isNotEmpty() && jp.isNotEmpty() -> "$hi\n$jp"
                hi.isNotEmpty() -> hi
                else -> ""
            }
        })
        assFile.writeText(buildAssContent(episodeTitle, sorted, speakersByLabel))

        val validations = listOf(
            validateSubtitleFile(jpFile, expectedCueCount = sorted.size, episodeDurationMs = episodeDurationMs),
            validateSubtitleFile(hiFile, expectedCueCount = sorted.size, episodeDurationMs = episodeDurationMs),
            validateSubtitleFile(biFile, expectedCueCount = sorted.size, episodeDurationMs = episodeDurationMs),
            validateSubtitleFile(assFile, expectedCueCount = sorted.size, episodeDurationMs = episodeDurationMs)
        )
        val issues = validations.filterNot { it.isValid }.map { "${it.file.name}: ${it.errorSummary}" }

        return GeneratedSubtitleBundle(
            japaneseSrtFile = jpFile,
            hindiHinglishSrtFile = hiFile,
            bilingualSrtFile = biFile,
            styledAssFile = assFile,
            cueCount = sorted.size,
            validationPassed = issues.isEmpty() && sorted.isNotEmpty(),
            validationIssues = issues
        )
    }

    fun buildSrtContent(
        dialogues: List<DialogueSegmentEntity>,
        textSelector: (DialogueSegmentEntity) -> String
    ): String {
        val sb = StringBuilder()
        dialogues.forEachIndexed { idx, dlg ->
            val startMs = dlg.timelineStartMs.coerceAtLeast(0L)
            val durMs = (if (dlg.actualGeneratedDurationMs > 0L) dlg.actualGeneratedDurationMs else dlg.originalDurationMs)
                .coerceAtLeast(250L)
            val endMs = startMs + durMs
            sb.append(idx + 1).append('\n')
            sb.append(formatSrtTimestamp(startMs)).append(" --> ").append(formatSrtTimestamp(endMs)).append('\n')
            sb.append(textSelector(dlg).trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun buildAssContent(
        episodeTitle: String,
        dialogues: List<DialogueSegmentEntity>,
        speakersByLabel: Map<String, SpeakerProfileEntity>
    ): String {
        // ASS colors use &H00BBGGRR format
        val speakerAssColors = mapOf(
            "A" to "&H00F67C9D",
            "B" to "&H00F8BD38",
            "C" to "&H0099D334",
            "D" to "&H000B9EF5"
        )
        val sb = StringBuilder()
        sb.appendLine("[Script Info]")
        sb.appendLine("; Generated by AnimeDub Studio — Agent 21 Subtitle Engine")
        sb.appendLine("Title: $episodeTitle")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("PlayResX: 1920")
        sb.appendLine("PlayResY: 1080")
        sb.appendLine("ScaledBorderAndShadow: yes")
        sb.appendLine()
        sb.appendLine("[V4+ Styles]")
        sb.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        sb.appendLine("Style: Default,Arial,54,&H00FFFFFF,&H000000FF,&H00121212,&H64000000,-1,0,0,0,100,100,0,0,1,3,1,2,60,60,48,1")

        val labels = dialogues.map { it.speakerLabel }.distinct().sorted()
        for (lbl in labels) {
            val primaryColor = speakerAssColors[lbl] ?: "&H00FFFFFF"
            sb.appendLine("Style: Speaker_$lbl,Arial,54,$primaryColor,&H000000FF,&H00121212,&H64000000,-1,0,0,0,100,100,0,0,1,3,1,2,60,60,48,1")
        }

        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        for (dlg in dialogues) {
            val startMs = dlg.timelineStartMs.coerceAtLeast(0L)
            val durMs = (if (dlg.actualGeneratedDurationMs > 0L) dlg.actualGeneratedDurationMs else dlg.originalDurationMs)
                .coerceAtLeast(250L)
            val endMs = startMs + durMs
            val spName = (speakersByLabel[dlg.speakerLabel]?.characterName ?: "Speaker_${dlg.speakerLabel}")
                .replace(",", " ")
            val text = dlg.romanHinglishText.trim()
                .replace("\r\n", "\\N")
                .replace("\n", "\\N")
            sb.appendLine(
                "Dialogue: 0,${formatAssTimestamp(startMs)},${formatAssTimestamp(endMs)},Speaker_${dlg.speakerLabel},$spName,0,0,0,,$text"
            )
        }
        return sb.toString()
    }

    fun validateSubtitleFile(
        file: File,
        expectedCueCount: Int = -1,
        episodeDurationMs: Long = 0L
    ): SubtitleValidationResult {
        val ext = file.extension.uppercase(Locale.US)
        if (!file.exists() || file.length() == 0L) {
            return SubtitleValidationResult(file, ext, 0, false, "File missing or empty.")
        }
        val content = file.readText()
        return when (ext) {
            "SRT" -> validateSrtText(file, content, expectedCueCount, episodeDurationMs)
            "ASS" -> validateAssText(file, content, expectedCueCount, episodeDurationMs)
            else -> SubtitleValidationResult(file, ext, 0, false, "Unsupported subtitle extension: .$ext")
        }
    }

    private fun isPlaceholderOrEmptyText(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true
        if (PLACEHOLDER_MARKERS.any { trimmed.contains(it, ignoreCase = true) }) return true
        if (BARE_ID_REGEX.matches(trimmed)) return true
        return false
    }

    private fun validateSrtText(
        file: File,
        content: String,
        expectedCueCount: Int,
        episodeDurationMs: Long
    ): SubtitleValidationResult {
        val timeLineRegex = Regex("""^(\d{2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})$""")
        val normalized = content.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) {
            return SubtitleValidationResult(file, "SRT", 0, false, "SRT content is empty.")
        }
        val blocks = normalized.split(Regex("""\n{2,}""")).filter { it.isNotBlank() }
        if (blocks.isEmpty()) {
            return SubtitleValidationResult(file, "SRT", 0, false, "No SRT cue blocks found.")
        }

        var cueCount = 0
        var lastStart = -1L
        var lastEnd = -1L
        var overlapCount = 0

        for ((idx, block) in blocks.withIndex()) {
            val lines = block.lines()
            val timeLineIndex = lines.indexOfFirst { timeLineRegex.matches(it.trim()) }
            if (timeLineIndex < 0) {
                return SubtitleValidationResult(file, "SRT", cueCount, false, "Block #${idx + 1} missing valid SRT timestamp line.")
            }
            val m = timeLineRegex.matchEntire(lines[timeLineIndex].trim())!!
            val startMs = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val endMs = toMs(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            if (endMs <= startMs) {
                return SubtitleValidationResult(file, "SRT", blocks.size, false, "Cue #${idx + 1} has end <= start.")
            }
            if (startMs < lastStart) {
                return SubtitleValidationResult(file, "SRT", blocks.size, false, "Cue #${idx + 1} timestamp is out of chronological order.")
            }
            if (episodeDurationMs > 0L && endMs > episodeDurationMs + 1500L) {
                return SubtitleValidationResult(
                    file,
                    "SRT",
                    blocks.size,
                    false,
                    "Cue #${idx + 1} ends at ${endMs}ms, exceeding episode duration (${episodeDurationMs}ms)."
                )
            }
            if (lastEnd > startMs) overlapCount++
            lastStart = startMs
            lastEnd = maxOf(lastEnd, endMs)

            val payload = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
            if (isPlaceholderOrEmptyText(payload)) {
                return SubtitleValidationResult(
                    file,
                    "SRT",
                    blocks.size,
                    false,
                    "Cue #${idx + 1} has empty or placeholder subtitle text."
                )
            }
            cueCount++
        }

        if (expectedCueCount > 0 && cueCount != expectedCueCount) {
            return SubtitleValidationResult(
                file,
                "SRT",
                cueCount,
                false,
                "Cue count mismatch: found $cueCount, expected $expectedCueCount."
            )
        }
        val overlapSuffix = if (overlapCount > 0) " ($overlapCount overlapping cues)" else ""
        return SubtitleValidationResult(file, "SRT", cueCount, true, "Valid SRT ($cueCount cues)$overlapSuffix.")
    }

    private fun validateAssText(
        file: File,
        content: String,
        expectedCueCount: Int,
        episodeDurationMs: Long
    ): SubtitleValidationResult {
        val hasScriptInfo = content.contains("[Script Info]")
        val hasStyles = content.contains("[V4+ Styles]")
        val hasEvents = content.contains("[Events]")
        val dialogueLines = content.lines().filter { it.startsWith("Dialogue:") }
        if (!hasScriptInfo || !hasStyles || !hasEvents || dialogueLines.isEmpty()) {
            return SubtitleValidationResult(
                file,
                "ASS",
                dialogueLines.size,
                false,
                "Missing required ASS sections or 0 Dialogue events."
            )
        }

        var lastStart = -1L
        for ((idx, line) in dialogueLines.withIndex()) {
            val fields = line.removePrefix("Dialogue:").trim().split(",", limit = 10)
            if (fields.size < 10) {
                return SubtitleValidationResult(file, "ASS", dialogueLines.size, false, "ASS Dialogue #${idx + 1} has fewer than 10 fields.")
            }
            val startMs = parseAssTimestampMs(fields[1].trim())
            val endMs = parseAssTimestampMs(fields[2].trim())
            if (startMs < 0L || endMs <= startMs) {
                return SubtitleValidationResult(file, "ASS", dialogueLines.size, false, "ASS Dialogue #${idx + 1} has invalid start/end timestamps.")
            }
            if (startMs < lastStart) {
                return SubtitleValidationResult(file, "ASS", dialogueLines.size, false, "ASS Dialogue #${idx + 1} is out of chronological order.")
            }
            if (episodeDurationMs > 0L && endMs > episodeDurationMs + 1500L) {
                return SubtitleValidationResult(
                    file,
                    "ASS",
                    dialogueLines.size,
                    false,
                    "ASS Dialogue #${idx + 1} ends at ${endMs}ms, exceeding episode duration (${episodeDurationMs}ms)."
                )
            }
            lastStart = startMs

            val text = fields[9].replace("\\N", "\n").trim()
            if (isPlaceholderOrEmptyText(text)) {
                return SubtitleValidationResult(
                    file,
                    "ASS",
                    dialogueLines.size,
                    false,
                    "ASS Dialogue #${idx + 1} has empty or placeholder text."
                )
            }
        }

        if (expectedCueCount > 0 && dialogueLines.size != expectedCueCount) {
            return SubtitleValidationResult(
                file,
                "ASS",
                dialogueLines.size,
                false,
                "ASS cue count mismatch: found ${dialogueLines.size}, expected $expectedCueCount."
            )
        }
        return SubtitleValidationResult(file, "ASS", dialogueLines.size, true, "Valid ASS (${dialogueLines.size} events).")
    }

    fun formatSrtTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun formatAssTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val centis = (safe % 1_000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", hours, minutes, seconds, centis)
    }

    private fun parseAssTimestampMs(ts: String): Long {
        val m = Regex("""^(\d+):(\d{2}):(\d{2})\.(\d{2})$""").matchEntire(ts) ?: return -1L
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val cs = m.groupValues[4].toLong()
        return h * 3_600_000L + min * 60_000L + sec * 1_000L + cs * 10L
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        return h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + ms.toLong()
    }
}
