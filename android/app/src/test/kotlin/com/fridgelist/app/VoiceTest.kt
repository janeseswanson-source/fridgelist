package com.fridgelist.app

import android.speech.SpeechRecognizer
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.voice.AndroidSpeechEngine
import com.fridgelist.app.voice.AudioFormatChoice
import com.fridgelist.app.voice.AudioRecorder
import com.fridgelist.app.voice.RecordedAudio
import com.fridgelist.app.voice.SpeechEngine
import com.fridgelist.app.voice.SpeechError
import com.fridgelist.app.voice.SpeechEvent
import com.fridgelist.app.voice.VoiceInput
import com.fridgelist.app.voice.VoiceLocales
import com.fridgelist.app.voice.VoiceState
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.voice.TranscribeRepository
import com.fridgelist.core.model.TranscribeAudioRequest
import com.fridgelist.core.model.TranscribeAudioResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A recognizer driven by the test: send events into [events]. */
class FakeEngine(override val onDevice: Boolean) : SpeechEngine {
    val events = Channel<SpeechEvent>(Channel.UNLIMITED)
    val languages = mutableListOf<String>()
    var stopped = false
    override fun listen(languageTag: String): Flow<SpeechEvent> {
        languages += languageTag
        return events.receiveAsFlow()
    }
    override fun stop() {
        stopped = true
    }
    fun say(vararg e: SpeechEvent) {
        e.forEach { events.trySend(it) }
        if (e.lastOrNull() is SpeechEvent.Final || e.lastOrNull() is SpeechEvent.Failed) events.close()
    }
}

class FakeRecorder(private val canStart: Boolean = true, private val audio: RecordedAudio? = RecordedAudio(ByteArray(100), "audio/ogg")) : AudioRecorder {
    var started = false
    var cancelled = false
    override fun start(maxSeconds: Int): Boolean = canStart.also { started = it }
    override fun stop(): RecordedAudio? = audio
    override fun cancel() {
        cancelled = true
    }
}

