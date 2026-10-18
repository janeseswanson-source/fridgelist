package com.fridgelist.app.voice

import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** What a recognizer reports while it listens. */
sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Failed(val error: SpeechError) : SpeechEvent
}

enum class SpeechError {
    /** Heard nothing usable. */
    NO_MATCH,

    /** The recognizer needs the network and doesn't have it. */
    NETWORK,

    /** This recognizer can't do this language: try the next one. */
    LANGUAGE_UNSUPPORTED,

    /** This recognizer isn't usable right now: try the next one. */
    UNAVAILABLE,
    NO_PERMISSION,
    OTHER,
}

/** One speech recognizer (on-device or online). */
interface SpeechEngine {
    val onDevice: Boolean

    /** Starts listening; the flow ends after a [SpeechEvent.Final] or [SpeechEvent.Failed]. Cancel to abort. */
    fun listen(languageTag: String): Flow<SpeechEvent>

    /** Asks for the final result now (the user tapped Stop). */
    fun stop()
}

/** The recognizers this phone offers, best first (ARCHITECTURE §8.6: on-device first). */
fun interface SpeechEngines {
    fun available(): List<SpeechEngine>
}

/** Cloud-fallback audio (CONTRACT §4 `transcribeAudio`). */
data class RecordedAudio(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?) = other is RecordedAudio && other.mimeType == mimeType && other.bytes.contentEquals(bytes)
    override fun hashCode() = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

interface AudioRecorder {
    /** False when the microphone can't be opened. */
    fun start(maxSeconds: Int): Boolean

    /** Stops and returns the audio, or null when nothing usable was recorded. */
    fun stop(): RecordedAudio?

    fun cancel()
}

/** Which container/codec the cloud fallback records in (CONTRACT §4 allows ogg, mp4, wav). */
enum class AudioFormatChoice(val mimeType: String, val extension: String) {
    /** API 29+: small and what Speech-to-Text v2 likes best. */
    OGG_OPUS("audio/ogg", "ogg"),

    /** API 26–28 have no Opus encoder in MediaRecorder. */
    MP4_AAC("audio/mp4", "m4a"),
    ;

    companion object {
        fun forSdk(sdk: Int): AudioFormatChoice = if (sdk >= 29) OGG_OPUS else MP4_AAC
    }
}

/**
 * Recognition language (prompt A4: "language = the member's locale"): the member's item language,
 * with the phone's region when it is the same language (ar-EG on an Egyptian phone), otherwise
 * the main market's region.
 */
object VoiceLocales {
    private val DEFAULT_REGION = mapOf(
        "ar" to "ar-SA",
        "tr" to "tr-TR",
        "en" to "en-US",
        "fil" to "fil-PH",
        "hi" to "hi-IN",
        "ur" to "ur-PK",
    )

    fun tagFor(itemLanguage: String, device: Locale): String =
        if (device.language == itemLanguage && device.country.isNotEmpty()) {
            "${device.language}-${device.country}"
        } else {
            DEFAULT_REGION[itemLanguage] ?: itemLanguage
        }
}
