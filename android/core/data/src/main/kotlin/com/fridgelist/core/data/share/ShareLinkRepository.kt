package com.fridgelist.core.data.share

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.model.CreateShareLinkRequest
import com.fridgelist.core.model.CreateShareLinkResponse
import com.fridgelist.core.model.GuestAddItemRequest
import com.fridgelist.core.model.GuestAddItemResponse
import com.fridgelist.core.model.ResolveShareLinkRequest
import com.fridgelist.core.model.ResolveShareLinkResponse
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §4 share links (fridge tags, QR codes). All need the network. */
interface ShareLinkRepository {
    suspend fun create(request: CreateShareLinkRequest): CreateShareLinkResponse

    suspend fun resolve(request: ResolveShareLinkRequest): ResolveShareLinkResponse

    suspend fun guestAdd(request: GuestAddItemRequest): GuestAddItemResponse
}

@Singleton
class CallableShareLinkRepository @Inject constructor(
    private val callables: CallableClient,
) : ShareLinkRepository {
    override suspend fun create(request: CreateShareLinkRequest): CreateShareLinkResponse =
        callables.call("createShareLink", request, CreateShareLinkRequest.serializer(), CreateShareLinkResponse.serializer())

    override suspend fun resolve(request: ResolveShareLinkRequest): ResolveShareLinkResponse =
        callables.call("resolveShareLink", request, ResolveShareLinkRequest.serializer(), ResolveShareLinkResponse.serializer())

    override suspend fun guestAdd(request: GuestAddItemRequest): GuestAddItemResponse =
        callables.call("guestAddItem", request, GuestAddItemRequest.serializer(), GuestAddItemResponse.serializer())
}
