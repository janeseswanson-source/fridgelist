import { deleteApp, type App } from "firebase-admin/app";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import type { BatchResponse, Message } from "firebase-admin/messaging";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import { createInviteCore, joinHouseholdCore } from "../../src/callables/invites.js";
import { registerLiveActivityTokenCore, startTripCore, updateTripCore } from "../../src/callables/trips.js";
import { closeStaleTripsCore } from "../../src/scheduled/closeStaleTrips.js";
import { FcmSender, RecordingSender } from "../../src/push/sender.js";
import { PushMissingNotifier } from "../../src/trips/alerts.js";
import { throttledLiveUpdate, type Clock } from "../../src/trips/live.js";
import { onItemWrittenCore } from "../../src/triggers/onItemWritten.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("trips"));
});
afterAll(async () => {
  await deleteApp(app);
});

const SHOPPER = "u-ali"; // owner, Arabic UI, Android
const MOM = "u-mom"; // adult, Turkish UI, iOS
const HELPER = "u-maria"; // helper, English UI, Android
const KID = "u-kid";
let hid = "";
let lid = "";

/** A clock tests control; `sleep` advances it. */
class FakeClock implements Clock {
  slept: number[] = [];
  constructor(public t = 1_800_000_000_000) {}
  now() {
    return new Date(this.t);
  }
  sleep(ms: number) {
    this.slept.push(ms);
    this.t += ms;
    return Promise.resolve();
  }
}

async function device(uid: string, platform: "ios" | "android", extra: Record<string, unknown> = {}) {
  await db
    .doc(`users/${uid}/devices/d-${uid}`)
    .set({ platform, fcmToken: `fcm-${uid}`, locale: "x", ...extra });
}

beforeEach(async () => {
  await clearDb();
  ({ householdId: hid, listId: lid } = await createHouseholdCore(db, SHOPPER, {
    name: "Home",
    defaultLanguage: "ar",
    displayName: "Ali",
    language: "ar",
    uiLanguage: "ar",
  }));
  for (const [uid, role, name, lang] of [
    [MOM, "adult", "Anne", "tr"],
    [HELPER, "helper", "Maria", "en"],
    [KID, "kid", "Kid", "ar"],
  ] as const) {
    const { code } = await createInviteCore(db, SHOPPER, { householdId: hid, role });
    await joinHouseholdCore(db, uid, { code, displayName: name, language: lang });
  }
  await db.doc(`users/${MOM}`).update({ uiLanguage: "tr" });
  await db.doc(`users/${HELPER}`).update({ uiLanguage: "en" });
  await device(SHOPPER, "android");
  await device(MOM, "ios", { apnsPushToStartToken: "ptsmom" });
  await device(HELPER, "android");
  await db
    .collection(`households/${hid}/lists/${lid}/items`)
    .doc("milk")
    .set({
      name: "süt",
      lang: "tr",
      names: { ar: "حليب" },
      status: "open",
      category: "dairy_eggs",
      addedBy: MOM,
      createdAt: Timestamp.fromMillis(1),
    });
  await db
    .collection(`households/${hid}/lists/${lid}/items`)
    .doc("labne")
    .set({
      name: "labne",
      lang: "tr",
      status: "missing",
      category: "dairy_eggs",
      addedBy: MOM,
      createdAt: Timestamp.fromMillis(2),
    });
});

const start = (
  data: Record<string, unknown>,
  sender = new RecordingSender(),
  uid = SHOPPER,
  clock = new FakeClock(),
) =>
  startTripCore(
    db,
    uid,
    { householdId: hid, auto: false, platform: "android", deviceId: `d-${uid}`, ...data },
    sender,
    clock,
  );
const kinds = (s: RecordingSender) => s.sent.map((o) => o.kind).sort();

