import { createHmac } from "node:crypto";
import { deleteApp, type App } from "firebase-admin/app";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { deleteMyAccountCore } from "../../src/callables/account.js";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import { createInviteCore, joinHouseholdCore } from "../../src/callables/invites.js";
import { linkWhatsAppCore } from "../../src/callables/whatsapp.js";
import { StaticFlags } from "../../src/lib/flags.js";
import { RecordingSender } from "../../src/push/sender.js";
import { computeUsualsCore } from "../../src/scheduled/computeUsuals.js";
import { AISLE_MIN_TRIPS, learnAisleOrderCore } from "../../src/scheduled/learnAisleOrder.js";
import type { SpeechToText } from "../../src/stt/speechToText.js";
import type { WhatsAppClient } from "../../src/whatsapp/client.js";
import { phoneHash } from "../../src/whatsapp/protocol.js";
import { handleWebhookPost } from "../../src/whatsapp/webhook.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("phase2"));
});
afterAll(async () => {
  await deleteApp(app);
});
beforeEach(async () => {
  await clearDb();
});

const DAY = 86_400_000;
const NOW = new Date(1_800_000_000_000);
const OWNER = "u-owner"; // Arabic UI
const KID = "u-kid";
const SECRET = "app-secret";
const ON = new StaticFlags({ whatsapp_enabled: true, usuals_enabled: true, aisle_learning_enabled: true });
const OFF = new StaticFlags({});
const MOM_PHONE = "971500000001";
const KID_PHONE = "971500000002";

async function household() {
  const { householdId, listId } = await createHouseholdCore(db, OWNER, {
    name: "بيتنا",
    defaultLanguage: "ar",
    displayName: "ماما",
    language: "ar",
    uiLanguage: "ar",
  });
  return { hid: householdId, lid: listId };
}

class FakeClient implements WhatsAppClient {
  sent: { to: string; text: string }[] = [];
  sendText(_pn: string, to: string, text: string) {
    this.sent.push({ to, text });
    return Promise.resolve();
  }
  downloadMedia(id: string) {
    if (id === "bad") return Promise.reject(new Error("graph 404"));
    return Promise.resolve({ bytes: Buffer.from("ogg"), mimeType: "audio/ogg" });
  }
}

class FakeStt implements SpeechToText {
  constructor(private readonly text: string) {}
  transcribe() {
    return Promise.resolve({ text: this.text, locale: "ar-SA", confidence: 0.9 });
  }
}

function payload(from: string, message: Record<string, unknown>) {
  return {
    entry: [
      {
        changes: [
          {
            value: {
              metadata: { phone_number_id: "PN1" },
              messages: [{ id: `m${String(Math.random())}`, from, ...message }],
            },
          },
        ],
      },
    ],
  };
}

/** Posts one message as Meta would (signed raw body); returns status and the reply sent. */
async function post(
  from: string,
  message: Record<string, unknown>,
  opts: { flags?: StaticFlags; stt?: SpeechToText; secret?: string; now?: Date } = {},
) {
  const client = new FakeClient();
  const body = payload(from, message);
  const raw = Buffer.from(JSON.stringify(body));
  const signature = `sha256=${createHmac("sha256", opts.secret ?? SECRET)
    .update(raw)
    .digest("hex")}`;
  const status = await handleWebhookPost(
    {
      db,
      flags: opts.flags ?? ON,
      client,
      stt: opts.stt ?? new FakeStt(""),
      appSecret: SECRET,
      now: opts.now ?? NOW,
    },
    raw,
    signature,
    body,
  );
  return { status, reply: client.sent[0]?.text ?? null, sent: client.sent };
}
const say = (from: string, text: string, opts = {}) =>
  post(from, { type: "text", text: { body: text } }, opts);

async function linkPhone(uid: string, hid: string, phone: string) {
  const { code } = await linkWhatsAppCore(db, uid, { householdId: hid }, ON, "+971 4 000 0000", NOW);
  return say(phone, `link ${code}`);
}

