package com.jnetaol.voicememo.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.jnetaol.voicememo.data.db.VoiceDatabase
import com.jnetaol.voicememo.data.model.Recording
import com.jnetaol.voicememo.logger.VoiceLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = VoiceDatabase.getInstance(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recordingsDir = File(application.filesDir, "recordings").also { if (!it.exists()) it.mkdirs() }

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingTime = MutableStateFlow(0L)
    val recordingTime: StateFlow<Long> = _recordingTime.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var timerJob: Job? = null
    private var currentRecordingFile: File? = null

    init {
        VoiceLogger.d("VoiceViewModel", "ViewModel init", "VM-001")
        loadRecordings()
    }

    fun loadRecordings() {
        scope.launch(Dispatchers.IO) {
            try { _recordings.value = db.recordingDao().getAll() }
            catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Load failed", "VM-ERR-001", e) }
        }
    }

    fun searchRecordings(query: String) {
        scope.launch(Dispatchers.IO) {
            try {
                _recordings.value = if (query.isBlank()) db.recordingDao().getAll()
                else db.recordingDao().search(query)
            } catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Search failed", "VM-ERR-002", e) }
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast("Microphone permission required")
            return
        }
        if (_isRecording.value) return

        try {
            val fileName = "voice_${System.currentTimeMillis()}.m4a"
            val file = File(recordingsDir, fileName)
            currentRecordingFile = file
            val dir = file.parentFile
            if (dir?.exists() != true) dir?.mkdirs()

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(getApplication())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            _isRecording.value = true
            _recordingTime.value = 0L
            timerJob = scope.launch {
                while (isActive) { delay(1000); _recordingTime.value += 1000 }
            }
            showToast("Recording started")
            VoiceLogger.d("VoiceViewModel", "Recording started", "VM-002", mapOf("file" to fileName))
        } catch (e: Exception) {
            VoiceLogger.e("VoiceViewModel", "Start recording failed", "VM-ERR-003", e)
            showToast("Recording failed: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            currentRecordingFile = null
        }
    }

    fun pauseRecording() {
        try {
            if (_isRecording.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                _isRecording.value = false
                timerJob?.cancel()
                VoiceLogger.d("VoiceViewModel", "Recording paused at ${_recordingTime.value}ms", "VM-003")
            }
        } catch (e: Exception) {
            VoiceLogger.e("VoiceViewModel", "Pause failed", "VM-ERR-004", e)
        }
    }

    fun resumeRecording() {
        try {
            if (!_isRecording.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                _isRecording.value = true
                timerJob = scope.launch {
                    while (isActive) { delay(1000); _recordingTime.value += 1000 }
                }
                VoiceLogger.d("VoiceViewModel", "Recording resumed", "VM-004")
            }
        } catch (e: Exception) {
            VoiceLogger.e("VoiceViewModel", "Resume failed", "VM-ERR-005", e)
        }
    }

    fun stopRecording(): Long {
        val duration = _recordingTime.value
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            mediaRecorder = null
            _isRecording.value = false
            timerJob?.cancel()
            _recordingTime.value = 0L

            val filePath = currentRecordingFile?.absolutePath ?: ""
            currentRecordingFile = null

            scope.launch(Dispatchers.IO) {
                try {
                    val recording = Recording(
                        title = "Recording ${SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US).format(Date())}",
                        filePath = filePath, durationMs = duration,
                        fileSizeBytes = if (File(filePath).exists()) File(filePath).length() else 0L
                    )
                    db.recordingDao().insert(recording)
                    loadRecordings()
                    withContext(Dispatchers.Main) {
                        showToast("Recording saved")
                    }
                    VoiceLogger.d("VoiceViewModel", "Recording saved", "VM-005", mapOf("duration" to duration.toString()))
                } catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Save failed", "VM-ERR-006", e) }
            }
        } catch (e: Exception) {
            VoiceLogger.e("VoiceViewModel", "Stop failed", "VM-ERR-007", e)
            _isRecording.value = false
            timerJob?.cancel()
            mediaRecorder?.release()
            mediaRecorder = null
        }
        return duration
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            mediaRecorder = null
            currentRecordingFile?.delete()
            currentRecordingFile = null
            _isRecording.value = false
            timerJob?.cancel()
            _recordingTime.value = 0L
            VoiceLogger.d("VoiceViewModel", "Recording cancelled", "VM-006")
        } catch (e: Exception) {
            VoiceLogger.e("VoiceViewModel", "Cancel failed", "VM-ERR-008", e)
        }
    }

    fun deleteRecording(recording: Recording) {
        scope.launch(Dispatchers.IO) {
            try {
                File(recording.filePath).delete()
                db.recordingDao().delete(recording.id)
                loadRecordings()
                withContext(Dispatchers.Main) { showToast("Recording deleted") }
            } catch (e: Exception) {
                VoiceLogger.e("VoiceViewModel", "Delete failed", "VM-ERR-009", e)
            }
        }
    }

    fun deleteAllRecordings() {
        scope.launch(Dispatchers.IO) {
            try {
                recordingsDir.listFiles()?.forEach { it.delete() }
                db.recordingDao().deleteAll()
                loadRecordings()
                withContext(Dispatchers.Main) { showToast("All recordings deleted") }
            } catch (e: Exception) {
                VoiceLogger.e("VoiceViewModel", "Delete all failed", "VM-ERR-010", e)
            }
        }
    }

    fun toggleFavorite(id: Long, fav: Boolean) {
        scope.launch(Dispatchers.IO) {
            try { db.recordingDao().toggleFavorite(id, fav); loadRecordings() }
            catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Fav toggle failed", "VM-ERR-011", e) }
        }
    }

    fun updateTags(id: Long, tags: String) {
        scope.launch(Dispatchers.IO) {
            try { db.recordingDao().updateTags(id, tags); loadRecordings() }
            catch (e: Exception) { VoiceLogger.e("VoiceViewModel", "Tag update failed", "VM-ERR-012", e) }
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

    override fun onCleared() {
        super.onCleared()
        mediaRecorder?.release()
        mediaRecorder = null
        scope.cancel()
        VoiceLogger.d("VoiceViewModel", "Cleared", "VM-006")
    }
}
