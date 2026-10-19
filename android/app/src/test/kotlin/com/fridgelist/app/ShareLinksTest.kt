package com.fridgelist.app

import android.nfc.NdefRecord
import androidx.lifecycle.SavedStateHandle
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.householdContext
import com.fridgelist.app.feature.share.NfcTags
import com.fridgelist.app.feature.share.NfcWriteResult
import com.fridgelist.app.feature.share.ShareTagViewModel
import com.fridgelist.app.feature.share.SharedLinkViewModel
import com.fridgelist.app.feature.share.TagStep
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.data.share.ShareLinkRepository
import com.fridgelist.core.model.CreateShareLinkRequest
import com.fridgelist.core.model.CreateShareLinkResponse
import com.fridgelist.core.model.GuestAddItemRequest
import com.fridgelist.core.model.GuestAddItemResponse
import com.fridgelist.core.model.GuestItem
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.ResolveShareLinkRequest
import com.fridgelist.core.model.ResolveShareLinkResponse
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.ShareMember
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ShareLinksTest {

    @get:Rule val main = MainDispatcherRule()

    private val url = "https://links.example.com/k/Ab3_-xYz0123456789abcdEF"

    private class FakeLinks(
        var resolveWith: ResolveShareLinkResponse? = null,
        var fail: ApiError? = null,
        var addFail: ApiError? = null,
    ) : ShareLinkRepository {
        val created = mutableListOf<CreateShareLinkRequest>()
        val guestAdds = mutableListOf<GuestAddItemRequest>()
        var resolves = 0

        override suspend fun create(request: CreateShareLinkRequest): CreateShareLinkResponse {
            fail?.let { throw ApiException(it, "x") }
            created += request
            return CreateShareLinkResponse("t${created.size}", "https://links.example.com/k/${request.kind}-${request.scope}-${created.size}")
        }

        override suspend fun resolve(request: ResolveShareLinkRequest): ResolveShareLinkResponse {
            resolves++
            fail?.let { throw ApiException(it, "x") }
            return checkNotNull(resolveWith)
        }

        override suspend fun guestAdd(request: GuestAddItemRequest): GuestAddItemResponse {
            addFail?.let { throw ApiException(it, "x") }
            guestAdds += request
            return GuestAddItemResponse(2)
        }
    }

    @Test
    fun tagHoldsOneUriRecordAndFitsTheSmallestSticker() {
        val message = NfcTags.message(url)
        val record = message.records.single() // no Android Application Record: other phones open the guest page
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.tnf)
        assertContentEquals(NdefRecord.RTD_URI, record.type)
        assertEquals(url, record.toUri().toString())
        assertTrue(NfcTags.fits(url, maxSize = 137), "NTAG213")
        assertTrue(!NfcTags.fits(url, maxSize = 30))
    }

    private fun share(links: FakeLinks): ShareTagViewModel {
        val items = FakeItems().apply { lists.value = listOf(ListDoc("l1", "البقالة", ListType.GROCERY)) }
        return ShareTagViewModel(SavedStateHandle(mapOf("listId" to "l1")), householdContext(Role.OWNER), items, links)
    }

    @Test
    fun qrAndTagLinksAreMadeOncePerKindAndScope() = runTest(main.dispatcher) {
        val links = FakeLinks()
        val vm = share(links)
        assertEquals("البقالة", vm.state.value.listName)
        assertEquals("https://links.example.com/k/qr-add-1", vm.state.value.qrUrl)

        vm.startTag()
        assertEquals(TagStep.HOLD, vm.state.value.tagStep)
        assertEquals("https://links.example.com/k/nfc-add-2", vm.tagUrl())
        vm.onTagWritten(NfcWriteResult.TOO_SMALL)
        assertEquals(TagStep.HOLD, vm.state.value.tagStep, "try another sticker without starting over")
        assertEquals(R.string.share_nfc_too_small, vm.state.value.message)
        vm.onTagWritten(NfcWriteResult.OK)
        assertEquals(TagStep.DONE, vm.state.value.tagStep)
        vm.startTag()
        assertEquals(2, links.created.size, "a second tag reuses the link")

        vm.onGuestsCanAdd(false)
        assertEquals("https://links.example.com/k/qr-view-3", vm.state.value.qrUrl)
        vm.onGuestsCanAdd(true)
        assertEquals("https://links.example.com/k/qr-add-1", vm.state.value.qrUrl)
        assertEquals(3, links.created.size)
        assertEquals(listOf("h1"), links.created.map { it.householdId }.distinct())
    }

    @Test
    fun offlineSaysSoAndRetries() = runTest(main.dispatcher) {
        val links = FakeLinks(fail = ApiError.NETWORK)
        val vm = share(links)
        assertNull(vm.state.value.qrUrl)
        assertEquals(R.string.share_offline, vm.state.value.message)
        vm.startTag()
        assertEquals(TagStep.IDLE, vm.state.value.tagStep)
        links.fail = null
        vm.loadQr()
        assertEquals("https://links.example.com/k/qr-add-1", vm.state.value.qrUrl)
    }

    private val link = ResolveShareLinkResponse("بيت العائلة", "البقالة", "add", listOf(GuestItem("i1", "حليب", "2 L")))

    private fun guest(links: FakeLinks, householdId: String? = "h1") = SharedLinkViewModel(
        SavedStateHandle(mapOf("token" to "Ab3_-xYz0123456789abcdEF")),
        links,
        FakeSession(Session("Ayşe", "tr", householdId, true)),
    ) { "tr" }

    @Test
    fun aMembersOwnTagOpensQuickAdd() = runTest(main.dispatcher) {
        val vm = guest(FakeLinks(link.copy(member = ShareMember("h1", "l1"))))
        assertEquals("l1", vm.state.value.openListId)
        // Member of that household, but this phone is set to another one: guest view.
        assertNull(guest(FakeLinks(link.copy(member = ShareMember("h1", "l1"))), householdId = "h2").state.value.openListId)
        assertNull(guest(FakeLinks(link)).state.value.openListId)
    }

    @Test
    fun guestsSeeTheListAndCanAddWhenAllowed() = runTest(main.dispatcher) {
        val links = FakeLinks(link)
        val vm = guest(links)
        assertEquals("حليب", vm.state.value.link?.items?.single()?.title)
        vm.onText("ekmek, süt")
        vm.onGuestName("  Komşu Ali ")
        vm.add()
        assertEquals(GuestAddItemRequest("Ab3_-xYz0123456789abcdEF", "ekmek, süt", "Komşu Ali"), links.guestAdds.single())
        assertEquals(2, vm.state.value.added)
        assertEquals("", vm.state.value.text)
        assertEquals(2, links.resolves, "reloaded to show the new items")

        links.addFail = ApiError.RESOURCE_EXHAUSTED
        vm.onText("çay")
        vm.add()
        assertEquals(R.string.guest_too_many, vm.state.value.addError)
        assertEquals("çay", vm.state.value.text, "kept to retry")
    }

    @Test
    fun viewOnlyLinksCantAdd() = runTest(main.dispatcher) {
        val links = FakeLinks(link.copy(scope = "view"))
        val vm = guest(links)
        vm.onText("ekmek")
        vm.add()
        assertTrue(links.guestAdds.isEmpty())
    }

    @Test
    fun deadLinksAndOfflineSayWhy() = runTest(main.dispatcher) {
        assertEquals(R.string.guest_not_found, guest(FakeLinks(fail = ApiError.NOT_FOUND)).state.value.error)
        val offline = FakeLinks(fail = ApiError.NETWORK)
        val vm = guest(offline)
        assertEquals(R.string.guest_offline, vm.state.value.error)
        offline.fail = null
        offline.resolveWith = link
        vm.load()
        assertEquals("البقالة", vm.state.value.link?.listName)
    }
}