describe("linkWhatsApp", () => {
  it("off by flag; members only; a 15-minute code", async () => {
    const { hid } = await household();
    await expectHttpsError(
      linkWhatsAppCore(db, OWNER, { householdId: hid }, OFF, "", NOW),
      "failed-precondition",
    );
    await expectHttpsError(
      linkWhatsAppCore(db, "stranger", { householdId: hid }, ON, "", NOW),
      "permission-denied",
    );
    await expectHttpsError(linkWhatsAppCore(db, OWNER, {}, ON, "", NOW), "invalid-argument");
    const { code, botNumber } = await linkWhatsAppCore(db, OWNER, { householdId: hid }, ON, "+971", NOW);
    expect(botNumber).toBe("+971");
    const doc = await db.doc(`whatsappLinkCodes/${code}`).get();
    expect(doc.data()).toEqual({
      uid: OWNER,
      householdId: hid,
      expiresAt: Timestamp.fromMillis(NOW.getTime() + 15 * 60_000),
    });
  });
});

describe("whatsappWebhook", () => {
  it("rejects a bad signature, does nothing while off", async () => {
    const { hid, lid } = await household();
    expect((await say(MOM_PHONE, "milk", { secret: "wrong" })).status).toBe(401);
    const off = await say(MOM_PHONE, "milk", { flags: OFF });
    expect(off).toMatchObject({ status: 200, reply: null });
    expect((await db.collection(`households/${hid}/lists/${lid}/items`).get()).size).toBe(0);
  });

  it("unlinked numbers get the link instructions in their script's language", async () => {
    await household();
    expect((await say(MOM_PHONE, "حليب")).reply).toContain("غير مرتبط");
    expect((await say(MOM_PHONE, "milk")).reply).toContain("isn't linked");
    expect((await post(MOM_PHONE, { type: "audio", audio: { id: "A1" } })).reply).toContain("isn't linked");
  });

  it("links with a code once, stores only the hash, then replies in the user's language", async () => {
    const { hid } = await household();
    const { code } = await linkWhatsAppCore(db, OWNER, { householdId: hid }, ON, "", NOW);
    const linked = await say(MOM_PHONE, `ربط ${code}`);
    expect(linked.reply).toContain("بيتنا");
    const link = await db.doc(`whatsappNumbers/${phoneHash(MOM_PHONE)}`).get();
    expect(link.data()).toMatchObject({ uid: OWNER, householdId: hid });
    expect(JSON.stringify(link.data())).not.toContain(MOM_PHONE);
    expect((await db.doc(`whatsappLinkCodes/${code}`).get()).exists).toBe(false);
    // Reused or expired codes are refused.
    expect((await say(KID_PHONE, `link ${code}`)).reply).toContain("doesn't work"); // Latin text: English
    const late = await linkWhatsAppCore(db, OWNER, { householdId: hid }, ON, "", NOW);
    const after = new Date(NOW.getTime() + 16 * 60_000);
    expect((await say(KID_PHONE, `link ${late.code}`, { now: after })).reply).not.toContain("بيتنا");
    expect((await db.doc(`whatsappNumbers/${phoneHash(KID_PHONE)}`).get()).exists).toBe(false);
  });

  it("adds items as wa:<uid>; lists; ticks off; kids' adds are pending", async () => {
    const { hid, lid } = await household();
    const { code } = await createInviteCore(db, OWNER, { householdId: hid, role: "kid" });
    await joinHouseholdCore(db, KID, { code, displayName: "Ali", language: "ar" });
    await linkPhone(OWNER, hid, MOM_PHONE);
    await linkPhone(KID, hid, KID_PHONE);
    const items = db.collection(`households/${hid}/lists/${lid}/items`);

    const added = await say(MOM_PHONE, "حليب، 2 خبز");
    expect(added.reply).toContain("إضافة"); // Arabic dual: "two items"
    const docs = (await items.get()).docs.map((d) => d.data());
    expect(docs).toHaveLength(2);
    expect(
      docs.every((d) => d.addedBy === `wa:${OWNER}` && d.addedVia === "whatsapp" && d.status === "open"),
    ).toBe(true);
    expect(docs.find((d) => d.name === "خبز")?.qty).toBe(2);

    await say(KID_PHONE, "شيبس");
    expect((await items.where("name", "==", "شيبس").get()).docs[0]?.get("status")).toBe("pending");

    const list = await say(MOM_PHONE, "القائمة");
    expect(list.reply).toContain("• حليب");
    expect(list.reply).not.toContain("شيبس"); // pending requests aren't on the list yet

    expect((await say(MOM_PHONE, "اشتريت حليب")).reply).toContain("حليب");
    const milk = (await items.where("name", "==", "حليب").get()).docs[0];
    expect(milk?.data()).toMatchObject({ status: "done", doneBy: OWNER });
    expect((await say(MOM_PHONE, "اشتريت موز")).reply).toContain("موز");
  });

  it("voice notes go through speech-to-text; failures get a message", async () => {
    const { hid, lid } = await household();
    await linkPhone(OWNER, hid, MOM_PHONE);
    await post(MOM_PHONE, { type: "audio", audio: { id: "A1" } }, { stt: new FakeStt("طماطم") });
    expect((await db.collection(`households/${hid}/lists/${lid}/items`).get()).docs[0]?.get("name")).toBe(
      "طماطم",
    );
    const silent = await post(MOM_PHONE, { type: "audio", audio: { id: "A1" } });
    const broken = await post(MOM_PHONE, { type: "audio", audio: { id: "bad" } }, { stt: new FakeStt("x") });
    expect(silent.reply).toBe(broken.reply);
    expect(silent.reply).not.toBeNull();
    expect((await db.collection(`households/${hid}/lists/${lid}/items`).get()).size).toBe(1);
  });

  it("a member who left loses the link; account deletion removes it", async () => {
    const { hid } = await household();
    const { code } = await createInviteCore(db, OWNER, { householdId: hid, role: "kid" });
    await joinHouseholdCore(db, KID, { code, displayName: "Ali", language: "ar" });
    await linkPhone(KID, hid, KID_PHONE);
    await db.doc(`households/${hid}/members/${KID}`).delete();
    expect((await say(KID_PHONE, "chips")).reply).toContain("isn't linked");
    expect((await db.doc(`whatsappNumbers/${phoneHash(KID_PHONE)}`).get()).exists).toBe(false);

    await linkPhone(OWNER, hid, MOM_PHONE);
    await linkWhatsAppCore(db, OWNER, { householdId: hid }, ON, "", NOW); // an unused code
    await deleteMyAccountCore(db, OWNER, {}, new RecordingSender(), () => Promise.resolve(), null, NOW);
    expect((await db.collection("whatsappNumbers").get()).size).toBe(0);
    expect((await db.collection("whatsappLinkCodes").get()).size).toBe(0);
  });

  it("rate-limits per number", async () => {
    const { hid } = await household();
    await linkPhone(OWNER, hid, MOM_PHONE); // 1
    for (let i = 0; i < 29; i++) await say(MOM_PHONE, "?");
    expect((await say(MOM_PHONE, "?")).reply).toContain("كثيرة");
  });
});

