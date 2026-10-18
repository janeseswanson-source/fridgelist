package com.fridgelist.core.data.callable

import com.fridgelist.core.model.ContractJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/** Calls a Cloud Function by name with a JSON body (CONTRACT §4). Throws [ApiException]. */
interface CallableClient {
    suspend fun call(name: String, body: JsonElement): JsonElement
}

/** Typed helper: encode the request, call, decode the response. */
suspend fun <Req, Res> CallableClient.call(
    name: String,
    request: Req,
    requestSerializer: KSerializer<Req>,
    responseSerializer: KSerializer<Res>,
): Res {
    val body = ContractJson.encodeToJsonElement(requestSerializer, request)
    val response = call(name, body)
    return try {
        ContractJson.decodeFromJsonElement(responseSerializer, response)
    } catch (e: IllegalArgumentException) {
        throw ApiException(ApiError.UNKNOWN, "Unexpected response from $name", e)
    }
}

/** CONTRACT §8 error codes, plus [NETWORK] for "never reached the server". */
enum class ApiError {
    UNAUTHENTICATED,
    PERMISSION_DENIED,
    INVALID_ARGUMENT,
    NOT_FOUND,
    FAILED_PRECONDITION,
    RESOURCE_EXHAUSTED,
    UNAVAILABLE,
    NETWORK,
    UNKNOWN,
}

class ApiException(val error: ApiError, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: error.name, cause)