class FakeTranscriber(private val result: () -> TranscribeAudioResponse) : TranscribeRepository {
    val requests = mutableListOf<TranscribeAudioRequest>()
    override suspend fun transcribe(request: TranscribeAudioRequest): TranscribeAudioResponse {
        requests += request
        return result()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val transcripts = mutableListOf<String>()

    private fun TestScope.voice(engines: List<SpeechEngine>, recorder: AudioRecorder = FakeRecorder(), transcriber: TranscribeRepository = FakeTranscriber { TranscribeAudioResponse("2 ekmek ve süt") }) =
        VoiceInput({ engines }, recorder, transcriber, backgroundScope).also {
            it.start("tr-TR", listOf("tr")) { text -> transcripts += text }
            runCurrent()
        }

    @Test
    fun partialsThenFinalDeliverTheTranscript() = runTest(main.dispatcher) {
        val engine = FakeEngine(onDevice = true)
        val voice = voice(listOf(engine))
        engine.say(SpeechEvent.Partial("2 ekmek"))
        runCurrent()
        assertEquals(VoiceState.Listening("2 ekmek", onDevice = true), voice.state.value)
        engine.say(SpeechEvent.Final("2 ekmek ve süt"))
        runCurrent()
        assertEquals(listOf("2 ekmek ve süt"), transcripts)
        assertEquals(VoiceState.Idle, voice.state.value)
        assertEquals(listOf("tr-TR"), engine.languages)
    }

    @Test
    fun unsupportedLanguageOnDeviceFallsBackToOnlineRecognizer() = runTest(main.dispatcher) {
        val onDevice = FakeEngine(onDevice = true)
        val online = FakeEngine(onDevice = false)
        val voice = voice(listOf(onDevice, online))
        onDevice.say(SpeechEvent.Failed(SpeechError.LANGUAGE_UNSUPPORTED))
        runCurrent()
        assertEquals(VoiceState.Listening("", onDevice = false), voice.state.value)
        online.say(SpeechEvent.Final("süt"))
        runCurrent()
        assertEquals(listOf("süt"), transcripts)
    }

    @Test
    fun noRecognizerRecordsAndTranscribesInTheCloud() = runTest(main.dispatcher) {
        val transcriber = FakeTranscriber { TranscribeAudioResponse("labne") }
        val recorder = FakeRecorder()
        val voice = voice(emptyList(), recorder, transcriber)
        assertTrue(recorder.started)
        assertEquals(VoiceState.Recording, voice.state.value)
        voice.stop()
        runCurrent()
        assertEquals(listOf("labne"), transcripts)
        val request = transcriber.requests.single()
        assertEquals("audio/ogg", request.mimeType)
        assertEquals(listOf("tr-TR", "tr"), request.localeHints)
    }

    @Test
    fun recordingStopsByItselfAfter15Seconds() = runTest(main.dispatcher) {
        val voice = voice(emptyList())
        advanceTimeBy(14_000)
        assertEquals(VoiceState.Recording, voice.state.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("2 ekmek ve süt"), transcripts)
    }

    @Test
    fun cloudOfflineSaysSoAndTypingStillWorks() = runTest(main.dispatcher) {
        val voice = voice(emptyList(), transcriber = FakeTranscriber { throw ApiException(ApiError.NETWORK) })
        voice.stop()
        runCurrent()
        assertEquals(VoiceState.Failed(R.string.voice_offline), voice.state.value)
        assertTrue(transcripts.isEmpty())
    }

    @Test
    fun unexpectedTranscribeFailureIsAMessageNotACrash() = runTest(main.dispatcher) {
        val voice = voice(emptyList(), transcriber = FakeTranscriber { throw IllegalStateException("boom") })
        voice.stop()
        runCurrent()
        assertEquals(VoiceState.Failed(R.string.error_generic), voice.state.value)
    }

    @Test
    fun micPermissionDeniedSaysSo() = runTest(main.dispatcher) {
        val voice = voice(emptyList())
        voice.permissionDenied()
        assertEquals(VoiceState.Failed(R.string.voice_permission_denied), voice.state.value)
    }

    @Test
    fun recorderUnavailable() = runTest(main.dispatcher) {
        val voice = voice(emptyList(), FakeRecorder(canStart = false))
        assertEquals(VoiceState.Failed(R.string.voice_unavailable), voice.state.value)
    }

    @Test
    fun tooLongAudioIsNotSent() = runTest(main.dispatcher) {
        val transcriber = FakeTranscriber { TranscribeAudioResponse("x") }
        val voice = voice(emptyList(), FakeRecorder(audio = RecordedAudio(ByteArray(800_000), "audio/ogg")), transcriber)
        voice.stop()
        runCurrent()
        assertEquals(VoiceState.Failed(R.string.voice_too_long), voice.state.value)
        assertTrue(transcriber.requests.isEmpty())
    }

    @Test
    fun noMatchAndStop() = runTest(main.dispatcher) {
        val engine = FakeEngine(onDevice = false)
        val voice = voice(listOf(engine))
        voice.stop()
        assertTrue(engine.stopped, "Stop asks the recognizer for its result")
        engine.say(SpeechEvent.Failed(SpeechError.NO_MATCH))
        runCurrent()
        assertEquals(VoiceState.Failed(R.string.voice_no_match), voice.state.value)
        voice.dismissError()
        assertEquals(VoiceState.Idle, voice.state.value)
    }

    @Test
    fun cancelDuringRecordingDiscardsAudio() = runTest(main.dispatcher) {
        val recorder = FakeRecorder()
        val voice = voice(emptyList(), recorder)
        voice.cancel()
        assertTrue(recorder.cancelled)
        assertEquals(VoiceState.Idle, voice.state.value)
    }

    @Test
    fun recognitionLanguage() {
        assertEquals("ar-EG", VoiceLocales.tagFor("ar", Locale.forLanguageTag("ar-EG")), "phone region wins for the same language")
        assertEquals("ar-SA", VoiceLocales.tagFor("ar", Locale.forLanguageTag("en-AE")))
        assertEquals("tr-TR", VoiceLocales.tagFor("tr", Locale.forLanguageTag("tr")))
        assertEquals("fil-PH", VoiceLocales.tagFor("fil", Locale.forLanguageTag("ar-AE")), "a helper speaks Filipino on an Emirati phone")
    }

    @Test
    fun audioFormatPerApiLevel() {
        assertEquals(AudioFormatChoice.MP4_AAC, AudioFormatChoice.forSdk(26))
        assertEquals(AudioFormatChoice.MP4_AAC, AudioFormatChoice.forSdk(28))
        assertEquals(AudioFormatChoice.OGG_OPUS, AudioFormatChoice.forSdk(29))
        assertEquals("audio/mp4", AudioFormatChoice.MP4_AAC.mimeType)
    }

    @Test
    fun recognizerErrorsMap() {
        assertEquals(SpeechError.NETWORK, AndroidSpeechEngine.mapError(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SpeechError.NO_MATCH, AndroidSpeechEngine.mapError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SpeechError.LANGUAGE_UNSUPPORTED, AndroidSpeechEngine.mapError(12))
        assertEquals(SpeechError.LANGUAGE_UNSUPPORTED, AndroidSpeechEngine.mapError(13))
        assertEquals(SpeechError.NO_PERMISSION, AndroidSpeechEngine.mapError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }
}
