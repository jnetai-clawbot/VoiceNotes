package com.jnetaol.voicememo.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.jnetaol.voicememo.BuildConfig
import com.jnetaol.voicememo.data.db.VoiceDatabase
import com.jnetaol.voicememo.data.model.Recording
import com.jnetaol.voicememo.engine.AudioTranscriber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val latestVersion: String) : UpdateCheckState
    data class UpdateAvailable(val latestVersion: String, val url: String) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val db = VoiceDatabase.getInstance(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recordingsDir = File(application.filesDir, "recordings").apply { if (!exists()) mkdirs() }

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingTime = MutableStateFlow(0L)
    val recordingTime: StateFlow<Long> = _recordingTime.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _processingIds = MutableStateFlow<Set<Long>>(emptySet())
    val processingIds: StateFlow<Set<Long>> = _processingIds.asStateFlow()

    private val _transcriptionStatus = MutableStateFlow<String?>(null)
    val transcriptionStatus: StateFlow<String?> = _transcriptionStatus.asStateFlow()

    private val _installSuggestion = MutableStateFlow<String?>(null)
    val installSuggestion: StateFlow<String?> = _installSuggestion.asStateFlow()

    private val _updateCheck = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateCheck: StateFlow<UpdateCheckState> = _updateCheck.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var timerJob: Job? = null
    private var currentFile: File? = null

    init { loadRecordings() }

    fun loadRecordings() {
        scope.launch(Dispatchers.IO) {
            try { _recordings.value = db.recordingDao().getAll() }
            catch (e: Exception) { showToast("DB error: ${e.message}") }
        }
    }

    fun searchRecordings(query: String) {
        scope.launch(Dispatchers.IO) {
            try {
                _recordings.value = if (query.isBlank()) db.recordingDao().getAll()
                else db.recordingDao().search(query)
            } catch (_: Exception) {}
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast("Please grant microphone permission")
            return
        }
        if (_isRecording.value) return

        _isRecording.value = true
        _recordingTime.value = 0L
        timerJob = scope.launch {
            while (isActive) { delay(1000); _recordingTime.value += 1000 }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val file = File(recordingsDir, "voice_${System.currentTimeMillis()}.m4a")
                file.parentFile?.mkdirs()
                currentFile = file

                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(app)
                } else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }

                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(96000)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                mediaRecorder = recorder

                showToast("Recording...")
            } catch (e: Exception) {
                showToast("Recorder error: ${e.message}")
                _isRecording.value = false
                timerJob?.cancel()
                currentFile = null
            }
        }
    }

    fun pauseRecording() {
        try {
            if (_isRecording.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                _isRecording.value = false
                timerJob?.cancel()
            }
        } catch (_: Exception) {}
    }

    fun resumeRecording() {
        try {
            if (!_isRecording.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                _isRecording.value = true
                timerJob = scope.launch {
                    while (isActive) { delay(1000); _recordingTime.value += 1000 }
                }
            }
        } catch (_: Exception) {}
    }

    fun stopRecording(): Long {
        val duration = _recordingTime.value
        _isRecording.value = false
        timerJob?.cancel()
        _recordingTime.value = 0L

        scope.launch(Dispatchers.IO) {
            try {
                val rec = mediaRecorder
                mediaRecorder = null
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}

                val filePath = currentFile?.absolutePath ?: ""
                currentFile = null

                val recording = Recording(
                    title = "Recording ${SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US).format(Date())}",
                    filePath = filePath,
                    durationMs = duration,
                    fileSizeBytes = if (File(filePath).exists()) File(filePath).length() else 0L
                )
                db.recordingDao().insert(recording)
                loadRecordings()
                showToast("Recording saved")
            } catch (e: Exception) {
                showToast("Save error: ${e.message}")
            }
        }
        return duration
    }

    fun cancelRecording() {
        scope.launch(Dispatchers.IO) {
            try {
                try { mediaRecorder?.stop() } catch (_: Exception) {}
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
                currentFile?.delete()
                currentFile = null
            } catch (_: Exception) {}
        }
        _isRecording.value = false
        timerJob?.cancel()
        _recordingTime.value = 0L
    }

    fun updateTranscription(id: Long, text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                db.recordingDao().updateTranscription(id, text)
                loadRecordings()
                showToast("Transcription saved")
            } catch (e: Exception) {
                showToast("Transcribe error: ${e.message}")
            }
        }
    }

    fun transcribeRecording(id: Long, filePath: String, language: String = "en") {
        if (_processingIds.value.contains(id) || filePath.isBlank()) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast("Microphone permission is required for transcription. Grant it in app settings.")
            return
        }
        _processingIds.update { it + id }
        _transcriptionStatus.value = "Transcribing\u2026"
        showToast("Transcribing...")
        scope.launch(Dispatchers.IO) {
            AudioTranscriber(app).transcribe(filePath, language, object : AudioTranscriber.Callback {
                override fun onResult(text: String) {
                    _processingIds.update { it - id }
                    _transcriptionStatus.value = null
                    scope.launch(Dispatchers.IO) {
                        db.recordingDao().updateTranscription(id, text)
                        loadRecordings()
                    }
                    showToast("Transcription saved")
                }

                override fun onError(message: String) {
                    _processingIds.update { it - id }
                    _transcriptionStatus.value = "Transcription failed: $message"
                    showToast("Transcription failed")
                }

                override fun onInstallSuggestion(message: String) {
                    _installSuggestion.value = message
                }
            })
        }
    }

    fun dismissInstallSuggestion() {
        _installSuggestion.value = null
    }

    fun checkForUpdates() {
        if (_updateCheck.value is UpdateCheckState.Checking) return
        _updateCheck.value = UpdateCheckState.Checking
        scope.launch {
            val state = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("https://api.github.com/repos/jnetai-clawbot/VoiceNotes/releases/latest")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    try {
                        if (conn.responseCode == 200) {
                            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            val latestVersion = (json.optString("tag_name") ?: "").removePrefix("v")
                            val releaseUrl = json.optString("html_url") ?: "https://github.com/jnetai-clawbot/VoiceNotes/releases/latest"
                            if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
                                UpdateCheckState.UpdateAvailable(latestVersion, releaseUrl)
                            } else {
                                UpdateCheckState.UpToDate(latestVersion)
                            }
                        } else {
                            UpdateCheckState.Error("GitHub returned HTTP ${conn.responseCode}")
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    UpdateCheckState.Error(e.message ?: "Could not reach GitHub")
                }
            }
            _updateCheck.value = state
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    fun deleteRecording(recording: Recording) {
        scope.launch(Dispatchers.IO) {
            try { File(recording.filePath).delete(); db.recordingDao().delete(recording.id); loadRecordings() }
            catch (_: Exception) {}
        }
    }

    fun deleteAllRecordings() {
        scope.launch(Dispatchers.IO) {
            try { recordingsDir.listFiles()?.forEach { it.delete() }; db.recordingDao().deleteAll(); loadRecordings() }
            catch (_: Exception) {}
        }
    }

    fun toggleFavorite(id: Long, fav: Boolean) {
        scope.launch(Dispatchers.IO) {
            try { db.recordingDao().toggleFavorite(id, fav); loadRecordings() }
            catch (_: Exception) {}
        }
    }

    fun updateTags(id: Long, tags: String) {
        scope.launch(Dispatchers.IO) {
            try { db.recordingDao().updateTags(id, tags); loadRecordings() }
            catch (_: Exception) {}
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

    fun showToast(msg: String) {
        scope.launch { _toastMessage.emit(msg) }
        scope.launch { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onCleared() {
        super.onCleared()
        mediaRecorder?.release()
        mediaRecorder = null
        scope.cancel()
    }
}
