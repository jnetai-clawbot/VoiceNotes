package com.jnetaol.voicememo.engine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
        val pcmFile = try {
            decodeToPcm(filePath)
        } catch (e: Exception) {
            VoiceLogger.e("Transcriber", "Decode failed", "VM-TRANS-ERR-001", e)
            mainHandler.post { callback.onError("Could not decode audio: ${e.message}") }
            return
        }
        mainHandler.post { startRecognition(pcmFile, language ?: "en", callback) }
    }

    private data class DecodedAudio(val bytes: ByteArray, val sampleRate: Int, val channels: Int, val encoding: Int)

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
            decoder.stop()
            decoder.release()
            extractor.release()
        }

        if (rawOut.size() == 0) throw IllegalStateException("Decoded audio is empty")

        val fmt = outputFormat ?: trackFormat
        val sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
        val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else MediaFormat.ENCODING_PCM_16BIT

        val pcm = toPcm16Mono(rawOut.toByteArray(), sampleRate, channels, encoding, 16000)
        val pcmFile = File(context.cacheDir, "vm_pcm_${System.currentTimeMillis()}.raw")
        pcmFile.writeBytes(pcm)
        VoiceLogger.d("Transcriber", "PCM ready", "VM-TRANS-001", mapOf(
            "srcRate" to sampleRate.toString(), "channels" to channels.toString(), "pcmBytes" to pcm.size.toString()))
        return pcmFile
    }

    private fun toPcm16Mono(raw: ByteArray, sampleRate: Int, channels: Int, encoding: Int, targetRate: Int): ByteArray {
        val frameCount = when (encoding) {
            MediaFormat.ENCODING_PCM_FLOAT -> raw.size / (4 * channels)
            else -> raw.size / (2 * channels)
        }
        if (frameCount <= 0) return ByteArray(0)

        val mono = ShortArray(frameCount)
        when (encoding) {
            MediaFormat.ENCODING_PCM_FLOAT -> {
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

    private fun finish(pfd: ParcelFileDescriptor?, recognizer: SpeechRecognizer?, pcmFile: File?) {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        pcmFile?.delete()
    }

    private fun startRecognition(pcmFile: File, language: String, callback: Callback) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            finish(null, null, pcmFile)
            callback.onError("Speech recognition is not available on this device")
            return
        }

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connectivity.activeNetwork == null) {
            finish(null, null, pcmFile)
            callback.onError("No internet connection. Connect to the internet to use Google transcription.")
            return
        }

        val pfd = try {
            ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            finish(null, null, pcmFile)
            callback.onError("Could not open audio: ${e.message}")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.ifBlank { "en" })
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(bytes: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
                finish(pfd, recognizer, pcmFile)
                if (text.isNotBlank()) {
                    VoiceLogger.d("Transcriber", "Transcription ready", "VM-TRANS-002")
                    callback.onResult(text)
                } else {
                    callback.onError("No speech recognized in the recording")
                }
            }

            override fun onError(error: Int) {
                finish(pfd, recognizer, pcmFile)
                VoiceLogger.e("Transcriber", "Recognition error", "VM-TRANS-ERR-002", null, mapOf("code" to error.toString()))
                callback.onError(errorMessage(error))
            }
        })

        recognizer.startListening(intent)
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient audio permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error while transcribing"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized in the recording"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy, try again"
        SpeechRecognizer.ERROR_SERVER -> "Google recognition server error"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Google recognition server disconnected"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Recording was too long for one transcription pass"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language model unavailable"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Could not check recognition support"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests, try again later"
        else -> "Transcription failed (code $error)"
    }
}