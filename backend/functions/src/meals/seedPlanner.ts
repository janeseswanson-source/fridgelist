import { Catalog } from "../lib/catalog.js";
import { LlmUnavailableError } from "../llm/transport.js";
import type { Cuisine, SeedDish } from "./dishes.js";
import type { MealPlanner, PlanRequest, PlannedMeal } from "./planner.js";

type Lang = "ar" | "tr" | "en";

const SERVES: Record<Lang, (n: number) => string> = {
  ar: (n) => `يكفي ${String(n)} أشخاص`,
  tr: (n) => `${String(n)} kişilik`,
  en: (n) => `Serves ${String(n)}`,
};

const lang = (l: string): Lang => (l === "ar" || l === "tr" ? l : "en");

/** Fisher–Yates with an injectable random source (tests pass a seeded one). */
function shuffled<T>(items: readonly T[], random: () => number): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j] as T, out[i] as T];
  }
  return out;
}

/**
 * Meal ideas without an LLM (owner choice: no paid AI at launch): the reviewed seed dishes for the
 * occasion, shuffled and taken round-robin across cuisines so a plan mixes Levantine, Gulf,
 * Turkish and Egyptian. Ingredients are scaled to the number of people. Titles come from the seed
 * names; the description is the servings.
 */
export class SeedMealPlanner implements MealPlanner {
  constructor(
    private readonly catalog: Catalog = Catalog.bundled,
    private readonly random: () => number = Math.random,
  ) {}

  plan(request: PlanRequest): Promise<PlannedMeal[]> {
    const byCuisine = new Map<Cuisine, SeedDish[]>();
    for (const dish of shuffled(request.candidates, this.random)) {
      byCuisine.set(dish.cuisine, [...(byCuisine.get(dish.cuisine) ?? []), dish]);
    }
    const queues = shuffled([...byCuisine.values()], this.random);
    const picked: SeedDish[] = [];
    while (picked.length < request.count && queues.some((q) => q.length > 0)) {
      for (const queue of queues) {
        const dish = queue.shift();
        if (dish && picked.length < request.count) picked.push(dish);
      }
    }
    return Promise.resolve(picked.map((dish) => this.meal(dish, request)));
  }

  private meal(dish: SeedDish, request: PlanRequest): PlannedMeal {
    const record = (f: (l: Lang) => string) =>
      Object.fromEntries(request.languages.map((l) => [l, f(lang(l))]));
    const scale = request.people / dish.servings;
    return {
      dishId: dish.id,
      title: record((l) => dish.names[l]),
      // Apps already show the cuisine as a label; the description adds the servings.
      description: record((l) => SERVES[l](request.people)),
      cuisine: dish.cuisine,
      tags: [dish.cuisine],
      ingredients: dish.ingredients.map((i) => ({
        canonicalId: i.canonicalId ?? null,
        name: i.canonicalId
          ? (this.catalog.item(i.canonicalId)?.names.en ?? i.canonicalId)
          : (i.names?.en ?? Object.values(i.names ?? {})[0] ?? dish.id),
        // Counted units stay whole; weights and volumes keep two decimals (scaleAndMerge rounds for shopping).
        qty: Math.min(999, Math.round(i.qty * scale * 100) / 100),
        unit: i.unit as PlannedMeal["ingredients"][number]["unit"],
      })),
    };
  }
}

/** The LLM when it answers; the seed planner when it can't (no key, outage, invalid twice). */
export class FallbackMealPlanner implements MealPlanner {
  constructor(
    private readonly primary: MealPlanner,
    private readonly fallback: MealPlanner,
  ) {}

  async plan(request: PlanRequest): Promise<PlannedMeal[]> {
    try {
      return await this.primary.plan(request);
    } catch (error) {
      if (error instanceof LlmUnavailableError) return this.fallback.plan(request);
      throw error;
    }
  }
}
