import { deleteApp, type App } from "firebase-admin/app";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { ExportStore } from "../../src/account/exportStore.js";
import { deleteMyAccountCore, exportMyDataCore } from "../../src/callables/account.js";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import { createInviteCore, joinHouseholdCore } from "../../src/callables/invites.js";
import { createShareLinkCore } from "../../src/callables/shareLinks.js";
import { RecordingSender } from "../../src/push/sender.js";
import { cleanupLinksCore } from "../../src/scheduled/cleanupLinks.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("account"));
});
afterAll(async () => {
  await deleteApp(app);
});

const NOW = new Date(1_800_000_000_000);
const OWNER = "u-owner";
const ADULT = "u-adult";
const KID = "u-kid";

class FakeStore implements ExportStore {
  saved: { path: string; json: string; validForMs: number }[] = [];
  deleted: { prefix: string; olderThanMs: number }[] = [];
  save(path: string, json: string, validForMs: number) {
    this.saved.push({ path, json, validForMs });
    return Promise.resolve(`https://signed.example/${path}`);
  }
  deleteOlderThan(prefix: string, olderThanMs: number) {
    this.deleted.push({ prefix, olderThanMs });
    return Promise.resolve(0);
  }
}

async function household(owner = OWNER, name = "Home") {
  const { householdId, listId } = await createHouseholdCore(db, owner, {
    name,
    defaultLanguage: "en",
    displayName: owner,
    language: "en",
    uiLanguage: "en",
  });
  return { hid: householdId, lid: listId };
}

async function join(hid: string, uid: string, role: "adult" | "kid" | "helper") {
  const { code } = await createInviteCore(db, OWNER, { householdId: hid, role });
  await joinHouseholdCore(db, uid, { code, displayName: uid, language: "tr" });
}

beforeEach(async () => {
  await clearDb();
});

describe("exportMyData", () => {
  it("the caller's own data, readable JSON, no push tokens, a 24 h URL", async () => {
    const { hid, lid } = await household();
    await join(hid, ADULT, "adult");
    await db
      .doc(`users/${OWNER}/devices/d1`)
      .set({ platform: "android", fcmToken: "secret-token", locale: "ar-SA" });
    const items = db.collection(`households/${hid}/lists/${lid}/items`);
    await items
      .doc("mine")
      .set({ name: "süt", addedBy: OWNER, status: "open", createdAt: Timestamp.fromDate(NOW) });
    await items.doc("theirs").set({ name: "çay", addedBy: ADULT, status: "open" });
    await db
      .collection(`households/${hid}/purchases`)
      .add({ name: "ekmek", by: OWNER, at: Timestamp.fromDate(NOW) });
    await db
      .collection(`households/${hid}/purchases`)
      .add({ name: "peynir", by: ADULT, at: Timestamp.fromDate(NOW) });

    const store = new FakeStore();
    const { url } = await exportMyDataCore(db, OWNER, store, NOW);
    expect(url).toBe(`https://signed.example/exports/${OWNER}/${String(NOW.getTime())}.json`);
    expect(store.saved[0]?.validForMs).toBe(24 * 3_600_000);
    const json = store.saved[0]?.json ?? "";
    expect(json).not.toContain("secret-token");
    expect(json).not.toContain("çay");
    expect(json).not.toContain("peynir");
    const data = JSON.parse(json) as {
      devices: Record<string, unknown>[];
      households: {
        householdName: string;
        itemsYouAdded: { name: string; createdAt: string }[];
        yourPurchases: unknown[];
      }[];
    };
    expect(data.devices).toEqual([{ deviceId: "d1", platform: "android", locale: "ar-SA" }]);
    expect(data.households[0]).toMatchObject({
      householdName: "Home",
      yourPurchases: [expect.objectContaining({ name: "ekmek" })],
    });
    expect(data.households[0]?.itemsYouAdded[0]).toMatchObject({ name: "süt", createdAt: NOW.toISOString() });
  });

  it("3 exports per day", async () => {
    await household();
    const store = new FakeStore();
    for (let i = 0; i < 3; i++) await exportMyDataCore(db, OWNER, store, NOW);
    await expectHttpsError(exportMyDataCore(db, OWNER, store, NOW), "resource-exhausted");
  });
});

