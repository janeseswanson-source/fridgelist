package com.fridgelist.app.voice

import androidx.annotation.StringRes
import com.fridgelist.app.R
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.voice.TranscribeRepository
import com.fridgelist.core.model.Limits
import com.fridgelist.core.model.TranscribeAudioRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64

sealed interface VoiceState {
    data object Idle : VoiceState

    /** A recognizer is listening; [partial] is the live transcript. */
    data class Listening(val partial: String = "", val onDevice: Boolean) : VoiceState

    /** Cloud fallback: recording up to [Limits.AUDIO_MAX_SECONDS]. */
    data object Recording : VoiceState

    data object Transcribing : VoiceState

    data class Failed(@param:StringRes val message: Int) : VoiceState
}

/**
 * Voice add (prompt A4, ARCHITECTURE §8.6): on-device recognizer → online recognizer →
 * record ≤ 15 s and `transcribeAudio`. Each step is tried only when the previous one can't do
 * the language or isn't available. The transcript goes to [onTranscript]; adding never waits on
 * voice (typing always works).
 */
class VoiceInput(
    private val engines: SpeechEngines,
    private val recorder: AudioRecorder,
    private val transcriber: TranscribeRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var job: Job? = null
    private var activeEngine: SpeechEngine? = null
    private var hints: List<String> = emptyList()
    private var deliver: (String) -> Unit = {}

    val isActive: Boolean get() = _state.value.let { it is VoiceState.Listening || it is VoiceState.Recording || it is VoiceState.Transcribing }

    fun start(languageTag: String, localeHints: List<String>, onTranscript: (String) -> Unit) {
        cancel()
        hints = (listOf(languageTag) + localeHints).distinct()
        deliver = onTranscript
        job = scope.launch { listenWith(engines.available(), languageTag) }
    }

    private suspend fun listenWith(candidates: List<SpeechEngine>, languageTag: String) {
        val engine = candidates.firstOrNull() ?: return record()
        activeEngine = engine
        _state.value = VoiceState.Listening(onDevice = engine.onDevice)
        var finished = false
        engine.listen(languageTag).collect { event ->
            when (event) {
                is SpeechEvent.Partial -> _state.value = VoiceState.Listening(event.text, engine.onDevice)
                is SpeechEvent.Final -> {
                    finished = true
                    finish(event.text)
                }
                is SpeechEvent.Failed -> {
                    finished = true
                    when (event.error) {
                        SpeechError.LANGUAGE_UNSUPPORTED, SpeechError.UNAVAILABLE -> {
                            activeEngine = null
                            listenWith(candidates.drop(1), languageTag)
                        }
                        SpeechError.NO_MATCH -> fail(R.string.voice_no_match)
                        SpeechError.NETWORK -> fail(R.string.voice_offline)
                        SpeechError.NO_PERMISSION -> fail(R.string.voice_permission_denied)
                        SpeechError.OTHER -> fail(R.string.error_generic)
                    }
                }
            }
        }
        if (!finished && _state.value is VoiceState.Listening) _state.value = VoiceState.Idle
    }

    private suspend fun record() {
        activeEngine = null
        if (!recorder.start(Limits.AUDIO_MAX_SECONDS)) return fail(R.string.voice_unavailable)
        _state.value = VoiceState.Recording
        delay(Limits.AUDIO_MAX_SECONDS * 1_000L)
        if (_state.value == VoiceState.Recording) transcribeRecording()
    }

    /** User tapped Stop: ask the recognizer for its result, or send the recording. */
    fun stop() {
        when (_state.value) {
            is VoiceState.Listening -> activeEngine?.stop()
            VoiceState.Recording -> {
                job?.cancel()
                job = scope.launch { transcribeRecording() }
            }
            else -> Unit
        }
    }

    private suspend fun transcribeRecording() {
        val audio = recorder.stop() ?: return fail(R.string.voice_no_match)
        val base64 = Base64.getEncoder().encodeToString(audio.bytes)
        if (base64.length > Limits.AUDIO_BASE64_MAX_BYTES) return fail(R.string.voice_too_long)
        _state.value = VoiceState.Transcribing
        try {
            val response = transcriber.transcribe(TranscribeAudioRequest(base64, audio.mimeType, hints))
            if (response.text.isBlank()) fail(R.string.voice_no_match) else finish(response.text)
        } catch (e: ApiException) {
            fail(
                when (e.error) {
                    ApiError.NETWORK, ApiError.UNAVAILABLE -> R.string.voice_offline
                    ApiError.RESOURCE_EXHAUSTED -> R.string.error_rate_limited
                    else -> R.string.error_generic
                },
            )
        }
    }

    private fun finish(text: String) {
        _state.value = VoiceState.Idle
        activeEngine = null
        if (text.isNotBlank()) deliver(text.trim())
    }

    private fun fail(@StringRes message: Int) {
        activeEngine = null
        _state.value = VoiceState.Failed(message)
    }

    /** Close the sheet / leave the screen: stop everything, keep nothing. */
    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value == VoiceState.Recording) recorder.cancel()
        activeEngine = null
        _state.value = VoiceState.Idle
    }

    fun dismissError() {
        if (_state.value is VoiceState.Failed) _state.value = VoiceState.Idle
    }
}
