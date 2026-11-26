package com.example.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min

data class VadDetectionOutput(
    val segments: List<Pair<Long, Long>>,
    val engineLabel: String,
    val isNeuralOnnx: Boolean,
    val meanSpeechProbability: Float // -1f when non-neural DSP fallback is used
)

/**
 * Genuine On-Device Silero VAD ONNX Runtime Engine (Agent 2).
 *
 * Loads the actual verified `onnx/model.onnx` downloaded from `onnx-community/silero-vad`
 * using ONNX Runtime Mobile (`ai.onnxruntime.OrtEnvironment` / `OrtSession`), resamples audio
 * to 16 kHz mono, feeds 512-sample windows (32 ms) with recurrent LSTM state tensors, and
 * extracts real neural speech probability boundaries.
 *
 * Supports both Silero VAD v5 (`input` + `state` [2,1,128] + `sr`) and v4 (`input` + `h` [2,1,64] + `c` [2,1,64] + `sr`)
 * tensor signatures by inspecting `session.inputNames` at runtime.
 *
 * Never claims Silero ONNX ran if `model.onnx` is missing or fails to load — returns `Result.failure`
 * so the caller transparently labels any energy-based DSP fallback.
 */
object SileroVadOnnxEngine {

    private const val TAG = "SileroVadOnnxEngine"
    private const val SAMPLE_RATE_16K = 16000
    private const val WINDOW_SAMPLES_16K = 512 // 32 ms at 16 kHz (Silero v5 standard)
    private const val CONTEXT_SAMPLES_V5 = 64  // Silero v5 prepends 64 context samples