describe("startTrip", () => {
  it("heading: trip, presence, event, and trip_heading to members who want it (each in their language)", async () => {
    const sender = new RecordingSender();
    const { tripId } = await start({ status: "heading", storeName: "Migros", lastCallMinutes: 10 }, sender);
    const trip = (await db.doc(`households/${hid}/trips/${tripId}`).get()).data();
    expect(trip).toMatchObject({
      shopperUid: SHOPPER,
      shopperName: "Ali",
      status: "heading",
      storeName: "Migros",
      listIds: [lid],
      auto: false,
    });
    expect((trip?.lastCallUntil as Timestamp).toMillis()).toBe(1_800_000_000_000 + 600_000);
    expect((await db.doc(`households/${hid}/members/${SHOPPER}`).get()).get("presence")).toMatchObject({
      tripId,
      status: "heading",
    });
    // Mom (iOS, Turkish) and Maria (Android, English); not the shopper, not... the kid has no device.
    expect(kinds(sender)).toEqual(["trip_heading", "trip_heading"]);
    const toMom = sender.sent.find((o) => o.device?.uid === MOM)?.message;
    expect(toMom?.apns?.payload?.aps.alert).toEqual({
      title: "Ali alışverişe çıktı: Migros",
      body: "10 dakika içinde ekleyebilirsin",
    });
    const toMaria = sender.sent.find((o) => o.device?.uid === HELPER)?.message;
    expect(toMaria?.data).toMatchObject({
      type: "trip_heading",
      title: "Ali is heading to Migros",
      tripId,
      listId: lid,
      lastCallUntil: "1800000600",
    });
  });

  it("notify preferences: tripHeading off → no push; trip_arrived only to those who turned it on", async () => {
    await db.doc(`households/${hid}/members/${HELPER}`).update({ "notify.tripHeading": false });
    const heading = new RecordingSender();
    await start({ status: "heading" }, heading);
    expect(heading.sent.map((o) => o.device?.uid)).toEqual([MOM]);
    await db.doc(`households/${hid}/members/${MOM}`).update({ "notify.tripArrived": true });
    const shopping = new RecordingSender();
    await start({ status: "shopping", storeId: "osm_node_1" }, shopping);
    // The new trip ended the heading one: the shopper's Android gets the final trip_update ("done")
    // that clears its ongoing notification; the household alert goes only to Mom.
    expect(shopping.sent.map((o) => `${String(o.device?.uid)}:${o.kind}`).sort()).toEqual(
      [`${MOM}:trip_arrived`, `${SHOPPER}:trip_update`].sort(),
    );
    expect(shopping.sent.find((o) => o.kind === "trip_update")?.message.data?.status).toBe("done");
  });

  it("a new trip ends the shopper's old one (missing items back to open)", async () => {
    const first = await start({ status: "shopping" });
    const second = await start({ status: "heading" });
    expect((await db.doc(`households/${hid}/trips/${first.tripId}`).get()).get("status")).toBe("done");
    expect((await db.doc(`households/${hid}/lists/${lid}/items/labne`).get()).get("status")).toBe("open");
    expect((await db.doc(`households/${hid}/members/${SHOPPER}`).get()).get("presence.tripId")).toBe(
      second.tripId,
    );
  });

  it("store from storeId; validation; kids can't shop (contract v3)", async () => {
    await db.doc("stores/osm_node_9").set({ name: "BİM", chainId: "bim" });
    const { tripId } = await start({ status: "shopping", storeId: "osm_node_9" });
    expect((await db.doc(`households/${hid}/trips/${tripId}`).get()).data()).toMatchObject({
      storeName: "BİM",
      chainId: "bim",
      storeId: "osm_node_9",
    });
    await expectHttpsError(start({ status: "heading" }, new RecordingSender(), KID), "permission-denied");
    await expectHttpsError(start({ status: "heading" }, new RecordingSender(), "u-out"), "permission-denied");
    await expectHttpsError(start({ status: "heading", lastCallMinutes: 7 }), "invalid-argument");
    await expectHttpsError(start({ status: "heading", listIds: ["nope"] }), "invalid-argument");
    await expectHttpsError(start({ status: "done" }), "invalid-argument");
  });

  it("an auto trip on iOS starts the Live Activity by push-to-start", async () => {
    const sender = new RecordingSender();
    await start({ status: "shopping", auto: true, platform: "ios", storeName: "Migros" }, sender, MOM);
    const live = sender.sent.find((o) => o.kind === "live_activity_start")?.message;
    expect(live?.apns?.liveActivityToken).toBe("ptsmom");
    expect(live?.apns?.payload?.aps).toMatchObject({
      event: "start",
      "attributes-type": "ShoppingAttributes",
      alert: { title: "Migros alışverişi", body: "Listende 1 ürün var" },
      "content-state": { status: "shopping", remaining: 1, done: 0, nextItems: ["süt"] },
    });
  });
});

