package com.example.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.data.local.DialogueSegmentEntity
import java.io.File
import kotlin.math.abs

data class VerificationCheckItem(
    val checkName: String,
    val passed: Boolean,
    val detail: String
)

data class FinalVerificationReport(
    val passed: Boolean,
    val checks: List<VerificationCheckItem>,
    val summary: String
)

/**
 * Agent 22 — Final Verification Agent.
 *
 * Strictly inspects the ACTUAL written output media file (`.wav` or `.mp4`), dialogue audio files,
 * and subtitle files before allowing an episode to be marked COMPLETED.
 *
 * Checks performed:
 * 1. Exported output file exists on disk with valid non-zero byte length.
 * 2. Audio integrity:
 *    - WAV: re-reads PCM samples via `AudioDspEngine.readWavFile()`, checks non-zero sample count,
 *      non-silent RMS (> -60 dBFS), safe peak headroom (<= 0.0 dBFS), and duration match.
 *    - MP4: re-opens container via `MediaExtractor`, verifies both video and audio MIME tracks
 *      exist with valid durations and sample rate.
 * 3. Dialogue completeness: verifies every dialogue segment has a real generated audio file on disk.
 * 4. Subtitle verification: runs `SubtitleGenerator.validateSubtitleFile()` on all generated `.srt`/`.ass` files.
 */
object FinalVerificationAgent {

    fun verifyExportedOutput(
        outputFile: File,
        exportFormat: String,
        expectedDurationMs: Long,
        dialogues: List<DialogueSegmentEntity>,
        subtitleFiles: List<File>
    ): FinalVerificationReport {
        val checks = mutableListOf<VerificationCheckItem>()

        // 1. Output file existence & minimum size
        val minBytes = if (exportFormat == "MP4_VIDEO") 1024L else 45L
        val fileExistsOk = outputFile.exists() && outputFile.length() >= minBytes
        checks.add(
            VerificationCheckItem(
                checkName = "Output File On Disk",
                passed = fileExistsOk,
                detail = if (fileExistsOk) {
                    "${outputFile.name} verified (${outputFile.length()} bytes)."
                } else {
                    "Output file missing or truncated (${outputFile.length()} bytes)."
                }
            )
        )

        // 2. Media stream & audio integrity
        if (fileExistsOk && exportFormat == "MP4_VIDEO") {
            checks.addAll(verifyMp4Container(outputFile, expectedDurationMs))
        } else if (fileExistsOk) {
            checks.addAll(verifyWavOutput(outputFile, expectedDurationMs))
        } else {
            checks.add(
                VerificationCheckItem(
                    checkName = "Media Stream Integrity",
                    passed = false,
                    detail = "Skipped: output file unavailable."
                )
            )
        }

        // 3. Dialogue completeness & text/timeline sanity
        if (dialogues.isNotEmpty()) {
            val missingDialogues = dialogues.filter { dlg ->
                val p = dlg.generatedAudioPath
                p.isNullOrBlank() || !File(p).exists() || File(p).length() <= 44L
            }
            val missingHinglish = dialogues.filter { it.romanHinglishText.isBlank() }
            val outOfBounds = if (expectedDurationMs > 0L) {
                dialogues.filter { it.timelineStartMs < 0L || it.timelineStartMs > expectedDurationMs + 500L }
            } else emptyList()
            val allDialoguesOk = missingDialogues.isEmpty() && missingHinglish.isEmpty() && outOfBounds.isEmpty()
            checks.add(
                VerificationCheckItem(
                    checkName = "Dialogue Audio, Text & Sync Completeness",
                    passed = allDialoguesOk,
                    detail = when {
                        allDialoguesOk -> "All ${dialogues.size} dialogue clips verified on disk with Roman Hinglish text and valid timeline positions."
                        missingDialogues.isNotEmpty() -> "${missingDialogues.size} of ${dialogues.size} dialogue clips missing audio (${missingDialogues.take(4).joinToString { it.dialogueId }})."
                        missingHinglish.isNotEmpty() -> "${missingHinglish.size} dialogue(s) missing Roman Hinglish text (${missingHinglish.take(4).joinToString { it.dialogueId }})."
                        else -> "${outOfBounds.size} dialogue(s) fall outside episode duration bounds (${expectedDurationMs}ms)."
                    }
                )
            )
        }

        // 4. Subtitle file validation
        if (subtitleFiles.isNotEmpty()) {
            val subResults = subtitleFiles.map {
                SubtitleGenerator.validateSubtitleFile(
                    file = it,
                    expectedCueCount = dialogues.size.takeIf { c -> c > 0 } ?: -1,
                    episodeDurationMs = expectedDurationMs
                )
            }
            val failedSubs = subResults.filterNot { it.isValid }
            checks.add(
                VerificationCheckItem(
                    checkName = "Subtitle Tracks (SRT + ASS)",
                    passed = failedSubs.isEmpty(),
                    detail = if (failedSubs.isEmpty()) {
                        "${subResults.size} subtitle files validated (${subResults.first().cueCount} cues each)."
                    } else {
                        failedSubs.joinToString("; ") { "${it.file.name}: ${it.errorSummary}" }
                    }
                )
            )
        }

        val allPassed = checks.all { it.passed }
        val passCount = checks.count { it.passed }
        val summary = if (allPassed) {
            "VERIFIED ($passCount/${checks.size} checks passed): ${outputFile.name}"
        } else {
            val failedNames = checks.filterNot { it.passed }.joinToString { it.checkName }
            "VERIFICATION FAILED ($passCount/${checks.size} passed) — Failed: $failedNames"
        }

        return FinalVerificationReport(
            passed = allPassed,
            checks = checks,
            summary = summary
        )
    }

