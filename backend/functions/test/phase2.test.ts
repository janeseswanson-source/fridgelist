import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { defaultFlag, emulatorFlag, StaticFlags } from "../src/lib/flags.js";
import { dueUsuals, usualKey, usualsFrom, type Purchase } from "../src/scheduled/computeUsuals.js";
import { addTrip, aisleOrder, tripRanks } from "../src/scheduled/learnAisleOrder.js";
import { CATEGORIES } from "../src/lib/categories.js";
import {
  inboundMessages,
  parseCommand,
  phoneHash,
  validSignature,
  verifyChallenge,
} from "../src/whatsapp/protocol.js";

const DAY = 86_400_000;
const T0 = Date.UTC(2026, 9, 1, 9);

describe("flags", () => {
  it("phase-2 flags are off by default (contract §10)", async () => {
    expect(defaultFlag("whatsapp_enabled")).toBe(false);
    expect(defaultFlag("usuals_enabled")).toBe(false);
    expect(defaultFlag("aisle_learning_enabled")).toBe(false);
    expect(await new StaticFlags({}).enabled("usuals_enabled")).toBe(false);
    expect(await new StaticFlags({ usuals_enabled: true }).enabled("usuals_enabled")).toBe(true);
  });

  it("the emulator reads FLAG_<KEY> overrides, never Remote Config", () => {
    expect(emulatorFlag("whatsapp_enabled", {})).toBe(false);
    expect(emulatorFlag("whatsapp_enabled", { FLAG_WHATSAPP_ENABLED: "true" })).toBe(true);
    expect(emulatorFlag("usuals_enabled", { FLAG_WHATSAPP_ENABLED: "true" })).toBe(false);
  });
});

describe("whatsapp protocol", () => {
  const body = Buffer.from('{"entry":[]}');
  const sign = (secret: string, raw = body) =>
    `sha256=${createHmac("sha256", secret).update(raw).digest("hex")}`;

  it("accepts only Meta's HMAC of the exact raw body", () => {
    expect(validSignature(body, sign("s3cret"), "s3cret")).toBe(true);
    expect(validSignature(body, sign("other"), "s3cret")).toBe(false);
    expect(validSignature(Buffer.from('{"entry": []}'), sign("s3cret"), "s3cret")).toBe(false);
    expect(validSignature(body, undefined, "s3cret")).toBe(false);
    expect(validSignature(body, "sha256=zz", "s3cret")).toBe(false);
    expect(validSignature(body, sign("s3cret").replace("sha256=", "sha1="), "s3cret")).toBe(false);
    expect(validSignature(body, sign(""), "")).toBe(false); // no secret configured: refuse all
  });

  it("verification challenge needs the verify token", () => {
    const q = { "hub.mode": "subscribe", "hub.verify_token": "tok", "hub.challenge": "123" };
    expect(verifyChallenge(q, "tok")).toEqual({ status: 200, body: "123" });
    expect(verifyChallenge(q, "nope").status).toBe(403);
    expect(verifyChallenge({ ...q, "hub.verify_token": "" }, "").status).toBe(403);
    expect(verifyChallenge({ ...q, "hub.mode": "unsubscribe" }, "tok").status).toBe(403);
  });

  it("hashes the digits only", () => {
    expect(phoneHash("+971 50 123 4567")).toBe(phoneHash("971501234567"));
    expect(phoneHash("971501234567")).toMatch(/^[0-9a-f]{64}$/);
  });

  it("parses commands in ar/tr/en; anything else is items", () => {
    expect(parseCommand("link ab-cd 2345")).toEqual({ kind: "link", code: "ABCD2345" });
    expect(parseCommand("ربط ABCD2345")).toEqual({ kind: "link", code: "ABCD2345" });
    expect(parseCommand("BAĞLA abcd2345")).toEqual({ kind: "link", code: "ABCD2345" });
    for (const w of ["list", "Liste", "القائمة", "اللسته"]) expect(parseCommand(w)).toEqual({ kind: "list" });
    expect(parseCommand("got milk")).toEqual({ kind: "got", text: "milk" });
    expect(parseCommand("اشتريت حليب")).toEqual({ kind: "got", text: "حليب" });
    expect(parseCommand("aldım süt")).toEqual({ kind: "got", text: "süt" });
    for (const w of ["help", "?", "مساعدة", "Yardım"]) expect(parseCommand(w)).toEqual({ kind: "help" });
    expect(parseCommand("ekle 2 ekmek")).toEqual({ kind: "add", text: "2 ekmek" });
    expect(parseCommand("milk, 2 bread")).toEqual({ kind: "add", text: "milk, 2 bread" });
    // A command word alone, or with the wrong shape, is an item.
    expect(parseCommand("list milk")).toEqual({ kind: "add", text: "list milk" });
    expect(parseCommand("got")).toEqual({ kind: "add", text: "got" });
  });

  it("reads text and voice messages, ignores statuses and junk", () => {
    const payload = {
      entry: [
        {
          changes: [
            {
              value: {
                metadata: { phone_number_id: "PN1" },
                messages: [
                  { id: "m1", from: "971500000001", type: "text", text: { body: "milk" } },
                  { id: "m2", from: "971500000001", type: "audio", audio: { id: "A1" } },
                  { id: "m3", type: "text", text: { body: "no sender" } },
                ],
              },
            },
            { value: { metadata: { phone_number_id: "PN1" }, statuses: [{ id: "s" }] } },
          ],
        },
      ],
    };
    expect(inboundMessages(payload)).toEqual([
      { id: "m1", from: "971500000001", phoneNumberId: "PN1", type: "text", text: "milk" },
      { id: "m2", from: "971500000001", phoneNumberId: "PN1", type: "audio", audioId: "A1" },
    ]);
    expect(inboundMessages(null)).toEqual([]);
    expect(inboundMessages({ entry: [{}] })).toEqual([]);
  });
});

