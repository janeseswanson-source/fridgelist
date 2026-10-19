import { deleteApp, type App } from "firebase-admin/app";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import { createInviteCore, joinHouseholdCore } from "../../src/callables/invites.js";
import { parseItemsCore } from "../../src/callables/parseItems.js";
import { transcribeAudioCore } from "../../src/callables/transcribeAudio.js";
import { matchKey, aliasId } from "../../src/lib/normalize.js";
import { SpeechUnavailableError, type SpeechToText } from "../../src/stt/speechToText.js";
import { onItemCreatedCore } from "../../src/triggers/onItemCreated.js";
import { onItemUpdatedCore, type MissingNotifier } from "../../src/triggers/onItemUpdated.js";
import { FakeLlm, MemoryCache } from "../fakes.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("items"));
});
afterAll(async () => {
  await deleteApp(app);
});

const OWNER = "u-owner";
const ADULT = "u-adult";
const KID = "u-kid";
let hid = "";
let lid = "";

beforeEach(async () => {
  await clearDb();
  ({ householdId: hid, listId: lid } = await createHouseholdCore(db, OWNER, {
    name: "Home",
    defaultLanguage: "ar",
    displayName: "Mama",
    language: "ar",
    uiLanguage: "ar",
  }));
  for (const [uid, role, language] of [
    [ADULT, "adult", "tr"],
    [KID, "kid", "ar"],
  ] as const) {
    const { code } = await createInviteCore(db, OWNER, { householdId: hid, role });
    await joinHouseholdCore(db, uid, { code, displayName: uid, language });
  }
});

const items = () => db.collection(`households/${hid}/lists/${lid}/items`);
let seq = 0;
/** What the apps write (Android ItemPatches.create), then the trigger. */
async function add(by: string, fields: Record<string, unknown>, llm = new FakeLlm()) {
  const ref = items().doc(`i${String(seq++)}`);
  await ref.set({
    urgent: false,
    status: by === KID ? "pending" : "open",
    addedBy: by,
    addedVia: "app_text",
    createdAt: Timestamp.fromMillis(1_000_000 + seq * 1000),
    updatedAt: Timestamp.now(),
    ...fields,
  });
  const outcome = await onItemCreatedCore(
    db,
    { householdId: hid, listId: lid, itemId: ref.id },
    llm,
    new MemoryCache(),
  );
  return { ref, outcome };
}
const events = async () => (await db.collection(`households/${hid}/events`).get()).docs.map((d) => d.data());

describe("parseItems", () => {
  const call = (text: string, localeHints = ["ar"], llm = new FakeLlm(), uid = ADULT) =>
    parseItemsCore(db, uid, { householdId: hid, text, localeHints }, llm, new MemoryCache());

  it("splits, resolves and translates into the member languages", async () => {
    const r = await call("٢ كيلو بندورة وعلبة لبنة", ["ar-SY"]);
    expect(r.source).toBe("catalog");
    expect(r.detectedLang).toBe("ar");
    expect(r.items).toEqual([
      expect.objectContaining({
        name: "بندورة",
        canonicalId: "tomato",
        qty: 2,
        unit: "kg",
        category: "produce",
      }),
      expect.objectContaining({ name: "لبنة", canonicalId: "labneh", qty: 1, unit: "pack" }),
    ]);
    expect(r.items[0]?.names).toEqual({ ar: "بندورة", tr: "Domates" });
    expect(r.items[0]).not.toHaveProperty("notes");
  });

  it("household aliases win; ambiguous words come back below 0.6 with alternatives", async () => {
    const ambiguous = await call("عيش");
    expect(ambiguous.items[0]).toMatchObject({ canonicalId: "bread", notes: "alternatives: bread, rice" });
    expect(ambiguous.items[0]?.confidence).toBeLessThan(0.6);
    const text = matchKey("عيش");
    await db
      .doc(`households/${hid}/aliases/${aliasId(text)}`)
      .set({ text, canonicalId: "rice", createdBy: OWNER });
    expect((await call("عيش")).items[0]).toMatchObject({ canonicalId: "rice", confidence: 1 });
  });

  it("unknown words use the LLM (mixed source); when it is down the local split still comes back", async () => {
    const llm = new FakeLlm({ "kedi kumu": { category: "pets", lang: "tr" } });
    const mixed = await call("süt ve kedi kumu", ["tr"], llm);
    expect(mixed.source).toBe("mixed");
    expect(mixed.items[1]).toMatchObject({ name: "kedi kumu", category: "pets" });
    const down = await call("süt ve kedi kumu", ["tr"], new FakeLlm({}, true));
    expect(down.items.map((i) => i.category)).toEqual(["dairy_eggs", "other"]);
  });

  it("quantities outside 0 < q ≤ 999 are dropped", async () => {
    expect((await call("0 milk", ["en"])).items[0]).not.toHaveProperty("qty");
    expect((await call("5000 milk", ["en"])).items[0]).not.toHaveProperty("qty");
  });

  it("members only; text ≤ 500; 30 calls per minute", async () => {
    await expectHttpsError(call("milk", ["en"], new FakeLlm(), "u-out"), "permission-denied");
    await expectHttpsError(call("x".repeat(501)), "invalid-argument");
    for (let i = 0; i < 30; i++) await call("milk", ["en"], new FakeLlm(), OWNER);
    await expectHttpsError(call("milk", ["en"], new FakeLlm(), OWNER), "resource-exhausted");
  });
});

