package com.fridgelist.app.notifications

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.core.data.firestore.ItemDraft
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.data.items.AliasRepository
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.i18n.LocalSplitter
import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.StatusRules
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * A reply typed into a trip notification (CONTRACT §5.1 inline reply): split locally, written as
 * items with `addedVia: notification_reply`. Works offline (Firestore queues the write).
 */
class NotificationReplyHandler @Inject constructor(
    private val contextSource: HouseholdContextSource,
    private val aliases: AliasRepository,
    private val items: ItemRepository,
) {
    /** Returns how many items were written; 0 when the reply was empty or the member isn't in that household. */
    suspend fun add(householdId: String, listId: String, text: String): Int {
        if (text.isBlank()) return 0
        val ctx = withTimeoutOrNull(CONTEXT_TIMEOUT_MS) { contextSource.context.first() } ?: return 0
        if (ctx.householdId != householdId) return 0
        val known = withTimeoutOrNull(ALIAS_TIMEOUT_MS) { aliases.aliases(householdId).first() }.orEmpty()
        val split = LocalSplitter().split(text, ctx.localeHints, known)
        val status = StatusRules.createStatus(ctx.role)
        val patches = split.map { ItemPatches.create(ItemDraft(it.name, it.qty, it.unit), status, ctx.uid, AddedVia.NOTIFICATION_REPLY) }
        items.add(householdId, listId, patches)
        return patches.size
    }

    private companion object {
        const val CONTEXT_TIMEOUT_MS = 5_000L
        const val ALIAS_TIMEOUT_MS = 1_500L
    }
}

@AndroidEntryPoint
class AddFromNotificationReceiver : BroadcastReceiver() {

    @Inject lateinit var handler: NotificationReplyHandler

    @SuppressLint("MissingPermission") // only updates a notification we already posted
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(HouseholdNotifier.REPLY_KEY) ?: return
        val householdId = intent.getStringExtra(EXTRA_HOUSEHOLD) ?: return
        val listId = intent.getStringExtra(EXTRA_LIST) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: Channels.HOUSEHOLD
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val count = handler.add(householdId, listId, reply.toString())
                Log.i(TAG, "reply added $count item(s)") // a count only, never the text
                NotificationManagerCompat.from(context).notify(notificationId, HouseholdNotifier.added(context, channel, title, reply))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NotificationReply"
        const val EXTRA_HOUSEHOLD = "householdId"
        const val EXTRA_LIST = "listId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }
}
