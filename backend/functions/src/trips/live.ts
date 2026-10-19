import { Timestamp, type DocumentData, type Firestore } from "firebase-admin/firestore";
import { log } from "../lib/log.js";
import { devicesOf, membersOf } from "../push/recipients.js";
import { liveActivityMessage, listChangedMessage, tripUpdateMessage, type Device } from "../push/messages.js";
import type { Outgoing, PushSender } from "../push/sender.js";
import { computeTripState, type StateItem, type TripState } from "./state.js";

/** ARCHITECTURE §3.6: at most one live update per trip per 5 s, always the newest state. */
export const LIVE_THROTTLE_MS = 5_000;
/** CONTRACT §5.1: `list_changed` at most once per device per minute. */
export const LIST_CHANGED_MS = 60_000;

const millis = (value: unknown): number | null => (value instanceof Timestamp ? value.toMillis() : null);

export const ACTIVE = ["heading", "shopping"] as const;

export interface Clock {
  now(): Date;
  sleep(ms: number): Promise<void>;
}
export const realClock: Clock = {
  now: () => new Date(),
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

export interface LoadedTrip {
  tripId: string;
  data: DocumentData;
  state: TripState;
  /** Items marked "couldn't find" in the trip's lists (CONTRACT §3.7 counts.missing). */
  missing: number;
  shopperDevice: Device | null;
}

/** The trip, its live state in the shopper's language, and the shopper's device on this trip. */
export async function loadTrip(
  db: Firestore,
  householdId: string,
  tripId: string,
  now: Date,
): Promise<LoadedTrip | null> {
  const [tripSnap, household, members] = await Promise.all([
    db.doc(`households/${householdId}/trips/${tripId}`).get(),
    db.doc(`households/${householdId}`).get(),
    membersOf(db, householdId),
  ]);
  const data = tripSnap.data();
  if (!data) return null;
  const shopperUid = String(data.shopperUid ?? "");
  const listIds = (data.listIds as string[] | undefined) ?? [];
  const lists = await Promise.all(
    listIds.map((lid) => db.collection(`households/${householdId}/lists/${lid}/items`).get()),
  );
  const items: StateItem[] = lists.flatMap((snap) =>
    snap.docs.map((d) => {
      const i = d.data();
      return {
        name: String(i.name ?? ""),
        lang: (i.lang as string | undefined) ?? null,
        names: (i.names as Record<string, string> | undefined) ?? null,
        status: String(i.status ?? ""),
        category: (i.category as string | undefined) ?? null,
        claimedBy: (i.claimedBy as string | undefined) ?? null,
        doneTripId: (i.doneTripId as string | undefined) ?? null,
        addedBy: String(i.addedBy ?? ""),
        createdAtMillis: millis(i.createdAt) ?? 0,
        sortKey: (i.sortKey as number | undefined) ?? null,
      };
    }),
  );
  const shopper = members.find((m) => m.uid === shopperUid);
  const state = computeTripState(
    {
      tripId,
      status: data.status as TripState["status"],
      shopperUid,
      startedAtMillis: millis(data.startedAt) ?? 0,
      lastCallUntilMillis: millis(data.lastCallUntil),
    },
    items,
    {
      shopperLanguage: shopper?.language ?? "en",
      defaultLanguage: String(household.get("defaultLanguage") ?? "en"),
      memberNames: Object.fromEntries(members.map((m) => [m.uid, m.displayName])),
      nowMillis: now.getTime(),
    },
  );
  const devices = shopperUid ? await devicesOf(db, shopperUid) : [];
  const shopperDevice = devices.find((d) => d.deviceId === data.shopperDeviceId) ?? null;
  const missing = items.filter((i) => i.status === "missing").length;
  return { tripId, data, state, missing, shopperDevice };
}

/** The shopper's live surface: Android `trip_update`, or the iOS Live Activity (update/end). */
export function liveMessages(
  householdId: string,
  trip: LoadedTrip,
  now: Date,
  event: "update" | "end" = "update",
): Outgoing[] {
  const device = trip.shopperDevice;
  if (!device) return [];
  const storeName = String(trip.data.storeName ?? "");
  if (device.platform === "android") {
    return [
      {
        kind: "trip_update",
        device,
        message: tripUpdateMessage(device, householdId, trip.tripId, storeName, trip.state),
      },
    ];
  }
  const token: unknown = trip.data.liveActivityToken;
  if (typeof token !== "string" || !token) return [];
  return [
    {
      kind: `live_activity_${event}`,
      device,
      message: liveActivityMessage({
        fcmToken: device.fcmToken,
        liveActivityToken: token,
        event,
        nowSeconds: Math.floor(now.getTime() / 1000),
        state: trip.state,
      }),
    },
  ];
}

/**
 * Throttled live update after a list change (ARCHITECTURE §3.6). Uses the trip's
 * `lastLiveUpdateAt` (CONTRACT §3.7): within 5 s of the last send, wait out the window, then
 * claim it in a transaction. Only the first claimer sends, and it reads the state *after*
 * claiming, so the newest change always goes out and bursts collapse into one push.
 */
export async function throttledLiveUpdate(
  db: Firestore,
  sender: PushSender,
  householdId: string,
  tripId: string,
  clock: Clock = realClock,
): Promise<"sent" | "skipped" | "inactive"> {
  const ref = db.doc(`households/${householdId}/trips/${tripId}`);
  const first = await ref.get();
  if (!ACTIVE.includes(first.get("status") as (typeof ACTIVE)[number])) return "inactive";
  const seen = millis(first.get("lastLiveUpdateAt")) ?? 0;
  const wait = seen + LIVE_THROTTLE_MS - clock.now().getTime();
  if (wait > 0) await clock.sleep(wait);

  const claimed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if ((millis(snap.get("lastLiveUpdateAt")) ?? 0) !== seen) return false; // someone sent after us
    if (!ACTIVE.includes(snap.get("status") as (typeof ACTIVE)[number])) return false;
    tx.update(ref, { lastLiveUpdateAt: Timestamp.fromDate(clock.now()) });
    return true;
  });
  if (!claimed) return "skipped";

  const now = clock.now();
  const trip = await loadTrip(db, householdId, tripId, now);
  if (!trip) return "inactive";
  await ref.update({ counts: tripCounts(trip) });
  await sender.send(liveMessages(householdId, trip, now));
  return "sent";
}

