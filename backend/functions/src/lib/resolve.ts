import type { ItemLanguage } from "../config.js";
import type { ClassifiedItem, LlmProvider } from "../llm/provider.js";
import { LlmUnavailableError } from "../llm/transport.js";
import { Catalog, resolveDialect, type Ambiguity } from "./catalog.js";
import { isCategory, type Category } from "./categories.js";
import { detectLanguage } from "./detectLang.js";
import { isTurkish, matchKey } from "./normalize.js";

/** Below this the apps ask which meaning was meant (prompt B4, Android Limits.AMBIGUOUS_CONFIDENCE). */
export const AMBIGUOUS_CONFIDENCE = 0.6;

export type ResolvedSource = "alias" | "catalog" | "dialect" | "ambiguous" | "cache" | "llm" | "unresolved";

export interface Resolved {
  name: string;
  lang: ItemLanguage;
  canonicalId: string | null;
  category: Category;
  /** Translations per target language; the item's own language keeps the name as typed. */
  names: Record<string, string>;
  confidence: number;
  source: ResolvedSource;
  /** "alternatives: rice, bread" for an ambiguous dialect word (the apps may show it). */
  notes?: string;
}

/** catalogCandidates (CONTRACT §3.15): LLM results for names outside the catalog. */
export interface CandidateCache {
  get(normalized: string): Promise<CachedCandidate | null>;
  put(normalized: string, candidate: CachedCandidate): Promise<void>;
}
export interface CachedCandidate {
  lang: string;
  category: Category;
  names: Record<string, string>;
}

export interface ResolveInput {
  name: string;
  /** The splitter's local guess, when it already has one. */
  canonicalId?: string | null;
  ambiguity?: Ambiguity | null;
}

export interface ResolveContext {
  localeHints: string[];
  aliases: ReadonlyMap<string, string>;
  targetLanguages: string[];
  llm: LlmProvider;
  cache?: CandidateCache;
  catalog?: Catalog;
}

function catalogNames(catalog: Catalog, canonicalId: string, targets: readonly string[], typed: string, lang: string) {
  const names: Record<string, string> = {};
  const item = catalog.item(canonicalId);
  for (const target of targets) {
    const name = target === lang ? typed : item?.names[target];
    if (name) names[target] = name;
  }
  return names;
}

/**
 * Resolution order (ARCHITECTURE §4.1, CONTRACT §3.16): household alias → dialect rule with
 * locale hints → catalog synonym (longest first) → cache → LLM for the rest. An LLM failure keeps
 * the local result (category `other`) and never throws: adding must not block.
 */
export async function resolveItems(inputs: readonly ResolveInput[], ctx: ResolveContext): Promise<Resolved[]> {
  const catalog = ctx.catalog ?? Catalog.bundled;
  const turkish = isTurkish(ctx.localeHints);
  const results: (Resolved | null)[] = [];
  const pending: { index: number; normalized: string }[] = [];

  for (const [index, input] of inputs.entries()) {
    const lang = detectLanguage(input.name, ctx.localeHints, catalog);
    const keys = matchKey(input.name, turkish).split(" ");
    const canonicalId = input.canonicalId ?? catalog.guessCanonical(keys, ctx.localeHints, ctx.aliases);
    const ambiguity = canonicalId === null ? (input.ambiguity ?? catalog.ambiguity(keys, ctx.localeHints, ctx.aliases)) : null;

    if (canonicalId !== null) {
      const viaAlias = ctx.aliases.has(keys.join(" ")) || keys.some((k) => ctx.aliases.has(k));
      const rule = keys.map((k) => catalog.dialectRule(k)).find((r) => r !== null);
      const viaDialect = !viaAlias && rule !== undefined && resolveDialect(rule, ctx.localeHints) === canonicalId;
      const category = catalog.item(canonicalId)?.category;
      results.push({
        name: input.name,
        lang,
        canonicalId,
        category: isCategory(category) ? category : "other",
        names: catalogNames(catalog, canonicalId, ctx.targetLanguages, input.name, lang),
        confidence: viaAlias ? 1 : viaDialect ? 0.85 : 0.95,
        source: viaAlias ? "alias" : viaDialect ? "dialect" : "catalog",
      });
    } else if (ambiguity !== null) {
      const [best = "", ...others] = ambiguity.candidates;
      const category = catalog.item(best)?.category;
      results.push({
        name: input.name,
        lang,
        canonicalId: best,
        category: isCategory(category) ? category : "other",
        names: catalogNames(catalog, best, ctx.targetLanguages, input.name, lang),
        confidence: 0.4,
        source: "ambiguous",
        notes: `alternatives: ${[best, ...others].join(", ")}`,
      });
    } else {
      results.push(null);
      pending.push({ index, normalized: matchKey(input.name, turkish) });
    }
  }

  // Cache, then one LLM batch for what is still unknown.
  const toAsk: { index: number; normalized: string }[] = [];
  for (const p of pending) {
    const cached = ctx.cache ? await ctx.cache.get(p.normalized) : null;
    const input = inputs[p.index];
    if (cached && input && ctx.targetLanguages.every((t) => t === cached.lang || cached.names[t])) {
      results[p.index] = {
        name: input.name,
        lang: detectLanguage(input.name, ctx.localeHints, catalog),
        canonicalId: null,
        category: cached.category,
        names: { ...cached.names, [cached.lang]: input.name },
        confidence: 0.8,
        source: "cache",
      };
    } else {
      toAsk.push(p);
    }
  }

  let classified: ClassifiedItem[] | null = null;
  if (toAsk.length > 0) {
    try {
      classified = await ctx.llm.classify({
        names: toAsk.map((p) => inputs[p.index]?.name ?? ""),
        localeHints: ctx.localeHints,
        targetLanguages: ctx.targetLanguages,
      });
    } catch (error) {
      if (!(error instanceof LlmUnavailableError)) throw error;
    }
  }

  for (const [i, p] of toAsk.entries()) {
    const input = inputs[p.index];
    if (!input) continue;
    const llm = classified?.[i];
    const lang = detectLanguage(input.name, ctx.localeHints, catalog);
    if (llm) {
      const names = { ...pick(llm.names, ctx.targetLanguages), [lang]: input.name };
      results[p.index] = {
        name: input.name,
        lang,
        canonicalId: llm.canonicalId,
        category: llm.category,
        names: llm.canonicalId ? { ...catalogNames(catalog, llm.canonicalId, ctx.targetLanguages, input.name, lang), ...names } : names,
        confidence: llm.confidence,
        source: "llm",
      };
      if (llm.canonicalId === null && ctx.cache) {
        await ctx.cache.put(p.normalized, { lang, category: llm.category, names });
      }
    } else {
      results[p.index] = {
        name: input.name,
        lang,
        canonicalId: null,
        category: "other",
        names: ctx.targetLanguages.includes(lang) ? { [lang]: input.name } : {},
        confidence: 0.3,
        source: "unresolved",
      };
    }
  }
  return results.filter((r): r is Resolved => r !== null);
}

function pick(names: Record<string, string>, languages: readonly string[]): Record<string, string> {
  return Object.fromEntries(Object.entries(names).filter(([lang]) => languages.includes(lang)));
}
