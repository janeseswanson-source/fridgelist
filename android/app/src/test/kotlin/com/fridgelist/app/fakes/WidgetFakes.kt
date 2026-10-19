package com.fridgelist.app.fakes

import com.fridgelist.app.widget.WidgetRefresher
import com.fridgelist.core.data.widget.WidgetSnapshotStore
import com.fridgelist.core.model.WidgetSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeWidgetStore(initial: WidgetSnapshot? = null) : WidgetSnapshotStore {
    val data = MutableStateFlow(initial)
    var writes = 0
    override val snapshot: Flow<WidgetSnapshot?> = data
    override suspend fun write(snapshot: WidgetSnapshot) {
        writes++
        data.value = snapshot
    }
    override suspend fun clear() {
        data.value = null
    }
}

class FakeWidgetRefresher : WidgetRefresher {
    var refreshes = 0
    override fun refresh() {
        refreshes++
    }
}
