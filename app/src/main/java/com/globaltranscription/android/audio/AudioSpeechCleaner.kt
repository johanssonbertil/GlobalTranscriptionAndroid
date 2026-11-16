package com.globaltranscription.android.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CleanedAudioResult(
    val cleanedPcm: ShortArray,
    val originalDurationMs: Long,
    val cleanedDurationMs: Long,
    val hadSpeech: Boolean
)

/**
 * Standard speech preprocessing pipeline:
 * 1. DC offset removal + ~80 Hz first-order IIR high-pass filter
 * 2. Frame-based Voice Activity Detection (energy dBFS + adaptive noise floor + ZCR + hangover)
 * 3. Concatenation of speech segments with short fade transitions
 * 4. Peak normalization to -1.0 dBFS (capped at +18 dB)
 */
object AudioSpeechCleaner {

    private const val FRAME_MS = 20
    private const val PRE_SPEECH_HANGOVER_FRAMES = 10   // 200 ms
    private const val POST_SPEECH_HANGOVER_FRAMES = 12  // 240 ms
    private const val BRIDGE_GAP_FRAMES = 15            // 300 ms
    private const val SEGMENT_GAP_MS = 40
    private const val FADE_MS = 5
    private const val TARGET_PEAK_LINEAR = 29204f       // ~ -1.0 dBFS (32767 * 10^(-1/20))
    private const val MAX_GAIN = 8.0f                   // +18 dB cap
    private const val ABSOLUTE_SILENCE_FLOOR_DB = -52.0
    private const val QUIET_TRIM_THRESHOLD_DB = -58.0

    fun cleanForTranscription(rawPcm: ShortArray, sampleRate: Int = 16000): CleanedAudioResult {
        if (rawPcm.isEmpty() || sampleRate <= 0) {
            return CleanedAudioResult(
                cleanedPcm = ShortArray(0),
                originalDurationMs = 0L,
                cleanedDurationMs = 0L,
                hadSpeech = false
            )
        }

        val originalDurationMs = (rawPcm.size * 1000L) / sampleRate
        val filtered = applyHighPassFilter(rawPcm, sampleRate)

        val frameSize = max(1, (sampleRate * FRAME_MS) / 1000)
        val numFrames = (filtered.size + frameSize - 1) / frameSize
        if (numFrames == 0) {
            return CleanedAudioResult(ShortArray(0), originalDurationMs, 0L, false)
        }

        val frameDb = DoubleArray(numFrames)
        val frameZcr = DoubleArray(numFrames)
        var maxFrameDb = -120.0

        for (i in 0 until numFrames) {
            val start = i * frameSize
            val end = min(start + frameSize, filtered.size)
            val len = end - start
            var sumSq = 0.0
            var zeroCrossings = 0
            var prev = filtered[start]
            for (j in start until end) {
                val sample = filtered[j]
                sumSq += sample.toDouble() * sample.toDouble()
                if (j > start && ((sample >= 0f && prev < 0f) || (sample < 0f && prev >= 0f))) {
                    zeroCrossings++
                }
                prev = sample
            }
            val rms = sqrt(sumSq / len)
            val db = 20.0 * log10(max(rms / 32768.0, 1e-9))
            frameDb[i] = db
            frameZcr[i] = if (len > 1) zeroCrossings.toDouble() / (len - 1) else 0.0
            if (db > maxFrameDb) {
                maxFrameDb = db
            }
        }

        // If the entire recording is digital silence / negligible noise floor, skip sending audio.
        if (maxFrameDb < ABSOLUTE_SILENCE_FLOOR_DB) {
            return CleanedAudioResult(
                cleanedPcm = ShortArray(0),
                originalDurationMs = originalDurationMs,
                cleanedDurationMs = 0L,
                hadSpeech = false
            )
        }

        val sortedDb = frameDb.sortedArray()
        val noiseFloorIndex = ((numFrames * 0.20).toInt()).coerceIn(0, numFrames - 1)
        val noiseFloorDb = sortedDb[noiseFloorIndex]
        val speechThresholdDb = max(noiseFloorDb + 8.0, -42.0).coerceAtMost(-26.0)

        val rawActive = BooleanArray(numFrames)
        var anyActive = false
        for (i in 0 until numFrames) {
            val isSpeechEnergy = frameDb[i] >= speechThresholdDb && frameDb[i] >= ABSOLUTE_SILENCE_FLOOR_DB
            val isPlausibleZcr = frameZcr[i] <= 0.65 || frameDb[i] >= (speechThresholdDb + 6.0)
            if (isSpeechEnergy && isPlausibleZcr) {
                rawActive[i] = true
                anyActive = true
            }
        }

        val activeMask = BooleanArray(numFrames)
        if (anyActive) {
            // Apply pre/post hangover padding.
            for (i in 0 until numFrames) {
                if (rawActive[i]) {
                    val from = max(0, i - PRE_SPEECH_HANGOVER_FRAMES)
                    val to = min(numFrames - 1, i + POST_SPEECH_HANGOVER_FRAMES)
                    for (k in from..to) {
                        activeMask[k] = true
                    }
                }
            }
            // Bridge short silent gaps between speech bursts.
            var idx = 0
            while (idx < numFrames) {
                if (!activeMask[idx]) {
                    val gapStart = idx
                    while (idx < numFrames && !activeMask[idx]) {
                        idx++
                    }
                    val gapEnd = idx // exclusive
                    val gapLen = gapEnd - gapStart
                    if (gapStart > 0 && gapEnd < numFrames && gapLen < BRIDGE_GAP_FRAMES) {
                        for (k in gapStart until gapEnd) {
                            activeMask[k] = true
                        }
                    }
                } else {
                    idx++
                }
            }
        } else {
            // Fallback: keep original audio trimmed of dead silence so valid quiet recordings aren't lost.
            var firstNonSilent = -1
            var lastNonSilent = -1
            for (i in 0 until numFrames) {
                if (frameDb[i] >= QUIET_TRIM_THRESHOLD_DB) {
                    if (firstNonSilent == -1) firstNonSilent = i
                    lastNonSilent = i
                }
            }
            if (firstNonSilent == -1) {
                return CleanedAudioResult(
                    cleanedPcm = ShortArray(0),
                    originalDurationMs = originalDurationMs,
                    cleanedDurationMs = 0L,
                    hadSpeech = false
                )
            }
            val startPad = max(0, firstNonSilent - 2)
            val endPad = min(numFrames - 1, lastNonSilent + 2)
            for (k in startPad..endPad) {
                activeMask[k] = true
            }
        }

        // Collect contiguous active sample intervals.
        val segments = mutableListOf<IntRange>()
        var frameIdx = 0
        while (frameIdx < numFrames) {
            if (activeMask[frameIdx]) {
                val segStartFrame = frameIdx
                while (frameIdx < numFrames && activeMask[frameIdx]) {
                    frameIdx++
                }
                val segEndFrame = frameIdx // exclusive
                val sampleStart = segStartFrame * frameSize
                val sampleEnd = min(segEndFrame * frameSize, filtered.size)
                if (sampleEnd > sampleStart) {
                    segments.add(sampleStart until sampleEnd)
                }
            } else {
                frameIdx++
            }
        }

        if (segments.isEmpty()) {
            return CleanedAudioResult(ShortArray(0), originalDurationMs, 0L, false)
        }

        val gapSamples = (sampleRate * SEGMENT_GAP_MS) / 1000
        val fadeSamples = min(gapSamples, (sampleRate * FADE_MS) / 1000)

        var totalSamples = 0
        for ((index, seg) in segments.withIndex()) {
            totalSamples += (seg.last - seg.first + 1)
            if (index < segments.lastIndex) {
                totalSamples += gapSamples
            }
        }

        val concatenated = FloatArray(totalSamples)
        var cursor = 0
        for ((index, seg) in segments.withIndex()) {
            val segLen = seg.last - seg.first + 1
            for (i in 0 until segLen) {
                var value = filtered[seg.first + i]
                // Smooth edges across spliced segments to prevent audible clicks.
                if (index > 0 && i < fadeSamples && fadeSamples > 0) {
                    value *= i.toFloat() / fadeSamples
                }
                if (index < segments.lastIndex && i >= segLen - fadeSamples && fadeSamples > 0) {
                    val rem = segLen - 1 - i
                    value *= rem.toFloat() / fadeSamples
                }
                concatenated[cursor++] = value
            }
            if (index < segments.lastIndex) {
                cursor += gapSamples // already zeroed
            }
        }

        val normalized = normalizePeak(concatenated)
        val cleanedDurationMs = (normalized.size * 1000L) / sampleRate
        return CleanedAudioResult(
            cleanedPcm = normalized,
            originalDurationMs = originalDurationMs,
            cleanedDurationMs = cleanedDurationMs,
            hadSpeech = true
        )
    }

