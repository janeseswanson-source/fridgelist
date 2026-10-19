import { deleteApp, type App } from "firebase-admin/app";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import { createInviteCore, joinHouseholdCore } from "../../src/callables/invites.js";
import {
  createShareLinkCore,
  guestAddItemCore,
  GUEST_ADDS_PER_HOUR,
  resolveShareLinkCore,
  revokeShareLinkCore,
  stripUrls,
  TOKEN,
} from "../../src/callables/shareLinks.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("shareLinks"));
});
afterAll(async () => {
  await deleteApp(app);
});

const OWNER = "u-owner";
const HELPER = "u-helper";
let hid = "";
let lid = "";
const NOW = new Date(1_800_000_000_000);

beforeEach(async () => {
  await clearDb();
  ({ householdId: hid, listId: lid } = await createHouseholdCore(db, OWNER, {
    name: "بيت العائلة",
    defaultLanguage: "ar",
    displayName: "Mama",
    language: "ar",
    uiLanguage: "ar",
  }));
  const { code } = await createInviteCore(db, OWNER, { householdId: hid, role: "helper" });
  await joinHouseholdCore(db, HELPER, { code, displayName: "Maria", language: "en" });
  const items = db.collection(`households/${hid}/lists/${lid}/items`);
  await items.doc("a").set({
    name: "süt",
    lang: "tr",
    names: { ar: "حليب", en: "Milk" },
    status: "open",
    category: "dairy_eggs",
    qty: 2,
    unit: "l",
    addedBy: "x",
  });
  await items
    .doc("b")
    .set({ name: "بندورة", lang: "ar", status: "claimed", category: "produce", qty: 3, addedBy: "x" });
  await items.doc("c").set({ name: "secret done item", status: "done", category: "other", addedBy: "x" });
  await items.doc("d").set({ name: "kid request", status: "pending", category: "other", addedBy: "x" });
});

const link = (data: Record<string, unknown> = {}, uid = OWNER) =>
  createShareLinkCore(db, uid, { householdId: hid, listId: lid, scope: "add", kind: "nfc", ...data }, NOW);

describe("createShareLink / revokeShareLink", () => {
  it("owner or adult; 24 URL-safe characters; the link doc per CONTRACT §3.11", async () => {
    const { token, url } = await link({ expiresInDays: 30 });
    expect(token).toMatch(TOKEN);
    expect(url).toBe(`https://links.example.com/k/${token}`);
    expect((await db.doc(`shareLinks/${token}`).get()).data()).toMatchObject({
      householdId: hid,
      listId: lid,
      scope: "add",
      kind: "nfc",
      createdBy: OWNER,
      revoked: false,
      guestAddsInWindow: 0,
      expiresAt: Timestamp.fromMillis(NOW.getTime() + 30 * 86_400_000),
    });
    await expectHttpsError(link({}, HELPER), "permission-denied");
    await expectHttpsError(link({ listId: "nope" }), "not-found");
    await expectHttpsError(link({ scope: "edit" }), "invalid-argument");
  });

  it("revoked links stop working; only the owning household can revoke", async () => {
    const { token } = await link();
    await expectHttpsError(revokeShareLinkCore(db, HELPER, { householdId: hid, token }), "permission-denied");
    await revokeShareLinkCore(db, OWNER, { householdId: hid, token });
    await expectHttpsError(resolveShareLinkCore(db, { token }, NOW), "not-found");
  });
});

