import { Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import { format } from "../i18n/index.js";
import { writeEvent } from "../lib/events.js";
import { log } from "../lib/log.js";
import { parse, requireMember, requireUid } from "../lib/requests.js";
import { id } from "../lib/schemas.js";
import { liveActivityMessage } from "../push/messages.js";
import { devicesOf } from "../push/recipients.js";
import { defaultSender, type PushSender } from "../push/sender.js";
import { sendTripAlert } from "../trips/alerts.js";
import { endTrip, type TripSummary } from "../trips/endTrip.js";
import { ACTIVE, liveMessages, loadTrip, realClock, type Clock } from "../trips/live.js";

/** CONTRACT §4 `startTrip`. */
export const StartTripRequest = z.object({
  householdId: id,
  status: z.enum(["heading", "shopping"]),
  auto: z.boolean(),
  storeId: id.optional(),
  storeName: z.string().trim().max(80).optional(),
  lastCallMinutes: z.union([z.literal(0), z.literal(5), z.literal(10), z.literal(15)]).optional(),
  listIds: z.array(id).min(1).max(20).optional(),
  platform: z.enum(["ios", "android", "web"]),
  deviceId: z.string().min(1).max(128),
});

/**
 * One active trip per shopper: a new trip ends the old one. Kids can't shop (CONTRACT v3).
 * Without `listIds` the trip covers every list that isn't archived. Members hear about it per
 * their notify preferences; an auto-detected trip on iOS starts the Live Activity by push.
 */
export async function startTripCore(
  db: Firestore,
  uid: string,
  data: unknown,
  sender: PushSender,
  clock: Clock = realClock,
): Promise<{ tripId: string }> {
  const input = parse(StartTripRequest, data);
  const member = await requireMember(db, input.householdId, uid);
  if (member.role === "kid") throw new HttpsError("permission-denied", "Kids can't start trips.");
  const now = clock.now();
  const household = db.doc(`households/${input.householdId}`);

  const lists = await household.collection("lists").get();
  const listIds = input.listIds ?? lists.docs.filter((d) => d.get("archived") !== true).map((d) => d.id);
  const known = new Set(lists.docs.map((d) => d.id));
  if (listIds.length === 0 || listIds.some((l) => !known.has(l)))
    throw new HttpsError("invalid-argument", "Invalid: listIds");

  const store = input.storeId ? await db.doc(`stores/${input.storeId}`).get() : null;
  const storeName = input.storeName ?? (store?.get("name") as string | undefined) ?? "";
  const chainId = store?.get("chainId") as string | undefined;

  const previous = await household
    .collection("trips")
    .where("shopperUid", "==", uid)
    .where("status", "in", [...ACTIVE])
    .get();
  for (const old of previous.docs) await endTrip(db, sender, input.householdId, old.id, "done", now);

  const minutes = input.status === "heading" ? (input.lastCallMinutes ?? 0) : 0;
  const lastCallUntil = minutes > 0 ? Timestamp.fromMillis(now.getTime() + minutes * 60_000) : null;
  const ref = household.collection("trips").doc();
  await ref.set({
    shopperUid: uid,
    shopperName: member.displayName,
    shopperPlatform: input.platform,
    shopperDeviceId: input.deviceId,
    status: input.status,
    ...(input.storeId ? { storeId: input.storeId } : {}),
    storeName,
    ...(chainId ? { chainId } : {}),
    auto: input.auto,
    listIds,
    ...(lastCallUntil ? { lastCallUntil } : {}),
    startedAt: Timestamp.fromDate(now),
    ...(input.status === "shopping" ? { arrivedAt: Timestamp.fromDate(now) } : {}),
    counts: { total: 0, done: 0, missing: 0 },
  });
  await household
    .collection("members")
    .doc(uid)
    .update({
      presence: { tripId: ref.id, status: input.status, storeName, since: Timestamp.fromDate(now) },
    });
  const eventType = input.status === "heading" ? "trip_heading" : "trip_arrived";
  await writeEvent(
    db,
    input.householdId,
    { type: eventType, actorUid: uid, actorName: member.displayName, tripId: ref.id },
    now,
  );

  const firstList = listIds[0] ?? "";
  await sendTripAlert(db, sender, eventType, {
    householdId: input.householdId,
    tripId: ref.id,
    shopperUid: uid,
    shopperName: member.displayName,
    storeName,
    listId: firstList,
    ...(lastCallUntil
      ? { lastCallUntil: Math.floor(lastCallUntil.toMillis() / 1000), lastCallMinutes: minutes }
      : {}),
  });

  // CONTRACT §5.2: auto-detected trips start the iOS Live Activity by push-to-start (iOS 17.2+).
  if (input.auto && input.platform === "ios") {
    const device = (await devicesOf(db, uid)).find((d) => d.deviceId === input.deviceId);
    const trip = await loadTrip(db, input.householdId, ref.id, now);
    if (device?.apnsPushToStartToken && trip) {
      const store = storeName || format(device.uiLanguage, "unknownStore");
      await sender.send([
        {
          kind: "live_activity_start",
          device,
          message: liveActivityMessage({
            fcmToken: device.fcmToken,
            liveActivityToken: device.apnsPushToStartToken,
            event: "start",
            nowSeconds: Math.floor(now.getTime() / 1000),
            state: trip.state,
            attributes: {
              tripId: ref.id,
              householdId: input.householdId,
              storeName,
              chainId: chainId ?? "other",
            },
            alert: {
              title: format(device.uiLanguage, "liveStartTitle", { store }),
              body: format(
                device.uiLanguage,
                "liveStartBody",
                { count: trip.state.remaining },
                trip.state.remaining,
              ),
            },
          }),
        },
      ]);
    }
  }
  log.info("trip_started", {
    householdId: input.householdId,
    status: input.status,
    auto: input.auto,
    ended: previous.size,
  });
  return { tripId: ref.id };
}

export const UpdateTripRequest = z.object({
  householdId: id,
  tripId: id,
  status: z.enum(["shopping", "done", "cancelled"]),
});

/** Shopper only. heading → shopping (arrived); → done/cancelled ends it (`failed-precondition` once ended). */
export async function updateTripCore(
  db: Firestore,
  uid: string,
  data: unknown,
  sender: PushSender,
  clock: Clock = realClock,
): Promise<{ ok: true; summary?: TripSummary }> {
  const input = parse(UpdateTripRequest, data);
  await requireMember(db, input.householdId, uid);
  const ref = db.doc(`households/${input.householdId}/trips/${input.tripId}`);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", "No such trip.");
  if (snap.get("shopperUid") !== uid)
    throw new HttpsError("permission-denied", "Only the shopper updates a trip.");
  const status: unknown = snap.get("status");
  if (status !== "heading" && status !== "shopping")
    throw new HttpsError("failed-precondition", "Trip already ended.");
  const now = clock.now();

  if (input.status !== "shopping") {
    const summary = await endTrip(db, sender, input.householdId, input.tripId, input.status, now);
    if (!summary) throw new HttpsError("failed-precondition", "Trip already ended.");
    return { ok: true, summary };
  }
  if (status === "shopping") return { ok: true }; // already there (repeated tap)

  await ref.update({ status: "shopping", arrivedAt: Timestamp.fromDate(now) });
  await db.doc(`households/${input.householdId}/members/${uid}`).update({
    "presence.status": "shopping",
    "presence.since": Timestamp.fromDate(now),
  });
  const shopperName = String(snap.get("shopperName") ?? "");
  await writeEvent(
    db,
    input.householdId,
    { type: "trip_arrived", actorUid: uid, actorName: shopperName, tripId: input.tripId },
    now,
  );
  await sendTripAlert(db, sender, "trip_arrived", {
    householdId: input.householdId,
    tripId: input.tripId,
    shopperUid: uid,
    shopperName,
    storeName: String(snap.get("storeName") ?? ""),
    listId: ((snap.get("listIds") as string[] | undefined) ?? [])[0] ?? "",
  });
  const trip = await loadTrip(db, input.householdId, input.tripId, now);
  if (trip) await sender.send(liveMessages(input.householdId, trip, now));
  return { ok: true };
}

export const RegisterLiveActivityTokenRequest = z.object({
  householdId: id,
  tripId: id,
  token: z.string().regex(/^[0-9a-fA-F]{16,512}$/),
});

/** iOS: the activity's push token (hex), so the server can update and end it (CONTRACT §5.2). */
export async function registerLiveActivityTokenCore(
  db: Firestore,
  uid: string,
  data: unknown,
): Promise<{ ok: true }> {
  const input = parse(RegisterLiveActivityTokenRequest, data);
  await requireMember(db, input.householdId, uid);
  const ref = db.doc(`households/${input.householdId}/trips/${input.tripId}`);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", "No such trip.");
  if (snap.get("shopperUid") !== uid) throw new HttpsError("permission-denied", "Only the shopper's device.");
  if (!ACTIVE.includes(snap.get("status") as (typeof ACTIVE)[number]))
    throw new HttpsError("failed-precondition", "Trip already ended.");
  await ref.update({ liveActivityToken: input.token.toLowerCase() });
  return { ok: true };
}

export const startTrip = onCall((request) =>
  startTripCore(getFirestore(), requireUid(request), request.data, defaultSender(getFirestore())),
);
export const updateTrip = onCall((request) =>
  updateTripCore(getFirestore(), requireUid(request), request.data, defaultSender(getFirestore())),
);
export const registerLiveActivityToken = onCall((request) =>
  registerLiveActivityTokenCore(getFirestore(), requireUid(request), request.data),
);
