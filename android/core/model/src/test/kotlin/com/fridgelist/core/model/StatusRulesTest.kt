package com.fridgelist.core.model

import com.fridgelist.core.model.ItemStatus.CLAIMED
import com.fridgelist.core.model.ItemStatus.DONE
import com.fridgelist.core.model.ItemStatus.MISSING
import com.fridgelist.core.model.ItemStatus.OPEN
import com.fridgelist.core.model.ItemStatus.PENDING
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One assertion per row of the CONTRACT §3.6 transition table, plus the forbidden ones. */
class StatusRulesTest {

    @Test
    fun createStatus() {
        assertEquals(PENDING, StatusRules.createStatus(Role.KID))
        for (role in listOf(Role.OWNER, Role.ADULT, Role.HELPER)) assertEquals(OPEN, StatusRules.createStatus(role))
    }

    @Test
    fun pendingApprovedByOwnerOrAdultOnly() {
        assertTrue(StatusRules.canTransition(PENDING, OPEN, Role.OWNER, false))
        assertTrue(StatusRules.canTransition(PENDING, OPEN, Role.ADULT, false))
        assertFalse(StatusRules.canTransition(PENDING, OPEN, Role.HELPER, false))
        assertFalse(StatusRules.canTransition(PENDING, OPEN, Role.KID, false))
        assertFalse(StatusRules.canTransition(PENDING, DONE, Role.OWNER, false))
    }

    @Test
    fun openGoesAnywhereButPending() {
        for (to in listOf(CLAIMED, DONE, MISSING)) assertTrue(StatusRules.canTransition(OPEN, to, Role.KID, false), "$to")
        assertFalse(StatusRules.canTransition(OPEN, PENDING, Role.OWNER, false))
    }

    @Test
    fun claimedBackToOpenOnlyForClaimerOrAdult() {
        assertTrue(StatusRules.canTransition(CLAIMED, OPEN, Role.HELPER, isClaimer = true))
        assertTrue(StatusRules.canTransition(CLAIMED, OPEN, Role.ADULT, isClaimer = false))
        assertFalse(StatusRules.canTransition(CLAIMED, OPEN, Role.HELPER, isClaimer = false))
        assertTrue(StatusRules.canTransition(CLAIMED, DONE, Role.KID, false))
        assertTrue(StatusRules.canTransition(CLAIMED, MISSING, Role.KID, false))
    }

    @Test
    fun doneAndMissing() {
        assertTrue(StatusRules.canTransition(DONE, OPEN, Role.KID, false))
        assertFalse(StatusRules.canTransition(DONE, CLAIMED, Role.OWNER, false))
        assertFalse(StatusRules.canTransition(DONE, MISSING, Role.OWNER, false))
        assertTrue(StatusRules.canTransition(MISSING, OPEN, Role.KID, false))
        assertTrue(StatusRules.canTransition(MISSING, DONE, Role.KID, false))
        assertFalse(StatusRules.canTransition(MISSING, CLAIMED, Role.OWNER, false))
    }

    @Test
    fun deleting() {
        assertTrue(StatusRules.canDelete(PENDING, Role.ADULT))
        assertFalse(StatusRules.canDelete(PENDING, Role.HELPER))
        assertTrue(StatusRules.canDelete(OPEN, Role.KID))
    }

    @Test
    fun undoTargets() {
        assertEquals(OPEN, StatusRules.undoTarget(previous = OPEN, current = DONE, role = Role.HELPER, isClaimer = false))
        assertEquals(OPEN, StatusRules.undoTarget(previous = CLAIMED, current = DONE, role = Role.HELPER, isClaimer = true), "no done→claimed")
        assertEquals(OPEN, StatusRules.undoTarget(previous = OPEN, current = CLAIMED, role = Role.HELPER, isClaimer = true))
        assertNull(StatusRules.undoTarget(previous = OPEN, current = CLAIMED, role = Role.HELPER, isClaimer = false))
    }
}