describe("computeUsuals", () => {
  const buy = (hid: string, name: string, daysAgo: number, canonicalId?: string) =>
    db.collection(`households/${hid}/purchases`).add({
      name,
      ...(canonicalId ? { canonicalId } : {}),
      by: OWNER,
      at: Timestamp.fromMillis(NOW.getTime() - daysAgo * DAY),
    });

  it("off by flag; writes due usuals not on a list; removes stale ones", async () => {
    const { hid, lid } = await household();
    for (const d of [21, 14, 7]) await buy(hid, "حليب", d, "milk"); // due now
    for (const d of [30, 20, 10]) await buy(hid, "خبز", d); // due now, but on the list
    for (const d of [20, 10, 0]) await buy(hid, "موز", d, "banana"); // due in 10 days
    for (const d of [200, 190, 180]) await buy(hid, "رز", d, "rice"); // too old
    await db.collection(`households/${hid}/lists/${lid}/items`).add({ name: "خبز", status: "open" });
    await db.doc(`households/${hid}/usuals/stale`).set({ name: "old" });

    expect(await computeUsualsCore(db, OFF, NOW)).toBeNull();
    expect((await db.collection(`households/${hid}/usuals`).get()).size).toBe(1);

    expect(await computeUsualsCore(db, ON, NOW)).toBe(1);
    const usuals = await db.collection(`households/${hid}/usuals`).get();
    expect(usuals.docs.map((d) => d.id)).toEqual(["milk"]);
    expect(usuals.docs[0]?.data()).toMatchObject({
      canonicalId: "milk",
      name: "حليب",
      everyDays: 7,
      count: 3,
      dueAt: Timestamp.fromMillis(NOW.getTime()),
    });
  });
});