describe("usuals", () => {
  const at = (days: number) => new Date(T0 + days * DAY);
  const milk = (days: number): Purchase => ({ canonicalId: "milk", name: "حليب", at: at(days) });

  it("keys by canonicalId, else a hash of the normalized name", () => {
    expect(usualKey({ canonicalId: "milk", name: "x" })).toBe("milk");
    expect(usualKey({ name: "Ekmek " })).toBe(usualKey({ name: "ekmek" }));
    expect(usualKey({ name: "ekmek" })).toMatch(/^n_[0-9a-f]{32}$/);
  });

  it("needs 3 purchase days; interval is the median gap", () => {
    expect(usualsFrom([milk(0), milk(7)])).toEqual([]);
    expect(usualsFrom([milk(0), milk(0.1), milk(7)])).toEqual([]); // same day counts once
    const [u] = usualsFrom([milk(0), milk(7), milk(14), milk(30)]);
    expect(u).toMatchObject({ key: "milk", everyDays: 7, count: 4, lastBoughtAt: at(30), dueAt: at(37) });
  });

  it("due from a day early, and never when already on a list", () => {
    const usuals = usualsFrom([milk(0), milk(7), milk(14)]); // due day 21
    expect(dueUsuals(usuals, new Set(), at(19))).toEqual([]);
    expect(dueUsuals(usuals, new Set(), at(20))).toHaveLength(1);
    expect(dueUsuals(usuals, new Set(["milk"]), at(25))).toEqual([]);
  });
});

describe("aisle learning", () => {
  it("ranks categories by first tick, 0…1; one category says nothing", () => {
    const ranks = tripRanks([
      { category: "dairy_eggs", at: 3 },
      { category: "produce", at: 1 },
      { category: "produce", at: 4 },
      { category: "bakery", at: 2 },
    ]);
    expect([...(ranks ?? [])]).toEqual([
      ["produce", 0],
      ["bakery", 0.5],
      ["dairy_eggs", 1],
    ]);
    expect(tripRanks([{ category: "produce", at: 1 }])).toBeNull();
  });

  it("orders seen categories by mean rank, then the rest in default order", () => {
    let stats = addTrip(
      {},
      new Map([
        ["dairy_eggs", 0],
        ["produce", 1],
      ] as const),
    );
    stats = addTrip(
      stats,
      new Map([
        ["dairy_eggs", 0],
        ["bakery", 0.5],
        ["produce", 1],
      ] as const),
    );
    expect(stats.dairy_eggs).toEqual({ sumRank: 0, n: 2 });
    const order = aisleOrder(stats);
    expect(order.slice(0, 3)).toEqual(["dairy_eggs", "bakery", "produce"]);
    expect([...order].sort()).toEqual([...CATEGORIES].sort());
    expect(aisleOrder({})).toEqual([...CATEGORIES]);
  });
});
