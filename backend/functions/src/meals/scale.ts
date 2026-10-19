import { matchKey } from "../lib/normalize.js";

export interface MealIngredient {
  canonicalId?: string | null;
  name: string;
  qty?: number | null;
  unit?: string | null;
}

/** Units sold by weight or volume are rounded up to sensible steps; counted ones to whole units. */
const STEP: Record<string, number> = { kg: 0.25, g: 50, l: 0.25, ml: 50 };

/** Rounds a needed amount up to what you would actually buy (never 0). */
export function shoppingQty(qty: number, unit: string | null | undefined): number {
  const step = unit ? STEP[unit] : undefined;
  if (step) return Math.min(999, Math.max(step, Math.ceil(qty / step - 1e-9) * step));
  return Math.min(999, Math.max(1, Math.ceil(qty - 1e-9)));
}

/**
 * Scales each meal's ingredients from its `servings` to `people`, merges the same ingredient
 * across meals (same canonicalId, else same normalized name; same unit → quantities add) and
 * rounds the totals for shopping.
 */
export function scaleAndMerge(
  meals: readonly { servings: number; ingredients: readonly MealIngredient[] }[],
  people: number,
): MealIngredient[] {
  const merged = new Map<string, MealIngredient>();
  for (const meal of meals) {
    const factor = people / Math.max(1, meal.servings);
    for (const ing of meal.ingredients) {
      const key = `${ing.canonicalId ?? `name:${matchKey(ing.name)}`}|${ing.unit ?? ""}`;
      const scaled = ing.qty != null ? ing.qty * factor : null;
      const existing = merged.get(key);
      if (existing) {
        if (scaled !== null) existing.qty = (existing.qty ?? 0) + scaled;
      } else {
        merged.set(key, {
          canonicalId: ing.canonicalId ?? null,
          name: ing.name,
          qty: scaled,
          unit: ing.unit ?? null,
        });
      }
    }
  }
  return [...merged.values()].map((i) => ({ ...i, qty: i.qty != null ? shoppingQty(i.qty, i.unit) : null }));
}
