package com.globaltranscription.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorderEngine(
    private val scope: CoroutineScope,
    private val onElapsedTick: (Long) -> Unit,
    private val onError: (String) -> Unit
) {

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    private var readJob: Job? = null
    private var timerJob: Job? = null

    private val isRecordingActive = AtomicBoolean(false)
    private val isPausedFlag = AtomicBoolean(false)

    private val lock = Any()
    private val recordedChunks = ArrayList<ShortArray>()
    private var totalSamplesRecorded = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        discard()

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes == AudioRecord.ERROR || minBufBytes == AudioRecord.ERROR_BAD_VALUE) {
            onError("Microphone initialization failed (invalid buffer size)")
            return false
        }

        val bufferBytes = maxOf(minBufBytes * 2, SAMPLE_RATE * 2 / 5) // >= 200ms buffer
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to construct AudioRecord", e)
            onError("Could not access microphone: ${e.message}")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("Microphone failed to initialize")
            return false
        }

        attachAudioEffects(recorder.audioSessionId)

        audioRecord = recorder
        synchronized(lock) {
            recordedChunks.clear()
            totalSamplesRecorded = 0L
        }
        isPausedFlag.set(false)
        isRecordingActive.set(true)

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            discard()
            onError("Failed to start microphone: ${e.message}")
            return false
        }

        val readBuffer = ShortArray(bufferBytes / 2)
        readJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRecordingActive.get()) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0 && !isPausedFlag.get()) {
                    val copy = readBuffer.copyOf(readCount)
                    synchronized(lock) {
                        recordedChunks.add(copy)
                        totalSamplesRecorded += readCount
                    }
                } else if (readCount < 0) {
                    delay(10)
                } else {
                    delay(15)
                }
            }
        }

        timerJob = scope.launch(Dispatchers.Main) {
            while (isActive && isRecordingActive.get()) {
                onElapsedTick(currentElapsedMs())
                delay(250)
            }
        }

        return true
    }

    fun pause() {
        isPausedFlag.set(true)
        onElapsedTick(currentElapsedMs())
    }

    fun resume() {
        isPausedFlag.set(false)
        onElapsedTick(currentElapsedMs())
    }

    fun currentElapsedMs(): Long {
        val samples = synchronized(lock) { totalSamplesRecorded }
        return (samples * 1000L) / SAMPLE_RATE
    }

    fun stop(): ShortArray {
        isRecordingActive.set(false)
        isPausedFlag.set(false)

        readJob?.cancel()
        readJob = null
        timerJob?.cancel()
        timerJob = null

        releaseAudioRecord()

        return synchronized(lock) {
            val total = totalSamplesRecorded.toInt().coerceAtLeast(0)
            val result = ShortArray(total)
            var offset = 0
            for (chunk in recordedChunks) {
                val len = minOf(chunk.size, total - offset)
                if (len > 0) {
                    chunk.copyInto(result, destinationOffset = offset, startIndex = 0, endIndex = len)
                    offset += len
                }
            }
            recordedChunks.clear()
            totalSamplesRecorded = 0L
            result
        }
    }

    fun discard() {
        isRecordingActive.set(false)
        isPausedFlag.set(false)
        readJob?.cancel()
        readJob = null
        timerJob?.cancel()
        timerJob = null
        releaseAudioRecord()
        synchronized(lock) {
            recordedChunks.clear()
            totalSamplesRecorded = 0L
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor unavailable", e)
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AcousticEchoCanceler unavailable", e)
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AutomaticGainControl unavailable", e)
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        } finally {
            noiseSuppressor = null
        }

        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        } finally {
            echoCanceler = null
        }

        try {
            gainControl?.release()
        } catch (_: Exception) {
        } finally {
            gainControl = null
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "AudioRecorderEngine"
    }
}
