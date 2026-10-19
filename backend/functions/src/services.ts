import { getFirestore, Timestamp, type Firestore } from "firebase-admin/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import { AnthropicTransport } from "./llm/anthropic.js";
import { ClassifyingLlm, NO_LLM, type LlmProvider } from "./llm/provider.js";
import { Catalog } from "./lib/catalog.js";
import { LlmMealPlanner, type MealPlanner } from "./meals/planner.js";
import { FallbackMealPlanner, SeedMealPlanner } from "./meals/seedPlanner.js";
import type { CachedCandidate, CandidateCache } from "./lib/resolve.js";
import { createHash } from "node:crypto";
import { configured } from "./lib/secrets.js";

/**
 * Secrets and params (prompt 01 stack). No key (empty locally, `none` when deployed) means no LLM:
 * the catalog and fallbacks only, at no cost. Both models default to Haiku to keep costs low.
 */
export const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");
export const LLM_MODEL_FAST = defineString("LLM_MODEL_FAST", { default: "claude-haiku-5-5" });
export const LLM_MODEL_SMART = defineString("LLM_MODEL_SMART", { default: "claude-haiku-5-5" });

/** The fast model for parsing, classification and translation; NO_LLM without a key. */
export function fastLlm(): LlmProvider {
  const key = configured(ANTHROPIC_API_KEY.value());
  if (!key) return NO_LLM;
  return new ClassifyingLlm(
    new AnthropicTransport(key),
    LLM_MODEL_FAST.value(),
    Catalog.bundled.items.map((item) => item.id),
  );
}

/**
 * Meal plans (prompt B8): the smart model when a key is set, falling back to the reviewed seed
 * dishes when it fails; with no key, the seed dishes alone (no LLM cost).
 */
export function smartMealPlanner(): MealPlanner {
  const key = configured(ANTHROPIC_API_KEY.value());
  if (!key) return new SeedMealPlanner();
  const llm = new LlmMealPlanner(
    new AnthropicTransport(key),
    LLM_MODEL_SMART.value(),
    Catalog.bundled.items.map((item) => item.id),
  );
  return new FallbackMealPlanner(llm, new SeedMealPlanner());
}

/** `catalogCandidates/{hash}` (CONTRACT §3.15). */
export class FirestoreCandidateCache implements CandidateCache {
  constructor(private readonly db: Firestore = getFirestore()) {}

  private ref(normalized: string) {
    return this.db.doc(
      `catalogCandidates/${createHash("sha256").update(normalized).digest("hex").slice(0, 32)}`,
    );
  }

  async get(normalized: string): Promise<CachedCandidate | null> {
    const snap = await this.ref(normalized).get();
    if (!snap.exists) return null;
    const data = snap.data() as CachedCandidate & { hits?: number };
    await this.ref(normalized).update({ hits: (data.hits ?? 0) + 1 });
    return { lang: data.lang, category: data.category, names: data.names };
  }

  async put(normalized: string, candidate: CachedCandidate): Promise<void> {
    await this.ref(normalized).set({ normalized, ...candidate, hits: 1, createdAt: Timestamp.now() });
  }
}
