import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { Catalog } from "../src/lib/catalog.js";
import { UNITS } from "../src/lib/units.js";
import {
  LlmUnavailableError,
  RecordingTransport,
  ReplayTransport,
  type LlmTransport,
} from "../src/llm/transport.js";
import { CUISINES, DISHES, OCCASIONS, candidateDishes, suggestionCount } from "../src/meals/dishes.js";
import { LlmMealPlanner, type PlanRequest } from "../src/meals/planner.js";
import { scaleAndMerge, shoppingQty } from "../src/meals/scale.js";

const catalogIds = Catalog.bundled.items.map((i) => i.id);

describe("seed dishes (flagged for owner review)", () => {
  it("about 40 dishes, valid cuisines, occasions, units and catalog ids; names in ar/tr/en", () => {
    expect(DISHES.length).toBeGreaterThanOrEqual(40);
    expect(new Set(DISHES.map((d) => d.id)).size).toBe(DISHES.length);
    for (const d of DISHES) {
      expect(CUISINES, d.id).toContain(d.cuisine);
      for (const o of d.occasions) expect(OCCASIONS, d.id).toContain(o);
      for (const lang of ["ar", "tr", "en"] as const) expect(d.names[lang], `${d.id}.${lang}`).toBeTruthy();
      expect(d.servings).toBe(4);
      for (const ing of d.ingredients) {
        expect(UNITS, `${d.id} unit`).toContain(ing.unit);
        expect(ing.qty, `${d.id} qty`).toBeGreaterThan(0);
        if (ing.canonicalId) expect(catalogIds, `${d.id} → ${ing.canonicalId}`).toContain(ing.canonicalId);
        else expect(ing.names && ing.names.ar && ing.names.tr && ing.names.en, `${d.id} names`).toBeTruthy();
      }
    }
  });

  it("enough candidates for 6–10 suggestions per occasion", () => {
    for (const o of OCCASIONS) expect(candidateDishes(o, [], []).length, o).toBeGreaterThanOrEqual(6);
  });

  it("candidates filter by cuisine and by excluded ids or names in any language", () => {
    const gulfIftar = candidateDishes("iftar", ["gulf"], []);
    expect(gulfIftar.every((d) => d.cuisine === "gulf" && d.occasions.includes("iftar"))).toBe(true);
    const ids = candidateDishes("iftar", [], ["كبسة دجاج", "Mercimek çorbası", "fattoush"]).map((d) => d.id);
    expect(ids).not.toContain("chicken_kabsa");
    expect(ids).not.toContain("mercimek_corbasi");
    expect(ids).not.toContain("fattoush");
    expect(ids).toContain("lentil_soup");
  });

  it("6–10 suggestions, more with more days", () => {
    expect([undefined, 1, 3, 5, 7].map(suggestionCount)).toEqual([6, 6, 8, 10, 10]);
  });
});

describe("scaling for shopping", () => {
  it("rounds weights and volumes up to steps, counts to whole units", () => {
    expect(shoppingQty(0.3, "kg")).toBe(0.5);
    expect(shoppingQty(1.0, "kg")).toBe(1);
    expect(shoppingQty(0.01, "kg")).toBe(0.25);
    expect(shoppingQty(120, "g")).toBe(150);
    expect(shoppingQty(1.2, "pack")).toBe(2);
    expect(shoppingQty(2, "piece")).toBe(2);
    expect(shoppingQty(5000, "kg")).toBe(999);
  });

  it("scales to people and merges the same ingredient across meals", () => {
    const merged = scaleAndMerge(
      [
        {
          servings: 4,
          ingredients: [
            { canonicalId: "onion", name: "onion", qty: 0.2, unit: "kg" },
            { name: "Cumin", qty: 1, unit: "pack" },
          ],
        },
        {
          servings: 4,
          ingredients: [
            { canonicalId: "onion", name: "onions", qty: 0.3, unit: "kg" },
            { name: "cumin ", qty: 1, unit: "pack" },
          ],
        },
      ],
      8,
    );
    expect(merged).toEqual([
      { canonicalId: "onion", name: "onion", qty: 1, unit: "kg" },
      { canonicalId: null, name: "Cumin", qty: 4, unit: "pack" },
    ]);
  });
});