/** CONTRACT §3.7 `counts`. */
export function tripCounts(trip: LoadedTrip): { total: number; done: number; missing: number } {
  return {
    total: trip.state.remaining + trip.state.done + trip.missing,
    done: trip.state.done,
    missing: trip.missing,
  };
}

/** Active trips that include `listId` (several people can shop at once). */
export async function activeTripsFor(db: Firestore, householdId: string, listId: string): Promise<string[]> {
  const snap = await db
    .collection(`households/${householdId}/trips`)
    .where("status", "in", [...ACTIVE])
    .get();
  return snap.docs
    .filter((d) => ((d.get("listIds") as string[] | undefined) ?? []).includes(listId))
    .map((d) => d.id);
}

/**
 * `list_changed` to the household's devices (widgets refresh while apps are closed), at most
 * once per device per minute (`pushThrottle/{uid}_{deviceId}`, functions only).
 */
export async function listChanged(
  db: Firestore,
  sender: PushSender,
  householdId: string,
  listId: string,
  now: Date,
  exceptUid?: string,
): Promise<number> {
  const members = await membersOf(db, householdId);
  const devices = (
    await Promise.all(members.filter((m) => m.uid !== exceptUid).map((m) => devicesOf(db, m.uid)))
  ).flat();
  const throttles = devices.map((d) => db.doc(`pushThrottle/${d.uid}_${d.deviceId}`));
  const snaps = throttles.length > 0 ? await db.getAll(...throttles) : [];
  const due = devices.filter((_, i) => {
    const last = millis(snaps[i]?.get("listChangedAt")) ?? 0;
    return now.getTime() - last >= LIST_CHANGED_MS;
  });
  if (due.length === 0) return 0;
  const batch = db.batch();
  for (const d of due)
    batch.set(
      db.doc(`pushThrottle/${d.uid}_${d.deviceId}`),
      { listChangedAt: Timestamp.fromDate(now) },
      { merge: true },
    );
  await batch.commit();
  await sender.send(
    due.map((device) => ({
      kind: "list_changed",
      device,
      message: listChangedMessage(device, householdId, listId),
    })),
  );
  log.info("list_changed", { householdId, devices: due.length });
  return due.length;
}
