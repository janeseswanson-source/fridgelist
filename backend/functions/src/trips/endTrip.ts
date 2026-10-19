import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { writeEvent } from "../lib/events.js";
import { log } from "../lib/log.js";
import type { PushSender } from "../push/sender.js";
import { ACTIVE, liveMessages, loadTrip, tripCounts } from "./live.js";

export interface TripSummary {
  done: number;
  missing: number;
}

/**
 * Ends a trip (`updateTrip(done|cancelled)`, a new trip by the same shopper, `closeStaleTrips`):
 * summary first, then the trip's `missing` items go back to `open` (CONTRACT §3.6), the shopper's
 * presence is removed, `trip_done` is logged and the live surfaces end (Android `trip_update`
 * with the final status, iOS Live Activity `end`). Returns null when the trip was not active.
 */
export async function endTrip(
  db: Firestore,
  sender: PushSender,
  householdId: string,
  tripId: string,
  status: "done" | "cancelled",
  now: Date,
): Promise<TripSummary | null> {
  const ref = db.doc(`households/${householdId}/trips/${tripId}`);
  const ended = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!ACTIVE.includes(snap.get("status") as (typeof ACTIVE)[number])) return false;
    tx.update(ref, { status, endedAt: Timestamp.fromDate(now) });
    return true;
  });
  if (!ended) return null;

  const trip = await loadTrip(db, householdId, tripId, now);
  if (!trip) return null;
  const summary = { done: trip.state.done, missing: trip.missing };
  await ref.update({ counts: tripCounts(trip) });

  const listIds = (trip.data.listIds as string[] | undefined) ?? [];
  for (const listId of listIds) {
    const missing = await db
      .collection(`households/${householdId}/lists/${listId}/items`)
      .where("status", "==", "missing")
      .get();
    await Promise.all(
      missing.docs.map((d) => d.ref.update({ status: "open", updatedAt: FieldValue.serverTimestamp() })),
    );
  }

  const shopperUid = String(trip.data.shopperUid ?? "");
  const member = db.doc(`households/${householdId}/members/${shopperUid}`);
  if ((await member.get()).get("presence.tripId") === tripId)
    await member.update({ presence: FieldValue.delete() });

  await writeEvent(
    db,
    householdId,
    { type: "trip_done", actorUid: shopperUid, actorName: String(trip.data.shopperName ?? ""), tripId },
    now,
  );
  await sender.send(liveMessages(householdId, trip, now, "end"));
  log.info("trip_ended", { householdId, status, done: summary.done, missing: summary.missing });
  return summary;
}