    internal fun applyHighPassFilter(rawPcm: ShortArray, sampleRate: Int = 16000): FloatArray {
        val out = FloatArray(rawPcm.size)
        if (rawPcm.isEmpty()) return out

        // First-order IIR high-pass filter (~80 Hz cutoff; alpha = 0.969f at 16 kHz)
        val alpha = if (sampleRate == 16000) {
            0.969f
        } else {
            val rc = 1.0f / (2.0f * PI.toFloat() * 80.0f)
            val dt = 1.0f / sampleRate.toFloat()
            rc / (rc + dt)
        }

        var prevX = rawPcm[0].toFloat()
        var prevY = 0f
        out[0] = 0f

        for (i in 1 until rawPcm.size) {
            val x = rawPcm[i].toFloat()
            val y = alpha * (prevY + x - prevX)
            out[i] = y
            prevX = x
            prevY = y
        }
        return out
    }

    private fun normalizePeak(samples: FloatArray): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        var maxAbs = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > maxAbs) maxAbs = a
        }
        val gain = if (maxAbs > 1f) {
            min(TARGET_PEAK_LINEAR / maxAbs, MAX_GAIN)
        } else {
            1f
        }
        return ShortArray(samples.size) { idx ->
            (samples[idx] * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int = 16000) {
        file.parentFile?.mkdirs()
        val dataByteCount = pcmData.size * 2
        val byteRate = sampleRate * 2 // 1 channel * 16 bits / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(36 + dataByteCount)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // PCM subchunk size
            putShort(1.toShort()) // AudioFormat = 1 (PCM)
            putShort(1.toShort()) // NumChannels = 1 (Mono)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2.toShort()) // BlockAlign = 2
            putShort(16.toShort()) // BitsPerSample = 16
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(dataByteCount)
        }.array()

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(header)
            val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            var idx = 0
            while (idx < pcmData.size) {
                buffer.clear()
                val chunkEnd = min(idx + 4096, pcmData.size)
                for (i in idx until chunkEnd) {
                    buffer.putShort(pcmData[i])
                }
                out.write(buffer.array(), 0, buffer.position())
                idx = chunkEnd
            }
            out.flush()
        }
    }
}
