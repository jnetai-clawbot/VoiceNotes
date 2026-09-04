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
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jnetaol.voicememo.logger.VoiceLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTranscriber(private val context: Context) {

    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun transcribe(filePath: String, language: String? = null, callback: Callback) {
        val lang = language?.ifBlank { null } ?: "en"
        if (!isOnline()) {
            mainHandler.post { callback.onError("No internet connection. Connect to the internet to use Google transcription.") }
            return
        }
        // Speaker playback is the most reliable path on devices whose recognizer
        // ignores EXTRA_AUDIO_SOURCE, so try it first with a fresh recognizer.
        mainHandler.post { attemptSpeakerPlayback(filePath, lang, callback, 1) }
    }

    private fun isOnline(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.activeNetwork != null
    }

    // -------- Attempt 1: play the clip out loud while the recognizer listens on the mic --------

    private fun attemptSpeakerPlayback(filePath: String, language: String, callback: Callback, attempt: Int) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onError("Speech recognition is not available on this device")
            return
        }

        // Make sure we play loudly through the speaker so the mic can hear it.
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
            callback.onError("Could not play audio for transcription: ${e.message}")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        var settled = false
        var playerStarted = false
        var fallbackInvoked = false

        fun releasePlayback() {
            if (!settled) settled = true
            try { player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
            try { recognizer.cancel() } catch (_: Exception) {}
            try { recognizer.destroy() } catch (_: Exception) {}
        }

        fun fallbackToFile(why: String) {
            if (fallbackInvoked) return
            fallbackInvoked = true
            releasePlayback()
            attemptFileFromBackground(filePath, language, why, callback)
        }

        val listener = object : RecognitionListener {
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(bytes: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onReadyForSpeech(params: Bundle?) {
                if (playerStarted || settled) return
                playerStarted = true
                VoiceLogger.d("Transcriber", "Playing audio for recognition", "VM-TRANS-004")
                try {
                    player.setOnCompletionListener { mp ->
                        try { recognizer.stopListening() } catch (_: Exception) {}
                    }
                    player.start()
                } catch (e: Exception) {
                    if (!settled) {
                        fallbackToFile("could not start playback (${e.message})")
                    }
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
                    VoiceLogger.d("Transcriber", "Speaker transcription ready", "VM-TRANS-005")
                    callback.onResult(text)
                } else {
                    fallbackToFile("speech playback heard nothing")
                }
            }

            override fun onError(error: Int) {
                if (settled) return
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && attempt < 2) {
                    VoiceLogger.w("Transcriber", "Recognizer busy on playback", "VM-TRANS-WARN-004")
                    settled = true
                    releasePlayback()
                    mainHandler.postDelayed({
                        attemptSpeakerPlayback(filePath, language, callback, attempt + 1)
                    }, 900)
                    return
                }
                VoiceLogger.e("Transcriber", "Speaker recognition error", "VM-TRANS-ERR-004", null,
                    mapOf("code" to error.toString()))
                fallbackToFile("speech playback error code $error")
            }
        }

        recognizer.setRecognitionListener(listener)
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            if (!settled) {
                VoiceLogger.e("Transcriber", "Failed to start playback recognition", "VM-TRANS-ERR-006", e)
                fallbackToFile("could not start playback recognition (${e.message})")
            }
        }
    }

    // -------- Attempt 2: feed the decoded PCM to the recognizer via EXTRA_AUDIO_SOURCE --------

    private fun attemptFileFromBackground(filePath: String, language: String, speakerNote: String, callback: Callback) {
        Thread {
            try {
                val pcmFile = decodeToPcm(filePath)
                val pfd = try {
                    ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: Exception) {
                    pcmFile.delete()
                    mainHandler.post { callback.onError("$speakerNote; direct file input failed to open audio") }
                    return@Thread
                }
                mainHandler.post { attemptFileRecognition(filePath, language, pfd, pcmFile, speakerNote, callback) }
            } catch (e: Exception) {
                VoiceLogger.e("Transcriber", "Decode failed", "VM-TRANS-ERR-001", e)
                mainHandler.post { callback.onError("$speakerNote; direct file input could not decode audio") }
            }
        }.start()
    }

    private fun attemptFileRecognition(
        filePath: String,
        language: String,
        pfd: ParcelFileDescriptor,
        pcmFile: File,
        speakerNote: String,
        callback: Callback
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            releaseSession(null, pfd, pcmFile)
            callback.onError("$speakerNote; speech recognition is not available on this device")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        var settled = false
        val listener = object : RecognitionListener {
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(bytes: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                if (settled) return
                settled = true
                val text = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
                releaseSession(recognizer, pfd, pcmFile)
                if (text.isNotBlank()) {
                    VoiceLogger.d("Transcriber", "File transcription ready", "VM-TRANS-002")
                    callback.onResult(text)
                } else {
                    callback.onError("$speakerNote; direct file input heard nothing")
                }
            }

            override fun onError(error: Int) {
                if (settled) return
                settled = true
                VoiceLogger.e("Transcriber", "File recognition error", "VM-TRANS-ERR-002", null,
                    mapOf("code" to error.toString()))
                releaseSession(recognizer, pfd, pcmFile)
                callback.onError("$speakerNote; direct file input error code $error")
            }
        }

        recognizer.setRecognitionListener(listener)
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            if (!settled) {
                settled = true
                releaseSession(recognizer, pfd, pcmFile)
                VoiceLogger.e("Transcriber", "Failed to start file recognition", "VM-TRANS-ERR-005", e)
                callback.onError("$speakerNote; direct file input could not start")
            }
        }
    }

    private fun releaseSession(recognizer: SpeechRecognizer?, pfd: ParcelFileDescriptor?, pcmFile: File?) {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        pcmFile?.delete()
    }

    private fun decodeToPcm(filePath: String): File {
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
        if (pcmRms(pcm) < 200) {
            VoiceLogger.w("Transcriber", "Decoded PCM looks too quiet", "VM-TRANS-WARN-005")
        }
        val pcmFile = File(context.cacheDir, "vm_pcm_${System.currentTimeMillis()}.raw")
        pcmFile.writeBytes(pcm)
        return pcmFile
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
}