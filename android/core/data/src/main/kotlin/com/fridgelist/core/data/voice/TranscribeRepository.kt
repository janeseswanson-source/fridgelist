package com.fridgelist.core.data.voice

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.model.TranscribeAudioRequest
import com.fridgelist.core.model.TranscribeAudioResponse
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §4 `transcribeAudio`: the cloud fallback when on-device speech can't help. */
interface TranscribeRepository {
    /** Throws ApiException (NETWORK when offline, RESOURCE_EXHAUSTED over 20 calls/hour). */
    suspend fun transcribe(request: TranscribeAudioRequest): TranscribeAudioResponse
}

@Singleton
class CallableTranscribeRepository @Inject constructor(
    private val callables: CallableClient,
) : TranscribeRepository {
    override suspend fun transcribe(request: TranscribeAudioRequest): TranscribeAudioResponse =
        callables.call("transcribeAudio", request, TranscribeAudioRequest.serializer(), TranscribeAudioResponse.serializer())
}
