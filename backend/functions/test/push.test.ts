import { describe, expect, it } from "vitest";
import { UI_LANGUAGES, type UiLanguage } from "../src/config.js";
import { ALL_TEXT, format } from "../src/i18n/index.js";
import {
  alertMessage,
  arrivedTexts,
  headingTexts,
  listChangedMessage,
  liveActivityMessage,
  missingTexts,
  tripUpdateMessage,
  type Device,
} from "../src/push/messages.js";
import { computeTripState, type StateItem } from "../src/trips/state.js";

const device = (platform: "ios" | "android", uiLanguage: UiLanguage): Device => ({
  uid: "u1",
  deviceId: "d1",
  platform,
  fcmToken: `fcm-${platform}`,
  uiLanguage,
});

const NAMES: Record<UiLanguage, { name: string; item: string }> = {
  ar: { name: "أمي", item: "لبنة" },
  tr: { name: "Ayşe", item: "labne" },
  en: { name: "Ali", item: "labneh" },
};

describe("templates", () => {
  it("every key exists in ar, tr and en, with the same placeholders", () => {
    const keys = Object.keys(ALL_TEXT.en).sort();
    for (const lang of UI_LANGUAGES) {
      expect(Object.keys(ALL_TEXT[lang]).sort()).toEqual(keys);
      for (const key of keys) {
        const placeholders = (t: unknown) =>
          [...JSON.stringify(t).matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
        const en = new Set(placeholders(ALL_TEXT.en[key]));
        for (const p of placeholders(ALL_TEXT[lang][key]))
          expect(en.has(p), `${lang}.${key} {${String(p)}}`).toBe(true);
      }
    }
  });

  it("Arabic plural forms (Western digits, as the Android app shows them); Turkish has one form", () => {
    expect(format("ar", "tripHeadingBody", { minutes: 5 }, 5)).toBe("أضف ما تحتاجه خلال 5 دقائق");
    expect(format("ar", "tripHeadingBody", { minutes: 15 }, 15)).toBe("أضف ما تحتاجه خلال 15 دقيقة");
    expect(format("ar", "liveStartBody", { count: 1 }, 1)).toBe("صنف واحد في قائمتك");
    expect(format("ar", "liveStartBody", { count: 2 }, 2)).toBe("صنفان في قائمتك");
    expect(format("ar", "liveStartBody", { count: 11 }, 11)).toBe("11 صنفا\u064B في قائمتك");
    expect(format("tr", "liveStartBody", { count: 1 }, 1)).toBe("Listende 1 ürün var");
    expect(format("en", "liveStartBody", { count: 1 }, 1)).toBe("1 item on your list");
  });
});

describe("CONTRACT §5.1 alerts, snapshot per language and platform", () => {
  for (const lang of UI_LANGUAGES) {
    const { name, item } = NAMES[lang];
    for (const platform of ["android", "ios"] as const) {
      const d = device(platform, lang);
      it(`trip_heading ${lang} ${platform}`, () => {
        for (const minutes of [0, 5, 10, 15]) {
          const t = headingTexts(lang, name, "Migros", minutes);
          const extra = {
            tripId: "t1",
            listId: "l1",
            ...(minutes > 0 ? { lastCallUntil: "1760780600" } : {}),
          };
          expect(alertMessage(d, "trip_heading", "h1", t.title, t.body, extra)).toMatchSnapshot(
            `${String(minutes)} min`,
          );
        }
      });
      it(`trip_arrived ${lang} ${platform}`, () => {
        const t = arrivedTexts(lang, name, "");
        expect(
          alertMessage(d, "trip_arrived", "h1", t.title, t.body, { tripId: "t1", listId: "l1" }),
        ).toMatchSnapshot();
      });
      it(`item_missing ${lang} ${platform}`, () => {
        const t = missingTexts(lang, name, item);
        expect(
          alertMessage(d, "item_missing", "h1", t.title, t.body, {
            itemId: "i1",
            listId: "l1",
            tripId: "t1",
          }),
        ).toMatchSnapshot();
      });
    }
  }

  it("Android alerts are data-only with title/body (contract v2); iOS uses aps.alert + category", () => {
    const android = alertMessage(device("android", "en"), "trip_heading", "h1", "T", "B", {
      tripId: "t1",
      listId: "l1",
    });
    expect(android).not.toHaveProperty("notification");
    expect(android).toMatchObject({
      data: { type: "trip_heading", householdId: "h1", title: "T", body: "B" },
      android: { priority: "high" },
    });
    const ios = alertMessage(device("ios", "en"), "trip_arrived", "h1", "T", "B", {
      tripId: "t1",
      listId: "l1",
    });
    expect(ios.data).not.toHaveProperty("title");
    expect(ios).toMatchObject({
      apns: { payload: { aps: { alert: { title: "T", body: "B" }, category: "TRIP_ARRIVED" } } },
    });
  });
});

describe("live surfaces and list_changed", () => {
  const state = {
    status: "shopping" as const,
    remaining: 7,
    done: 5,
    nextItems: ["حليب", "خبز عربي", "بيض"],
    lastCallUntil: 1760780600,
    latestAddBy: "أمي",
    latestAddName: "لبنة",
  };

  it("Android trip_update: every value a string, nextItems JSON (CONTRACT §5.3)", () => {
    const m = tripUpdateMessage(device("android", "ar"), "h1", "t1", "Migros", state);
    expect(m).toMatchSnapshot();
    expect(Object.values(m.data ?? {}).every((v) => typeof v === "string")).toBe(true);
    expect(JSON.parse(m.data?.nextItems ?? "")).toEqual(state.nextItems);
    const minimal = tripUpdateMessage(device("android", "ar"), "h1", "t1", "", {
      status: "heading",
      remaining: 0,
      done: 0,
      nextItems: [],
    });
    expect(minimal.data).not.toHaveProperty("lastCallUntil");
  });

  it.each(["start", "update", "end"] as const)("iOS Live Activity %s (CONTRACT §5.2)", (event) => {
    const m = liveActivityMessage({
      fcmToken: "fcm-ios",
      liveActivityToken: "abc123",
      event,
      nowSeconds: 1760780000,
      state,
      ...(event === "start"
        ? {
            attributes: { tripId: "t1", householdId: "h1", storeName: "Migros", chainId: "migros" },
            alert: { title: "At Migros", body: "7 items on your list" },
          }
        : {}),
    });
    expect(m).toMatchSnapshot();
    expect(m.apns?.headers?.["apns-priority"]).toBe(event === "update" ? "5" : "10");
  });

  it("list_changed: data-only on Android, silent background push on iOS", () => {
    expect(listChangedMessage(device("android", "tr"), "h1", "l1")).toMatchSnapshot();
    expect(listChangedMessage(device("ios", "tr"), "h1", "l1")).toMatchSnapshot();
  });
});

describe("trip state (nextItems, counts, last call)", () => {
  const trip = {
    tripId: "t1",
    status: "shopping" as const,
    shopperUid: "shopper",
    startedAtMillis: 1_000,
    lastCallUntilMillis: 900_000,
  };
  const ctx = {
    shopperLanguage: "tr",
    defaultLanguage: "ar",
    memberNames: { mom: "Anne", shopper: "Ali" },
    nowMillis: 5_000,
  };
  const item = (over: Partial<StateItem>): StateItem => ({
    name: "x",
    status: "open",
    category: "other",
    createdAtMillis: 500,
    ...over,
  });

  it("counts, aisle order, the shopper's language, others' claims skipped", () => {
    const items = [
      item({ name: "deterjan", category: "household_cleaning" }),
      item({ name: "حليب", lang: "ar", names: { tr: "süt" }, category: "dairy_eggs" }),
      item({ name: "domates", category: "produce", lang: "tr" }),
      item({ name: "ekmek", category: "bakery", status: "claimed", claimedBy: "mom" }),
      item({ name: "yumurta", category: "dairy_eggs", status: "claimed", claimedBy: "shopper" }),
      item({ name: "peynir", status: "done", doneTripId: "t1" }),
      item({ name: "zeytin", status: "done", doneTripId: "old" }),
      item({ name: "cips", status: "pending" }),
      item({ name: "labne", status: "missing" }),
    ];
    expect(computeTripState(trip, items, ctx)).toEqual({
      status: "shopping",
      remaining: 4,
      done: 1,
      nextItems: ["domates", "süt", "yumurta", "deterjan"],
      lastCallUntil: 900,
    });
  });

  it("during last call, the newest item someone else added is highlighted", () => {
    const items = [
      item({ name: "old", addedBy: "mom", createdAtMillis: 500 }),
      item({ name: "lokum", addedBy: "mom", createdAtMillis: 3_000 }),
      item({ name: "mine", addedBy: "shopper", createdAtMillis: 4_000 }),
    ];
    expect(computeTripState(trip, items, ctx)).toMatchObject({ latestAddBy: "Anne", latestAddName: "lokum" });
    expect(computeTripState({ ...trip, lastCallUntilMillis: 4_000 }, items, ctx)).not.toHaveProperty(
      "latestAddBy",
    );
  });

  it("nextItems stops at 4", () => {
    const items = Array.from({ length: 9 }, (_, i) => item({ name: `n${String(i)}`, createdAtMillis: i }));
    expect(computeTripState(trip, items, ctx).nextItems).toEqual(["n0", "n1", "n2", "n3"]);
  });
});
