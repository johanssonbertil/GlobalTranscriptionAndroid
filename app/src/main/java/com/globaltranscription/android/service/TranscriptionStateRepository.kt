package com.globaltranscription.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecorderStatus {
    IDLE,
    RECORDING,
    PAUSED,
    RECORDED,
    TRANSCRIBING,
    TRANSCRIBED,
    ERROR
}

data class SessionState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0L,
    val savedFilePath: String? = null,
    val savedFileUri: String? = null,
    val savedFileDisplayName: String? = null,
    val statusDetail: String = "",
    val transcriptText: String = "",
    val speechCleanupStats: String = "",
    val errorMessage: String? = null
)

object TranscriptionStateRepository {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun update(transform: (SessionState) -> SessionState) {
        _state.update(transform)
    }

    fun resetToIdle() {
        _state.value = SessionState()
    }
}
