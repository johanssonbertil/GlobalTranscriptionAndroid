package com.globaltranscription.android.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.globaltranscription.android.data.StorageFolderPreset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedRecording(
    val shareUri: Uri,
    val displayPath: String,
    val fileName: String
)

class RecordingStorageManager(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveRecording(
        pcmData: ShortArray,
        sampleRate: Int,
        folderPreset: StorageFolderPreset,
        maxStoredFiles: Int
    ): SavedRecording {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "GT_$timestamp.wav"

        // Enforce retention limit before writing the new file so peak usage stays minimal.
        val keepBeforeInsert = (maxStoredFiles - 1).coerceAtLeast(0)
        pruneOldRecordings(keepBeforeInsert)

        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(pcmData, sampleRate, fileName, folderPreset)
                ?: saveToAppExternalFallback(pcmData, sampleRate, fileName)
        } else {
            saveLegacyPublicFile(pcmData, sampleRate, fileName, folderPreset)
                ?: saveToAppExternalFallback(pcmData, sampleRate, fileName)
        }

        recordHistoryEntry(saved.historyEntry)
        pruneOldRecordings(maxStoredFiles.coerceAtLeast(1))

        return saved.recording
    }

    fun pruneOldRecordings(maxKeep: Int) {
        val entries = loadHistory().toMutableList()
        var changed = false
        while (entries.size > maxKeep) {
            val oldest = entries.removeAt(0)
            deleteHistoryEntry(oldest)
            changed = true
        }
        if (changed) {
            saveHistory(entries)
        }
    }

    private data class InternalSavedResult(
        val recording: SavedRecording,
        val historyEntry: TrackedFileEntry
    )

    private data class TrackedFileEntry(
        val uriString: String?,
        val filePath: String?
    )

    private fun saveViaMediaStore(
        pcmData: ShortArray,
        sampleRate: Int,
        fileName: String,
        folderPreset: StorageFolderPreset
    ): InternalSavedResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val tempFile = File(context.cacheDir, "tmp_save_$fileName")
        return try {
            AudioSpeechCleaner.writeWavFile(tempFile, pcmData, sampleRate)

            val (collectionUri, relativePath) = resolveCollectionAndRelativePath(folderPreset)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val insertedUri = try {
                resolver.insert(collectionUri, values)
            } catch (e: IllegalArgumentException) {
                // Fallback to Downloads collection if Recordings/Music relative path is rejected on older OEM ROMs.
                Log.w(TAG, "MediaStore insert rejected $relativePath, falling back to Downloads", e)
                val fallbackPath = "${Environment.DIRECTORY_DOWNLOADS}/$APP_SUBFOLDER"
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, fallbackPath)
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } ?: return null

            resolver.openOutputStream(insertedUri)?.use { out ->
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: run {
                resolver.delete(insertedUri, null, null)
                return null
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(insertedUri, publishValues, null, null)

            val actualRelPath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) ?: relativePath
            InternalSavedResult(
                recording = SavedRecording(
                    shareUri = insertedUri,
                    displayPath = "$actualRelPath/$fileName",
                    fileName = fileName
                ),
                historyEntry = TrackedFileEntry(
                    uriString = insertedUri.toString(),
                    filePath = null
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving via MediaStore", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun resolveCollectionAndRelativePath(folderPreset: StorageFolderPreset): Pair<Uri, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/$APP_SUBFOLDER"
        }
        return when (folderPreset) {
            StorageFolderPreset.DOWNLOADS -> {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/$APP_SUBFOLDER"
            }
            StorageFolderPreset.RECORDINGS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_RECORDINGS}/$APP_SUBFOLDER"
                } else {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/$APP_SUBFOLDER"
                }
            }
            StorageFolderPreset.MUSIC -> {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/$APP_SUBFOLDER"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacyPublicFile(
        pcmData: ShortArray,
        sampleRate: Int,
        fileName: String,
        folderPreset: StorageFolderPreset
    ): InternalSavedResult? {
        return try {
            val baseDir = Environment.getExternalStoragePublicDirectory(folderPreset.resolvePublicDirectoryName())
            val targetDir = File(baseDir, APP_SUBFOLDER)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return null
            }
            val destFile = File(targetDir, fileName)
            AudioSpeechCleaner.writeWavFile(destFile, pcmData, sampleRate)
            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
            InternalSavedResult(
                recording = SavedRecording(
                    shareUri = shareUri,
                    displayPath = destFile.absolutePath,
                    fileName = fileName
                ),
                historyEntry = TrackedFileEntry(
                    uriString = null,
                    filePath = destFile.absolutePath
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Legacy public dir write unavailable, using app fallback", e)
            null
        }
    }

    private fun saveToAppExternalFallback(
        pcmData: ShortArray,
        sampleRate: Int,
        fileName: String
    ): InternalSavedResult {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val targetDir = File(baseDir, APP_SUBFOLDER).apply { mkdirs() }
        val destFile = File(targetDir, fileName)
        AudioSpeechCleaner.writeWavFile(destFile, pcmData, sampleRate)
        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destFile
        )
        return InternalSavedResult(
            recording = SavedRecording(
                shareUri = shareUri,
                displayPath = destFile.absolutePath,
                fileName = fileName
            ),
            historyEntry = TrackedFileEntry(
                uriString = null,
                filePath = destFile.absolutePath
            )
        )
    }

    private fun deleteHistoryEntry(entry: TrackedFileEntry) {
        entry.uriString?.let { uriStr ->
            try {
                context.contentResolver.delete(Uri.parse(uriStr), null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete MediaStore URI $uriStr", e)
            }
        }
        entry.filePath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete file $path", e)
            }
        }
    }

    private fun recordHistoryEntry(entry: TrackedFileEntry) {
        val entries = loadHistory().toMutableList()
        entries.add(entry)
        saveHistory(entries)
    }

    private fun loadHistory(): List<TrackedFileEntry> {
        val raw = prefs.getString(KEY_TRACKED_FILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val uri = obj.optString("uri", "").ifBlank { null }
                    val path = obj.optString("path", "").ifBlank { null }
                    if (uri != null || path != null) {
                        add(TrackedFileEntry(uri, path))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(entries: List<TrackedFileEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            val obj = JSONObject().apply {
                if (entry.uriString != null) put("uri", entry.uriString)
                if (entry.filePath != null) put("path", entry.filePath)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_TRACKED_FILES, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "RecordingStorageManager"
        private const val PREFS_NAME = "recording_storage_history"
        private const val KEY_TRACKED_FILES = "tracked_files_json"
        private const val APP_SUBFOLDER = "GlobalTranscription"
    }
}