describe("learnAisleOrder", () => {
  async function trip(hid: string, lid: string, storeId: string | null, ticks: string[], endedAgoH = 2) {
    const ref = await db.collection(`households/${hid}/trips`).add({
      status: "done",
      listIds: [lid],
      ...(storeId ? { storeId } : {}),
      endedAt: Timestamp.fromMillis(NOW.getTime() - endedAgoH * 3_600_000),
    });
    let t = NOW.getTime() - 3 * 3_600_000;
    for (const category of ticks) {
      await db.collection(`households/${hid}/lists/${lid}/items`).add({
        name: category,
        category,
        status: "done",
        doneTripId: ref.id,
        doneAt: Timestamp.fromMillis((t += 60_000)),
      });
    }
    return ref;
  }

  it("off by flag; counts each trip once; publishes the order after 5 trips", async () => {
    const { hid, lid } = await household();
    await db.doc("stores/osm_node_1").set({ name: "Carrefour", chainId: "carrefour" });
    const first = await trip(hid, lid, "osm_node_1", ["dairy_eggs", "bakery", "produce"]);
    await trip(hid, lid, null, ["dairy_eggs", "produce"]); // no store
    await trip(hid, lid, "osm_node_1", ["produce", "bakery"], 30); // too old

    expect(await learnAisleOrderCore(db, OFF, NOW)).toBeNull();
    expect(await learnAisleOrderCore(db, ON, NOW)).toBe(1);
    expect(await learnAisleOrderCore(db, ON, NOW)).toBe(0); // already counted
    expect((await first.get()).get("aisleLearned")).toBe(true);
    let store = await db.doc("stores/osm_node_1").get();
    expect(store.get("aisleTrips")).toBe(1);
    expect(store.get("aisleOrder")).toBeUndefined();

    for (let i = 1; i < AISLE_MIN_TRIPS; i++) await trip(hid, lid, "osm_node_1", ["dairy_eggs", "produce"]);
    await trip(hid, lid, "osm_node_1", ["produce"]); // one category: marked, not counted
    expect(await learnAisleOrderCore(db, ON, NOW)).toBe(AISLE_MIN_TRIPS - 1);
    store = await db.doc("stores/osm_node_1").get();
    expect(store.get("aisleTrips")).toBe(AISLE_MIN_TRIPS);
    expect((store.get("aisleOrder") as string[]).slice(0, 3)).toEqual(["dairy_eggs", "bakery", "produce"]);
    expect(store.get("aisleStats")).toMatchObject({ dairy_eggs: { sumRank: 0, n: 5 } });
  });
});
