package com.jnetaol.voicememo.engine

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jnetaol.voicememo.logger.VoiceLogger
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTranscriber(private val context: Context) {

    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
        fun onInstallSuggestion(message: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Embedded API key used by Google's own browser speech input; lets us POST audio
    // straight to the same free recognition backend that SpeechRecognizer uses.
    private val GOOGLE_SPEECH_KEY = "AIzaSyBOti4mM-6x9WDnZIjIyrEU_OpHqDPBBLw"
    private val GOOGLE_SPEECH_URL = "https://www.google.com/speech-api/v2/recognize"

    fun transcribe(filePath: String, language: String? = null, callback: Callback) {
        val lang = language?.ifBlank { null } ?: "en"

        if (!isOnline()) {
            // No internet: only the offline attempt can work.
            mainHandler.post {
                attemptPlaybackListen(filePath, lang, callback, offline = true) { note ->
                    callback.onError("No internet connection. Offline transcription: $note")
                }
            }
            return
        }

        // Tier 1: send the decoded audio directly to Google (runs off the main thread).
        Thread {
            val pcm = try {
                decodeToPcmBytes(filePath)
            } catch (e: Exception) {
                VoiceLogger.e("Transcriber", "Decode failed", "VM-TRANS-ERR-001", e)
                "".toByteArray()
            }
            val decodeNote = "decode failed"
            val pcmToSend = if (pcm.isEmpty()) ByteArray(0) else pcm
            googleCloudDirect(pcmToSend, lang,
                onSuccess = { text -> mainHandler.post { callback.onResult(text) } },
                onFailure = { note1 ->
                    val tier1Note = if (pcm.isEmpty()) decodeNote else "Google direct: $note1"
                    mainHandler.post {
                        attemptOfflineThenPlayback(filePath, lang, callback, tier1Note)
                    }
                })
        }.start()
    }

    private fun isOnline(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.activeNetwork != null
    }

    // Tier 2 (offline, local) then Tier 3 (mic playback, online).
    private fun attemptOfflineThenPlayback(filePath: String, language: String, callback: Callback, tier1Note: String) {
        attemptPlaybackListen(filePath, language, callback, offline = true) { offlineNote ->
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                callback.onInstallSuggestion(
                    "A speech recognition service is not installed on this device. Install \"Google Speech Services\" (or run the 'Hey Google'/voice typing setup) so it can transcribe locally on the phone."
                )
            }
            mainHandler.postDelayed({
                attemptPlaybackListen(filePath, language, callback, offline = false) { finalNote ->
                    callback.onError("$tier1Note; offline: $offlineNote; playback: $finalNote")
                }
            }, 800)
        }
    }

    // -------- Google direct upload (Tier 1) --------

    private fun googleCloudDirect(pcm: ByteArray, language: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        if (pcm.isEmpty()) {
            onFailure("could not decode audio")
            return
        }
        try {
            val url = "$GOOGLE_SPEECH_URL?output=json&lang=${URLEncoder.encode(language, "UTF-8")}&key=$GOOGLE_SPEECH_KEY&client=chromium&maxresults=1&pfilter=0"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/l16; rate=16000")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            conn.outputStream.use { it.write(pcm) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
            }
            conn.disconnect()
            if (code != 200) {
                VoiceLogger.e("Transcriber", "Google direct HTTP error", "VM-TRANS-ERR-007", null, mapOf("code" to code.toString()))
                onFailure("HTTP $code")
                return
            }
            val text = extractTranscript(body)
            if (text.isNotBlank()) {
                VoiceLogger.d("Transcriber", "Google direct OK", "VM-TRANS-007")
                onSuccess(text)
            } else {
                VoiceLogger.w("Transcriber", "Google direct returned no text", "VM-TRANS-WARN-006")
                onFailure("returned no text")
            }
        } catch (e: Exception) {
            VoiceLogger.e("Transcriber", "Google direct failed", "VM-TRANS-ERR-008", e)
            onFailure("network error ${e.message}")
        }
    }

    private fun extractTranscript(response: String): String {
        val sb = StringBuilder()
        response.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            try {
                val obj = JSONObject(line)
                val results = obj.optJSONArray("result") ?: return@forEach
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val alts = r.optJSONArray("alternative") ?: continue
                    if (alts.length() == 0) continue
                    val first = alts.optJSONObject(0) ?: continue
                    val t = first.optString("transcript").trim()
                    if (t.isNotBlank()) sb.append(t).append(' ')
                }
            } catch (_: Exception) {}
        }
        return sb.toString().trim()
    }

    // -------- Mic + speaker playback attempt (offline or online) --------

    private fun attemptPlaybackListen(
        filePath: String,
        language: String,
        callback: Callback,
        offline: Boolean,
        onFail: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFail("speech recognition service not available")
            return
        }

        if (offline) {
            callback.onInstallSuggestion(
                "If offline voice data for '${language}' is not downloaded, transcription may fail. You can download it in Settings \u2192 System \u2192 Languages & input \u2192 Speech, or via the Google app voice settings."
            )
        }

        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (_: Exception) {}

        val player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(filePath)
                prepare()
                setVolume(1f, 1f)
            }
        } catch (e: Exception) {
            VoiceLogger.e("Transcriber", "MediaPlayer prepare failed", "VM-TRANS-ERR-003", e)
            onFail("could not play audio (${e.message})")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        }

        var settled = false
        var playerStarted = false
        var failCount = 0

        fun releasePlayback() {
            if (!settled) settled = true
            try { player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
            try { recognizer.cancel() } catch (_: Exception) {}
            try { recognizer.destroy() } catch (_: Exception) {}
        }

        fun failNow(note: String) {
            if (settled) return
            settled = true
            releasePlayback()
            onFail(note)
        }

        val listener = object : RecognitionListener {
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(bytes: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onReadyForSpeech(params: Bundle?) {
                if (playerStarted || settled) return
                playerStarted = true
                VoiceLogger.d("Transcriber", "Playing audio for recognition (offline=$offline)", "VM-TRANS-004")
                try {
                    player.setOnCompletionListener { mp ->
                        try { recognizer.stopListening() } catch (_: Exception) {}
                    }
                    player.start()
                } catch (e: Exception) {
                    failNow("could not start playback")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                if (settled) return
                settled = true
                val text = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
                releasePlayback()
                if (text.isNotBlank()) {
                    VoiceLogger.d("Transcriber", "Offline=$offline transcription ready", "VM-TRANS-005")
                    callback.onResult(text)
                } else {
                    onFail("no speech heard while playing${if (offline) " (offline)" else ""}")
                }
            }

            override fun onError(error: Int) {
                if (settled) return
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && failCount < 2) {
                    failCount++
                    settled = true
                    releasePlayback()
                    mainHandler.postDelayed({
                        attemptPlaybackListen(filePath, language, callback, offline, onFail)
                    }, 900)
                    return
                }
                if (offline && (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)) {
                    callback.onInstallSuggestion(
                        "Your phone has no offline voice data installed for backup/local transcription. Download the offline language pack (Settings \u2192 Languages & input \u2192 Speech, or Google app voice settings), or an offline speech app from the Play Store."
                    )
                }
                failNow(errorMessage(error, offline))
            }
        }

        recognizer.setRecognitionListener(listener)
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            if (!settled) {
                VoiceLogger.e("Transcriber", "Failed to start recognition", "VM-TRANS-ERR-006", e)
                failNow("could not start recognition (${e.message})")
            }
        }
    }

    // -------- PCM decode helpers --------

    private fun decodeToPcmBytes(filePath: String): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)
        var trackFormat: MediaFormat? = null
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackFormat = format
                trackIndex = i
                break
            }
        }
        if (trackFormat == null) {
            extractor.release()
            throw IllegalStateException("No audio track found")
        }
        extractor.selectTrack(trackIndex)

        val decoder = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(trackFormat, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        val rawOut = ByteArrayOutputStream()
        val timeoutUs = 20_000L
        var inputDone = false
        var outputDone = false
        var outputFormat: MediaFormat? = null
        var tryAgainCount = 0

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            decoder.getOutputBuffer(outIndex)?.let { buf ->
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buf.get(bytes)
                                rawOut.write(bytes)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++tryAgainCount > 300) {
                            VoiceLogger.w("Transcriber", "Decode draining timed out", "VM-TRANS-WARN")
                            break
                        }
                    }
                }
            }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        if (rawOut.size() == 0) throw IllegalStateException("Decoded audio is empty")

        val fmt = outputFormat ?: trackFormat
        val sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
        val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

        val pcm = toPcm16Mono(rawOut.toByteArray(), sampleRate, channels, encoding, 16000)
        VoiceLogger.d("Transcriber", "PCM ready", "VM-TRANS-006", mapOf(
            "srcRate" to sampleRate.toString(),
            "channels" to channels.toString(),
            "pcmBytes" to pcm.size.toString(),
            "rms" to pcmRms(pcm).toString()))
        return pcm
    }

    private fun pcmRms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val count = pcm.size / 2
        val step = maxOf(1, count / 8000)
        var sum = 0L
        var sampled = 0
        var i = 0
        while (i < count) {
            val s = bb.getShort(i * 2).toInt().toLong()
            sum += s * s
            sampled++
            i += step
        }
        return if (sampled == 0) 0.0 else Math.sqrt(sum.toDouble() / sampled)
    }

    private fun toPcm16Mono(raw: ByteArray, sampleRate: Int, channels: Int, encoding: Int, targetRate: Int): ByteArray {
        val frameCount = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> raw.size / (4 * channels)
            else -> raw.size / (2 * channels)
        }
        if (frameCount <= 0) return ByteArray(0)

        val mono = ShortArray(frameCount)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                for (ch in 0 until channels) {
                    for (i in 0 until frameCount) {
                        val v = (bb.getFloat((i * channels + ch) * 4) * 32767f).toInt().coerceIn(-32768, 32767)
                        mono[i] = if (ch == 0) v.toShort() else ((mono[i].toInt() + v) / 2).toShort()
                    }
                }
            }
            else -> {
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until frameCount) {
                    var acc = 0
                    for (ch in 0 until channels) acc += bb.getShort((i * channels + ch) * 2).toInt()
                    mono[i] = (acc / channels).toShort()
                }
            }
        }

        val outLen = ((mono.size.toLong() * targetRate) / sampleRate).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i.toDouble() * sampleRate / targetRate
            val idx = minOf(srcPos.toInt(), mono.size - 1)
            val frac = srcPos - idx
            val next = minOf(idx + 1, mono.size - 1)
            out[i] = (mono[idx] * (1.0 - frac) + mono[next] * frac).toInt().toShort()
        }

        val bb = ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in out) bb.putShort(s)
        return bb.array()
    }

    private fun errorMessage(error: Int, offline: Boolean): String {
        val tag = if (offline) " (offline)" else " (playback)"
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error$tag"
            SpeechRecognizer.ERROR_CLIENT -> "Client error$tag"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for transcription$tag"
            SpeechRecognizer.ERROR_NETWORK -> "Network error while transcribing$tag"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized while playing the clip$tag"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy, try again$tag"
            SpeechRecognizer.ERROR_SERVER -> "Recognition server error$tag"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognition server disconnected$tag"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Recognition timed out$tag"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported$tag"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Offline language data not available$tag"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Could not check recognition support$tag"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests, try again later$tag"
            else -> "Transcription failed (code $error)$tag"
        }
    }
}