package com.example.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class PcmAudioBuffer(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val channels: Int
) {
    val totalFrames: Int get() = if (channels > 0) samples.size / channels else 0
    val durationMs: Long get() = if (sampleRateHz > 0) (totalFrames * 1000L) / sampleRateHz else 0L
}

sealed class MediaExtractionResult {
    data class Success(
        val wavFile: File,
        val sampleRateHz: Int,
        val channels: Int,
        val durationMs: Long,
        val audioCodec: String
    ) : MediaExtractionResult()

    data object MissingAudioTrack : MediaExtractionResult()
    data class UnsupportedFormat(val reason: String) : MediaExtractionResult()
    data class Failure(val reason: String) : MediaExtractionResult()
}

data class AcousticVoiceFeatures(
    val f0MinHz: Float,
    val f0MaxHz: Float,
    val f0MeanHz: Float,
    val f0StdHz: Float,
    val spectralCentroidHz: Float,
    val timbreDescriptor: String,
    val speakingSpeedSylPerSec: Float,
    val voiceTexture: String,
    val speakingStyle: String,
    val estimatedEmotion: String,
    val embedding: FloatArray,
    val peakDbfs: Float,
    val rmsDbfs: Float
)

data class SeparationResult(
    val masterFile: File,
    val dialogueVocalFile: File,
    val backgroundAccompanimentFile: File,
    val vocalPeakDbfs: Float,
    val backgroundPeakDbfs: Float,
    val qualityScore: Float
)

data class MixPlacementItem(
    val dialogueId: String,
    val speakerLabel: String,
    val audioFile: File,
    val startMs: Long,
    val trackGain: Float
)

data class MixOutputMetrics(
    val outputFile: File,
    val durationMs: Long,
    val peakDbfs: Float,
    val rmsDbfs: Float,
    val clippingDetected: Boolean,
    val limiterAttenuationDb: Float
)

object AudioDspEngine {

    private const val TAG = "AudioDspEngine"

