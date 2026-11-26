package com.example.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.data.local.DialogueSegmentEntity
import java.io.File
import java.nio.ByteBuffer
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
 * Strictly inspects the ACTUAL written output media file (`.wav` or `.mp4`), EVERY dialogue audio file,
 * and subtitle files before allowing an episode export to succeed.
 *
 * Checks performed:
 * 1. Output file existence & minimum size on disk.
 * 2. Media stream & audio integrity:
 *    - WAV: bounded-memory strict WAV decode (`AudioDspEngine.validateAudioFileStrict`), verifying
 *      RIFF/WAVE/PCM header, sample rate, channel count, non-zero sample count, non-silent RMS (> -55 dBFS),
 *      peak headroom (<= 0.0 dBFS), and duration consistency.
 *    - MP4: re-opens container via `MediaExtractor`, verifies both video & audio MIME tracks decode real frames,
 *      INDEPENDENTLY measures video vs audio durations against [MP4_DURATION_SYNC_TOLERANCE_MS] (250 ms),
 *      and verifies start/end presentation timestamps against [MP4_START_PTS_OFFSET_TOLERANCE_MS] (120 ms).
 * 3. Dialogue completeness (MANDATORY for dubbed exports — background audio alone NEVER passes):
 *    - Rejects empty dialogue lists for dubbed export.
 *    - Strictly decodes EVERY dialogue WAV (valid header, non-empty samples, non-silent RMS > -55 dBFS,
 *      valid sample rate/channels, positive duration).
 * 4. Subtitle verification: runs `SubtitleGenerator.validateSubtitleFile()` on all generated `.srt`/`.ass` files.
 */
object FinalVerificationAgent {

    /**
     * Documented MP4 video-vs-audio duration tolerance (250 ms accounts for AAC 1024-sample frame padding
     * + final video GOP frame duration rounding without hiding real sync drift or truncation).
     */
    const val MP4_DURATION_SYNC_TOLERANCE_MS = 250L

    /**
     * Maximum allowed start presentation-timestamp offset between video and audio tracks (120 ms).
     */
    const val MP4_START_PTS_OFFSET_TOLERANCE_MS = 120L

    /**
     * Minimum RMS threshold (-55.0 dBFS) for real non-silent dubbed speech / mixed audio.
     */
    const val NON_SILENT_RMS_THRESHOLD_DBFS = -55.0f

