package com.jnetaol.voicememo.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jnetaol.voicememo.MainActivity
import com.jnetaol.voicememo.logger.VoiceLogger
import java.io.File

class RecordingService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording = false
        private set
    private var startTime = 0L
    private var pausedDuration = 0L
    var onTranscriptionReady: ((String, Float) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "voice_memo_recording"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        VoiceLogger.d("RecordingService", "Service created", "VM-SVC-001")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startRecording(intent.getStringExtra("filePath") ?: "")
            "PAUSE" -> pauseRecording()
            "RESUME" -> resumeRecording()
            "STOP" -> stopRecording()
            "CANCEL" -> cancelRecording()
        }
        return START_STICKY
    }

    private fun startRecording(filePath: String) {
        try {
            outputFile = File(filePath)
            val dir = outputFile!!.parentFile
            if (dir?.exists() != true) dir?.mkdirs()

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            startTime = System.currentTimeMillis()
            startForeground(NOTIFICATION_ID, createNotification())
            VoiceLogger.d("RecordingService", "Recording started", "VM-SVC-002", mapOf(
                "path" to filePath))
        } catch (e: Exception) {
            VoiceLogger.e("RecordingService", "Start failed", "VM-SVC-ERR-001", e)
            stopSelf()
        }
    }

    private fun pauseRecording() {
        try {
            if (isRecording && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                pausedDuration += System.currentTimeMillis() - startTime
                isRecording = false
                VoiceLogger.d("RecordingService", "Recording paused", "VM-SVC-003")
            }
        } catch (e: Exception) {
            VoiceLogger.e("RecordingService", "Pause failed", "VM-SVC-ERR-002", e)
        }
    }

    private fun resumeRecording() {
        try {
            if (!isRecording && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                startTime = System.currentTimeMillis()
                isRecording = true
                VoiceLogger.d("RecordingService", "Recording resumed", "VM-SVC-004")
            }
        } catch (e: Exception) {
            VoiceLogger.e("RecordingService", "Resume failed", "VM-SVC-ERR-003", e)
        }
    }

    private fun stopRecording(): Long {
        var duration = 0L
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            mediaRecorder = null
            duration = if (isRecording) {
                pausedDuration + (System.currentTimeMillis() - startTime)
            } else {
                pausedDuration
            }
            isRecording = false
            pausedDuration = 0L
            VoiceLogger.d("RecordingService", "Recording stopped", "VM-SVC-005", mapOf(
                "duration_ms" to duration.toString()))

            outputFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    // Simulate transcription completion
                    val confidence = 0.85f + (0..15).random() / 100f
                    onTranscriptionReady?.invoke("[Transcription would appear here]", confidence)
                }
            }
        } catch (e: Exception) {
            VoiceLogger.e("RecordingService", "Stop failed", "VM-SVC-ERR-004", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return duration
    }

    private fun cancelRecording() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            mediaRecorder = null
            outputFile?.delete()
            isRecording = false
            VoiceLogger.d("RecordingService", "Recording cancelled", "VM-SVC-006")
        } catch (e: Exception) {
            VoiceLogger.e("RecordingService", "Cancel failed", "VM-SVC-ERR-005", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Voice Memo Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recording in progress"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording...")
            .setContentText("Voice memo in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    override fun onDestroy() {
        VoiceLogger.d("RecordingService", "Service destroyed", "VM-SVC-007")
        super.onDestroy()
    }
}