describe("onItemCreated", () => {
  it("fills lang, names for every member language, canonicalId and category; logs item_added", async () => {
    const { ref, outcome } = await add(ADULT, { name: "süt", qty: 2, unit: "l" });
    expect(outcome).toBe("enriched");
    expect((await ref.get()).data()).toMatchObject({
      lang: "tr",
      canonicalId: "milk",
      category: "dairy_eggs",
      categorySource: "auto",
      names: { ar: "حليب", tr: "süt" },
    });
    expect(await events()).toEqual([
      expect.objectContaining({ type: "item_added", actorUid: ADULT, itemName: "süt", via: "app_text" }),
    ]);
  });

  it("keeps a member's category", async () => {
    const { ref } = await add(ADULT, { name: "ekmek", category: "frozen", categorySource: "user" });
    expect((await ref.get()).data()).toMatchObject({
      category: "frozen",
      categorySource: "user",
      canonicalId: "bread",
    });
  });

  it("uses the adder's device locale for dialect words (and then merges like any duplicate)", async () => {
    await db.doc(`users/${OWNER}/devices/d1`).set({ platform: "android", fcmToken: "t", locale: "ar-EG" });
    const { ref: aish, outcome } = await add(OWNER, { name: "عيش" });
    expect(outcome).toBe("enriched");
    expect((await aish.get()).get("canonicalId")).toBe("bread");
    const { outcome: second } = await add(ADULT, { name: "ekmek" });
    expect(second).toBe("merged");
  });

  it("an ambiguous word gets the likely category but no canonicalId", async () => {
    const { ref } = await add(OWNER, { name: "عيش" });
    const data = (await ref.get()).data();
    expect(data?.category).toBe("bakery");
    expect(data).not.toHaveProperty("canonicalId");
  });

  it("merges into an older open item: same unit adds, requestedBy grows, the newer doc goes", async () => {
    const { ref: first } = await add(OWNER, { name: "حليب", qty: 1, unit: "l" });
    const { ref: second, outcome } = await add(ADULT, { name: "süt", qty: 2, unit: "l", urgent: true });
    expect(outcome).toBe("merged");
    expect((await second.get()).exists).toBe(false);
    expect((await first.get()).data()).toMatchObject({
      qty: 3,
      unit: "l",
      urgent: true,
      requestedBy: [OWNER, ADULT],
    });
    expect((await events()).map((e): unknown => e.type).sort()).toEqual(["item_added", "item_merged"]);
  });

  it("different unit: the amount goes to notes", async () => {
    const { ref: first } = await add(OWNER, { name: "حليب", qty: 1, unit: "l" });
    await add(ADULT, { name: "milk", qty: 6, unit: "bottle" });
    expect((await first.get()).get("notes")).toBe("+ 6 bottle");
  });

  it("kids' requests never merge, and nothing merges into them", async () => {
    const { ref: request } = await add(KID, { name: "chips" });
    const { outcome } = await add(ADULT, { name: "chips" });
    expect(outcome).toBe("enriched");
    expect((await request.get()).get("status")).toBe("pending");
    expect((await items().get()).size).toBe(2);
  });

  it("done items are not merge targets; reruns are no-ops", async () => {
    const { ref: done } = await add(OWNER, { name: "bread" });
    await done.update({ status: "done" });
    const { ref: again, outcome } = await add(OWNER, { name: "bread" });
    expect(outcome).toBe("enriched");
    expect(
      await onItemCreatedCore(db, { householdId: hid, listId: lid, itemId: again.id }, new FakeLlm()),
    ).toBe("skipped");
  });
});

