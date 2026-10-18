package com.fridgelist.core.model

/**
 * Item status transitions clients may make (CONTRACT §3.6). Security rules enforce the same table;
 * the app uses it to offer only actions that will succeed.
 */
object StatusRules {

    /** Who creates items with which status: kids' items always start as `pending`. */
    fun createStatus(role: Role): ItemStatus = if (role == Role.KID) ItemStatus.PENDING else ItemStatus.OPEN

    fun canApprove(role: Role): Boolean = role == Role.OWNER || role == Role.ADULT

    /**
     * @param isClaimer true when the acting member is the item's `claimedBy`.
     */
    fun canTransition(from: ItemStatus, to: ItemStatus, role: Role, isClaimer: Boolean): Boolean = when (from) {
        ItemStatus.PENDING -> to == ItemStatus.OPEN && canApprove(role)
        ItemStatus.OPEN -> to == ItemStatus.CLAIMED || to == ItemStatus.DONE || to == ItemStatus.MISSING
        ItemStatus.CLAIMED -> when (to) {
            ItemStatus.OPEN -> isClaimer || canApprove(role)
            ItemStatus.DONE, ItemStatus.MISSING -> true
            else -> false
        }
        ItemStatus.DONE -> to == ItemStatus.OPEN
        ItemStatus.MISSING -> to == ItemStatus.OPEN || to == ItemStatus.DONE
    }

    /** `pending` items may be deleted by owner/adult only; other items by any member. */
    fun canDelete(status: ItemStatus, role: Role): Boolean =
        if (status == ItemStatus.PENDING) canApprove(role) else true

    /**
     * Where Undo goes after a status change. The table has no way back to `claimed` or `pending`,
     * so undoing from `done`/`missing` returns the item to `open`.
     */
    fun undoTarget(previous: ItemStatus, current: ItemStatus, role: Role, isClaimer: Boolean): ItemStatus? =
        when {
            canTransition(current, previous, role, isClaimer) -> previous
            canTransition(current, ItemStatus.OPEN, role, isClaimer) -> ItemStatus.OPEN
            else -> null
        }
}
