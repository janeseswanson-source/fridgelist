package com.fridgelist.core.data.account

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.model.DeleteMyAccountRequest
import com.fridgelist.core.model.EmptyRequest
import com.fridgelist.core.model.ExportMyDataResponse
import com.fridgelist.core.model.OkResponse
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §4 account callables (PRODUCT_SPEC F12; both stores require in-app deletion). */
interface AccountRepository {
    suspend fun exportMyData(): ExportMyDataResponse

    suspend fun deleteMyAccount(request: DeleteMyAccountRequest)
}

@Singleton
class CallableAccountRepository @Inject constructor(
    private val callables: CallableClient,
) : AccountRepository {
    override suspend fun exportMyData(): ExportMyDataResponse =
        callables.call("exportMyData", EmptyRequest(), EmptyRequest.serializer(), ExportMyDataResponse.serializer())

    override suspend fun deleteMyAccount(request: DeleteMyAccountRequest) {
        callables.call("deleteMyAccount", request, DeleteMyAccountRequest.serializer(), OkResponse.serializer())
    }
}