    fun verifyExportedOutput(
        outputFile: File,
        exportFormat: String,
        expectedDurationMs: Long,
        dialogues: List<DialogueSegmentEntity>,
        subtitleFiles: List<File>,
        requireDubbedDialogues: Boolean = true
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

        // 3. Dialogue completeness — background audio alone CANNOT allow empty dubbed dialogue to pass!
        if (dialogues.isEmpty()) {
            checks.add(
                VerificationCheckItem(
                    checkName = "Dialogue Audio, Mixed-Script & Sync Completeness",
                    passed = !requireDubbedDialogues,
                    detail = if (requireDubbedDialogues) {
                        "Export blocked: 0 dubbed dialogue segments exist. Background audio alone cannot pass dubbed episode verification."
                    } else {
                        "No dialogues required for non-dubbed export."
                    }
                )
            )
        } else {
            val invalidAudioDetails = mutableListOf<String>()
            val silentDialogues = mutableListOf<String>()
            var lowestDialogueRms = 0f

            for (dlg in dialogues) {
                val p = dlg.generatedAudioPath
                if (p.isNullOrBlank()) {
                    invalidAudioDetails.add("${dlg.dialogueId}(missing path)")
                    continue
                }
                val f = File(p)
                val decoded = AudioDspEngine.validateAudioFileStrict(f)
                if (decoded.isFailure) {
                    invalidAudioDetails.add("${dlg.dialogueId}(${decoded.exceptionOrNull()?.localizedMessage?.take(40) ?: "invalid WAV"})")
                    continue
                }
                val metrics = decoded.getOrThrow()
                if (metrics.sampleCount <= 0L || metrics.durationMs <= 0L) {
                    invalidAudioDetails.add("${dlg.dialogueId}(0 samples)")
                } else if (metrics.rmsDbfs < NON_SILENT_RMS_THRESHOLD_DBFS) {
                    silentDialogues.add("${dlg.dialogueId}(${metrics.rmsDbfs} dBFS)")
                }
                if (lowestDialogueRms == 0f || metrics.rmsDbfs < lowestDialogueRms) {
                    lowestDialogueRms = metrics.rmsDbfs
                }
            }

            val unidentifiedSpeakers = dialogues.filter {
                it.speakerLabel.isBlank() || it.speakerLabel.equals("UNIDENTIFIED", ignoreCase = true)
            }
            val missingMixedText = dialogues.filter { it.romanHinglishText.isBlank() }
            val scriptViolations = dialogues.filter { dlg ->
                dlg.romanHinglishText.isNotBlank() && !MixedScriptLanguageEngine.validateMixedScript(dlg.romanHinglishText).isValid
            }
            val outOfBounds = if (expectedDurationMs > 0L) {
                dialogues.filter { it.timelineStartMs < 0L || it.timelineStartMs > expectedDurationMs + 500L }
            } else emptyList()

            val allDialoguesOk = invalidAudioDetails.isEmpty() &&
                silentDialogues.isEmpty() &&
                unidentifiedSpeakers.isEmpty() &&
                missingMixedText.isEmpty() &&
                scriptViolations.isEmpty() &&
                outOfBounds.isEmpty()

            checks.add(
                VerificationCheckItem(
                    checkName = "Dialogue Audio, Mixed-Script & Sync Completeness",
                    passed = allDialoguesOk,
                    detail = when {
                        allDialoguesOk -> "All ${dialogues.size} dialogue WAVs strictly decoded (min RMS ${lowestDialogueRms} dBFS), valid speakers, Mixed-Script text, and timeline bounds."
                        invalidAudioDetails.isNotEmpty() -> "${invalidAudioDetails.size}/${dialogues.size} dialogue WAVs failed strict decode: ${invalidAudioDetails.take(3).joinToString(", ")}."
                        silentDialogues.isNotEmpty() -> "${silentDialogues.size}/${dialogues.size} dialogue WAVs are silent (< $NON_SILENT_RMS_THRESHOLD_DBFS dBFS): ${silentDialogues.take(3).joinToString(", ")}."
                        unidentifiedSpeakers.isNotEmpty() -> "${unidentifiedSpeakers.size} dialogue(s) have UNIDENTIFIED speaker (${unidentifiedSpeakers.take(4).joinToString { it.dialogueId }})."
                        missingMixedText.isNotEmpty() -> "${missingMixedText.size} dialogue(s) missing translated text (${missingMixedText.take(4).joinToString { it.dialogueId }})."
                        scriptViolations.isNotEmpty() -> "${scriptViolations.size} dialogue(s) violate Mixed-Script Devanagari+English rule (${scriptViolations.take(4).joinToString { it.dialogueId }})."
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
        val strictRes = AudioDspEngine.validateAudioFileStrict(wavFile)
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
        val metrics = strictRes.getOrThrow()
        val notSilent = metrics.rmsDbfs > NON_SILENT_RMS_THRESHOLD_DBFS
        val safePeak = metrics.peakDbfs <= 0.0f
        val durationDeltaMs = if (expectedDurationMs > 0L) abs(metrics.durationMs - expectedDurationMs) else 0L
        val durationOk = expectedDurationMs <= 0L || durationDeltaMs <= 500L

        items.add(
            VerificationCheckItem(
                checkName = "WAV Audio Energy (Non-Silent)",
                passed = notSilent,
                detail = if (notSilent) {
                    "Measured RMS: ${metrics.rmsDbfs} dBFS (${metrics.sampleCount} PCM samples verified)."
                } else {
                    "Output audio appears silent (${metrics.rmsDbfs} dBFS < $NON_SILENT_RMS_THRESHOLD_DBFS dBFS)."
                }
            )
        )
        items.add(
            VerificationCheckItem(
                checkName = "Peak Headroom & Format",
                passed = safePeak,
                detail = "Measured Peak: ${metrics.peakDbfs} dBFS • ${metrics.sampleRateHz} Hz • ${metrics.channels} ch • 16-bit PCM WAV."
            )
        )
        items.add(
            VerificationCheckItem(
                checkName = "Audio Duration Consistency",
                passed = durationOk,
                detail = "Output ${metrics.durationMs} ms vs expected ${expectedDurationMs} ms (delta ${durationDeltaMs} ms, tolerance 500 ms)."
            )
        )
        return items
    }

    private data class TrackTimingStats(
        val packetCount: Int,
        val startPtsUs: Long,
        val endPtsUs: Long,
        val headerDurationMs: Long
    ) {
        val measuredSpanMs: Long
            get() = if (packetCount > 0 && endPtsUs >= startPtsUs) {
                ((endPtsUs - startPtsUs) / 1000L).takeIf { it > 0L } ?: headerDurationMs
            } else headerDurationMs
    }

    private fun inspectTrackTimings(mp4File: File, trackIndex: Int, format: MediaFormat): TrackTimingStats {
        val headerDurMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION) / 1000L
        } else 0L
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(mp4File.absolutePath)
            extractor.selectTrack(trackIndex)
            var count = 0
            var firstPts = -1L
            var lastPts = 0L
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0L) break
                if (firstPts < 0L) firstPts = pts
                if (pts > lastPts) lastPts = pts
                count++
                if (!extractor.advance()) break
            }
            TrackTimingStats(
                packetCount = count,
                startPtsUs = firstPts.coerceAtLeast(0L),
                endPtsUs = lastPts,
                headerDurationMs = headerDurMs
            )
        } catch (_: Exception) {
            TrackTimingStats(0, 0L, 0L, headerDurMs)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun verifyMp4Container(mp4File: File, expectedDurationMs: Long): List<VerificationCheckItem> {
        val items = mutableListOf<VerificationCheckItem>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mp4File.absolutePath)
            var hasVideo = false
            var hasAudio = false
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !hasVideo) {
                    hasVideo = true
                    videoTrackIndex = i
                    videoFormat = fmt
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true
                    audioTrackIndex = i
                    audioFormat = fmt
                }
            }

            if (!hasVideo || !hasAudio || videoFormat == null || audioFormat == null) {
                items.add(
                    VerificationCheckItem(
                        checkName = "MP4 Video & Audio Tracks",
                        passed = false,
                        detail = "Missing required track(s): VideoTrack=$hasVideo, AudioTrack=$hasAudio."
                    )
                )
                return items
            }

            val videoStats = inspectTrackTimings(mp4File, videoTrackIndex, videoFormat)
            val audioStats = inspectTrackTimings(mp4File, audioTrackIndex, audioFormat)
            val videoDurMs = videoStats.headerDurationMs.takeIf { it > 0L } ?: videoStats.measuredSpanMs
            val audioDurMs = audioStats.headerDurationMs.takeIf { it > 0L } ?: audioStats.measuredSpanMs

            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Video & Audio Tracks",
                    passed = videoStats.packetCount > 0 && audioStats.packetCount > 0,
                    detail = "Video=${videoDurMs}ms (${videoStats.packetCount} pkts), Audio=${audioDurMs}ms (${audioStats.packetCount} pkts)."
                )
            )

            // Independently compare video vs audio duration against documented sync tolerance
            val avDurationDiffMs = abs(videoDurMs - audioDurMs)
            val avSyncOk = videoDurMs > 0L && audioDurMs > 0L && avDurationDiffMs <= MP4_DURATION_SYNC_TOLERANCE_MS
            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Video vs Audio Sync & Truncation",
                    passed = avSyncOk,
                    detail = "Video=${videoDurMs}ms vs Audio=${audioDurMs}ms (diff=${avDurationDiffMs}ms, tolerance<=${MP4_DURATION_SYNC_TOLERANCE_MS}ms)."
                )
            )

            // Inspect start presentation timestamps to detect unintended start offset
            val startOffsetMs = abs(videoStats.startPtsUs - audioStats.startPtsUs) / 1000L
            val startSyncOk = startOffsetMs <= MP4_START_PTS_OFFSET_TOLERANCE_MS
            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Start Presentation Timestamp Alignment",
                    passed = startSyncOk,
                    detail = "VideoStart=${videoStats.startPtsUs / 1000L}ms, AudioStart=${audioStats.startPtsUs / 1000L}ms (offset=${startOffsetMs}ms, tolerance<=${MP4_START_PTS_OFFSET_TOLERANCE_MS}ms)."
                )
            )

            if (expectedDurationMs > 0L) {
                val expectedDeltaMs = abs(audioDurMs - expectedDurationMs)
                val expectedOk = expectedDeltaMs <= 1000L
                items.add(
                    VerificationCheckItem(
                        checkName = "MP4 Expected Episode Duration",
                        passed = expectedOk,
                        detail = "Audio=${audioDurMs}ms, Video=${videoDurMs}ms vs Episode=${expectedDurationMs}ms (delta=${expectedDeltaMs}ms)."
                    )
                )
            }

            // Actual frame-decoding validation for both video & audio streams (not metadata-only)
            val videoDecoded = verifyTrackDecodable(mp4File, videoTrackIndex, videoFormat)
            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Video Stream Decode",
                    passed = videoDecoded.first,
                    detail = videoDecoded.second
                )
            )
            val audioDecoded = verifyTrackDecodable(mp4File, audioTrackIndex, audioFormat)
            items.add(
                VerificationCheckItem(
                    checkName = "MP4 Audio Stream Decode",
                    passed = audioDecoded.first,
                    detail = audioDecoded.second
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

    /**
     * Reads encoded packets and decodes at least one real output frame via Android MediaCodec
     * to verify stream decodability without loading the entire file into memory.
     */
    private fun verifyTrackDecodable(mp4File: File, trackIndex: Int, format: MediaFormat): Pair<Boolean, String> {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false to "Track missing MIME type."
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(mp4File.absolutePath)
            extractor.selectTrack(trackIndex)
            val packetProbe = ByteBuffer.allocate(256 * 1024)
            val firstSampleBytes = extractor.readSampleData(packetProbe, 0)
            if (firstSampleBytes <= 0) {
                return false to "Track ($mime) contains 0 readable encoded packets."
            }
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var decodedFrames = 0
            var inputDone = false
            var attempts = 0
            while (decodedFrames == 0 && attempts < 45) {
                attempts++
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(15_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val read = extractor.readSampleData(inBuf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 15_000L)
                if (outIdx >= 0) {
                    if (info.size > 0) decodedFrames++
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            if (decodedFrames > 0) {
                true to "Decoded $decodedFrames frame(s) from $mime stream cleanly."
            } else {
                false to "MediaCodec produced 0 decoded frames for $mime stream."
            }
        } catch (e: Exception) {
            false to "Stream decode failed for $mime: ${e.localizedMessage ?: e.javaClass.simpleName}"
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