describe("updateTrip and registerLiveActivityToken", () => {
  it("heading → shopping: trip_arrived and a live update to the shopper's Android", async () => {
    const { tripId } = await start({ status: "heading", storeName: "Migros" });
    await db.doc(`households/${hid}/members/${HELPER}`).update({ "notify.tripArrived": true });
    const sender = new RecordingSender();
    expect(
      await updateTripCore(
        db,
        SHOPPER,
        { householdId: hid, tripId, status: "shopping" },
        sender,
        new FakeClock(),
      ),
    ).toEqual({ ok: true });
    expect(kinds(sender)).toEqual(["trip_arrived", "trip_update"]);
    const update = sender.sent.find((o) => o.kind === "trip_update")?.message.data;
    expect(update).toMatchObject({
      status: "shopping",
      remaining: "1",
      done: "0",
      nextItems: JSON.stringify(["حليب"]),
    });
    expect(
      await updateTripCore(db, SHOPPER, { householdId: hid, tripId, status: "shopping" }, sender),
    ).toEqual({ ok: true });
  });

  it("done: summary, missing reopened, presence gone, final trip_update; then failed-precondition", async () => {
    const { tripId } = await start({ status: "shopping" });
    await db.doc(`households/${hid}/lists/${lid}/items/milk`).update({ status: "done", doneTripId: tripId });
    const sender = new RecordingSender();
    const result = await updateTripCore(db, SHOPPER, { householdId: hid, tripId, status: "done" }, sender);
    expect(result).toEqual({ ok: true, summary: { done: 1, missing: 1 } });
    expect((await db.doc(`households/${hid}/trips/${tripId}`).get()).data()).toMatchObject({
      status: "done",
      counts: { done: 1, missing: 1 },
    });
    expect((await db.doc(`households/${hid}/lists/${lid}/items/labne`).get()).get("status")).toBe("open");
    expect((await db.doc(`households/${hid}/members/${SHOPPER}`).get()).get("presence")).toBeUndefined();
    expect(sender.sent.find((o) => o.kind === "trip_update")?.message.data?.status).toBe("done");
    await expectHttpsError(
      updateTripCore(db, SHOPPER, { householdId: hid, tripId, status: "done" }, sender),
      "failed-precondition",
    );
  });

  it("shopper only; unknown trips", async () => {
    const { tripId } = await start({ status: "shopping" });
    await expectHttpsError(
      updateTripCore(db, MOM, { householdId: hid, tripId, status: "done" }, new RecordingSender()),
      "permission-denied",
    );
    await expectHttpsError(
      updateTripCore(
        db,
        SHOPPER,
        { householdId: hid, tripId: "nope", status: "done" },
        new RecordingSender(),
      ),
      "not-found",
    );
  });

  it("iOS activity token: hex, shopper only, active trips; then update/end go to it", async () => {
    const { tripId } = await start({ status: "shopping", platform: "ios" }, new RecordingSender(), MOM);
    await expectHttpsError(
      registerLiveActivityTokenCore(db, MOM, { householdId: hid, tripId, token: "not hex!" }),
      "invalid-argument",
    );
    await expectHttpsError(
      registerLiveActivityTokenCore(db, SHOPPER, { householdId: hid, tripId, token: "abcdef0123456789" }),
      "permission-denied",
    );
    await registerLiveActivityTokenCore(db, MOM, { householdId: hid, tripId, token: "ABCDEF0123456789" });
    const sender = new RecordingSender();
    await updateTripCore(db, MOM, { householdId: hid, tripId, status: "done" }, sender);
    const end = sender.sent.find((o) => o.kind === "live_activity_end")?.message;
    expect(end?.apns?.liveActivityToken).toBe("abcdef0123456789");
    expect(end?.apns?.payload?.aps).toMatchObject({
      event: "end",
      "dismissal-date": expect.any(Number) as number,
    });
  });
});

