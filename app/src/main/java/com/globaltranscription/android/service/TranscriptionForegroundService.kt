package com.globaltranscription.android.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.globaltranscription.android.MainActivity
import com.globaltranscription.android.audio.AudioRecorderEngine
import com.globaltranscription.android.audio.AudioSpeechCleaner
import com.globaltranscription.android.audio.RecordingStorageManager
import com.globaltranscription.android.data.AppPreferences
import com.globaltranscription.android.data.ControlMode
import com.globaltranscription.android.network.AssemblyAiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class TranscriptionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var preferences: AppPreferences
    private lateinit var storageManager: RecordingStorageManager
    private lateinit var assemblyAiClient: AssemblyAiClient
    private lateinit var recorderEngine: AudioRecorderEngine
    private lateinit var bubbleOverlayManager: BubbleOverlayManager
    private lateinit var notificationManager: NotificationManager

    private var lastRecordedPcm: ShortArray? = null
    private var transcribeJob: Job? = null
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences.getInstance(this)
        storageManager = RecordingStorageManager(this)
        assemblyAiClient = AssemblyAiClient()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        NotificationHelper.createNotificationChannel(this)

        recorderEngine = AudioRecorderEngine(
            scope = serviceScope,
            onElapsedTick = { elapsedMs ->
                val current = TranscriptionStateRepository.state.value
                if (current.status == RecorderStatus.RECORDING || current.status == RecorderStatus.PAUSED) {
                    TranscriptionStateRepository.update { it.copy(elapsedMs = elapsedMs) }
                }
                if (current.status == RecorderStatus.RECORDING && elapsedMs >= MAX_RECORDING_DURATION_MS) {
                    showToast("Recording ended (5-minute limit reached)")
                    endRecording()
                }
            },
            onError = { err ->
                TranscriptionStateRepository.update {
                    it.copy(status = RecorderStatus.ERROR, errorMessage = err)
                }
                showToast(err)
            }
        )

        bubbleOverlayManager = BubbleOverlayManager(
            context = this,
            onAction = { action -> handleServiceAction(action) },
            onOpenApp = {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        )

        serviceScope.launch {
            TranscriptionStateRepository.state.collectLatest { session ->
                updateNotificationAndOverlay(session)
            }
        }

        serviceScope.launch {
            preferences.settings.collectLatest { settings ->
                if (settings.serviceEnabled) {
                    syncOverlayVisibility(settings.controlMode)
                    if (isForegroundStarted) {
                        val notif = NotificationHelper.buildNotification(
                            this@TranscriptionForegroundService,
                            TranscriptionStateRepository.state.value,
                            settings.controlMode
                        )
                        notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notif)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        val action = intent?.action ?: ACTION_START_SERVICE
        handleServiceAction(action)
        return START_STICKY
    }

    private fun handleServiceAction(action: String) {
        when (action) {
            ACTION_START_SERVICE -> {
                preferences.setServiceEnabled(true)
                syncOverlayVisibility(preferences.settings.value.controlMode)
            }

            ACTION_STOP_SERVICE -> {
                stopServiceGracefully()
            }

            ACTION_REFRESH_MODE -> {
                syncOverlayVisibility(preferences.settings.value.controlMode)
                updateNotificationAndOverlay(TranscriptionStateRepository.state.value)
            }

            ACTION_START_RECORDING -> startRecording()
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
            ACTION_END_RECORDING -> endRecording()
            ACTION_TRANSCRIBE_OR_COPY -> handleTranscribeOrCopy()
            ACTION_CANCEL_TRANSCRIBE -> cancelTranscription()
        }
    }

    private fun startRecording() {
        if (!hasRecordAudioPermission()) {
            showToast("Microphone permission required")
            val openApp = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(openApp)
            return
        }

        transcribeJob?.cancel()
        transcribeJob = null
        lastRecordedPcm = null

        val started = recorderEngine.start()
        if (started) {
            // Re-promote foreground service with MICROPHONE type now that audio permission is guaranteed.
            promoteForeground()
            TranscriptionStateRepository.update {
                SessionState(
                    status = RecorderStatus.RECORDING,
                    elapsedMs = 0L
                )
            }
        }
    }

    private fun pauseRecording() {
        if (TranscriptionStateRepository.state.value.status != RecorderStatus.RECORDING) return
        recorderEngine.pause()
        TranscriptionStateRepository.update {
            it.copy(
                status = RecorderStatus.PAUSED,
                elapsedMs = recorderEngine.currentElapsedMs()
            )
        }
    }

    private fun resumeRecording() {
        if (TranscriptionStateRepository.state.value.status != RecorderStatus.PAUSED) return
        recorderEngine.resume()
        TranscriptionStateRepository.update {
            it.copy(
                status = RecorderStatus.RECORDING,
                elapsedMs = recorderEngine.currentElapsedMs()
            )
        }
    }

    private fun endRecording() {
        val currentStatus = TranscriptionStateRepository.state.value.status
        if (currentStatus != RecorderStatus.RECORDING && currentStatus != RecorderStatus.PAUSED) return

        val elapsed = recorderEngine.currentElapsedMs()
        val rawPcm = recorderEngine.stop()
        if (rawPcm.isEmpty()) {
            lastRecordedPcm = null
            TranscriptionStateRepository.resetToIdle()
            showToast("Recording was empty")
            return
        }

        lastRecordedPcm = rawPcm
        val settings = preferences.settings.value

        TranscriptionStateRepository.update {
            it.copy(
                status = RecorderStatus.RECORDED,
                elapsedMs = elapsed,
                savedFileDisplayName = "Saving audio...",
                transcriptText = "",
                speechCleanupStats = "",
                errorMessage = null
            )
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val saved = storageManager.saveRecording(
                    pcmData = rawPcm,
                    sampleRate = AudioRecorderEngine.SAMPLE_RATE,
                    folderPreset = settings.storageFolder,
                    maxStoredFiles = settings.maxStoredFiles
                )
                withContext(Dispatchers.Main.immediate) {
                    TranscriptionStateRepository.update { state ->
                        if (state.status == RecorderStatus.RECORDED) {
                            state.copy(
                                savedFilePath = saved.displayPath,
                                savedFileUri = saved.shareUri.toString(),
                                savedFileDisplayName = saved.fileName
                            )
                        } else {
                            state
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving recording", e)
            }
        }
    }

    private fun handleTranscribeOrCopy() {
        val current = TranscriptionStateRepository.state.value
        if (current.status == RecorderStatus.TRANSCRIBED) {
            val text = current.transcriptText
            if (text.isNotBlank()) {
                copyToClipboard(text)
                showToast("Copied transcription to clipboard")
            } else {
                showToast("Transcription is empty")
            }
            return
        }

        if (current.status != RecorderStatus.RECORDED && current.status != RecorderStatus.ERROR) {
            return
        }

        val rawPcm = lastRecordedPcm
        if (rawPcm == null || rawPcm.isEmpty()) {
            showToast("No recording available to transcribe")
            TranscriptionStateRepository.resetToIdle()
            return
        }

        val settings = preferences.settings.value
        val apiKey = settings.apiKey.trim()
        if (apiKey.isEmpty()) {
            TranscriptionStateRepository.update {
                it.copy(
                    status = RecorderStatus.ERROR,
                    errorMessage = "AssemblyAI API key is empty"
                )
            }
            showToast("Please enter an AssemblyAI API key in the app")
            return
        }

        transcribeJob?.cancel()
        transcribeJob = serviceScope.launch {
            var tempUploadFile: File? = null
            try {
                TranscriptionStateRepository.update {
                    it.copy(
                        status = RecorderStatus.TRANSCRIBING,
                        statusDetail = if (settings.cleanAudioEnabled) "Cleaning speech (VAD)..." else "Preparing WAV...",
                        errorMessage = null
                    )
                }

                val cleanupResult = withContext(Dispatchers.Default) {
                    if (settings.cleanAudioEnabled) {
                        AudioSpeechCleaner.cleanForTranscription(rawPcm, AudioRecorderEngine.SAMPLE_RATE)
                    } else {
                        null
                    }
                }

                if (cleanupResult != null && !cleanupResult.hadSpeech) {
                    TranscriptionStateRepository.update {
                        it.copy(
                            status = RecorderStatus.ERROR,
                            statusDetail = "",
                            errorMessage = "No speech detected (skipped upload to save tokens)"
                        )
                    }
                    showToast("No speech detected — skipped upload")
                    return@launch
                }

                val pcmToSend = cleanupResult?.cleanedPcm ?: rawPcm
                val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}.wav")
                tempUploadFile = tempFile

                withContext(Dispatchers.IO) {
                    AudioSpeechCleaner.writeWavFile(tempFile, pcmToSend, AudioRecorderEngine.SAMPLE_RATE)
                }

                val result = assemblyAiClient.transcribeAudio(tempFile, apiKey) { statusMsg ->
                    TranscriptionStateRepository.update { state ->
                        if (state.status == RecorderStatus.TRANSCRIBING) {
                            state.copy(statusDetail = statusMsg)
                        } else {
                            state
                        }
                    }
                }

                result.fold(
                    onSuccess = { text ->
                        val sentDurationMs = cleanupResult?.cleanedDurationMs
                            ?: ((rawPcm.size * 1000L) / AudioRecorderEngine.SAMPLE_RATE)
                        preferences.addTranscribedDurationMs(sentDurationMs)

                        val stats = if (cleanupResult != null) {
                            String.format(
                                Locale.US,
                                "Sent %.1fs speech / %.1fs total",
                                cleanupResult.cleanedDurationMs / 1000.0,
                                cleanupResult.originalDurationMs / 1000.0
                            )
                        } else {
                            ""
                        }

                        if (text.isNotBlank()) {
                            copyToClipboard(text)
                            showToast("Transcribed & copied to clipboard!")
                        } else {
                            showToast("Transcription completed (empty text)")
                        }

                        TranscriptionStateRepository.update {
                            it.copy(
                                status = RecorderStatus.TRANSCRIBED,
                                statusDetail = "",
                                transcriptText = text,
                                speechCleanupStats = stats,
                                errorMessage = null
                            )
                        }
                    },
                    onFailure = { error ->
                        val msg = error.message ?: "Transcription failed"
                        TranscriptionStateRepository.update {
                            it.copy(
                                status = RecorderStatus.ERROR,
                                statusDetail = "",
                                errorMessage = msg
                            )
                        }
                        showToast(msg)
                    }
                )
            } catch (_: CancellationException) {
                TranscriptionStateRepository.update {
                    it.copy(status = RecorderStatus.RECORDED, statusDetail = "")
                }
            } finally {
                tempUploadFile?.delete()
            }
        }
    }

    private fun cancelTranscription() {
        transcribeJob?.cancel()
        transcribeJob = null
        TranscriptionStateRepository.update {
            if (lastRecordedPcm != null) {
                it.copy(status = RecorderStatus.RECORDED, statusDetail = "")
            } else {
                SessionState()
            }
        }
    }

    private fun stopServiceGracefully() {
        transcribeJob?.cancel()
        transcribeJob = null
        recorderEngine.discard()
        lastRecordedPcm = null
        bubbleOverlayManager.hide()
        preferences.setServiceEnabled(false)
        TranscriptionStateRepository.resetToIdle()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        stopSelf()
    }

    private fun ensureForeground() {
        if (!isForegroundStarted) {
            promoteForeground()
        }
    }

    private fun promoteForeground() {
        val controlMode = preferences.settings.value.controlMode
        val notification = NotificationHelper.buildNotification(
            this,
            TranscriptionStateRepository.state.value,
            controlMode
        )

        val fgsType = resolveForegroundServiceType()
        try {
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID, notification, fgsType)
            isForegroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed with type=$fgsType", e)
            // Fallback to basic specialUse / 0 if microphone permission state caused SecurityException.
            val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID, notification, fallbackType)
            isForegroundStarted = true
        }
    }

    private fun resolveForegroundServiceType(): Int {
        val hasMic = hasRecordAudioPermission()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (hasMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        } else {
            0
        }
    }

    private fun syncOverlayVisibility(controlMode: ControlMode) {
        if (controlMode == ControlMode.BUBBLE && Settings.canDrawOverlays(this)) {
            bubbleOverlayManager.show()
        } else {
            bubbleOverlayManager.hide()
        }
    }

    private fun updateNotificationAndOverlay(state: SessionState) {
        bubbleOverlayManager.updateState(state)
        if (isForegroundStarted) {
            val controlMode = preferences.settings.value.controlMode
            val notification = NotificationHelper.buildNotification(this, state, controlMode)
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", text))
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard copy failed", e)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        recorderEngine.discard()
        bubbleOverlayManager.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TranscriptionFgService"
        private const val MAX_RECORDING_DURATION_MS = 5 * 60 * 1000L

        const val ACTION_START_SERVICE = "com.globaltranscription.android.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.globaltranscription.android.action.STOP_SERVICE"
        const val ACTION_START_RECORDING = "com.globaltranscription.android.action.START_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.globaltranscription.android.action.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.globaltranscription.android.action.RESUME_RECORDING"
        const val ACTION_END_RECORDING = "com.globaltranscription.android.action.END_RECORDING"
        const val ACTION_TRANSCRIBE_OR_COPY = "com.globaltranscription.android.action.TRANSCRIBE_OR_COPY"
        const val ACTION_CANCEL_TRANSCRIBE = "com.globaltranscription.android.action.CANCEL_TRANSCRIBE"
        const val ACTION_REFRESH_MODE = "com.globaltranscription.android.action.REFRESH_MODE"

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
                this.action = action
            }
            if (action == ACTION_STOP_SERVICE) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
