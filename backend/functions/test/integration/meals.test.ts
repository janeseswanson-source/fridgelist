import { deleteApp, type App } from "firebase-admin/app";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import { mealToItemsCore, suggestMealsCore } from "../../src/callables/meals.js";
import type { ParsedItem } from "../../src/callables/parseItems.js";
import { LlmUnavailableError } from "../../src/llm/transport.js";
import type { MealPlanner, PlannedMeal, PlanRequest } from "../../src/meals/planner.js";
import { SeedMealPlanner } from "../../src/meals/seedPlanner.js";
import { FakeLlm } from "../fakes.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("meals"));
});
afterAll(async () => {
  await deleteApp(app);
});

const OWNER = "u-owner";
let hid = "";
let lid = "";
const NOW = new Date(1_800_000_000_000);

/** Turns each candidate seed dish into a planned meal (what a good model answer looks like). */
class SeedPlanner implements MealPlanner {
  requests: PlanRequest[] = [];
  plan(request: PlanRequest): Promise<PlannedMeal[]> {
    this.requests.push(request);
    return Promise.resolve(
      request.candidates.slice(0, request.count).map((d) => ({
        dishId: d.id,
        title: Object.fromEntries(
          request.languages.map((l) => [l, (d.names as Record<string, string | undefined>)[l] ?? d.names.en]),
        ),
        description: Object.fromEntries(request.languages.map((l) => [l, `${d.names.en} (${l})`])),
        cuisine: d.cuisine,
        tags: ["main"],
        ingredients: d.ingredients.map((i) => ({
          canonicalId: i.canonicalId ?? null,
          name: i.names?.en ?? i.canonicalId ?? "x",
          qty: (i.qty * request.people) / d.servings,
          unit: i.unit as PlannedMeal["ingredients"][number]["unit"],
        })),
      })),
    );
  }
}

beforeEach(async () => {
  await clearDb();
  ({ householdId: hid, listId: lid } = await createHouseholdCore(db, OWNER, {
    name: "Ev",
    defaultLanguage: "tr",
    displayName: "Ayşe",
    language: "tr",
    uiLanguage: "tr",
  }));
});

const suggest = (data: Record<string, unknown>, planner: MealPlanner = new SeedPlanner(), now = NOW) =>
  suggestMealsCore(
    db,
    OWNER,
    { householdId: hid, people: 4, occasion: "iftar", lang: "ar", ...data },
    planner,
    now,
  );

describe("suggestMeals", () => {
  it("6–10 grounded meals in the asked language, cached for 7 days", async () => {
    const planner = new SeedPlanner();
    const { meals } = await suggest({ cuisines: ["turkish"], days: 3, people: 6 }, planner);
    // Turkish iftar has 6 seed dishes; the fake planner returns at most that many (a real plan is exactly 8).
    expect(meals).toHaveLength(6);
    expect(planner.requests[0]).toMatchObject({
      occasion: "iftar",
      cuisines: ["turkish"],
      people: 6,
      count: 8,
      languages: ["ar", "tr"],
    });
    expect(meals[0]).toMatchObject({
      title: "شوربة عدس تركية",
      servings: 6,
      cuisine: "turkish",
      tags: ["main"],
    });
    const doc = (await db.doc(`households/${hid}/mealSuggestions/${meals[0]?.mealId ?? ""}`).get()).data() as
      { expireAt: Timestamp; ingredients: unknown[] } | undefined;
    expect(doc).toMatchObject({
      servings: 6,
      occasion: "iftar",
      cuisine: "turkish",
      title: { ar: "شوربة عدس تركية", tr: "Mercimek çorbası" },
    });
    expect((doc?.expireAt as Timestamp).toMillis()).toBe(NOW.getTime() + 7 * 86_400_000);
    expect(doc?.ingredients[0]).toEqual({
      canonicalId: "lentils_red",
      name: "lentils_red",
      qty: 0.375,
      unit: "kg",
    });
  });

  it("no LLM → unavailable; members only; validation; 10 calls per hour per household", async () => {
    const down: MealPlanner = { plan: () => Promise.reject(new LlmUnavailableError("down")) };
    await expectHttpsError(suggest({}, down), "unavailable");
    await expectHttpsError(
      suggestMealsCore(
        db,
        "u-out",
        { householdId: hid, people: 4, occasion: "iftar", lang: "ar" },
        new SeedPlanner(),
      ),
      "permission-denied",
    );
    await expectHttpsError(suggest({ people: 31 }), "invalid-argument");
    await expectHttpsError(suggest({ occasion: "brunch" }), "invalid-argument");
    for (let i = 0; i < 9; i++) await suggest({});
    await expectHttpsError(suggest({}), "resource-exhausted");
  });
});

