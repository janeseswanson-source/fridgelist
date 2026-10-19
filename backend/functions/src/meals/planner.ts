import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { z } from "zod";
import { UNITS } from "../lib/units.js";
import { LlmUnavailableError, type LlmTransport } from "../llm/transport.js";
import { CUISINES, type Cuisine, type Occasion, type SeedDish } from "./dishes.js";

const PROMPT = readFileSync(resolve(import.meta.dirname, "../llm/prompts/meals.md"), "utf8");

const Ingredient = z.object({
  canonicalId: z.string().nullable(),
  name: z.string().min(1).max(80),
  qty: z.number().positive().max(999).nullable(),
  unit: z.enum(UNITS).nullable(),
});

const PlannedMeal = z.object({
  dishId: z.string().nullable(),
  title: z.record(z.string(), z.string().min(1).max(80)),
  description: z.record(z.string(), z.string().min(1).max(240)),
  cuisine: z.enum(CUISINES),
  tags: z.array(z.string().min(1).max(20)).max(4),
  ingredients: z.array(Ingredient).min(1).max(30),
});
export type PlannedMeal = z.infer<typeof PlannedMeal>;
const PlanOutput = z.object({ meals: z.array(PlannedMeal) });

export interface PlanRequest {
  occasion: Occasion;
  cuisines: readonly Cuisine[];
  people: number;
  count: number;
  languages: readonly string[];
  candidates: readonly SeedDish[];
}

export interface MealPlanner {
  /** Exactly `count` meals for `people` servings; throws LlmUnavailableError otherwise. */
  plan(request: PlanRequest): Promise<PlannedMeal[]>;
}

/**
 * LLM_MODEL_SMART through a forced tool call (ARCHITECTURE §3.5): zod-validated, one retry, then
 * LlmUnavailableError. Meals have no offline fallback ("error for meals"); suggestMeals turns it
 * into `unavailable`.
 */
export class LlmMealPlanner implements MealPlanner {
  constructor(
    private readonly transport: LlmTransport,
    private readonly model: string,
    private readonly catalogIds: readonly string[],
  ) {}

  async plan(request: PlanRequest): Promise<PlannedMeal[]> {
    const tool = {
      model: this.model,
      system: `${PROMPT}\nCatalog ids: ${this.catalogIds.join(", ")}`,
      user: JSON.stringify({
        occasion: request.occasion,
        cuisines: request.cuisines,
        servings: request.people,
        meals: request.count,
        languages: request.languages,
        candidates: request.candidates.map((d) => ({
          id: d.id,
          names: d.names,
          cuisine: d.cuisine,
          servings: d.servings,
          ingredients: d.ingredients,
        })),
      }),
      toolName: "plan_meals",
      toolDescription: "Return the planned meals.",
      inputSchema: z.toJSONSchema(PlanOutput) as Record<string, unknown>,
      maxTokens: 8_000,
    };
    const ids = new Set(this.catalogIds);
    for (let attempt = 0; attempt < 2; attempt++) {
      const parsed = PlanOutput.safeParse(await this.transport.complete(tool));
      const complete =
        parsed.success &&
        parsed.data.meals.length === request.count &&
        parsed.data.meals.every((m) => request.languages.every((l) => m.title[l] && m.description[l]));
      if (parsed.success && complete) {
        return parsed.data.meals.map((m) => ({
          ...m,
          ingredients: m.ingredients.map((i) => ({
            ...i,
            canonicalId: i.canonicalId && ids.has(i.canonicalId) ? i.canonicalId : null,
          })),
        }));
      }
    }
    throw new LlmUnavailableError("meal plan invalid twice");
  }
}

export const NO_MEALS: MealPlanner = {
  plan: () => Promise.reject(new LlmUnavailableError("no LLM configured")),
};
