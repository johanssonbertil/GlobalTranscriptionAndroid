package com.globaltranscription.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.globaltranscription.android.MainActivity
import com.globaltranscription.android.R
import com.globaltranscription.android.data.ControlMode
import java.util.Locale

object NotificationHelper {

    const val CHANNEL_ID = "global_transcription_channel"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(
        context: Context,
        state: SessionState,
        controlMode: ControlMode
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        when (state.status) {
            RecorderStatus.IDLE -> {
                val hint = if (controlMode == ControlMode.BUBBLE) {
                    "Floating bubble active • Tap Start to record"
                } else {
                    "Ready • Tap Start to begin recording"
                }
                builder.setContentTitle("Global Transcription Ready")
                    .setContentText(hint)
                    .addAction(
                        0,
                        "Start",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_START_RECORDING, 1)
                    )
                    .addAction(
                        0,
                        "Disable",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_STOP_SERVICE, 2)
                    )
            }

            RecorderStatus.RECORDING -> {
                builder.setContentTitle("Recording • ${formatDuration(state.elapsedMs)}")
                    .setContentText("Listening via microphone...")
                    .addAction(
                        0,
                        "Pause",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_PAUSE_RECORDING, 3)
                    )
                    .addAction(
                        0,
                        "End",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_END_RECORDING, 4)
                    )
            }

            RecorderStatus.PAUSED -> {
                builder.setContentTitle("Paused • ${formatDuration(state.elapsedMs)}")
                    .setContentText("Recording paused")
                    .addAction(
                        0,
                        "Resume",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_RESUME_RECORDING, 5)
                    )
                    .addAction(
                        0,
                        "End",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_END_RECORDING, 4)
                    )
            }

            RecorderStatus.RECORDED -> {
                val fileLabel = state.savedFileDisplayName ?: "recording.wav"
                builder.setContentTitle("Recorded • ${formatDuration(state.elapsedMs)}")
                    .setContentText("Saved $fileLabel • Tap Transcribe")
                    .addAction(
                        0,
                        "New",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_START_RECORDING, 1)
                    )
                    .addAction(
                        0,
                        "Transcribe",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY, 6)
                    )
            }

            RecorderStatus.TRANSCRIBING -> {
                val detail = state.statusDetail.ifBlank { "Cleaning speech & transcribing..." }
                builder.setContentTitle("Transcribing...")
                    .setContentText(detail)
                    .setProgress(0, 0, true)
                    .addAction(
                        0,
                        "Cancel",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_CANCEL_TRANSCRIBE, 7)
                    )
            }

            RecorderStatus.TRANSCRIBED -> {
                val preview = state.transcriptText.ifBlank { "(No speech words recognized)" }
                builder.setContentTitle("Transcribed (Copied)")
                    .setContentText(preview)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                    .addAction(
                        0,
                        "New",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_START_RECORDING, 1)
                    )
                    .addAction(
                        0,
                        "Copy",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY, 6)
                    )
            }

            RecorderStatus.ERROR -> {
                val err = state.errorMessage ?: "Transcription failed"
                builder.setContentTitle("Transcription Issue")
                    .setContentText(err)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(err))
                    .addAction(
                        0,
                        "New",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_START_RECORDING, 1)
                    )
                    .addAction(
                        0,
                        "Retry",
                        serviceActionPendingIntent(context, TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY, 6)
                    )
            }
        }

        return builder.build()
    }

    private fun serviceActionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