describe("live update throttle (≤ 1 per trip per 5 s, newest state)", () => {
  it("first change sends at once; a change 2 s later waits out the window and sends the newest state", async () => {
    const clock = new FakeClock();
    const { tripId } = await start({ status: "shopping" }, new RecordingSender(), SHOPPER, clock);
    const sender = new RecordingSender();
    expect(await throttledLiveUpdate(db, sender, hid, tripId, clock)).toBe("sent");
    clock.t += 2_000;
    await db.doc(`households/${hid}/lists/${lid}/items/milk`).update({ status: "done", doneTripId: tripId });
    expect(await throttledLiveUpdate(db, sender, hid, tripId, clock)).toBe("sent");
    expect(clock.slept).toEqual([3_000]);
    expect(sender.sent.map((o) => o.message.data?.done)).toEqual(["0", "1"]);
  });

  it("a burst inside one window collapses into one push", async () => {
    const clock = new FakeClock();
    const { tripId } = await start({ status: "shopping" }, new RecordingSender(), SHOPPER, clock);
    const sender = new RecordingSender();
    await throttledLiveUpdate(db, sender, hid, tripId, clock);
    clock.t += 1_000;
    // Three triggers observe the same window; each waits, only the first claim sends.
    const results = await Promise.all(
      [1, 2, 3].map(() => throttledLiveUpdate(db, sender, hid, tripId, clock)),
    );
    expect(results.filter((r) => r === "sent")).toHaveLength(1);
    expect(sender.sent).toHaveLength(2);
  });

  it("ended trips get no live updates", async () => {
    const { tripId } = await start({ status: "shopping" });
    await updateTripCore(
      db,
      SHOPPER,
      { householdId: hid, tripId, status: "cancelled" },
      new RecordingSender(),
    );
    expect(await throttledLiveUpdate(db, new RecordingSender(), hid, tripId, new FakeClock())).toBe(
      "inactive",
    );
  });
});

describe("item fan-out, item_missing, stale trips, dead tokens", () => {
  it("list_changed ≤ 1 per device per minute, never to the adder; active trips get a live update", async () => {
    const clock = new FakeClock();
    await start({ status: "shopping" }, new RecordingSender(), SHOPPER, clock);
    const sender = new RecordingSender();
    await onItemWrittenCore(db, sender, { householdId: hid, listId: lid }, MOM, clock);
    expect(
      sender.sent
        .filter((o) => o.kind === "list_changed")
        .map((o) => o.device?.uid)
        .sort(),
    ).toEqual([HELPER, SHOPPER].sort());
    expect(sender.sent.filter((o) => o.kind === "trip_update")).toHaveLength(1);
    const again = new RecordingSender();
    clock.t += 30_000;
    await onItemWrittenCore(db, again, { householdId: hid, listId: lid }, undefined, clock);
    expect(again.sent.filter((o) => o.kind === "list_changed").map((o) => o.device?.uid)).toEqual([MOM]);
  });

  it("item_missing: to the adder, in their language, with the item in their display language", async () => {
    const { tripId } = await start({ status: "shopping" });
    const sender = new RecordingSender();
    await new PushMissingNotifier(db, sender).itemMissing({
      householdId: hid,
      listId: lid,
      itemId: "labne",
      tripId,
      recipientUid: MOM,
    });
    expect(sender.sent[0]?.message.apns?.payload?.aps).toMatchObject({
      alert: { title: "labne bulunamadı", body: "Listede kalıyor" },
      category: "ITEM_MISSING",
    });
    expect(sender.sent[0]?.message.data).toEqual({
      type: "item_missing",
      householdId: hid,
      itemId: "labne",
      listId: lid,
      tripId,
    });
  });

  it("closeStaleTrips ends trips older than 4 hours only", async () => {
    const clock = new FakeClock();
    const old = await start({ status: "shopping" }, new RecordingSender(), SHOPPER, clock);
    const fresh = await start(
      { status: "heading" },
      new RecordingSender(),
      MOM,
      new FakeClock(clock.t + 4 * 3_600_000),
    );
    expect(
      await closeStaleTripsCore(db, new RecordingSender(), new Date(clock.t + 4 * 3_600_000 + 60_000)),
    ).toBe(1);
    expect((await db.doc(`households/${hid}/trips/${old.tripId}`).get()).get("status")).toBe("done");
    expect((await db.doc(`households/${hid}/trips/${fresh.tripId}`).get()).get("status")).toBe("heading");
  });

  it("FCM: a token reported unregistered removes that device doc", async () => {
    const fake = (messages: Message[]): Promise<BatchResponse> =>
      Promise.resolve({
        successCount: messages.length - 1,
        failureCount: 1,
        responses: messages.map((_, i) =>
          i === 0
            ? { success: false, error: { code: "messaging/registration-token-not-registered" } as never }
            : { success: true, messageId: "m" },
        ),
      });
    await new FcmSender(db, fake).send([
      { kind: "x", device: { uid: MOM, deviceId: `d-${MOM}` }, message: { token: "dead" } },
      { kind: "x", device: { uid: HELPER, deviceId: `d-${HELPER}` }, message: { token: "fine" } },
    ]);
    expect((await db.doc(`users/${MOM}/devices/d-${MOM}`).get()).exists).toBe(false);
    expect((await db.doc(`users/${HELPER}/devices/d-${HELPER}`).get()).exists).toBe(true);
  });
});
