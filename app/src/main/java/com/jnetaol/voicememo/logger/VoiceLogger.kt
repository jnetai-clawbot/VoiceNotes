package com.jnetaol.voicememo.logger

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LogLevel(val emoji: String, val priority: Int) {
    DEBUG("\uD83D\uDD0D", 0), INFO("\u2139\uFE0F", 1), WARN("\u26A0\uFE0F", 2),
    ERROR("\u274C", 3), FATAL("\uD83D\uDC80", 4)
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val errorCode: String = "VM-000",
    val throwable: Throwable? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    val formattedTime: String get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
    val stackTrace: String get() {
        if (throwable == null) return ""
        val sw = StringWriter(); throwable.printStackTrace(PrintWriter(sw)); return sw.toString()
    }
    fun toLine(): String {
        val meta = if (metadata.isNotEmpty()) " | meta=$metadata" else ""
        val err = if (throwable != null) "\n$stackTrace" else ""
        return "$formattedTime ${level.emoji} [$errorCode] ${level.name.padEnd(5)} | $tag | $message$meta$err"
    }
}

object VoiceLogger {
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val _logFlow = MutableSharedFlow<LogEntry>(replay = 200, extraBufferCapacity = 200)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logFile: File? = null
    private var writer: FileWriter? = null
    private val writeChannel = Channel<LogEntry>(Channel.UNLIMITED)
    private var initialized = false

    fun init(context: Context): VoiceLogger {
        if (initialized) return this
        initialized = true
        try {
            val logDir = File(context.filesDir, "vm_logs")
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, "vm_debug_${System.currentTimeMillis()}.log")
            writer = FileWriter(logFile, true)
            scope.launch { for (entry in writeChannel) writeToFile(entry) }
            d("VoiceLogger", "Logger initialized", "VM-001", mapOf(
                "device" to Build.MODEL, "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT.toString()))
        } catch (e: Exception) { android.util.Log.e("VoiceLogger", "Init failed", e) }
        return this
    }

    fun d(tag: String, message: String, errorCode: String = "VM-000", metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.DEBUG, tag, message, errorCode, null, metadata)
    fun i(tag: String, message: String, errorCode: String = "VM-000", metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.INFO, tag, message, errorCode, null, metadata)
    fun w(tag: String, message: String, errorCode: String = "VM-000", metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.WARN, tag, message, errorCode, null, metadata)
    fun e(tag: String, message: String, errorCode: String = "VM-ERR", throwable: Throwable? = null, metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.ERROR, tag, message, errorCode, throwable, metadata)
    fun fatal(tag: String, message: String, errorCode: String = "VM-FATAL", throwable: Throwable? = null, metadata: Map<String, String> = emptyMap()) =
        log(LogLevel.FATAL, tag, message, errorCode, throwable, metadata)

    private fun log(level: LogLevel, tag: String, message: String, errorCode: String, throwable: Throwable?, metadata: Map<String, String>) {
        val entry = LogEntry(level = level, tag = tag, message = message, errorCode = errorCode, throwable = throwable, metadata = metadata)
        logQueue.add(entry)
        scope.launch { _logFlow.emit(entry); writeChannel.send(entry) }
    }

    private fun writeToFile(entry: LogEntry) {
        try { writer?.let { it.append(entry.toLine()); it.append("\n"); it.flush() } } catch (_: Exception) {}
    }

    fun exportLogsZip(): File? {
        return try {
            val exportFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "vm_debug_${System.currentTimeMillis()}.zip")
            writer?.flush(); writer?.close(); writer = null
            ZipOutputStream(exportFile.outputStream()).use { zos ->
                logFile?.let { if (it.exists()) { zos.putNextEntry(ZipEntry(it.name)); it.inputStream().use { i -> i.copyTo(zos) }; zos.closeEntry() } }
            }
            writer = logFile?.let { FileWriter(it, true) }; exportFile
        } catch (e: Exception) { e("VoiceLogger", "Export failed", "VM-ERR-001", e); writer = logFile?.let { FileWriter(it, true) }; null }
    }

    fun shutdown() { scope.cancel(); try { writer?.flush(); writer?.close() } catch (_: Exception) {} }
}
