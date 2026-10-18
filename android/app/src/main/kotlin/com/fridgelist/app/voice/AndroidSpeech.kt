package com.fridgelist.app.voice

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import javax.inject.Inject

/** `SpeechRecognizer`, on-device (API 31+) or the phone's default online service. */
class AndroidSpeechEngine(
    private val context: Context,
    override val onDevice: Boolean,
) : SpeechEngine {

    private var recognizer: SpeechRecognizer? = null

    override fun listen(languageTag: String): Flow<SpeechEvent> = callbackFlow {
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = best(results)
                trySend(if (text.isNullOrBlank()) SpeechEvent.Failed(SpeechError.NO_MATCH) else SpeechEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                // Error code only: the recognizer never hands us user text here (CLAUDE.md rule 4).
                Log.i(TAG, "recognizer error $error (onDevice=$onDevice)")
                trySend(SpeechEvent.Failed(mapError(error)))
                close()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
        awaitClose {
            r.cancel()
            r.destroy()
            recognizer = null
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        /** Language errors (12, 13) exist from API 31; matching on the numbers keeps minSdk 26 lint-clean. */
        fun mapError(error: Int): SpeechError = when (error) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.NETWORK
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_MATCH
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.LANGUAGE_UNSUPPORTED
            SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.UNAVAILABLE
            else -> SpeechError.OTHER
        }

        private const val TAG = "Speech"
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

class AndroidSpeechEngines @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SpeechEngines {
    override fun available(): List<SpeechEngine> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            add(AndroidSpeechEngine(context, onDevice = true))
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) add(AndroidSpeechEngine(context, onDevice = false))
    }
}

/** `MediaRecorder` to a cache file; OGG/Opus on API 29+, else AAC in MP4 (CONTRACT §4 mime types). */
class MediaAudioRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private val format = AudioFormatChoice.forSdk(Build.VERSION.SDK_INT)

    override fun start(maxSeconds: Int): Boolean = try {
        val out = File(context.cacheDir, "voice.${format.extension}").also { it.delete() }
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (format == AudioFormatChoice.OGG_OPUS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        }
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(24_000)
        r.setAudioChannels(1)
        r.setMaxDuration(maxSeconds * 1_000)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
        true
    } catch (e: Exception) {
        Log.w(TAG, "recorder start failed: ${e.javaClass.simpleName}")
        release()
        false
    }

    override fun stop(): RecordedAudio? {
        val stopped = runCatching { recorder?.stop() }.isSuccess
        release()
        val bytes = file?.takeIf { stopped && it.length() > 0 }?.readBytes()
        file?.delete() // never keep voice on disk
        file = null
        return bytes?.let { RecordedAudio(it, format.mimeType) }
    }

    override fun cancel() {
        runCatching { recorder?.stop() }
        release()
        file?.delete()
        file = null
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }

    private companion object {
        const val TAG = "VoiceRecorder"
    }
}
