import { Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { log } from "../lib/log.js";
import { defaultSender, type PushSender } from "../push/sender.js";
import { endTrip } from "../trips/endTrip.js";
import { ACTIVE } from "../trips/live.js";

/** ARCHITECTURE §6: trips still open after 4 hours are over. */
export const STALE_AFTER_MS = 4 * 60 * 60 * 1000;

/**
 * Ends every heading/shopping trip that started more than 4 hours ago, like `updateTrip(done)`:
 * missing items back to open, presence removed, Live Activity / Live Update ended.
 * Needs the `trips` collection-group index (status, startedAt) in firestore.indexes.json.
 */
export async function closeStaleTripsCore(db: Firestore, sender: PushSender, now: Date): Promise<number> {
  const cutoff = Timestamp.fromMillis(now.getTime() - STALE_AFTER_MS);
  const stale = await db
    .collectionGroup("trips")
    .where("status", "in", [...ACTIVE])
    .where("startedAt", "<", cutoff)
    .get();
  let ended = 0;
  for (const trip of stale.docs) {
    const householdId = trip.ref.parent.parent?.id;
    if (!householdId) continue;
    if (await endTrip(db, sender, householdId, trip.id, "done", now)) ended++;
  }
  log.info("close_stale_trips", { found: stale.size, ended });
  return ended;
}

export const closeStaleTrips = onSchedule({ schedule: "every 30 minutes", timeZone: "Etc/UTC" }, async () => {
  await closeStaleTripsCore(getFirestore(), defaultSender(getFirestore()), new Date());
});
