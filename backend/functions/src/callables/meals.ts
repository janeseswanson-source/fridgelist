import { Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import { LlmUnavailableError } from "../llm/transport.js";
import type { LlmProvider } from "../llm/provider.js";
import { log } from "../lib/log.js";
import { matchKey } from "../lib/normalize.js";
import { consume } from "../lib/rateLimit.js";
import { parse, requireMember, requireUid } from "../lib/requests.js";
import { resolveItems } from "../lib/resolve.js";
import { id, itemLanguage } from "../lib/schemas.js";
import { CUISINES, OCCASIONS, candidateDishes, suggestionCount } from "../meals/dishes.js";
import type { MealPlanner } from "../meals/planner.js";
import { scaleAndMerge, type MealIngredient } from "../meals/scale.js";
import { ANTHROPIC_API_KEY, fastLlm, smartMealPlanner } from "../services.js";
import type { ParsedItem } from "./parseItems.js";

/** CONTRACT §3.10: suggestions expire after 7 days (TTL on `expireAt`, B9). */
export const MEAL_TTL_DAYS = 7;
/** PRODUCT_SPEC F11: items bought in the last 7 days are skipped. */
export const RECENTLY_BOUGHT_DAYS = 7;

export const SuggestMealsRequest = z.object({
  householdId: id,
  people: z.number().int().min(1).max(30),
  occasion: z.enum(OCCASIONS),
  cuisines: z.array(z.enum(CUISINES)).max(5).optional(),
  days: z.number().int().min(1).max(7).optional(),
  exclude: z.array(z.string().max(60)).max(50).optional(),
  lang: itemLanguage,
});

export interface SuggestedMeal {
  mealId: string;
  title: string;
  description: string;
  servings: number;
  cuisine: string;
  tags: string[];
}

/**
 * Grounded on the seed dishes, planned by LLM_MODEL_SMART for `people` servings, cached in
 * `mealSuggestions` (7 days). 10 calls per hour per household. No LLM → `unavailable`
 * (ARCHITECTURE §3.5: no offline fallback for meals).
 */
export async function suggestMealsCore(
  db: Firestore,
  uid: string,
  data: unknown,
  planner: MealPlanner,
  now: Date = new Date(),
): Promise<{ meals: SuggestedMeal[] }> {
  const input = parse(SuggestMealsRequest, data);
  await requireMember(db, input.householdId, uid);
  await consume(db, "suggestMeals", input.householdId, now);
  const household = await db.doc(`households/${input.householdId}`).get();
  const languages = [...new Set([input.lang, String(household.get("defaultLanguage") ?? "en")])];
  const cuisines = input.cuisines ?? [];
  const count = suggestionCount(input.days);

  let planned;
  try {
    planned = await planner.plan({
      occasion: input.occasion,
      cuisines,
      people: input.people,
      count,
      languages,
      candidates: candidateDishes(input.occasion, cuisines, input.exclude ?? []),
    });
  } catch (error) {
    if (error instanceof LlmUnavailableError)
      throw new HttpsError("unavailable", "Meal ideas are unavailable right now.");
    throw error;
  }

  const collection = db.collection(`households/${input.householdId}/mealSuggestions`);
  const batch = db.batch();
  const meals = planned.map((meal): SuggestedMeal => {
    const ref = collection.doc();
    batch.set(ref, {
      title: meal.title,
      description: meal.description,
      servings: input.people,
      occasion: input.occasion,
      cuisine: meal.cuisine,
      ingredients: meal.ingredients.map((i) => ({
        ...(i.canonicalId ? { canonicalId: i.canonicalId } : {}),
        name: i.name,
        ...(i.qty !== null ? { qty: i.qty } : {}),
        ...(i.unit !== null ? { unit: i.unit } : {}),
      })),
      createdAt: Timestamp.fromDate(now),
      expireAt: Timestamp.fromMillis(now.getTime() + MEAL_TTL_DAYS * 86_400_000),
    });
    return {
      mealId: ref.id,
      title: meal.title[input.lang] ?? meal.title.en ?? Object.values(meal.title)[0] ?? "",
      description:
        meal.description[input.lang] ?? meal.description.en ?? Object.values(meal.description)[0] ?? "",
      servings: input.people,
      cuisine: meal.cuisine,
      tags: meal.tags,
    };
  });
  await batch.commit();
  log.info("meals_suggested", {
    householdId: input.householdId,
    occasion: input.occasion,
    count: meals.length,
  });
  return { meals };
}

export const MealToItemsRequest = z.object({
  householdId: id,
  listId: id,
  mealIds: z.array(id).min(1).max(10),
  people: z.number().int().min(1).max(30),
  lang: itemLanguage,
});

export interface Skipped {
  name: string;
  reason: "on_list" | "recently_bought";
}

/** Keys an item or purchase can be matched by: canonicalId, and every name it has. */
function keysOf(doc: { canonicalId?: unknown; name?: unknown; names?: unknown }): Set<string> {
  const keys = new Set<string>();
  if (typeof doc.canonicalId === "string") keys.add(`id:${doc.canonicalId}`);
  if (typeof doc.name === "string") keys.add(`name:${matchKey(doc.name)}`);
  if (doc.names && typeof doc.names === "object") {
    for (const n of Object.values(doc.names as Record<string, unknown>))
      if (typeof n === "string") keys.add(`name:${matchKey(n)}`);
  }
  return keys;
}

/**
 * The chosen meals' ingredients for `people`, merged and rounded for shopping, minus what is
 * already on the list (open, claimed or requested) or was bought in the last 7 days. Names come
 * back in `lang`. No writes: the app confirms, then writes them with `addedVia: meal_plan`.
 */
export async function mealToItemsCore(
  db: Firestore,
  uid: string,
  data: unknown,
  llm: LlmProvider,
  now: Date = new Date(),
): Promise<{ items: ParsedItem[]; skipped: Skipped[] }> {
  const input = parse(MealToItemsRequest, data);
  await requireMember(db, input.householdId, uid);
  const h = db.doc(`households/${input.householdId}`);
  const [list, household, meals, listItems, purchases] = await Promise.all([
    h.collection("lists").doc(input.listId).get(),
    h.get(),
    db.getAll(...input.mealIds.map((m) => h.collection("mealSuggestions").doc(m))),
    h.collection(`lists/${input.listId}/items`).where("status", "in", ["open", "claimed", "pending"]).get(),
    h
      .collection("purchases")
      .where("at", ">=", Timestamp.fromMillis(now.getTime() - RECENTLY_BOUGHT_DAYS * 86_400_000))
      .get(),
  ]);
  if (!list.exists) throw new HttpsError("not-found", "No such list.");
  const found = meals.filter(
    (m) => m.exists && ((m.get("expireAt") as Timestamp | undefined)?.toMillis() ?? 0) > now.getTime(),
  );
  if (found.length === 0) throw new HttpsError("not-found", "These meal ideas have expired.");

  const ingredients = scaleAndMerge(
    found.map((m) => ({
      servings: Number(m.get("servings") ?? 4),
      ingredients: (m.get("ingredients") as MealIngredient[] | undefined) ?? [],
    })),
    input.people,
  );
  const onList = new Set(listItems.docs.flatMap((d) => [...keysOf(d.data())]));
  const bought = new Set(purchases.docs.flatMap((d) => [...keysOf(d.data())]));

  const targets = [
    ...new Set([input.lang, ...((household.get("memberLanguages") as string[] | undefined) ?? [])]),
  ];
  const resolved = await resolveItems(
    ingredients.map((i) => ({ name: i.name, canonicalId: i.canonicalId ?? null })),
    { localeHints: ["en"], aliases: new Map(), targetLanguages: targets, llm, cache: undefined },
  );

  const items: ParsedItem[] = [];
  const skipped: Skipped[] = [];
  resolved.forEach((r, i) => {
    const ing = ingredients[i];
    if (!ing) return;
    const name = r.names[input.lang] ?? r.name;
    const keys = keysOf({ canonicalId: r.canonicalId, name: ing.name, names: r.names });
    const hit = (set: Set<string>) => [...keys].some((k) => set.has(k));
    if (hit(onList)) return skipped.push({ name, reason: "on_list" });
    if (hit(bought)) return skipped.push({ name, reason: "recently_bought" });
    items.push({
      name,
      ...(r.canonicalId ? { canonicalId: r.canonicalId } : {}),
      ...(ing.qty != null ? { qty: ing.qty } : {}),
      ...(ing.unit ? { unit: ing.unit } : {}),
      category: r.category,
      names: r.names,
      confidence: r.confidence,
    });
  });
  log.info("meal_to_items", {
    householdId: input.householdId,
    meals: found.length,
    items: items.length,
    skipped: skipped.length,
  });
  return { items, skipped };
}

export const suggestMeals = onCall(
  { secrets: [ANTHROPIC_API_KEY], timeoutSeconds: 120, memory: "512MiB" },
  (request) => suggestMealsCore(getFirestore(), requireUid(request), request.data, smartMealPlanner()),
);
export const mealToItems = onCall({ secrets: [ANTHROPIC_API_KEY] }, (request) =>
  mealToItemsCore(getFirestore(), requireUid(request), request.data, fastLlm()),
);
