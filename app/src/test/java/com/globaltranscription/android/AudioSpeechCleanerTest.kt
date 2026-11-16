package com.globaltranscription.android

import com.globaltranscription.android.audio.AudioSpeechCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioSpeechCleanerTest {

    @Test
    fun highPassFilterRemovesDcOffset() {
        val sampleRate = 16000
        val dcSignal = ShortArray(sampleRate) { 5000 }

        val filtered = AudioSpeechCleaner.applyHighPassFilter(dcSignal, sampleRate)
        // After the transient (~100ms), DC should settle very close to 0.
        val tailMax = filtered.drop(sampleRate / 2).maxOf { abs(it) }
        assertTrue("Expected DC offset to decay near 0, got tailMax=$tailMax", tailMax < 5.0f)
    }

    @Test
    fun vadStripsSurroundingSilenceAndPreservesSpeech() {
        val sampleRate = 16000
        val silenceBefore = ShortArray(sampleRate * 2) // 2.0s silence
        val speechBurst = ShortArray(sampleRate) { i ->
            (sin(2.0 * PI * 440.0 * i / sampleRate) * 8000.0).roundToInt().toShort()
        } // 1.0s voiced tone
        val silenceAfter = ShortArray(sampleRate * 2) // 2.0s silence

        val combined = silenceBefore + speechBurst + silenceAfter
        val result = AudioSpeechCleaner.cleanForTranscription(combined, sampleRate)

        assertTrue("Speech should be detected", result.hadSpeech)
        assertEquals(5000L, result.originalDurationMs)
        assertTrue(
            "Cleaned duration (${result.cleanedDurationMs}ms) should strip most of the 4s silence",
            result.cleanedDurationMs in 900L..1700L
        )
        val peak = result.cleanedPcm.maxOf { abs(it.toInt()) }
        assertTrue("Peak should be normalized near -1 dBFS (~29204), got $peak", peak in 26000..30500)
    }

    @Test
    fun pureSilenceReturnsNoSpeech() {
        val sampleRate = 16000
        val silent = ShortArray(sampleRate * 3) // 3.0s zeros

        val result = AudioSpeechCleaner.cleanForTranscription(silent, sampleRate)
        assertFalse("Pure silence should return hadSpeech=false", result.hadSpeech)
        assertEquals(0, result.cleanedPcm.size)
    }

    @Test
    fun writeWavFileWritesValidHeaderAndPayload() {
        val sampleRate = 16000
        val pcm = ShortArray(400) { idx -> ((idx % 40) * 500 - 10000).toShort() }
        val tempFile = File.createTempFile("wav_test_", ".wav")
        try {
            AudioSpeechCleaner.writeWavFile(tempFile, pcm, sampleRate)

            val bytes = tempFile.readBytes()
            assertEquals(44 + pcm.size * 2, bytes.size)

            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(36 + pcm.size * 2, bb.getInt(4))
            assertEquals(1.toShort(), bb.getShort(20)) // PCM format
            assertEquals(1.toShort(), bb.getShort(22)) // Mono
            assertEquals(sampleRate, bb.getInt(24))
            assertEquals(sampleRate * 2, bb.getInt(28))
            assertEquals(16.toShort(), bb.getShort(34)) // 16-bit
            assertEquals(pcm.size * 2, bb.getInt(40))
            assertEquals(pcm[0], bb.getShort(44))
            assertEquals(pcm[pcm.lastIndex], bb.getShort(44 + (pcm.size - 1) * 2))
        } finally {
            tempFile.delete()
        }
    }
}