describe("onItemUpdated", () => {
  const notifier = () => {
    const sent: string[] = [];
    const n: MissingNotifier = {
      itemMissing: (i) => {
        sent.push(i.recipientUid);
        return Promise.resolve();
      },
    };
    return { n, sent };
  };
  const update = (
    id: string,
    before: Record<string, unknown>,
    after: Record<string, unknown>,
    n: MissingNotifier,
    now = new Date(),
  ) => onItemUpdatedCore(db, { householdId: hid, listId: lid, itemId: id }, before, after, n, now);

  it("done → purchase record (with the trip's store) and item_done; quick undo removes it", async () => {
    await db.doc(`households/${hid}/trips/t1`).set({
      status: "shopping",
      storeId: "osm_node_1",
      chainId: "migros",
      listIds: [lid],
      shopperUid: ADULT,
    });
    const item = { name: "süt", canonicalId: "milk", qty: 2, unit: "l", addedBy: OWNER };
    const now = new Date(2_000_000_000_000);
    expect(
      await update(
        "i1",
        { ...item, status: "open" },
        { ...item, status: "done", doneBy: ADULT, doneTripId: "t1" },
        notifier().n,
        now,
      ),
    ).toBe("purchase");
    const purchases = await db.collection(`households/${hid}/purchases`).get();
    expect(purchases.docs[0]?.data()).toMatchObject({
      canonicalId: "milk",
      name: "süt",
      qty: 2,
      unit: "l",
      storeId: "osm_node_1",
      chainId: "migros",
      tripId: "t1",
      by: ADULT,
    });
    expect((await events()).find((e) => e.type === "item_done")).toMatchObject({
      actorUid: ADULT,
      tripId: "t1",
    });

    await update(
      "i1",
      { ...item, status: "done" },
      { ...item, status: "open" },
      notifier().n,
      new Date(now.getTime() + 60_000),
    );
    expect((await db.collection(`households/${hid}/purchases`).get()).size).toBe(0);
  });

  it("an undo hours later keeps the purchase", async () => {
    const item = { name: "ekmek", addedBy: OWNER };
    const now = new Date(2_000_000_000_000);
    await update(
      "i2",
      { ...item, status: "open" },
      { ...item, status: "done", doneBy: OWNER },
      notifier().n,
      now,
    );
    await update(
      "i2",
      { ...item, status: "done" },
      { ...item, status: "open" },
      notifier().n,
      new Date(now.getTime() + 3 * 3_600_000),
    );
    expect((await db.collection(`households/${hid}/purchases`).get()).size).toBe(1);
  });

  it("missing → event and a push to the adder, respecting notify.itemMissing and never the shopper", async () => {
    await db.doc(`households/${hid}/trips/t1`).set({ status: "shopping", listIds: [lid], shopperUid: ADULT });
    const item = { name: "labne", addedBy: OWNER };
    const a = notifier();
    expect(await update("i3", { ...item, status: "open" }, { ...item, status: "missing" }, a.n)).toBe(
      "missing",
    );
    expect(a.sent).toEqual([OWNER]);
    expect((await events()).find((e) => e.type === "item_missing")).toMatchObject({
      actorUid: ADULT,
      tripId: "t1",
    });

    await db.doc(`households/${hid}/members/${OWNER}`).update({ "notify.itemMissing": false });
    const b = notifier();
    await update("i3", { ...item, status: "open" }, { ...item, status: "missing" }, b.n);
    expect(b.sent).toEqual([]);

    const c = notifier();
    await update(
      "i4",
      { name: "x", addedBy: ADULT, status: "open" },
      { name: "x", addedBy: ADULT, status: "missing" },
      c.n,
    );
    expect(c.sent).toEqual([]);
  });

  it("other changes do nothing", async () => {
    expect(await update("i5", { status: "open", qty: 1 }, { status: "open", qty: 2 }, notifier().n)).toBe(
      "none",
    );
  });
});

describe("transcribeAudio", () => {
  const stt = (text = "2 süt ve labne", fail = false): SpeechToText & { languages: string[][] } => {
    const languages: string[][] = [];
    return {
      languages,
      transcribe: (_audio, _mime, codes) => {
        languages.push(codes);
        return fail
          ? Promise.reject(new SpeechUnavailableError("down"))
          : Promise.resolve({ text, locale: "tr-TR", confidence: 0.9 });
      },
    };
  };
  const audio = Buffer.from("fake-ogg-bytes").toString("base64");

  it("transcribes with up to 3 recognition languages from the hints", async () => {
    const engine = stt();
    const r = await transcribeAudioCore(
      db,
      ADULT,
      { audioBase64: audio, mimeType: "audio/ogg", localeHints: ["tr", "ar-SA", "en"] },
      engine,
    );
    expect(r).toEqual({ text: "2 süt ve labne", locale: "tr-TR", confidence: 0.9 });
    expect(engine.languages[0]).toEqual(["tr-TR", "ar-SA", "en-US"]);
  });

  it("validation, unavailable, and 20 per hour", async () => {
    const call = (data: Record<string, unknown>, engine = stt()) =>
      transcribeAudioCore(
        db,
        KID,
        { audioBase64: audio, mimeType: "audio/ogg", localeHints: ["ar"], ...data },
        engine,
      );
    await expectHttpsError(call({ mimeType: "audio/flac" }), "invalid-argument");
    await expectHttpsError(call({ audioBase64: "not base64!" }), "invalid-argument");
    await expectHttpsError(call({ audioBase64: "A".repeat(1_000_004) }), "invalid-argument");
    await expectHttpsError(call({}, stt("", true)), "unavailable");
    for (let i = 0; i < 19; i++) await call({});
    await expectHttpsError(call({}), "resource-exhausted");
  });
});
