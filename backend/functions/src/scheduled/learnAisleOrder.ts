import { Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { CATEGORIES, isCategory, type Category } from "../lib/categories.js";
import { remoteFlags, type Flags } from "../lib/flags.js";
import { log } from "../lib/log.js";

/** A learned order is published once a store has this many usable trips. */
export const AISLE_MIN_TRIPS = 5;
const LOOKBACK_MS = 24 * 60 * 60 * 1000;

export type AisleStats = Partial<Record<Category, { sumRank: number; n: number }>>;

/**
 * The categories in the order the shopper first ticked them, ranked 0…1. Fewer than two
 * categories says nothing about the layout, so the trip is skipped (null).
 */
export function tripRanks(
  ticks: readonly { category: Category; at: number }[],
): Map<Category, number> | null {
  const order: Category[] = [];
  for (const t of [...ticks].sort((a, b) => a.at - b.at))
    if (!order.includes(t.category)) order.push(t.category);
  if (order.length < 2) return null;
  return new Map(order.map((c, i) => [c, i / (order.length - 1)]));
}

export function addTrip(stats: AisleStats, ranks: ReadonlyMap<Category, number>): AisleStats {
  const out: AisleStats = { ...stats };
  for (const [c, rank] of ranks) {
    const s = out[c] ?? { sumRank: 0, n: 0 };
    out[c] = { sumRank: s.sumRank + rank, n: s.n + 1 };
  }
  return out;
}

/** Seen categories by mean rank (ties keep the default order), then the unseen ones in default order. */
export function aisleOrder(stats: AisleStats): Category[] {
  const seen = CATEGORIES.filter((c) => (stats[c]?.n ?? 0) > 0);
  const mean = (c: Category) => (stats[c]?.sumRank ?? 0) / (stats[c]?.n ?? 1);
  const sorted = [...seen].sort((a, b) => mean(a) - mean(b) || CATEGORIES.indexOf(a) - CATEGORIES.indexOf(b));
  return [...sorted, ...CATEGORIES.filter((c) => !seen.includes(c))];
}

/**
 * Nightly, off unless `aisle_learning_enabled`. Trips that ended `done` in the last 24 hours at a
 * known store add their tick order to the store's stats; each trip counts once (`aisleLearned`).
 * Needs the `trips` collection-group index (status, endedAt).
 */
export async function learnAisleOrderCore(db: Firestore, flags: Flags, now: Date): Promise<number | null> {
  if (!(await flags.enabled("aisle_learning_enabled"))) return null;
  const trips = await db
    .collectionGroup("trips")
    .where("status", "==", "done")
    .where("endedAt", ">=", Timestamp.fromMillis(now.getTime() - LOOKBACK_MS))
    .get();
  let learned = 0;
  for (const trip of trips.docs) {
    const storeId = trip.get("storeId") as string | undefined;
    const household = trip.ref.parent.parent;
    if (trip.get("aisleLearned") === true || !storeId || !household) continue;
    const listIds = (trip.get("listIds") as string[] | undefined) ?? [];
    const ticks: { category: Category; at: number }[] = [];
    for (const listId of listIds) {
      const items = await household
        .collection(`lists/${listId}/items`)
        .where("doneTripId", "==", trip.id)
        .get();
      for (const item of items.docs) {
        const category: unknown = item.get("category");
        const at = (item.get("doneAt") as Timestamp | undefined)?.toMillis();
        if (isCategory(category) && at !== undefined) ticks.push({ category, at });
      }
    }
    const ranks = tripRanks(ticks);
    const storeRef = db.doc(`stores/${storeId}`);
    await db.runTransaction(async (tx) => {
      const [store, fresh] = await Promise.all([tx.get(storeRef), tx.get(trip.ref)]);
      if (fresh.get("aisleLearned") === true) return;
      tx.update(trip.ref, { aisleLearned: true });
      if (!ranks || !store.exists) return;
      const stats = addTrip((store.get("aisleStats") as AisleStats | undefined) ?? {}, ranks);
      const count = Number(store.get("aisleTrips") ?? 0) + 1;
      tx.update(storeRef, {
        aisleStats: stats,
        aisleTrips: count,
        ...(count >= AISLE_MIN_TRIPS ? { aisleOrder: aisleOrder(stats) } : {}),
        updatedAt: Timestamp.fromDate(now),
      });
      learned++;
    });
  }
  log.info("learn_aisle_order", { trips: trips.size, learned });
  return learned;
}

export const learnAisleOrder = onSchedule(
  { schedule: "every day 02:00", timeZone: "Asia/Riyadh" },
  async () => {
    await learnAisleOrderCore(getFirestore(), remoteFlags, new Date());
  },
);
