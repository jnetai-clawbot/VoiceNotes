package com.jnetaol.voicememo.ui.screens

import android.app.Application
import android.content.Intent
import androidx.lifecycle.*
import com.jnetaol.voicememo.data.db.VoiceDatabase
import com.jnetaol.voicememo.data.model.Recording
import com.jnetaol.voicememo.engine.RecordingService
import com.jnetaol.voicememo.logger.VoiceLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = VoiceDatabase.getInstance(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recordingsDir = File(application.filesDir, "recordings").also { if (!it.exists()) it.mkdirs() }

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingTime = MutableStateFlow(0L)
    val recordingTime: StateFlow<Long> = _recordingTime.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var timerJob: Job? = null

    init {
        VoiceLogger.d("VoiceViewModel", "ViewModel init", "VM-001")
        loadRecordings()
    }

    fun loadRecordings() {
        scope.launch {
            try { _recordings.value = db.recordingDao().getAll() }
            catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Load failed", "VM-ERR-001", e) }
        }
    }

    fun searchRecordings(query: String) {
        scope.launch {
            try {
                _recordings.value = if (query.isBlank()) db.recordingDao().getAll()
                else db.recordingDao().search(query)
            } catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Search failed", "VM-ERR-002", e) }
        }
    }

    fun startRecording() {
        try {
            val fileName = "voice_${System.currentTimeMillis()}.m4a"
            val file = File(recordingsDir, fileName)
            val intent = Intent(getApplication(), RecordingService::class.java).apply {
                action = "START"
                putExtra("filePath", file.absolutePath)
            }
            getApplication<Application>().startForegroundService(intent)
            _isRecording.value = true
            _recordingTime.value = 0
            timerJob = scope.launch {
                while (isActive) { delay(1000); _recordingTime.value += 1000 }
            }
            VoiceLogger.d("VoiceViewModel", "Recording started", "VM-002", mapOf("file" to fileName))
        } catch (e: Exception) {
            VoiceLogger.e("VoiceViewModel", "Start recording failed", "VM-ERR-003", e)
            showToast("Failed to start recording")
        }
    }

    fun pauseRecording() {
        val intent = Intent(getApplication(), RecordingService::class.java).apply { action = "PAUSE" }
        getApplication<Application>().startService(intent)
        _isRecording.value = false
        timerJob?.cancel()
        VoiceLogger.d("VoiceViewModel", "Recording paused at ${_recordingTime.value}ms", "VM-003")
    }

    fun resumeRecording() {
        val intent = Intent(getApplication(), RecordingService::class.java).apply { action = "RESUME" }
        getApplication<Application>().startService(intent)
        _isRecording.value = true
        timerJob = scope.launch {
            while (isActive) { delay(1000); _recordingTime.value += 1000 }
        }
        VoiceLogger.d("VoiceViewModel", "Recording resumed", "VM-004")
    }

    fun stopRecording(): Long {
        val intent = Intent(getApplication(), RecordingService::class.java).apply { action = "STOP" }
        getApplication<Application>().startService(intent)
        _isRecording.value = false
        timerJob?.cancel()
        val duration = _recordingTime.value
        _recordingTime.value = 0

        scope.launch {
            try {
                val filePath = File(recordingsDir, "voice_${System.currentTimeMillis() - duration}.m4a").absolutePath
                val recording = Recording(
                    title = "Recording ${SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US).format(Date())}",
                    filePath = filePath, durationMs = duration,
                    fileSizeBytes = 1024 * (duration / 100)
                )
                db.recordingDao().insert(recording)
                loadRecordings()
                VoiceLogger.d("VoiceViewModel", "Recording saved", "VM-005", mapOf("duration" to duration.toString()))
            } catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Save failed", "VM-ERR-004", e) }
        }
        return duration
    }

    fun cancelRecording() {
        val intent = Intent(getApplication(), RecordingService::class.java).apply { action = "CANCEL" }
        getApplication<Application>().startService(intent)
        _isRecording.value = false
        timerJob?.cancel()
        _recordingTime.value = 0
    }

    fun deleteRecording(recording: Recording) {
        scope.launch {
            try {
                File(recording.filePath).delete()
                db.recordingDao().delete(recording.id)
                loadRecordings()
                showToast("Recording deleted")
            } catch (e: Exception) {
                VoiceLogger.e("VoiceViewModel", "Delete failed", "VM-ERR-005", e)
            }
        }
    }

    fun deleteAllRecordings() {
        scope.launch {
            try {
                recordingsDir.listFiles()?.forEach { it.delete() }
                db.recordingDao().deleteAll()
                loadRecordings()
                showToast("All recordings deleted")
            } catch (e: Exception) {
                VoiceLogger.e("VoiceViewModel", "Delete all failed", "VM-ERR-006", e)
            }
        }
    }

    fun toggleFavorite(id: Long, fav: Boolean) {
        scope.launch {
            try { db.recordingDao().toggleFavorite(id, fav); loadRecordings() }
            catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Fav toggle failed", "VM-ERR-007", e) }
        }
    }

    fun updateTags(id: Long, tags: String) {
        scope.launch {
            try { db.recordingDao().updateTags(id, tags); loadRecordings() }
            catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Tag update failed", "VM-ERR-008", e) }
        }
    }

    fun exportAsText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== VoiceMemo Export ===")
        sb.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(Date())}")
        sb.appendLine()
        _recordings.value.forEach { rec ->
            sb.appendLine("--- ${rec.title} (${rec.durationMs / 1000}s) ---")
            sb.appendLine(rec.transcription.ifBlank { "[No transcription]" })
            sb.appendLine()
        }
        return sb.toString()
    }

    fun showToast(msg: String) { scope.launch { _toastMessage.emit(msg) } }

    override fun onCleared() { super.onCleared(); scope.cancel(); VoiceLogger.d("VoiceViewModel", "Cleared", "VM-006") }
}