    private fun verifyWavOutput(wavFile: File, expectedDurationMs: Long): List<VerificationCheckItem> {
        val items = mutableListOf<VerificationCheckItem>()
        val strictRes = AudioDspEngine.readWavFileStrict(wavFile)
        if (strictRes.isFailure) {
            items.add(
                VerificationCheckItem(
                    "WAV PCM Decode",
                    false,
                    "Invalid WAV container: ${strictRes.exceptionOrNull()?.localizedMessage ?: "malformed PCM header"}"
                )
            )
            return items
        }
        val buf = strictRes.getOrThrow()
        if (buf.samples.isEmpty()) {
            items.add(VerificationCheckItem("WAV PCM Decode", false, "WAV file contains 0 readable PCM samples."))
            return items
        }

        val rmsDbfs = AudioDspEngine.computeRmsDbfs(buf)
        val peakDbfs = AudioDspEngine.computePeakDbfs(buf)
        val notSilent = rmsDbfs > -60.0f
        val safePeak = peakDbfs <= 0.0f
        val durationDeltaMs = if (expectedDurationMs > 0L) abs(buf.durationMs - expectedDurationMs) else 0L
        val durationOk = expectedDurationMs <= 0L || durationDeltaMs <= 500L

        items.add(
            VerificationCheckItem(
                checkName = "WAV Audio Energy (Non-Silent)",
                passed = notSilent,
                detail = if (notSilent) "Measured RMS: $rmsDbfs dBFS (non-silent audio verified)." else "Output audio appears silent ($rmsDbfs dBFS)."
            )
        )
        items.add(
            VerificationCheckItem(
                checkName = "Peak Headroom & Clipping",
                passed = safePeak,
                detail = "Measured Peak: $peakDbfs dBFS (${buf.sampleRateHz} Hz, ${buf.channels} ch)."
            )
        )
        items.add(
            VerificationCheckItem(
                checkName = "Audio Duration Consistency",
                passed = durationOk,
                detail = "Output ${buf.durationMs} ms vs expected ${expectedDurationMs} ms (delta ${durationDeltaMs} ms)."
            )
        )
        return items
    }

    private fun verifyMp4Container(mp4File: File, expectedDurationMs: Long): List<VerificationCheckItem> {
        val items = mutableListOf<VerificationCheckItem>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mp4File.absolutePath)
            var hasVideo = false
            var hasAudio = false
            var videoDurationMs = 0L
            var audioDurationMs = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    videoDurationMs = dur
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    audioDurationMs = dur
                }
            }
            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Video & Audio Tracks",
                    passed = hasVideo && hasAudio,
                    detail = "VideoTrack=$hasVideo (${videoDurationMs}ms), AudioTrack=$hasAudio (${audioDurationMs}ms)."
                )
            )
            val measuredDur = maxOf(videoDurationMs, audioDurationMs)
            val delta = if (expectedDurationMs > 0L && measuredDur > 0L) abs(measuredDur - expectedDurationMs) else 0L
            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Duration Consistency",
                    passed = measuredDur > 0L && (expectedDurationMs <= 0L || delta <= 1500L),
                    detail = "Container duration ${measuredDur}ms vs expected ${expectedDurationMs}ms (delta ${delta}ms)."
                )
            )
        } catch (e: Exception) {
            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Container Inspection",
                    passed = false,
                    detail = "MediaExtractor failed to inspect MP4: ${e.localizedMessage}"
                )
            )
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
        return items
    }
}
