package com.globaltranscription.android.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import com.globaltranscription.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ControlMode(val label: String) {
    BUBBLE("Floating Bubble"),
    NOTIFICATION("Notification")
}

enum class StorageFolderPreset(
    val displayPath: String,
    val baseDirectoryName: String
) {
    DOWNLOADS("Downloads/GlobalTranscription", Environment.DIRECTORY_DOWNLOADS),
    RECORDINGS("Recordings/GlobalTranscription", "Recordings"),
    MUSIC("Music/GlobalTranscription", Environment.DIRECTORY_MUSIC);

    fun resolvePublicDirectoryName(): String {
        return if (this == RECORDINGS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Environment.DIRECTORY_RECORDINGS
        } else {
            baseDirectoryName
        }
    }
}

data class SettingsState(
    val serviceEnabled: Boolean = false,
    val controlMode: ControlMode = ControlMode.BUBBLE,
    val maxStoredFiles: Int = 1,
    val storageFolder: StorageFolderPreset = StorageFolderPreset.DOWNLOADS,
    val apiKey: String = BuildConfig.DEFAULT_ASSEMBLYAI_API_KEY,
    val cleanAudioEnabled: Boolean = true,
    val totalTranscribedSeconds: Double = 0.0
)

class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private fun loadSettings(): SettingsState {
        val modeName = prefs.getString(KEY_CONTROL_MODE, ControlMode.BUBBLE.name)
        val controlMode = ControlMode.entries.firstOrNull { it.name == modeName } ?: ControlMode.BUBBLE

        val folderName = prefs.getString(KEY_STORAGE_FOLDER, StorageFolderPreset.DOWNLOADS.name)
        val storageFolder = StorageFolderPreset.entries.firstOrNull { it.name == folderName }
            ?: StorageFolderPreset.DOWNLOADS

        val storedKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_ASSEMBLYAI_API_KEY

        return SettingsState(
            serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false),
            controlMode = controlMode,
            maxStoredFiles = prefs.getInt(KEY_MAX_STORED_FILES, 1).coerceIn(MIN_FILES, MAX_FILES),
            storageFolder = storageFolder,
            apiKey = storedKey,
            cleanAudioEnabled = prefs.getBoolean(KEY_CLEAN_AUDIO_ENABLED, true),
            totalTranscribedSeconds = prefs.getLong(KEY_TOTAL_TRANSCRIBED_MS, 0L) / 1000.0
        )
    }

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
        _settings.update { it.copy(serviceEnabled = enabled) }
    }

    fun setControlMode(mode: ControlMode) {
        prefs.edit().putString(KEY_CONTROL_MODE, mode.name).apply()
        _settings.update { it.copy(controlMode = mode) }
    }

    fun setMaxStoredFiles(count: Int) {
        val clamped = count.coerceIn(MIN_FILES, MAX_FILES)
        prefs.edit().putInt(KEY_MAX_STORED_FILES, clamped).apply()
        _settings.update { it.copy(maxStoredFiles = clamped) }
    }

    fun setStorageFolder(folder: StorageFolderPreset) {
        prefs.edit().putString(KEY_STORAGE_FOLDER, folder.name).apply()
        _settings.update { it.copy(storageFolder = folder) }
    }

    fun setApiKey(key: String) {
        val cleaned = key.trim()
        prefs.edit().putString(KEY_API_KEY, cleaned).apply()
        _settings.update { it.copy(apiKey = cleaned) }
    }

    fun setCleanAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLEAN_AUDIO_ENABLED, enabled).apply()
        _settings.update { it.copy(cleanAudioEnabled = enabled) }
    }

    fun addTranscribedDurationMs(durationMs: Long) {
        if (durationMs <= 0L) return
        val updatedMs = prefs.getLong(KEY_TOTAL_TRANSCRIBED_MS, 0L) + durationMs
        prefs.edit().putLong(KEY_TOTAL_TRANSCRIBED_MS, updatedMs).apply()
        _settings.update { it.copy(totalTranscribedSeconds = updatedMs / 1000.0) }
    }

    fun resetTranscribedDuration() {
        prefs.edit().putLong(KEY_TOTAL_TRANSCRIBED_MS, 0L).apply()
        _settings.update { it.copy(totalTranscribedSeconds = 0.0) }
    }

    companion object {
        private const val PREFS_NAME = "global_transcription_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_CONTROL_MODE = "control_mode"
        private const val KEY_MAX_STORED_FILES = "max_stored_files"
        private const val KEY_STORAGE_FOLDER = "storage_folder"
        private const val KEY_API_KEY = "assemblyai_api_key"
        private const val KEY_CLEAN_AUDIO_ENABLED = "clean_audio_enabled"
        private const val KEY_TOTAL_TRANSCRIBED_MS = "total_transcribed_ms"

        const val MIN_FILES = 1
        const val MAX_FILES = 20

        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context).also { instance = it }
            }
        }
    }
}
