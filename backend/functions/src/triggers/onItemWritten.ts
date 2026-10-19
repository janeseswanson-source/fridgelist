import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { defaultSender, type PushSender } from "../push/sender.js";
import { activeTripsFor, listChanged, realClock, throttledLiveUpdate, type Clock } from "../trips/live.js";

/**
 * Live fan-out for any item create, update or delete (the "B6 hook" of B4's triggers):
 * - every active trip on this list gets a throttled live update (≤ 1 per 5 s, newest state);
 * - the household's devices get `list_changed` (≤ 1 per device per minute), except the adder's
 *   own devices for a new item (their app already shows it).
 * Pushes only; it never writes items, so it can't loop.
 */
export async function onItemWrittenCore(
  db: Firestore,
  sender: PushSender,
  ref: { householdId: string; listId: string },
  addedByOnCreate: string | undefined,
  clock: Clock = realClock,
): Promise<void> {
  const trips = await activeTripsFor(db, ref.householdId, ref.listId);
  await Promise.all([
    ...trips.map((tripId) => throttledLiveUpdate(db, sender, ref.householdId, tripId, clock)),
    listChanged(db, sender, ref.householdId, ref.listId, clock.now(), addedByOnCreate),
  ]);
}

export const onItemWritten = onDocumentWritten(
  { document: "households/{householdId}/lists/{listId}/items/{itemId}", timeoutSeconds: 60 },
  async (event) => {
    const before = event.data?.before;
    const after = event.data?.after;
    const created = !before?.exists && after?.exists === true;
    const addedBy = created ? (after.get("addedBy") as string | undefined) : undefined;
    const db = getFirestore();
    await onItemWrittenCore(db, defaultSender(db), event.params, addedBy);
  },
);
