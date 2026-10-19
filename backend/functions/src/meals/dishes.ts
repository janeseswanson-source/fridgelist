import seed from "./seed_dishes.json" with { type: "json" };
import { matchKey } from "../lib/normalize.js";

/** CONTRACT §2. */
export const OCCASIONS = ["everyday", "iftar", "suhoor", "guests", "eid"] as const;
export type Occasion = (typeof OCCASIONS)[number];
export const CUISINES = ["levantine", "gulf", "turkish", "egyptian", "international"] as const;
export type Cuisine = (typeof CUISINES)[number];

export interface SeedIngredient {
  canonicalId?: string;
  names?: Record<string, string>;
  qty: number;
  unit: string;
}

export interface SeedDish {
  id: string;
  names: Record<"ar" | "tr" | "en", string>;
  cuisine: Cuisine;
  occasions: Occasion[];
  servings: number;
  ingredients: SeedIngredient[];
}

export const DISHES: readonly SeedDish[] = (seed as { dishes: SeedDish[] }).dishes;

/**
 * Seed dishes that fit the request, used to ground the model: the occasion, the cuisines asked
 * for (all when none), minus dishes the household excluded (by id or by any of its names).
 * Stable order (seed order) so recordings replay.
 */
export function candidateDishes(
  occasion: Occasion,
  cuisines: readonly Cuisine[],
  exclude: readonly string[],
): SeedDish[] {
  const excluded = new Set(exclude.map((e) => matchKey(e)));
  return DISHES.filter(
    (d) =>
      d.occasions.includes(occasion) &&
      (cuisines.length === 0 || cuisines.includes(d.cuisine)) &&
      !excluded.has(matchKey(d.id)) &&
      !Object.values(d.names).some((n) => excluded.has(matchKey(n))),
  );
}

/** PRODUCT_SPEC F11: 6–10 suggestions; more days → more to choose from. */
export function suggestionCount(days: number | undefined): number {
  return Math.min(10, Math.max(6, (days ?? 1) + 5));
}