    fun detectSpeechSegmentsOnnx(
        modelOnnxFile: File,
        audioBuffer: PcmAudioBuffer,
        threshold: Float = 0.45f,
        negThreshold: Float = 0.30f,
        minSpeechMs: Long = 320L,
        mergeSilenceGapMs: Long = 300L,
        maxSegmentMs: Long = 8000L
    ): Result<VadDetectionOutput> {
        if (!modelOnnxFile.exists() || modelOnnxFile.length() < 100_000L) {
            return Result.failure(
                IllegalStateException("Silero VAD ONNX weights not downloaded at ${modelOnnxFile.absolutePath}")
            )
        }
        if (audioBuffer.samples.isEmpty() || audioBuffer.sampleRateHz <= 0) {
            return Result.failure(IllegalArgumentException("Audio buffer is empty."))
        }

        val mono16k = AudioDspEngine.resampleMono(audioBuffer, SAMPLE_RATE_16K)
        if (mono16k.samples.size < WINDOW_SAMPLES_16K) {
            return Result.failure(IllegalArgumentException("Audio clip shorter than 32 ms minimum Silero VAD window."))
        }

        var session: OrtSession? = null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            session = env.createSession(modelOnnxFile.absolutePath, opts)
            val inputNames = session.inputNames

            val isV5State = inputNames.contains("state")
            val isV4Hc = inputNames.contains("h") && inputNames.contains("c")
            if (!isV5State && !isV4Hc) {
                return Result.failure(
                    IllegalStateException("Unsupported Silero ONNX input signature: ${inputNames.joinToString()}")
                )
            }

            val probabilities = ArrayList<Float>((mono16k.samples.size / WINDOW_SAMPLES_16K) + 1)

            // Recurrent hidden states
            var stateV5 = FloatArray(2 * 1 * 128)
            var stateH = FloatArray(2 * 1 * 64)
            var stateC = FloatArray(2 * 1 * 64)
            var contextPrev = FloatArray(CONTEXT_SAMPLES_V5)

            val srTensor = if (isV5State) {
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE_16K.toLong())), longArrayOf())
            } else {
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE_16K.toLong())), longArrayOf(1))
            }

            try {
                var offset = 0
                while (offset + WINDOW_SAMPLES_16K <= mono16k.samples.size) {
                    val chunkFloat = FloatArray(WINDOW_SAMPLES_16K) { i ->
                        mono16k.samples[offset + i].toFloat() / 32768f
                    }

                    val inputs = HashMap<String, OnnxTensor>(4)
                    val inputTensor: OnnxTensor
                    if (isV5State) {
                        val combined = FloatArray(CONTEXT_SAMPLES_V5 + WINDOW_SAMPLES_16K)
                        System.arraycopy(contextPrev, 0, combined, 0, CONTEXT_SAMPLES_V5)
                        System.arraycopy(chunkFloat, 0, combined, CONTEXT_SAMPLES_V5, WINDOW_SAMPLES_16K)
                        System.arraycopy(chunkFloat, WINDOW_SAMPLES_16K - CONTEXT_SAMPLES_V5, contextPrev, 0, CONTEXT_SAMPLES_V5)

                        inputTensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(combined),
                            longArrayOf(1, combined.size.toLong())
                        )
                        val stateTensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(stateV5),
                            longArrayOf(2, 1, 128)
                        )
                        inputs["input"] = inputTensor
                        inputs["state"] = stateTensor
                        if (inputNames.contains("sr")) inputs["sr"] = srTensor

                        try {
                            session.run(inputs).use { result ->
                                val probVal = extractScalarFloat(result[0].value)
                                probabilities.add(probVal.coerceIn(0f, 1f))
                                if (result.size() > 1) {
                                    flattenToFloatArray(result[1].value, stateV5)
                                }
                            }
                        } finally {
                            inputTensor.close()
                            stateTensor.close()
                        }
                    } else {
                        inputTensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(chunkFloat),
                            longArrayOf(1, WINDOW_SAMPLES_16K.toLong())
                        )
                        val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(stateH), longArrayOf(2, 1, 64))
                        val cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(stateC), longArrayOf(2, 1, 64))
                        inputs["input"] = inputTensor
                        inputs["h"] = hTensor
                        inputs["c"] = cTensor
                        if (inputNames.contains("sr")) inputs["sr"] = srTensor

                        try {
                            session.run(inputs).use { result ->
                                val probVal = extractScalarFloat(result[0].value)
                                probabilities.add(probVal.coerceIn(0f, 1f))
                                if (result.size() > 2) {
                                    flattenToFloatArray(result[1].value, stateH)
                                    flattenToFloatArray(result[2].value, stateC)
                                }
                            }
                        } finally {
                            inputTensor.close()
                            hTensor.close()
                            cTensor.close()
                        }
                    }
                    offset += WINDOW_SAMPLES_16K
                }
            } finally {
                srTensor.close()
            }

            // Hysteresis thresholding to convert frame probabilities into [startMs, endMs] dialogue windows
            val frameMs = 32L
            val rawRanges = mutableListOf<Pair<Long, Long>>()
            var inSpeech = false
            var speechStartFrame = 0
            var speechProbSum = 0.0
            var speechProbCount = 0

            for (i in probabilities.indices) {
                val p = probabilities[i]
                if (!inSpeech && p >= threshold) {
                    inSpeech = true
                    speechStartFrame = i
                    speechProbSum += p
                    speechProbCount++
                } else if (inSpeech) {
                    if (p < negThreshold) {
                        inSpeech = false
                        val startMs = (speechStartFrame * frameMs).coerceAtLeast(0L)
                        val endMs = min(mono16k.durationMs, i * frameMs)
                        if (endMs > startMs) rawRanges.add(startMs to endMs)
                    } else {
                        speechProbSum += p
                        speechProbCount++
                    }
                }
            }
            if (inSpeech) {
                rawRanges.add((speechStartFrame * frameMs) to mono16k.durationMs)
            }

            val merged = mutableListOf<Pair<Long, Long>>()
            for (r in rawRanges) {
                if (merged.isEmpty()) {
                    merged.add(r)
                } else {
                    val prev = merged.last()
                    if (r.first - prev.second <= mergeSilenceGapMs) {
                        merged[merged.lastIndex] = prev.first to r.second
                    } else {
                        merged.add(r)
                    }
                }
            }

            val finalSegments = mutableListOf<Pair<Long, Long>>()
            for ((s, e) in merged) {
                if (e - s < minSpeechMs) continue
                if (e - s <= maxSegmentMs) {
                    finalSegments.add(s to e)
                } else {
                    var cursor = s
                    while (cursor < e) {
                        val chunkEnd = min(e, cursor + 6000L)
                        if (chunkEnd - cursor >= minSpeechMs) {
                            finalSegments.add(cursor to chunkEnd)
                        }
                        cursor = chunkEnd
                    }
                }
            }

            val meanProb = if (speechProbCount > 0) {
                (speechProbSum / speechProbCount).toFloat().coerceIn(0f, 1f)
            } else {
                -1f
            }

            Result.success(
                VadDetectionOutput(
                    segments = finalSegments,
                    engineLabel = "Silero VAD ONNX (ONNX Runtime Mobile)",
                    isNeuralOnnx = true,
                    meanSpeechProbability = meanProb
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Silero ONNX Runtime execution failed: ${t.localizedMessage}", t)
            Result.failure(IllegalStateException("Silero ONNX execution failed: ${t.localizedMessage}", t))
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractScalarFloat(value: Any?): Float {
        return when (value) {
            is Float -> value
            is FloatArray -> value.firstOrNull() ?: 0f
            is Array<*> -> extractScalarFloat(value.firstOrNull())
            else -> 0f
        }
    }

    private fun flattenToFloatArray(value: Any?, target: FloatArray): Int {
        var idx = 0
        fun recurse(node: Any?) {
            if (idx >= target.size || node == null) return
            when (node) {
                is FloatArray -> {
                    val copyLen = min(node.size, target.size - idx)
                    System.arraycopy(node, 0, target, idx, copyLen)
                    idx += copyLen
                }
                is Array<*> -> {
                    for (child in node) recurse(child)
                }
            }
        }
        recurse(value)
        return idx
    }
}