describe("LLM meal planner (LLM_MODEL_SMART, replayed)", () => {
  const request: PlanRequest = {
    occasion: "iftar",
    cuisines: ["levantine"],
    people: 6,
    count: 1,
    languages: ["ar", "tr"],
    candidates: candidateDishes("iftar", ["levantine"], []).slice(0, 2),
  };
  const meal = {
    dishId: "lentil_soup",
    title: { ar: "شوربة عدس", tr: "Mercimek çorbası" },
    description: { ar: "شوربة دافئة لبداية الإفطار.", tr: "İftara sıcak bir başlangıç." },
    cuisine: "levantine",
    tags: ["soup"],
    ingredients: [
      { canonicalId: "lentils_red", name: "red lentils", qty: 0.45, unit: "kg" },
      { canonicalId: "unicorn", name: "magic", qty: 1, unit: "pack" },
    ],
  };

  it("validates, drops unknown canonical ids, and replays from a recording offline", async () => {
    const dir = mkdtempSync(join(tmpdir(), "meals-"));
    const real: LlmTransport = { complete: () => Promise.resolve({ meals: [meal] }) };
    const recorded = await new LlmMealPlanner(
      new RecordingTransport(real, dir),
      "claude-sonnet-5-5",
      catalogIds,
    ).plan(request);
    expect(recorded[0]?.ingredients.map((i) => i.canonicalId)).toEqual(["lentils_red", null]);
    const replayed = await new LlmMealPlanner(new ReplayTransport(dir), "claude-sonnet-5-5", catalogIds).plan(
      request,
    );
    expect(replayed).toEqual(recorded);
  });

  it("wrong count or a missing language: one retry, then unavailable", async () => {
    let calls = 0;
    const missingTurkish: LlmTransport = {
      complete: () => {
        calls++;
        return Promise.resolve({ meals: [{ ...meal, title: { ar: "شوربة" } }] });
      },
    };
    await expect(new LlmMealPlanner(missingTurkish, "m", catalogIds).plan(request)).rejects.toBeInstanceOf(
      LlmUnavailableError,
    );
    expect(calls).toBe(2);
    const twoMeals: LlmTransport = { complete: () => Promise.resolve({ meals: [meal, meal] }) };
    await expect(new LlmMealPlanner(twoMeals, "m", catalogIds).plan(request)).rejects.toBeInstanceOf(
      LlmUnavailableError,
    );
  });
});

/**
 * Prompt B8: "one real recorded response per occasion is saved for my review". Runs only with
 * RECORD=1 and a key (see RUNBOOK); writes replayable recordings plus a readable review file.
 */
describe.runIf(process.env.RECORD === "1")("record one real meal plan per occasion (owner review)", () => {
  it("records iftar, suhoor, everyday, guests and eid", { timeout: 600_000 }, async () => {
    const { AnthropicTransport } = await import("../src/llm/anthropic.js");
    const { writeFileSync } = await import("node:fs");
    const dir = join(import.meta.dirname, "recordings");
    const planner = new LlmMealPlanner(
      new RecordingTransport(new AnthropicTransport(process.env.ANTHROPIC_API_KEY ?? ""), dir),
      process.env.LLM_MODEL_SMART ?? "claude-sonnet-5-5",
      catalogIds,
    );
    const review: Record<string, unknown> = {};
    for (const occasion of OCCASIONS) {
      review[occasion] = await planner.plan({
        occasion,
        cuisines: [],
        people: 6,
        count: suggestionCount(3),
        languages: ["ar", "tr", "en"],
        candidates: candidateDishes(occasion, [], []),
      });
    }
    writeFileSync(join(dir, "meals-review.json"), `${JSON.stringify(review, null, 2)}\n`);
  });
});