describe("deleteMyAccount", () => {
  const deleted: string[] = [];
  const run = (uid: string, data: Record<string, unknown> = {}, store = new FakeStore()) =>
    deleteMyAccountCore(
      db,
      uid,
      data,
      new RecordingSender(),
      (u) => {
        deleted.push(u);
        return Promise.resolve();
      },
      store,
      NOW,
    );

  it("the last member's household is deleted with everything in it, its links and invites", async () => {
    const { hid, lid } = await household();
    await db
      .collection(`households/${hid}/lists/${lid}/items`)
      .add({ name: "x", addedBy: OWNER, status: "open" });
    await createShareLinkCore(db, OWNER, { householdId: hid, listId: lid, scope: "view", kind: "qr" }, NOW);
    await createInviteCore(db, OWNER, { householdId: hid, role: "adult" });
    await db.doc(`users/${OWNER}/devices/d1`).set({ platform: "ios", fcmToken: "t" });
    await db.doc(`rateLimits/parseItems:${OWNER}`).set({ count: 1 });
    await db.doc(`pushThrottle/${OWNER}_d1`).set({ listChangedAt: Timestamp.fromDate(NOW) });
    const store = new FakeStore();

    expect(await run(OWNER, {}, store)).toEqual({ ok: true });
    expect((await db.doc(`households/${hid}`).get()).exists).toBe(false);
    expect((await db.collection(`households/${hid}/lists/${lid}/items`).get()).size).toBe(0);
    expect((await db.collection("shareLinks").get()).size).toBe(0);
    expect((await db.collection("invites").get()).size).toBe(0);
    expect((await db.doc(`users/${OWNER}`).get()).exists).toBe(false);
    expect((await db.doc(`users/${OWNER}/devices/d1`).get()).exists).toBe(false);
    expect((await db.doc(`rateLimits/parseItems:${OWNER}`).get()).exists).toBe(false);
    expect((await db.doc(`pushThrottle/${OWNER}_d1`).get()).exists).toBe(false);
    expect(store.deleted).toEqual([{ prefix: `exports/${OWNER}/`, olderThanMs: 0 }]);
    expect(deleted).toContain(OWNER);
  });

  it("an owner with other members must hand over to a non-kid member; nothing changes otherwise", async () => {
    const { hid } = await household();
    await join(hid, ADULT, "adult");
    await join(hid, KID, "kid");
    await expectHttpsError(run(OWNER), "failed-precondition");
    await expectHttpsError(run(OWNER, { transferOwnershipTo: KID }), "failed-precondition");
    await expectHttpsError(run(OWNER, { transferOwnershipTo: "u-stranger" }), "failed-precondition");
    expect((await db.doc(`households/${hid}`).get()).get("ownerUid")).toBe(OWNER);
    expect((await db.doc(`users/${OWNER}`).get()).exists).toBe(true);
  });

  it("handing over: new owner, membership gone, name removed from history, active trip cancelled", async () => {
    const { hid, lid } = await household();
    await join(hid, ADULT, "adult");
    await db
      .collection(`households/${hid}/events`)
      .add({ type: "item_added", actorUid: OWNER, actorName: "Mama" });
    await db
      .collection(`households/${hid}/trips`)
      .doc("t1")
      .set({
        shopperUid: OWNER,
        shopperName: "Mama",
        status: "shopping",
        listIds: [lid],
        startedAt: Timestamp.fromDate(NOW),
      });
    await db
      .collection(`households/${hid}/lists/${lid}/items`)
      .add({ name: "süt", addedBy: OWNER, status: "open" });

    await run(OWNER, { transferOwnershipTo: ADULT });
    expect((await db.doc(`households/${hid}`).get()).get("ownerUid")).toBe(ADULT);
    expect((await db.doc(`households/${hid}/members/${ADULT}`).get()).get("role")).toBe("owner");
    expect((await db.doc(`households/${hid}/members/${OWNER}`).get()).exists).toBe(false);
    const trip = (await db.doc(`households/${hid}/trips/t1`).get()).data();
    expect(trip).toMatchObject({ status: "cancelled", shopperName: "" });
    const events = (await db.collection(`households/${hid}/events`).where("actorUid", "==", OWNER).get())
      .docs;
    expect(events.every((e) => e.get("actorName") === "")).toBe(true);
    // The household's list keeps what was added (others rely on it).
    expect((await db.collection(`households/${hid}/lists/${lid}/items`).get()).size).toBe(1);
  });

  it("a non-owner just leaves", async () => {
    const { hid } = await household();
    await join(hid, ADULT, "adult");
    await run(ADULT);
    expect((await db.doc(`households/${hid}/members/${ADULT}`).get()).exists).toBe(false);
    expect((await db.doc(`households/${hid}`).get()).get("ownerUid")).toBe(OWNER);
  });
});

describe("cleanupLinks (daily)", () => {
  it("revoked and expired links and expired invites go; live ones stay; old exports are cleaned", async () => {
    const { hid, lid } = await household();
    const live = await createShareLinkCore(
      db,
      OWNER,
      { householdId: hid, listId: lid, scope: "add", kind: "nfc" },
      NOW,
    );
    const expiring = await createShareLinkCore(
      db,
      OWNER,
      { householdId: hid, listId: lid, scope: "add", kind: "nfc", expiresInDays: 1 },
      NOW,
    );
    const revoked = await createShareLinkCore(
      db,
      OWNER,
      { householdId: hid, listId: lid, scope: "view", kind: "qr" },
      NOW,
    );
    await db.doc(`shareLinks/${revoked.token}`).update({ revoked: true });
    await db
      .doc("invites/OLDOLD23")
      .set({ householdId: hid, expiresAt: Timestamp.fromMillis(NOW.getTime() - 1) });
    await db
      .doc("invites/NEWNEW23")
      .set({ householdId: hid, expiresAt: Timestamp.fromMillis(NOW.getTime() + 86_400_000) });
    const store = new FakeStore();

    const result = await cleanupLinksCore(db, store, new Date(NOW.getTime() + 2 * 86_400_000));
    expect(result).toEqual({ links: 2, invites: 2, exports: 0 }); // both invites are past expiry 2 days later
    expect((await db.doc(`shareLinks/${live.token}`).get()).exists).toBe(true);
    expect((await db.doc(`shareLinks/${expiring.token}`).get()).exists).toBe(false);
    expect((await db.doc("invites/NEWNEW23").get()).exists).toBe(false); // expired by then too
    expect(store.deleted).toEqual([{ prefix: "exports/", olderThanMs: 24 * 3_600_000 }]);
  });
});
