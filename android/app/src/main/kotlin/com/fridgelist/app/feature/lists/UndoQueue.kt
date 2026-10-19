package com.fridgelist.app.feature.lists

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One pending undo; [id] makes repeated identical messages show again. */
data class UndoUi(@param:StringRes val message: Int, val id: Long)

/**
 * "Every destructive action has undo" (PRODUCT_SPEC F2). One action at a time: a new one settles
 * the previous one as not undone. [settle] is called when the snackbar closes.
 */
class UndoQueue {
    private val _undo = MutableStateFlow<UndoUi?>(null)
    val undo: StateFlow<UndoUi?> = _undo.asStateFlow()
    private var onUndo: (() -> Unit)? = null
    private var onCommit: (() -> Unit)? = null

    fun offer(@StringRes message: Int, undo: () -> Unit, commit: () -> Unit = {}) {
        settle(undone = false)
        onUndo = undo
        onCommit = commit
        _undo.value = UndoUi(message, System.nanoTime())
    }

    fun settle(undone: Boolean) {
        val action = if (undone) onUndo else onCommit
        onUndo = null
        onCommit = null
        _undo.value = null
        action?.invoke()
    }
}
