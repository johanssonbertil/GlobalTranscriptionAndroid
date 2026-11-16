package com.globaltranscription.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.globaltranscription.android.audio.RecordingStorageManager
import com.globaltranscription.android.data.AppPreferences
import com.globaltranscription.android.data.ControlMode
import com.globaltranscription.android.data.SettingsState
import com.globaltranscription.android.data.StorageFolderPreset
import com.globaltranscription.android.service.NotificationHelper
import com.globaltranscription.android.service.RecorderStatus
import com.globaltranscription.android.service.SessionState
import com.globaltranscription.android.service.TranscriptionForegroundService
import com.globaltranscription.android.service.TranscriptionStateRepository
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlobalTranscriptionTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun GlobalTranscriptionTheme(content: @Composable () -> Unit) {
    val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF60A5FA),
            secondary = Color(0xFF34D399),
            background = Color(0xFF0B1120),
            surface = Color(0xFF111827),
            surfaceVariant = Color(0xFF1E293B)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1D4ED8),
            secondary = Color(0xFF059669),
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1F5F9)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember { AppPreferences.getInstance(context) }
    val storageManager = remember { RecordingStorageManager(context) }

    val settings by preferences.settings.collectAsStateWithLifecycle()
    val session by TranscriptionStateRepository.state.collectAsStateWithLifecycle()

    var hasAudioPerm by remember { mutableStateOf(checkAudioPerm(context)) }
    var hasNotifPerm by remember { mutableStateOf(checkNotifPerm(context)) }
    var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var pendingActionAfterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingOverlayAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun refreshPermissions() {
        hasAudioPerm = checkAudioPerm(context)
        hasNotifPerm = checkNotifPerm(context)
        hasOverlayPerm = Settings.canDrawOverlays(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissions()
        val pending = pendingOverlayAction
        pendingOverlayAction = null
        if (Settings.canDrawOverlays(context)) {
            pending?.invoke()
        } else {
            Toast.makeText(
                context,
                "Overlay permission not granted; switched to Notification mode",
                Toast.LENGTH_LONG
            ).show()
            preferences.setControlMode(ControlMode.NOTIFICATION)
            if (settings.serviceEnabled) {
                TranscriptionForegroundService.sendAction(
                    context,
                    TranscriptionForegroundService.ACTION_REFRESH_MODE
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        val pending = pendingActionAfterPermissions
        pendingActionAfterPermissions = null
        if (checkAudioPerm(context)) {
            pending?.invoke()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for recording",
                Toast.LENGTH_LONG
            ).show()
            preferences.setServiceEnabled(false)
        }
    }

    fun requestOverlayPermission(onGranted: () -> Unit) {
        if (Settings.canDrawOverlays(context)) {
            onGranted()
            return
        }
        pendingOverlayAction = onGranted
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        overlayLauncher.launch(intent)
    }

    fun startServiceWithChecks(mode: ControlMode) {
        val missing = buildList {
            if (!checkAudioPerm(context)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !checkNotifPerm(context)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (missing.isNotEmpty()) {
            pendingActionAfterPermissions = { startServiceWithChecks(mode) }
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        if (mode == ControlMode.BUBBLE && !Settings.canDrawOverlays(context)) {
            requestOverlayPermission {
                preferences.setServiceEnabled(true)
                TranscriptionForegroundService.sendAction(
                    context,
                    TranscriptionForegroundService.ACTION_START_SERVICE
                )
            }
            return
        }

        preferences.setServiceEnabled(true)
        TranscriptionForegroundService.sendAction(
            context,
            TranscriptionForegroundService.ACTION_START_SERVICE
        )
    }

    fun stopService() {
        preferences.setServiceEnabled(false)
        TranscriptionForegroundService.sendAction(
            context,
            TranscriptionForegroundService.ACTION_STOP_SERVICE
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Global Transcription",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "System-wide voice capture & AssemblyAI Universal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Service Enable / Disable + Mode Card
            ServiceMasterCard(
                settings = settings,
                hasAudioPerm = hasAudioPerm,
                hasNotifPerm = hasNotifPerm,
                hasOverlayPerm = hasOverlayPerm,
                onToggleService = { enabled ->
                    if (enabled) {
                        startServiceWithChecks(settings.controlMode)
                    } else {
                        stopService()
                    }
                },
                onSelectMode = { mode ->
                    preferences.setControlMode(mode)
                    startServiceWithChecks(mode)
                }
            )

            // 2. Live Status, Controls, Transcript & Share Card
            LiveSessionCard(
                session = session,
                serviceEnabled = settings.serviceEnabled,
                onAction = { action ->
                    if (!settings.serviceEnabled) {
                        startServiceWithChecks(settings.controlMode)
                    }
                    TranscriptionForegroundService.sendAction(context, action)
                },
                onCopyText = { text ->
                    copyTextToClipboard(context, text)
                    Toast.makeText(context, "Copied transcription to clipboard", Toast.LENGTH_SHORT).show()
                },
                onShareAudio = { uriString ->
                    shareAudioFile(context, uriString)
                }
            )

            // 3. Audio Storage Settings Card
            StorageSettingsCard(
                settings = settings,
                onDecrementMaxFiles = {
                    val next = (settings.maxStoredFiles - 1).coerceAtLeast(AppPreferences.MIN_FILES)
                    preferences.setMaxStoredFiles(next)
                    storageManager.pruneOldRecordings(next)
                },
                onIncrementMaxFiles = {
                    val next = (settings.maxStoredFiles + 1).coerceAtMost(AppPreferences.MAX_FILES)
                    preferences.setMaxStoredFiles(next)
                },
                onSelectFolder = { folder ->
                    preferences.setStorageFolder(folder)
                }
            )

            // 4. Transcription & Speech Cleanup Settings Card
            TranscriptionSettingsCard(
                settings = settings,
                onToggleCleanAudio = { preferences.setCleanAudioEnabled(it) },
                onApiKeyChange = { preferences.setApiKey(it) },
                onResetUsage = { preferences.resetTranscribedDuration() },
                onOpenBillingDashboard = { openBillingDashboard(context) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServiceMasterCard(
    settings: SettingsState,
    hasAudioPerm: Boolean,
    hasNotifPerm: Boolean,
    hasOverlayPerm: Boolean,
    onToggleService: (Boolean) -> Unit,
    onSelectMode: (ControlMode) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (settings.serviceEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = if (settings.serviceEnabled) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (settings.serviceEnabled) "Service Active" else "Service Disabled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (settings.serviceEnabled) {
                                "Controlled via ${settings.controlMode.label}"
                            } else {
                                "Enable to show floating bubble or notification"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = settings.serviceEnabled,
                    onCheckedChange = onToggleService
                )
            }

            HorizontalDivider()

            Text(
                text = "Control Surface",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ControlMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.serviceEnabled && settings.controlMode == mode,
                        onClick = { onSelectMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ControlMode.entries.size
                        ),
                        icon = {
                            Icon(
                                imageVector = if (mode == ControlMode.BUBBLE) {
                                    Icons.Rounded.BubbleChart
                                } else {
                                    Icons.Rounded.Notifications
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    ) {
                        Text(mode.label)
                    }
                }
            }

            // Permission summary pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PermissionBadge(label = "Mic", granted = hasAudioPerm, modifier = Modifier.weight(1f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionBadge(label = "Notif", granted = hasNotifPerm, modifier = Modifier.weight(1f))
                }
                if (settings.controlMode == ControlMode.BUBBLE) {
                    PermissionBadge(label = "Overlay", granted = hasOverlayPerm, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PermissionBadge(
    label: String,
    granted: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (granted) {
            Color(0x1A10B981)
        } else {
            Color(0x1AF59E0B)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF10B981) else Color(0xFFF59E0B),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LiveSessionCard(
    session: SessionState,
    serviceEnabled: Boolean,
    onAction: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onShareAudio: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = when (session.status) {
                            RecorderStatus.IDLE -> "Idle"
                            RecorderStatus.RECORDING -> "Recording • ${NotificationHelper.formatDuration(session.elapsedMs)}"
                            RecorderStatus.PAUSED -> "Paused • ${NotificationHelper.formatDuration(session.elapsedMs)}"
                            RecorderStatus.RECORDED -> "Recorded • ${NotificationHelper.formatDuration(session.elapsedMs)}"
                            RecorderStatus.TRANSCRIBING -> "Transcribing"
                            RecorderStatus.TRANSCRIBED -> "Transcribed"
                            RecorderStatus.ERROR -> "Error"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (session.status == RecorderStatus.TRANSCRIBING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (session.statusDetail.isNotBlank()) {
                    Text(
                        text = session.statusDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!session.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x1AEF4444)
                ) {
                    Text(
                        text = session.errorMessage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF4444)
                    )
                }
            }

            // Quick control buttons mirroring the bubble/notification state machine
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (session.status) {
                    RecorderStatus.IDLE -> {
                        Button(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_START_RECORDING) },
                            modifier = Modifier.weight(1f),
                            enabled = serviceEnabled
                        ) {
                            Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start Recording")
                        }
                    }

                    RecorderStatus.RECORDING -> {
                        FilledTonalButton(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_PAUSE_RECORDING) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pause")
                        }
                        Button(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_END_RECORDING) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("End")
                        }
                    }

                    RecorderStatus.PAUSED -> {
                        Button(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_RESUME_RECORDING) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Resume")
                        }
                        Button(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_END_RECORDING) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("End")
                        }
                    }

                    RecorderStatus.RECORDED, RecorderStatus.ERROR -> {
                        OutlinedButton(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_START_RECORDING) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("New Recording")
                        }
                        Button(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Transcribe")
                        }
                    }

                    RecorderStatus.TRANSCRIBING -> {
                        OutlinedButton(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_CANCEL_TRANSCRIBE) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel Transcription")
                        }
                    }

                    RecorderStatus.TRANSCRIBED -> {
                        OutlinedButton(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_START_RECORDING) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("New Recording")
                        }
                        Button(
                            onClick = { onAction(TranscriptionForegroundService.ACTION_TRANSCRIBE_OR_COPY) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                    }
                }
            }

            if (session.transcriptText.isNotBlank()) {
                var isTranscriptExpanded by remember(session.transcriptText) { mutableStateOf(false) }
                var isTranscriptTruncated by remember(session.transcriptText) { mutableStateOf(false) }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Latest Transcription",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (session.speechCleanupStats.isNotBlank()) {
                            Text(
                                text = session.speechCleanupStats,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    IconButton(onClick = { onCopyText(session.transcriptText) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy text")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isTranscriptExpanded || isTranscriptTruncated) {
                            isTranscriptExpanded = !isTranscriptExpanded
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isTranscriptExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 210.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = session.transcriptText,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text(
                                text = "Tap to collapse",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = session.transcriptText,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { result ->
                                    isTranscriptTruncated = result.hasVisualOverflow
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (isTranscriptTruncated) {
                                Text(
                                    text = "Tap to show full text",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            if (!session.savedFilePath.isNullOrBlank()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Saved Audio File",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = session.savedFilePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val shareUri = session.savedFileUri
                    if (!shareUri.isNullOrBlank()) {
                        FilledTonalButton(onClick = { onShareAudio(shareUri) }) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageSettingsCard(
    settings: SettingsState,
    onDecrementMaxFiles: () -> Unit,
    onIncrementMaxFiles: () -> Unit,
    onSelectFolder: (StorageFolderPreset) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Audio Storage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recordings Kept on Device",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Older recordings are deleted automatically when a new one is saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.width(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = onDecrementMaxFiles,
                        enabled = settings.maxStoredFiles > AppPreferences.MIN_FILES,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Decrease")
                    }
                    Text(
                        text = settings.maxStoredFiles.toString(),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalIconButton(
                        onClick = onIncrementMaxFiles,
                        enabled = settings.maxStoredFiles < AppPreferences.MAX_FILES,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Increase")
                    }
                }
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Save Location",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StorageFolderPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.storageFolder == preset,
                        onClick = { onSelectFolder(preset) },
                        label = { Text(preset.displayPath) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptionSettingsCard(
    settings: SettingsState,
    onToggleCleanAudio: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onResetUsage: () -> Unit,
    onOpenBillingDashboard: () -> Unit
) {
    var apiKeyVisible by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Transcription (AssemblyAI Universal)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clean Audio & Send Speech Only",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Applies 80 Hz high-pass filter, VAD silence removal, and peak normalization before upload to save tokens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.cleanAudioEnabled,
                    onCheckedChange = onToggleCleanAudio
                )
            }

            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("AssemblyAI API Key") },
                singleLine = true,
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector = if (apiKeyVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = "Toggle API key visibility"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            val totalSec = settings.totalTranscribedSeconds
            val totalMin = totalSec / 60.0
            val estUniversal2Cost = totalMin * 0.0025 // $0.15/hr
            val estUniversal35Cost = totalMin * 0.0035 // $0.21/hr

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Usage & Remaining Funds",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (totalSec > 0.0) {
                            TextButton(onClick = onResetUsage) {
                                Text("Reset")
                            }
                        }
                    }

                    Text(
                        text = String.format(
                            Locale.US,
                            "Tracked speech sent: %.1fs (%.2f min) • Est. cost: ~$%.4f – $%.4f",
                            totalSec,
                            totalMin,
                            estUniversal2Cost,
                            estUniversal35Cost
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = "AssemblyAI's REST API does not expose account balance directly via API key. Check exact remaining credits in your billing dashboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = onOpenBillingDashboard,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Check Remaining Funds (AssemblyAI Billing)")
                    }
                }
            }
        }
    }
}

private fun checkAudioPerm(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

private fun checkNotifPerm(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", text))
}

private fun shareAudioFile(context: Context, uriString: String) {
    try {
        val uri = Uri.parse(uriString)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Audio Recording"))
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to share audio: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun openBillingDashboard(context: Context) {
    try {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.assemblyai.com/dashboard/home")
        )
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