describe("resolveShareLink (public)", () => {
  it("only open and claimed items, titled in the requested language, nothing else", async () => {
    const { token } = await link({ scope: "view" });
    const r = await resolveShareLinkCore(db, { token, lang: "en-GB" }, NOW);
    expect(r).toEqual({
      householdName: "بيت العائلة",
      listName: "البقالة",
      scope: "view",
      items: [
        { id: "b", title: "بندورة", subtitle: "×3", category: "produce", status: "claimed" },
        { id: "a", title: "Milk", subtitle: "2 L", category: "dairy_eggs", status: "open" },
      ],
    });
    // No language → the household's default (Arabic).
    expect((await resolveShareLinkCore(db, { token }, NOW)).items.map((i) => i.title)).toEqual([
      "بندورة",
      "حليب",
    ]);
  });

  it("expired, unknown and malformed tokens", async () => {
    const { token } = await link({ expiresInDays: 1 });
    await expectHttpsError(
      resolveShareLinkCore(db, { token }, new Date(NOW.getTime() + 86_400_000)),
      "not-found",
    );
    await expectHttpsError(resolveShareLinkCore(db, { token: "A".repeat(24) }, NOW), "not-found");
    await expectHttpsError(resolveShareLinkCore(db, { token: "short" }, NOW), "invalid-argument");
  });
});

describe("guestAddItem (public)", () => {
  it("splits like any add and writes guest_web items by guest:<token>; event without user ids", async () => {
    const { token } = await link();
    expect(await guestAddItemCore(db, { token, text: "٢ خبز وجبنة", guestName: "Ahmet" }, NOW)).toEqual({
      added: 2,
    });
    const added = (
      await db.collection(`households/${hid}/lists/${lid}/items`).where("addedVia", "==", "guest_web").get()
    ).docs.map((d) => d.data());
    expect(added).toHaveLength(2);
    expect(added).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ name: "خبز", qty: 2, status: "open", addedBy: `guest:${token}` }),
        expect.objectContaining({ name: "جبنة", status: "open" }),
      ]),
    );
    const events = (await db.collection(`households/${hid}/events`).where("type", "==", "guest_added").get())
      .docs;
    expect(events[0]?.data()).toMatchObject({ actorName: "Ahmet", via: "guest_web" });
  });

  it("view-only links can't add; URLs are stripped; empty after stripping is invalid", async () => {
    const view = await link({ scope: "view" });
    await expectHttpsError(
      guestAddItemCore(db, { token: view.token, text: "milk" }, NOW),
      "permission-denied",
    );
    const { token } = await link();
    await expectHttpsError(
      guestAddItemCore(db, { token, text: "https://spam.example/buy" }, NOW),
      "invalid-argument",
    );
    await expectHttpsError(guestAddItemCore(db, { token, text: "x".repeat(201) }, NOW), "invalid-argument");
    await guestAddItemCore(db, { token, text: "milk www.spam.com", guestName: "visit spam.com" }, NOW);
    const names = (
      await db.collection(`households/${hid}/lists/${lid}/items`).where("addedVia", "==", "guest_web").get()
    ).docs.map((d) => d.get("name") as string);
    expect(names).toEqual(["milk"]);
  });

  it(`at most ${String(GUEST_ADDS_PER_HOUR)} items per hour per link, then the window resets`, async () => {
    const { token } = await link();
    for (let i = 0; i < 4; i++) {
      await guestAddItemCore(db, { token, text: "a, b, c, d, e" }, new Date(NOW.getTime() + i * 60_000));
    }
    await expectHttpsError(
      guestAddItemCore(db, { token, text: "one more" }, new Date(NOW.getTime() + 10 * 60_000)),
      "resource-exhausted",
    );
    expect(
      await guestAddItemCore(db, { token, text: "later" }, new Date(NOW.getTime() + 61 * 60_000)),
    ).toEqual({ added: 1 });
  });
});

describe("stripUrls", () => {
  it.each([
    ["milk https://x.co/a bread", "milk bread"],
    ["see www.example.com now", "see now"],
    ["buy at shop.com.tr/sale", "buy at"],
    ["süt ve ekmek", "süt ve ekmek"],
    ["2.5 kg rice", "2.5 kg rice"],
  ])("%s → %s", (input, output) => {
    expect(stripUrls(input)).toBe(output);
  });
});
