import {
  FieldPath,
  getFirestore,
  Timestamp,
  type DocumentData,
  type Firestore,
} from "firebase-admin/firestore";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { memberName, writeEvent } from "../lib/events.js";
import { log } from "../lib/log.js";
import { defaultSender } from "../push/sender.js";
import { PushMissingNotifier } from "../trips/alerts.js";
import type { ItemRef } from "./onItemCreated.js";

/** Sends the `item_missing` push (CONTRACT §5.1); B6: `PushMissingNotifier`. */
export interface MissingNotifier {
  itemMissing(input: {
    householdId: string;
    listId: string;
    itemId: string;
    tripId?: string;
    recipientUid: string;
  }): Promise<void>;
}

export const PENDING_MISSING_NOTIFIER: MissingNotifier = {
  itemMissing: (input) => {
    log.info("item_missing_push_stub", { householdId: input.householdId, itemId: input.itemId });
    return Promise.resolve();
  },
};

/** An undo within this window removes the purchase record again. */
export const UNDO_WINDOW_MS = 30 * 60 * 1000;

/** The shopper of the household's active trip for this list, if any (who marked it missing). */
async function activeShopper(db: Firestore, householdId: string, listId: string) {
  const trips = await db
    .collection(`households/${householdId}/trips`)
    .where("status", "in", ["heading", "shopping"])
    .get();
  const trip = trips.docs.find((t) => ((t.get("listIds") as string[] | undefined) ?? []).includes(listId));
  return trip ? { tripId: trip.id, uid: String(trip.get("shopperUid") ?? "") } : null;
}

/**
 * Prompt B4 `onItemUpdated`: `done` → purchase record + `item_done` event; `done` undone soon
 * after → the purchase record is removed; `missing` → `item_missing` event and a push to the
 * person who added it (respecting `notify.itemMissing`, never to the shopper themselves).
 */
export async function onItemUpdatedCore(
  db: Firestore,
  ref: ItemRef,
  before: DocumentData,
  after: DocumentData,
  notifier: MissingNotifier,
  now: Date = new Date(),
): Promise<"purchase" | "undo" | "missing" | "none"> {
  if (before.status === after.status) return "none";
  const purchases = db.collection(`households/${ref.householdId}/purchases`);

  if (after.status === "done") {
    const by = String(after.doneBy ?? "");
    const tripId = typeof after.doneTripId === "string" ? after.doneTripId : undefined;
    const trip = tripId
      ? (await db.doc(`households/${ref.householdId}/trips/${tripId}`).get()).data()
      : undefined;
    // `{itemId}_{millis}` lets an undo find the record without adding a field to CONTRACT §3.9.
    await purchases.doc(`${ref.itemId}_${String(now.getTime())}`).set({
      ...(after.canonicalId ? { canonicalId: after.canonicalId as string } : {}),
      name: String(after.name ?? ""),
      ...(typeof after.qty === "number" ? { qty: after.qty } : {}),
      ...(typeof after.unit === "string" ? { unit: after.unit } : {}),
      ...(trip?.storeId ? { storeId: trip.storeId as string } : {}),
      ...(trip?.chainId ? { chainId: trip.chainId as string } : {}),
      ...(tripId ? { tripId } : {}),
      by,
      at: Timestamp.fromDate(now),
    });
    await writeEvent(
      db,
      ref.householdId,
      {
        type: "item_done",
        actorUid: by,
        actorName: await memberName(db, ref.householdId, by),
        itemId: ref.itemId,
        itemName: String(after.name ?? ""),
        tripId,
      },
      now,
    );
    return "purchase";
  }

  if (before.status === "done" && after.status === "open") {
    const recent = await purchases
      .where(FieldPath.documentId(), ">=", `${ref.itemId}_`)
      .where(FieldPath.documentId(), "<", `${ref.itemId}_`)
      .get();
    const cutoff = now.getTime() - UNDO_WINDOW_MS;
    await Promise.all(
      recent.docs
        .filter((d) => ((d.get("at") as Timestamp | undefined)?.toMillis() ?? 0) >= cutoff)
        .map((d) => d.ref.delete()),
    );
    return "undo";
  }

  if (after.status === "missing") {
    const shopper = await activeShopper(db, ref.householdId, ref.listId);
    const actorUid = shopper?.uid ?? "";
    await writeEvent(
      db,
      ref.householdId,
      {
        type: "item_missing",
        actorUid,
        actorName: await memberName(db, ref.householdId, actorUid),
        itemId: ref.itemId,
        itemName: String(after.name ?? ""),
        tripId: shopper?.tripId,
      },
      now,
    );
    const recipient = String(after.addedBy ?? "");
    if (recipient && !recipient.includes(":") && recipient !== actorUid) {
      const member = await db.doc(`households/${ref.householdId}/members/${recipient}`).get();
      const wants = (member.get("notify") as { itemMissing?: boolean } | undefined)?.itemMissing !== false;
      if (member.exists && wants) {
        await notifier.itemMissing({
          ...ref,
          recipientUid: recipient,
          ...(shopper ? { tripId: shopper.tripId } : {}),
        });
      }
    }
    return "missing";
  }
  return "none";
}

export const onItemUpdated = onDocumentUpdated(
  "households/{householdId}/lists/{listId}/items/{itemId}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after) return;
    const db = getFirestore();
    await onItemUpdatedCore(db, event.params, before, after, new PushMissingNotifier(db, defaultSender(db)));
  },
);