describe("mealToItems", () => {
  async function mealIds(people = 4) {
    const { meals } = await suggest({ cuisines: ["levantine"], people }, new SeedPlanner());
    return meals.map((m) => m.mealId);
  }
  const toItems = (ids: string[], people: number, now = NOW) =>
    mealToItemsCore(
      db,
      OWNER,
      { householdId: hid, listId: lid, mealIds: ids, people, lang: "tr" },
      new FakeLlm(),
      now,
    );

  it("scales to people, merges across meals, names in the asked language", async () => {
    const ids = await mealIds(4);
    const { items, skipped } = await toItems(ids.slice(0, 2), 8); // lentil soup + fattoush, doubled
    expect(skipped).toEqual([]);
    const byId = new Map<string, ParsedItem>(
      items.flatMap((i) => (i.canonicalId ? [[i.canonicalId, i] as const] : [])),
    );
    expect(byId.get("lentils_red")).toMatchObject({ name: "Kırmızı mercimek", qty: 0.75, unit: "kg" });
    // Lemon in both: 0.2 + 0.2 per 4 → 0.8 kg for 8.
    expect(byId.get("lemon")).toMatchObject({ qty: 1, unit: "kg" });
    expect(byId.get("olive_oil")?.qty).toBe(0.25);
    expect(items.every((i) => typeof i.category === "string")).toBe(true);
  });

  it("skips what's already on the list (any language) and what was bought in the last 7 days", async () => {
    const ids = await mealIds(4);
    await db
      .collection(`households/${hid}/lists/${lid}/items`)
      .add({ name: "limon", names: { ar: "ليمون" }, canonicalId: "lemon", status: "open", addedBy: OWNER });
    await db
      .collection(`households/${hid}/lists/${lid}/items`)
      .add({ name: "soğan", status: "done", addedBy: OWNER });
    await db.collection(`households/${hid}/purchases`).add({
      canonicalId: "olive_oil",
      name: "zeytinyağı",
      by: OWNER,
      at: Timestamp.fromMillis(NOW.getTime() - 2 * 86_400_000),
    });
    await db.collection(`households/${hid}/purchases`).add({
      canonicalId: "onion",
      name: "soğan",
      by: OWNER,
      at: Timestamp.fromMillis(NOW.getTime() - 9 * 86_400_000),
    });
    const { items, skipped } = await toItems(ids.slice(0, 1), 4);
    expect(skipped).toEqual(
      expect.arrayContaining([
        { name: "Limon", reason: "on_list" },
        { name: "Zeytinyağı", reason: "recently_bought" },
      ]),
    );
    expect(items.map((i) => i.canonicalId)).toContain("onion"); // done on the list and bought 9 days ago: still needed
  });

  it("F11 without an LLM: a 6-person, 3-day iftar plan gives a sensible, deduplicated list in Arabic and Turkish", async () => {
    let seed = 7;
    const random = () => (seed = (seed * 9301 + 49297) % 233280) / 233280;
    const { meals } = await suggest(
      { people: 6, days: 3, lang: "ar" },
      new SeedMealPlanner(undefined, random),
    );
    expect(meals).toHaveLength(8); // 3 days → 8 ideas (6–10)
    expect(new Set(meals.map((m) => m.cuisine)).size).toBeGreaterThanOrEqual(3); // a mix, not all one cuisine
    expect(meals.every((m) => /[\u0600-\u06FF]/u.test(m.title) && m.servings === 6)).toBe(true);
    await db
      .collection(`households/${hid}/lists/${lid}/items`)
      .add({ name: "soğan", canonicalId: "onion", status: "open", addedBy: OWNER });

    const ids = meals.slice(0, 3).map((m) => m.mealId);
    for (const lang of ["ar", "tr"]) {
      const { items, skipped } = await mealToItemsCore(
        db,
        OWNER,
        { householdId: hid, listId: lid, mealIds: ids, people: 6, lang },
        new FakeLlm(),
        NOW,
      );
      const keys = items.map((i) => i.canonicalId ?? i.name);
      expect(new Set(keys).size).toBe(keys.length); // merged across dishes
      expect(items.length).toBeGreaterThan(3);
      expect(items.every((i) => i.qty == null || (i.qty > 0 && i.qty <= 999))).toBe(true);
      expect(items.map((i) => i.canonicalId)).not.toContain("onion"); // already on the list
      if (skipped.length > 0) expect(skipped.every((s) => s.reason === "on_list")).toBe(true);
      const script = lang === "ar" ? /[\u0600-\u06FF]/u : /[A-Za-zÇĞİÖŞÜçğıöşü]/u;
      expect(items.filter((i) => i.canonicalId).every((i) => script.test(i.name))).toBe(true);
    }
  });

  it("expired or unknown meals and unknown lists", async () => {
    const ids = await mealIds();
    await expectHttpsError(toItems(ids, 4, new Date(NOW.getTime() + 8 * 86_400_000)), "not-found");
    await expectHttpsError(toItems(["nope"], 4), "not-found");
    await expectHttpsError(
      mealToItemsCore(
        db,
        OWNER,
        { householdId: hid, listId: "nope", mealIds: ids, people: 4, lang: "tr" },
        new FakeLlm(),
        NOW,
      ),
      "not-found",
    );
  });
});