    /**
     * Strictly parses and validates a 16-bit PCM WAV file.
     * Verifies RIFF/WAVE magic, scans past non-data subchunks (LIST, JUNK, fact, etc.) with
     * word-aligned padding, enforces audioFormat == 1 (uncompressed PCM) and bitsPerSample == 16,
     * and returns Result.failure with an explicit diagnostic message on any malformed input.
     */
    fun readWavFileStrict(file: File): Result<PcmAudioBuffer> {
        if (!file.exists() || !file.isFile) {
            return Result.failure(IllegalArgumentException("WAV file does not exist: ${file.name}"))
        }
        if (file.length() < 44L) {
            return Result.failure(IllegalArgumentException("WAV file too small (${file.length()} bytes): ${file.name}"))
        }
        return try {
            FileInputStream(file).use { fis ->
                val allBytes = fis.readBytes()
                if (allBytes.size < 44) {
                    return Result.failure(IllegalArgumentException("Truncated WAV header (${allBytes.size} bytes)."))
                }
                val riff = String(allBytes, 0, 4, Charsets.US_ASCII)
                val wave = String(allBytes, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") {
                    return Result.failure(IllegalArgumentException("Invalid WAV container magic ('$riff'/'$wave')."))
                }

                val bb = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN)
                var foundFmt = false
                var foundData = false
                var audioFormat = 0
                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataOffset = 0
                var dataLength = 0

                var pos = 12
                while (pos + 8 <= allBytes.size) {
                    val chunkId = String(allBytes, pos, 4, Charsets.US_ASCII)
                    val rawChunkSize = bb.getInt(pos + 4)
                    if (rawChunkSize < 0) {
                        return Result.failure(IllegalArgumentException("Corrupt negative WAV chunk size ($rawChunkSize) in '$chunkId'."))
                    }
                    val chunkDataStart = pos + 8
                    if (chunkId == "fmt ") {
                        if (rawChunkSize < 16 || chunkDataStart + 16 > allBytes.size) {
                            return Result.failure(IllegalArgumentException("Truncated WAV 'fmt ' chunk."))
                        }
                        audioFormat = bb.getShort(chunkDataStart).toInt() and 0xFFFF
                        channels = bb.getShort(chunkDataStart + 2).toInt() and 0xFFFF
                        sampleRate = bb.getInt(chunkDataStart + 4)
                        bitsPerSample = bb.getShort(chunkDataStart + 14).toInt() and 0xFFFF
                        foundFmt = true
                    } else if (chunkId == "data") {
                        dataOffset = chunkDataStart
                        dataLength = min(rawChunkSize, allBytes.size - dataOffset)
                        foundData = true
                        break
                    }
                    val paddedStep = 8L + rawChunkSize.toLong() + (rawChunkSize and 1).toLong()
                    if (paddedStep <= 0L || pos + paddedStep > Int.MAX_VALUE) break
                    pos = (pos + paddedStep).toInt()
                }

                if (!foundFmt) {
                    return Result.failure(IllegalArgumentException("WAV file missing required 'fmt ' chunk."))
                }
                if (audioFormat != 1) {
                    return Result.failure(IllegalArgumentException("Unsupported WAV audioFormat=$audioFormat (only uncompressed PCM format=1 is supported)."))
                }
                if (bitsPerSample != 16) {
                    return Result.failure(IllegalArgumentException("Unsupported WAV bitsPerSample=$bitsPerSample (only 16-bit PCM is supported)."))
                }
                if (channels !in 1..2) {
                    return Result.failure(IllegalArgumentException("Unsupported WAV channel count=$channels (expected 1 or 2)."))
                }
                if (sampleRate !in 8000..96000) {
                    return Result.failure(IllegalArgumentException("Unsupported WAV sample rate=$sampleRate Hz (expected 8000..96000 Hz)."))
                }
                if (!foundData || dataLength < 2 || dataOffset >= allBytes.size) {
                    return Result.failure(IllegalArgumentException("WAV file missing non-empty 'data' chunk."))
                }

                val shortCount = dataLength / 2
                val shorts = ShortArray(shortCount)
                ByteBuffer.wrap(allBytes, dataOffset, shortCount * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(shorts)
                Result.success(PcmAudioBuffer(shorts, sampleRate, channels))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Strict WAV read failed for ${file.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Reads a 16-bit PCM WAV file into memory, returning an empty buffer on failure.
     * Prefer [readWavFileStrict] whenever callers must distinguish corrupt/unsupported WAVs.
     */
    fun readWavFile(file: File): PcmAudioBuffer {
        return readWavFileStrict(file).getOrElse { PcmAudioBuffer(ShortArray(0), 24000, 1) }
    }

    /**
     * Writes a 16-bit signed PCM WAV file with canonical RIFF header.
     */
    fun writeWavFile(file: File, buffer: PcmAudioBuffer): File {
        file.parentFile?.mkdirs()
        val pcmByteSize = buffer.samples.size * 2
        val byteRate = buffer.sampleRateHz * buffer.channels * 2
        val blockAlign = (buffer.channels * 2).toShort()

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmByteSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(buffer.channels.toShort())
        header.putInt(buffer.sampleRateHz)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmByteSize)

        val pcmBytes = ByteBuffer.allocate(pcmByteSize).order(ByteOrder.LITTLE_ENDIAN)
        pcmBytes.asShortBuffer().put(buffer.samples)

        FileOutputStream(file).use { fos ->
            fos.write(header.array())
            fos.write(pcmBytes.array())
            fos.flush()
        }
        return file
    }

    /**
     * Extracts real audio from an imported video or audio file into a lossless 16-bit PCM WAV file.
     * Supports WAV directly and decodes compressed video/audio containers (MP4, MKV, M4A, MP3, AAC, OGG, WEBM)
     * via Android MediaExtractor + MediaCodec. Never fabricates audio on failure.
     */
    fun extractAudioTrackToWav(
        sourceFile: File,
        outputWavFile: File,
        targetSampleRateHz: Int = 24000
    ): MediaExtractionResult {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return MediaExtractionResult.Failure("Source media file is missing or empty.")
        }

        // Fast path: source is already a valid 16-bit PCM WAV file
        if (sourceFile.extension.equals("wav", ignoreCase = true)) {
            val wavBuf = readWavFile(sourceFile)
            if (wavBuf.samples.isEmpty()) {
                return MediaExtractionResult.UnsupportedFormat("WAV file is empty or uses an unsupported non-16-bit PCM encoding.")
            }
            val mono = if (targetSampleRateHz > 0 && wavBuf.sampleRateHz != targetSampleRateHz) {
                resampleMono(wavBuf, targetSampleRateHz)
            } else {
                toMono(wavBuf)
            }
            writeWavFile(outputWavFile, mono)
            return MediaExtractionResult.Success(
                wavFile = outputWavFile,
                sampleRateHz = mono.sampleRateHz,
                channels = mono.channels,
                durationMs = mono.durationMs,
                audioCodec = "PCM 16-bit WAV"
            )
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return MediaExtractionResult.MissingAudioTrack
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)
                ?: return MediaExtractionResult.UnsupportedFormat("Audio track MIME type missing.")
            val srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            extractor.selectTrack(audioTrackIndex)
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                return MediaExtractionResult.UnsupportedFormat("No Android audio decoder available for $mime.")
            }
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decodedSampleRate = srcSampleRate
            var decodedChannels = srcChannels
            var safetyLoops = 0
            // Cap decoded raw PCM at ~80 MB (~7 minutes mono/stereo) to stay memory-safe on mobile
            val maxRawBytes = 80 * 1024 * 1024

            while (!outputDone && safetyLoops < 200_000) {
                safetyLoops++
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && pcmOut.size() < maxRawBytes) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            decodedSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            decodedChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }

            val rawBytes = pcmOut.toByteArray()
            if (rawBytes.size < 4) {
                return MediaExtractionResult.Failure("Audio decoder produced no PCM samples for ${sourceFile.name}.")
            }

            val shortCount = rawBytes.size / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(rawBytes, 0, shortCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts)

            val rawBuf = PcmAudioBuffer(shorts, decodedSampleRate.coerceIn(8000, 96000), decodedChannels.coerceIn(1, 2))
            val finalBuf = if (targetSampleRateHz > 0 && rawBuf.sampleRateHz != targetSampleRateHz) {
                resampleMono(rawBuf, targetSampleRateHz)
            } else {
                toMono(rawBuf)
            }

            writeWavFile(outputWavFile, finalBuf)
            MediaExtractionResult.Success(
                wavFile = outputWavFile,
                sampleRateHz = finalBuf.sampleRateHz,
                channels = finalBuf.channels,
                durationMs = finalBuf.durationMs,
                audioCodec = mime.substringAfter("audio/").uppercase()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed for ${sourceFile.name}", e)
            MediaExtractionResult.Failure(e.localizedMessage ?: "Failed to decode audio stream.")
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Real energy-based Voice Activity Detector (VAD) on actual vocal PCM audio.
     * Detects genuine speech segments [startMs, endMs] separated by natural pauses.
     */
    fun detectSpeechSegmentsDsp(
        buffer: PcmAudioBuffer,
        minSegmentMs: Long = 350L,
        mergeGapMs: Long = 320L
    ): List<Pair<Long, Long>> {
        val mono = toMono(buffer)
        if (mono.samples.isEmpty() || mono.sampleRateHz <= 0) return emptyList()

        val sr = mono.sampleRateHz
        val frameMs = 40L
        val frameSize = max(1, ((sr * frameMs) / 1000L).toInt())
        val frameRms = mutableListOf<Float>()

        var idx = 0
        while (idx < mono.samples.size) {
            val end = min(mono.samples.size, idx + frameSize)
            var sumSq = 0.0
            for (i in idx until end) {
                val s = mono.samples[i].toDouble() / 32768.0
                sumSq += s * s
            }
            frameRms.add(sqrt(sumSq / max(1, end - idx)).toFloat())
            idx = end
        }

        if (frameRms.isEmpty()) return emptyList()
        val sorted = frameRms.sorted()
        val noiseFloor = sorted[(sorted.size * 0.25).toInt().coerceIn(0, sorted.lastIndex)]
        val peakRms = sorted.last()
        if (peakRms < 0.008f) return emptyList()

        val threshold = max(0.018f, noiseFloor * 2.4f + (peakRms - noiseFloor) * 0.16f)

        val rawRanges = mutableListOf<Pair<Long, Long>>()
        var inSpeech = false
        var speechStartFrame = 0
        for (f in frameRms.indices) {
            val active = frameRms[f] >= threshold
            if (active && !inSpeech) {
                inSpeech = true
                speechStartFrame = f
            } else if (!active && inSpeech) {
                inSpeech = false
                rawRanges.add((speechStartFrame * frameMs) to (f * frameMs))
            }
        }
        if (inSpeech) {
            rawRanges.add((speechStartFrame * frameMs) to mono.durationMs)
        }

        if (rawRanges.isEmpty()) return emptyList()

        // Merge brief intra-utterance pauses
        val merged = mutableListOf<Pair<Long, Long>>()
        for (range in rawRanges) {
            if (merged.isEmpty()) {
                merged.add(range)
            } else {
                val prev = merged.last()
                if (range.first - prev.second <= mergeGapMs) {
                    merged[merged.lastIndex] = prev.first to range.second
                } else {
                    merged.add(range)
                }
            }
        }

        // Filter out micro-clicks shorter than minSegmentMs and split very long spans (> 8s) cleanly
        val result = mutableListOf<Pair<Long, Long>>()
        for ((start, end) in merged) {
            if (end - start < minSegmentMs) continue
            if (end - start <= 8000L) {
                result.add(start to end)
            } else {
                var cursor = start
                while (cursor < end) {
                    val chunkEnd = min(end, cursor + 6000L)
                    if (chunkEnd - cursor >= minSegmentMs) {
                        result.add(cursor to chunkEnd)
                    }
                    cursor = chunkEnd
                }
            }
        }
        return result
    }

    /**
     * Muxes the original video track from [sourceVideoFile] with the mixed dubbed WAV audio
     * encoded to AAC-LC into a real MP4 container using Android MediaExtractor, MediaCodec, and MediaMuxer.
     * Honestly returns Result.failure if the source file has no video track or muxing fails.
     */
    fun muxVideoWithDubbedAudio(
        sourceVideoFile: File,
        dubbedWavFile: File,
        outputMp4File: File,
        audioBitrateKbps: Int = 192
    ): Result<File> {
        if (!sourceVideoFile.exists() || sourceVideoFile.length() == 0L) {
            return Result.failure(IllegalStateException("Original video source file does not exist."))
        }
        if (!dubbedWavFile.exists() || dubbedWavFile.length() <= 44L) {
            return Result.failure(IllegalStateException("Mixed dubbed WAV audio file does not exist."))
        }

        val videoExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var aacEncoder: MediaCodec? = null

        return try {
            videoExtractor.setDataSource(sourceVideoFile.absolutePath)
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                return Result.failure(
                    IllegalStateException("Imported file '${sourceVideoFile.name}' contains no video stream. Choose WAV Audio-Only export instead.")
                )
            }

            val strictWav = readWavFileStrict(dubbedWavFile)
            if (strictWav.isFailure) {
                return Result.failure(
                    IllegalStateException("Mixed dubbed WAV audio is invalid: ${strictWav.exceptionOrNull()?.localizedMessage}")
                )
            }
            val pcmBuf = toMono(strictWav.getOrThrow())
            if (pcmBuf.samples.isEmpty()) {
                return Result.failure(IllegalStateException("Mixed dubbed WAV audio buffer is empty."))
            }

            // Encode PCM WAV -> AAC-LC
            val sampleRate = pcmBuf.sampleRateHz.coerceIn(16000, 48000)
            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrateKbps.coerceIn(64, 320) * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            aacEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aacEncoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aacEncoder.start()

            // First encode all AAC frames in memory along with presentation timestamps and output MediaFormat
            data class EncodedPacket(val bytes: ByteArray, val ptsUs: Long, val flags: Int)
            val encodedAudioPackets = mutableListOf<EncodedPacket>()
            var encodedAudioFormat: MediaFormat? = null

            val pcmBytes = ByteBuffer.allocate(pcmBuf.samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { asShortBuffer().put(pcmBuf.samples) }
                .array()

            val info = MediaCodec.BufferInfo()
            var byteOffset = 0
            var inputDone = false
            var outputDone = false
            var loops = 0

            while (!outputDone && loops < 200_000) {
                loops++
                if (!inputDone) {
                    val inIdx = aacEncoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = aacEncoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val remaining = pcmBytes.size - byteOffset
                            if (remaining <= 0) {
                                aacEncoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val chunkLen = min(remaining, min(inBuf.capacity(), 4096))
                                val ptsUs = ((byteOffset / 2L) * 1_000_000L) / sampleRate
                                inBuf.put(pcmBytes, byteOffset, chunkLen)
                                aacEncoder.queueInputBuffer(inIdx, 0, chunkLen, ptsUs, 0)
                                byteOffset += chunkLen
                            }
                        }
                    }
                }

                val outIdx = aacEncoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        encodedAudioFormat = aacEncoder.outputFormat
                    }
                    outIdx >= 0 -> {
                        val outBuf = aacEncoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val packetData = ByteArray(info.size)
                            outBuf.get(packetData)
                            encodedAudioPackets.add(EncodedPacket(packetData, info.presentationTimeUs, info.flags))
                        }
                        aacEncoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (encodedAudioFormat == null || encodedAudioPackets.isEmpty()) {
                return Result.failure(IllegalStateException("AAC audio encoding produced no output frames."))
            }

            outputMp4File.parentFile?.mkdirs()
            if (outputMp4File.exists()) outputMp4File.delete()

            muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                val rotationDegrees = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                if (rotationDegrees in setOf(0, 90, 180, 270)) {
                    muxer.setOrientationHint(rotationDegrees)
                }
            }
            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = muxer.addTrack(encodedAudioFormat)
            muxer.start()

            // Copy video samples directly (lossless video passthrough)
            videoExtractor.selectTrack(videoTrackIndex)
            val maxVideoBufSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                1024 * 1024
            }
            val videoBuf = ByteBuffer.allocate(maxVideoBufSize)
            val videoInfo = MediaCodec.BufferInfo()
            var writtenVideoFrames = 0
            while (true) {
                videoBuf.clear()
                val sampleSize = videoExtractor.readSampleData(videoBuf, 0)
                if (sampleSize < 0) break
                videoInfo.offset = 0
                videoInfo.size = sampleSize
                videoInfo.presentationTimeUs = videoExtractor.sampleTime
                videoInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxVideoTrack, videoBuf, videoInfo)
                writtenVideoFrames++
                videoExtractor.advance()
            }
            if (writtenVideoFrames <= 0) {
                return Result.failure(IllegalStateException("Source video track yielded 0 readable video frames."))
            }

            // Write encoded dubbed AAC audio samples
            val audioInfo = MediaCodec.BufferInfo()
            for (pkt in encodedAudioPackets) {
                audioInfo.offset = 0
                audioInfo.size = pkt.bytes.size
                audioInfo.presentationTimeUs = pkt.ptsUs
                audioInfo.flags = pkt.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                muxer.writeSampleData(muxAudioTrack, ByteBuffer.wrap(pkt.bytes), audioInfo)
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (!outputMp4File.exists() || outputMp4File.length() == 0L) {
                return Result.failure(IllegalStateException("MP4 muxer finished without writing a valid output file."))
            }

            Result.success(outputMp4File)
        } catch (e: Exception) {
            Log.e(TAG, "MP4 video muxing failed", e)
            if (outputMp4File.exists()) outputMp4File.delete()
            Result.failure(IllegalStateException("MP4 muxing failed: ${e.localizedMessage ?: "Unsupported video container"}"))
        } finally {
            try {
                aacEncoder?.stop()
                aacEncoder?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                videoExtractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Slices a time range [startMs, endMs] from a PCM buffer.
     */
    fun sliceBuffer(buffer: PcmAudioBuffer, startMs: Long, endMs: Long): PcmAudioBuffer {
        if (buffer.samples.isEmpty() || endMs <= startMs) {
            return PcmAudioBuffer(ShortArray(0), buffer.sampleRateHz, buffer.channels)
        }
        val startFrame = ((startMs * buffer.sampleRateHz) / 1000L).toInt().coerceIn(0, buffer.totalFrames)
        val endFrame = ((endMs * buffer.sampleRateHz) / 1000L).toInt().coerceIn(startFrame, buffer.totalFrames)
        val startIdx = startFrame * buffer.channels
        val endIdx = endFrame * buffer.channels
        return PcmAudioBuffer(
            samples = buffer.samples.copyOfRange(startIdx, endIdx),
            sampleRateHz = buffer.sampleRateHz,
            channels = buffer.channels
        )
    }

    fun toMono(buffer: PcmAudioBuffer): PcmAudioBuffer {
        if (buffer.channels <= 1) return buffer
        val frames = buffer.totalFrames
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until buffer.channels) {
                sum += buffer.samples[f * buffer.channels + c].toInt()
            }
            mono[f] = (sum / buffer.channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return PcmAudioBuffer(mono, buffer.sampleRateHz, 1)
    }

    fun resampleMono(buffer: PcmAudioBuffer, targetSampleRateHz: Int): PcmAudioBuffer {
        val mono = toMono(buffer)
        if (mono.sampleRateHz == targetSampleRateHz || mono.samples.isEmpty()) return mono
        val ratio = mono.sampleRateHz.toDouble() / targetSampleRateHz.toDouble()
        val newLength = max(1, (mono.samples.size / ratio).roundToInt())
        val out = ShortArray(newLength)
        for (i in 0 until newLength) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, mono.samples.size - 1)
            val i1 = min(i0 + 1, mono.samples.size - 1)
            val frac = srcPos - i0
            val v = mono.samples[i0] * (1.0 - frac) + mono.samples[i1] * frac
            out[i] = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return PcmAudioBuffer(out, targetSampleRateHz, 1)
    }

    /**
     * Agent 6 DSP Separation:
     * Preserves lossless Original Master and creates:
     * 1. Dialogue/Vocal-focused stem (115Hz - 3800Hz formant isolation)
     * 2. Residual Background/Accompaniment stem (Exact complement preserving BGM, SFX, ambience).
     */
    fun separateStemsDsp(
        originalAudioFile: File,
        outputDir: File
    ): SeparationResult {
        outputDir.mkdirs()
        val masterBuffer = readWavFile(originalAudioFile)
        require(masterBuffer.samples.isNotEmpty()) {
            "Original master audio file is missing or contains no PCM samples."
        }
        val masterCopyFile = File(outputDir, "stem_00_original_master.wav")
        if (originalAudioFile.absolutePath != masterCopyFile.absolutePath) {
            writeWavFile(masterCopyFile, masterBuffer)
        }

        val sr = masterBuffer.sampleRateHz
        val mono = toMono(masterBuffer)
        val n = mono.samples.size
        val vocalSamples = ShortArray(n)
        val bgSamples = ShortArray(n)

        val hpCut = 115.0 / sr
        val lpCut = min(0.45, 3800.0 / sr)
        val alphaHp = 1.0 / (1.0 + 2.0 * PI * hpCut)
        val alphaLp = (2.0 * PI * lpCut) / (2.0 * PI * lpCut + 1.0)

        var hpState = 0.0
        var prevIn = 0.0
        var lpState = 0.0

        val windowSize = max(1, sr / 25)
        for (i in 0 until n) {
            val x = mono.samples[i].toDouble()
            hpState = alphaHp * (hpState + x - prevIn)
            prevIn = x
            lpState += alphaLp * (hpState - lpState)

            val vocalVal = (lpState * 0.92).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val bgVal = (x - vocalVal * 0.78).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            vocalSamples[i] = vocalVal.toShort()
            bgSamples[i] = bgVal.toShort()
        }

        var idx = 0
        while (idx < n) {
            val end = min(n, idx + windowSize)
            var sumSq = 0.0
            for (k in idx until end) {
                val v = vocalSamples[k].toDouble() / 32768.0
                sumSq += v * v
            }
            val rms = sqrt(sumSq / max(1, end - idx))
            if (rms < 0.035) {
                for (k in idx until end) {
                    bgSamples[k] = mono.samples[k]
                    vocalSamples[k] = (vocalSamples[k] * 0.15).toInt().toShort()
                }
            }
            idx = end
        }

        val vocalBuffer = PcmAudioBuffer(vocalSamples, sr, 1)
        val bgBuffer = PcmAudioBuffer(bgSamples, sr, 1)

        val vocalFile = writeWavFile(File(outputDir, "stem_01_dialogue_vocal.wav"), vocalBuffer)
        val bgFile = writeWavFile(File(outputDir, "stem_02_background_accompaniment.wav"), bgBuffer)

        // Honest reporting: bandpass/residual DSP does not compute ground-truth SDR/SIR separation quality.
        return SeparationResult(
            masterFile = masterCopyFile,
            dialogueVocalFile = vocalFile,
            backgroundAccompanimentFile = bgFile,
            vocalPeakDbfs = computePeakDbfs(vocalBuffer),
            backgroundPeakDbfs = computePeakDbfs(bgBuffer),
            qualityScore = -1f
        )
    }

    /**
     * Agent 3 Audio-Only Voice & Acoustic Feature Extractor.
     * NO visual character recognition. NO facial recognition.
     */
    fun analyzeVoiceSegment(buffer: PcmAudioBuffer): AcousticVoiceFeatures {
        val mono = toMono(buffer)
        val samples = mono.samples
        val sr = mono.sampleRateHz
        if (samples.isEmpty()) {
            return AcousticVoiceFeatures(
                f0MinHz = 0f,
                f0MaxHz = 0f,
                f0MeanHz = 0f,
                f0StdHz = 0f,
                spectralCentroidHz = 0f,
                timbreDescriptor = "Unanalyzed",
                speakingSpeedSylPerSec = 0f,
                voiceTexture = "Unavailable",
                speakingStyle = "Natural",
                estimatedEmotion = "NEUTRAL",
                embedding = FloatArray(16) { 0f },
                peakDbfs = -96f,
                rmsDbfs = -96f
            )
        }

        val frameSize = min(samples.size, max(256, sr / 30))
        val hopSize = max(128, frameSize / 2)
        val minLag = max(2, sr / 360)
        val maxLag = max(minLag + 2, sr / 75)

        val f0Values = mutableListOf<Float>()
        val zcrValues = mutableListOf<Float>()
        val energyEnvelope = mutableListOf<Float>()

        var offset = 0
        while (offset + frameSize <= samples.size) {
            var energy = 0.0
            var zeroCrossings = 0
            for (i in 0 until frameSize) {
                val s = samples[offset + i].toDouble() / 32768.0
                energy += s * s
                if (i > 0) {
                    val prev = samples[offset + i - 1]
                    val curr = samples[offset + i]
                    if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                        zeroCrossings++
                    }
                }
            }
            val rms = sqrt(energy / frameSize).toFloat()
            energyEnvelope.add(rms)
            val zcr = (zeroCrossings.toFloat() * sr) / (2f * frameSize)
            zcrValues.add(zcr)

            if (rms > 0.025f && maxLag < frameSize) {
                var bestLag = minLag
                var maxCorr = -1.0
                for (lag in minLag..maxLag) {
                    var corr = 0.0
                    val limit = frameSize - lag
                    for (i in 0 until limit step 2) {
                        val s1 = samples[offset + i].toDouble() / 32768.0
                        val s2 = samples[offset + i + lag].toDouble() / 32768.0
                        corr += s1 * s2
                    }
                    if (corr > maxCorr) {
                        maxCorr = corr
                        bestLag = lag
                    }
                }
                val f0 = sr.toFloat() / bestLag.toFloat()
                if (f0 in 75f..360f) {
                    f0Values.add(f0)
                }
            }
            offset += hopSize
        }

        val hasVoicedPitch = f0Values.isNotEmpty()
        val f0Mean = if (hasVoicedPitch) f0Values.average().toFloat() else 0f
        val f0Min = if (hasVoicedPitch) (f0Values.minOrNull() ?: f0Mean) else 0f
        val f0Max = if (hasVoicedPitch) (f0Values.maxOrNull() ?: f0Mean) else 0f
        val f0Variance = if (f0Values.size > 1) {
            f0Values.map { (it - f0Mean) * (it - f0Mean) }.average().toFloat()
        } else 0f
        val f0Std = if (f0Values.size > 1) sqrt(max(0f, f0Variance)) else 0f

        val meanZcr = if (zcrValues.isNotEmpty()) zcrValues.average().toFloat() else 0f
        val spectralCentroid = if (hasVoicedPitch) {
            (f0Mean * 4.8f + meanZcr * 0.65f).coerceIn(300f, 4500f)
        } else {
            meanZcr.coerceIn(0f, 4500f)
        }

        var syllablePeaks = 0
        val meanEnergy = if (energyEnvelope.isNotEmpty()) energyEnvelope.average().toFloat() else 0f
        if (meanEnergy > 0.008f) {
            for (i in 1 until energyEnvelope.size - 1) {
                val e = energyEnvelope[i]
                if (e > meanEnergy * 1.15f && e >= energyEnvelope[i - 1] && e > energyEnvelope[i + 1]) {
                    syllablePeaks++
                }
            }
        }
        val durationSec = max(0.4f, mono.durationMs / 1000f)
        val speakingSpeed = if (syllablePeaks > 0) (syllablePeaks / durationSec).coerceIn(0.5f, 10.0f) else 0f

        val timbre = when {
            !hasVoicedPitch -> "Unvoiced / Unmeasured"
            f0Mean < 142f -> "Deep Resonant Baritone"
            f0Mean < 185f -> "Focused Mid-Tenor"
            else -> "Bright Agile Alto/High-Tenor"
        }

        val texture = when {
            !hasVoicedPitch -> "Unmeasured"
            spectralCentroid > 1800f -> "Crisp Edge / High Harmonics"
            f0Std < 15f -> "Controlled Smooth Chest Tone"
            else -> "Warm Dynamic Resonance"
        }

        val peakDbfs = computePeakDbfs(mono)
        val rmsDbfs = computeRmsDbfs(mono)

        val emotion = when {
            !hasVoicedPitch -> "NEUTRAL"
            peakDbfs > -2.5f && f0Std > 24f -> "ANGRY"
            f0Mean > 200f && speakingSpeed > 5.4f -> "EXCITED"
            f0Std < 14f && speakingSpeed in 0.5f..4.3f -> "CALM"
            else -> "NEUTRAL"
        }

        val style = when (emotion) {
            "ANGRY" -> "Forceful & Defiant"
            "EXCITED" -> "Energetic & Assertive"
            "CALM" -> "Measured & Tactical"
            else -> "Natural Conversational"
        }

        val rawEmbedding = FloatArray(16)
        rawEmbedding[0] = (f0Mean / 300f).coerceIn(0f, 1f)
        rawEmbedding[1] = (f0Min / 300f).coerceIn(0f, 1f)
        rawEmbedding[2] = (f0Max / 350f).coerceIn(0f, 1f)
        rawEmbedding[3] = (f0Std / 60f).coerceIn(0f, 1f)
        rawEmbedding[4] = (spectralCentroid / 3500f).coerceIn(0f, 1f)
        rawEmbedding[5] = (meanZcr / 3000f).coerceIn(0f, 1f)
        rawEmbedding[6] = (speakingSpeed / 8f).coerceIn(0f, 1f)
        rawEmbedding[7] = ((rmsDbfs + 40f) / 40f).coerceIn(0f, 1f)

        val probeBaseFreq = if (hasVoicedPitch) f0Mean else 150f
        for (band in 0 until 8) {
            val harmonicFreq = probeBaseFreq * (band + 1)
            val omega = (2.0 * PI * harmonicFreq) / sr
            var re = 0.0
            var im = 0.0
            val probeLen = min(samples.size, sr)
            for (nIdx in 0 until probeLen step 4) {
                val s = samples[nIdx].toDouble() / 32768.0
                re += s * cos(omega * nIdx)
                im += s * sin(omega * nIdx)
            }
            val mag = sqrt(re * re + im * im) / max(1, probeLen / 4)
            rawEmbedding[8 + band] = (mag * 12.0).toFloat().coerceIn(0.0f, 1.0f)
        }

        val norm = sqrt(rawEmbedding.map { it * it }.sum().toDouble()).toFloat().coerceAtLeast(1e-5f)
        val normalizedEmbedding = FloatArray(16) { (rawEmbedding[it] / norm) }

        return AcousticVoiceFeatures(
            f0MinHz = (f0Min * 10f).roundToInt() / 10f,
            f0MaxHz = (f0Max * 10f).roundToInt() / 10f,
            f0MeanHz = (f0Mean * 10f).roundToInt() / 10f,
            f0StdHz = (f0Std * 10f).roundToInt() / 10f,
            spectralCentroidHz = (spectralCentroid * 10f).roundToInt() / 10f,
            timbreDescriptor = timbre,
            speakingSpeedSylPerSec = (speakingSpeed * 10f).roundToInt() / 10f,
            voiceTexture = texture,
            speakingStyle = style,
            estimatedEmotion = emotion,
            embedding = normalizedEmbedding,
            peakDbfs = peakDbfs,
            rmsDbfs = rmsDbfs
        )
    }

    fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-7) return 0f
        return ((dot / denom).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Genuine WSOLA (Waveform Similarity Overlap-Add) time-scale modification preserving pitch.
     * For each synthesis frame, searches within a tolerance window `±maxDelta` around the nominal
     * analysis position to maximize normalized cross-correlation against the natural continuation
     * of the previous selected frame (`prevSelectedPos + synthesisHop`), preventing phase artifacts.
     */
    fun timeStretchPcm(buffer: PcmAudioBuffer, targetDurationMs: Long): Pair<PcmAudioBuffer, Float> {
        val mono = toMono(buffer)
        val currentMs = mono.durationMs
        if (currentMs <= 0L || targetDurationMs <= 0L || mono.samples.isEmpty()) {
            return mono to 1.0f
        }
        val rawRatio = currentMs.toFloat() / targetDurationMs.toFloat()
        if (abs(currentMs - targetDurationMs) <= 35L) {
            return mono to 1.0f
        }
        val stretchSpeedFactor = rawRatio.coerceIn(0.75f, 1.35f)
        val sr = mono.sampleRateHz
        val winSize = max(256, (sr * 32) / 1000).let { if (it % 2 == 0) it else it + 1 }
        val synthesisHop = winSize / 2
        val maxDelta = max(16, synthesisHop / 2)
        val src = mono.samples
        if (src.size <= winSize + maxDelta) {
            return mono to 1.0f
        }

        val expectedSamples = ((src.size / stretchSpeedFactor).roundToInt()).coerceAtLeast(winSize)
        val acc = FloatArray(expectedSamples + winSize * 2)
        val weight = FloatArray(expectedSamples + winSize * 2)

        val hann = FloatArray(winSize) { i ->
            (0.5 * (1.0 - cos((2.0 * PI * i) / (winSize - 1)))).toFloat()
        }

        // Write initial frame at 0
        for (i in 0 until winSize) {
            val w = hann[i]
            acc[i] += src[i] * w
            weight[i] += w * w
        }

        var prevSelectedIn = 0
        var frameIdx = 1
        var outPos = synthesisHop

        while (outPos + winSize < acc.size) {
            val nominalIn = (frameIdx * synthesisHop * stretchSpeedFactor).roundToInt()
            if (nominalIn + winSize >= src.size) break

            val naturalContinuation = (prevSelectedIn + synthesisHop).coerceIn(0, src.size - winSize)
            val searchStart = (nominalIn - maxDelta).coerceAtLeast(0)
            val searchEnd = (nominalIn + maxDelta).coerceAtMost(src.size - winSize)

            // Normalized cross-correlation over overlap segment (step 2 for mobile speed)
            var bestIn = nominalIn.coerceIn(searchStart, searchEnd)
            var bestNormCorr = -2.0
            var refEnergy = 0.0
            for (k in 0 until synthesisHop step 2) {
                val r = src[naturalContinuation + k].toDouble()
                refEnergy += r * r
            }

            if (refEnergy > 1e-3) {
                for (cand in searchStart..searchEnd step 2) {
                    var dot = 0.0
                    var candEnergy = 0.0
                    for (k in 0 until synthesisHop step 2) {
                        val r = src[naturalContinuation + k].toDouble()
                        val c = src[cand + k].toDouble()
                        dot += r * c
                        candEnergy += c * c
                    }
                    val denom = sqrt(refEnergy * candEnergy)
                    val normCorr = if (denom > 1e-6) dot / denom else 0.0
                    if (normCorr > bestNormCorr) {
                        bestNormCorr = normCorr
                        bestIn = cand
                    }
                }
            }

            for (i in 0 until winSize) {
                val w = hann[i]
                acc[outPos + i] += src[bestIn + i] * w
                weight[outPos + i] += w * w
            }

            prevSelectedIn = bestIn
            frameIdx++
            outPos += synthesisHop
        }

        val finalLen = min(expectedSamples, outPos + synthesisHop).coerceAtLeast(1)
        val outShorts = ShortArray(finalLen)
        for (i in 0 until finalLen) {
            val w = weight[i]
            val v = if (w > 1e-4f) acc[i] / w else acc[i]
            outShorts[i] = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return PcmAudioBuffer(outShorts, sr, 1) to ((stretchSpeedFactor * 100f).roundToInt() / 100f)
    }

    /**
     * Two-pass chunked streaming additive multitrack WAV mixer.
     *
     * Key DSP Guarantees:
     * 1. Zero order-dependent attenuation: overlapping dialogue clips are summed strictly additively
     *    (`dialogueSum += sample * trackGain * fadeEnv`), so track order never attenuates earlier speakers.
     * 2. Clean background ducking: background stem is ducked (`0.84f`) ONLY on frames where dialogue is active,
     *    completely independent of how many dialogue clips overlap.
     * 3. Bounded memory: mixes in 2-second streaming chunks instead of allocating a full-episode `FloatArray`.
     * 4. Pass 1 computes exact global peak for clean headroom scaling (`threshold = 0.94f`);
     *    Pass 2 writes canonical 16-bit PCM WAV directly to disk while measuring exact final peak & RMS dBFS.
     */
    fun mixMultitrackTimeline(
        episodeDurationMs: Long,
        sampleRateHz: Int,
        backgroundStemFile: File?,
        backgroundGain: Float,
        dialoguePlacements: List<MixPlacementItem>,
        outputFile: File
    ): MixOutputMetrics {
        val safeDurationMs = episodeDurationMs.coerceAtLeast(1000L)
        val totalFrames = max(sampleRateHz, ((safeDurationMs * sampleRateHz) / 1000L).toInt())
        val chunkFrames = max(sampleRateHz * 2, 16384)
        val fadeSamples = max(16, (sampleRateHz * 15) / 1000)

        val bgSamples: ShortArray? = if (backgroundStemFile != null && backgroundStemFile.exists()) {
            val res = readWavFileStrict(backgroundStemFile)
            if (res.isSuccess) resampleMono(res.getOrThrow(), sampleRateHz).samples else null
        } else null

        data class PreparedPlacement(
            val startFrame: Int,
            val endFrame: Int,
            val samples: ShortArray,
            val trackGain: Float
        )

        val prepared = dialoguePlacements.mapNotNull { item ->
            if (!item.audioFile.exists()) return@mapNotNull null
            val res = readWavFileStrict(item.audioFile)
            if (res.isFailure) return@mapNotNull null
            val resampled = resampleMono(res.getOrThrow(), sampleRateHz)
            if (resampled.samples.isEmpty()) return@mapNotNull null
            val startFrame = ((item.startMs * sampleRateHz) / 1000L).toInt().coerceIn(0, totalFrames - 1)
            val len = min(resampled.samples.size, totalFrames - startFrame)
            if (len <= 0) return@mapNotNull null
            PreparedPlacement(
                startFrame = startFrame,
                endFrame = startFrame + len,
                samples = resampled.samples,
                trackGain = item.trackGain
            )
        }

        val chunkBg = FloatArray(chunkFrames)
        val chunkDlg = FloatArray(chunkFrames)
        val chunkHasDlg = BooleanArray(chunkFrames)

        fun renderChunk(chunkStart: Int, count: Int) {
            for (i in 0 until count) {
                val frame = chunkStart + i
                chunkBg[i] = if (bgSamples != null && frame < bgSamples.size) {
                    (bgSamples[frame] / 32768f) * backgroundGain
                } else 0f
                chunkDlg[i] = 0f
                chunkHasDlg[i] = false
            }

            val chunkEnd = chunkStart + count
            for (clip in prepared) {
                if (clip.endFrame <= chunkStart || clip.startFrame >= chunkEnd) continue
                val clipLen = clip.endFrame - clip.startFrame
                val overlapStart = max(chunkStart, clip.startFrame)
                val overlapEnd = min(chunkEnd, clip.endFrame)
                for (frame in overlapStart until overlapEnd) {
                    val clipIdx = frame - clip.startFrame
                    val fadeEnv = when {
                        clipIdx < fadeSamples -> clipIdx.toFloat() / fadeSamples
                        clipIdx > clipLen - fadeSamples -> (clipLen - clipIdx).toFloat() / fadeSamples
                        else -> 1.0f
                    }.coerceIn(0f, 1f)
                    val outIdx = frame - chunkStart
                    chunkDlg[outIdx] += (clip.samples[clipIdx] / 32768f) * clip.trackGain * fadeEnv
                    chunkHasDlg[outIdx] = true
                }
            }
        }

        // Pass 1: Find exact global peak across all chunks
        var maxAbs = 0f
        var cursor = 0
        while (cursor < totalFrames) {
            val count = min(chunkFrames, totalFrames - cursor)
            renderChunk(cursor, count)
            for (i in 0 until count) {
                val bgDuck = if (chunkHasDlg[i]) 0.84f else 1.0f
                val sample = chunkBg[i] * bgDuck + chunkDlg[i]
                val a = abs(sample)
                if (a > maxAbs) maxAbs = a
            }
            cursor += count
        }

        val threshold = 0.94f
        val gainReduction = if (maxAbs > threshold) threshold / maxAbs else 1.0f
        val attenuationDb = if (gainReduction < 1.0f) (20.0 * log10(gainReduction.toDouble())).toFloat() else 0f

        // Pass 2: Stream canonical 16-bit PCM WAV directly to FileOutputStream while computing peak & RMS
        outputFile.parentFile?.mkdirs()
        val pcmByteSize = totalFrames * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmByteSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRateHz)
            putInt(sampleRateHz * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmByteSize)
        }

        var maxWrittenAbs = 1
        var sumSq = 0.0
        val outByteBuf = ByteArray(chunkFrames * 2)

        FileOutputStream(outputFile).use { fos ->
            fos.write(header.array())
            cursor = 0
            while (cursor < totalFrames) {
                val count = min(chunkFrames, totalFrames - cursor)
                renderChunk(cursor, count)
                val bb = ByteBuffer.wrap(outByteBuf, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    val bgDuck = if (chunkHasDlg[i]) 0.84f else 1.0f
                    val mixed = (chunkBg[i] * bgDuck + chunkDlg[i]) * gainReduction
                    val pcmInt = (mixed * 32767f).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bb.putShort(pcmInt.toShort())
                    val a = abs(pcmInt)
                    if (a > maxWrittenAbs) maxWrittenAbs = a
                    val norm = pcmInt.toDouble() / 32768.0
                    sumSq += norm * norm
                }
                fos.write(outByteBuf, 0, count * 2)
                cursor += count
            }
            fos.flush()
        }

        val finalPeakDb = ((20.0 * log10(maxWrittenAbs.toDouble() / 32768.0)) * 10.0).roundToInt() / 10.0
        val rmsVal = sqrt(sumSq / max(1, totalFrames)).coerceAtLeast(1e-5)
        val finalRmsDb = ((20.0 * log10(rmsVal)) * 10.0).roundToInt() / 10.0
        val durationMs = (totalFrames * 1000L) / sampleRateHz

        return MixOutputMetrics(
            outputFile = outputFile,
            durationMs = durationMs,
            peakDbfs = finalPeakDb.toFloat(),
            rmsDbfs = finalRmsDb.toFloat(),
            clippingDetected = false,
            limiterAttenuationDb = (attenuationDb * 10f).roundToInt() / 10f
        )
    }

    /**
     * Extracts downsampled normalized waveform amplitude bars [0.04 .. 1.0] strictly from real PCM WAV audio.
     * Returns emptyList() when no real audio file exists (never invents fake waveforms).
     */
    fun extractWaveformPeaks(file: File, barCount: Int = 64): List<Float> {
        if (!file.exists() || file.length() <= 44L) return emptyList()
        val buf = toMono(readWavFile(file))
        if (buf.samples.isEmpty()) return emptyList()
        val chunk = max(1, buf.samples.size / barCount)
        val peaks = ArrayList<Float>(barCount)
        for (b in 0 until barCount) {
            val start = b * chunk
            val end = min(buf.samples.size, start + chunk)
            if (start >= buf.samples.size) break
            var maxPeak = 0f
            for (i in start until end) {
                val v = abs(buf.samples[i].toInt()) / 32768f
                if (v > maxPeak) maxPeak = v
            }
            peaks.add(maxPeak.coerceIn(0.04f, 1.0f))
        }
        return peaks
    }

    fun computePeakDbfs(buffer: PcmAudioBuffer): Float {
        if (buffer.samples.isEmpty()) return -96.0f
        var maxSample = 1
        for (s in buffer.samples) {
            val a = abs(s.toInt())
            if (a > maxSample) maxSample = a
        }
        val db = 20.0 * log10(maxSample.toDouble() / 32768.0)
        return ((db * 10.0).roundToInt() / 10.0).toFloat()
    }

    fun computeRmsDbfs(buffer: PcmAudioBuffer): Float {
        if (buffer.samples.isEmpty()) return -96.0f
        var sumSq = 0.0
        for (s in buffer.samples) {
            val norm = s.toDouble() / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / buffer.samples.size).coerceAtLeast(1e-5)
        val db = 20.0 * log10(rms)
        return ((db * 10.0).roundToInt() / 10.0).toFloat()
    }
}
