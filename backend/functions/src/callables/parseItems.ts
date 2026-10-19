import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import type { LlmProvider } from "../llm/provider.js";
import { detectLanguage } from "../lib/detectLang.js";
import { Catalog } from "../lib/catalog.js";
import { LocalSplitter } from "../lib/localSplit.js";
import { log } from "../lib/log.js";
import { consume } from "../lib/rateLimit.js";
import { parse, requireMember, requireUid } from "../lib/requests.js";
import { resolveItems, type CandidateCache, type ResolveContext } from "../lib/resolve.js";
import { id } from "../lib/schemas.js";
import { ANTHROPIC_API_KEY, FirestoreCandidateCache, fastLlm } from "../services.js";

export const ParseItemsRequest = z.object({
  householdId: id,
  text: z.string().max(500),
  localeHints: z.array(z.string().max(20)).max(5),
});

/** CONTRACT §4 ParsedItem. Optional fields are omitted, not null (CONTRACT §1). */
export interface ParsedItem {
  name: string;
  canonicalId?: string;
  qty?: number;
  unit?: string;
  notes?: string;
  category: string;
  names: Record<string, string>;
  confidence: number;
}

export interface ParseItemsResponse {
  items: ParsedItem[];
  detectedLang: string;
  source: "catalog" | "llm" | "mixed";
}

/** Household aliases as normalized text → canonicalId (CONTRACT §3.16). */
export async function householdAliases(db: Firestore, householdId: string): Promise<Map<string, string>> {
  const snap = await db.collection(`households/${householdId}/aliases`).get();
  const map = new Map<string, string>();
  for (const doc of snap.docs) {
    const text: unknown = doc.get("text");
    const canonicalId: unknown = doc.get("canonicalId");
    if (typeof text === "string" && typeof canonicalId === "string") map.set(text, canonicalId);
  }
  return map;
}

/**
 * Splits locally (same rules as the apps, fixtures/split_cases.json), then resolves each item:
 * alias → dialect → catalog → cache → LLM. No writes except the rate-limit counter and the
 * LLM cache. Ambiguous dialect words come back with confidence < 0.6 and the alternatives in
 * `notes`, so the apps ask once.
 */
export async function parseItemsCore(
  db: Firestore,
  uid: string,
  data: unknown,
  llm: LlmProvider,
  cache?: CandidateCache,
): Promise<ParseItemsResponse> {
  const input = parse(ParseItemsRequest, data);
  await requireMember(db, input.householdId, uid);
  await consume(db, "parseItems", uid);

  const [aliases, household] = await Promise.all([
    householdAliases(db, input.householdId),
    db.doc(`households/${input.householdId}`).get(),
  ]);
  const targetLanguages = (household.get("memberLanguages") as string[] | undefined) ?? [];
  const result = await parseText(input.text, {
    localeHints: input.localeHints,
    aliases,
    targetLanguages,
    llm,
    cache,
  });
  const viaLlm = result.items.length > 0 && result.source !== "catalog";
  log.info("parse_items", {
    householdId: input.householdId,
    items: result.items.length,
    viaLlm,
    source: result.source,
  });
  return result;
}

/**
 * The pure pipeline (no Firestore): local split with the apps' rules, then resolve each item.
 * Shared by `parseItems` and the golden test over fixtures/parse_cases.json.
 */
export async function parseText(
  text: string,
  ctx: Omit<ResolveContext, "catalog">,
): Promise<ParseItemsResponse> {
  const split = new LocalSplitter().split(text, ctx.localeHints, ctx.aliases);
  const resolved = await resolveItems(split, ctx);
  const items = resolved.map((r, i): ParsedItem => {
    const local = split[i];
    const qty = local?.qty;
    return {
      name: r.name,
      ...(r.canonicalId !== null ? { canonicalId: r.canonicalId } : {}),
      ...(qty != null && qty > 0 && qty <= 999 ? { qty } : {}),
      ...(local?.unit ? { unit: local.unit } : {}),
      ...(r.notes ? { notes: r.notes } : {}),
      category: r.category,
      names: r.names,
      confidence: r.confidence,
    };
  });
  const viaLlm = resolved.filter((r) => r.source === "llm").length;
  const source = viaLlm === 0 ? "catalog" : viaLlm === resolved.length ? "llm" : "mixed";
  const detectedLang = resolved[0]?.lang ?? detectLanguage(text, ctx.localeHints, Catalog.bundled);
  return { items, detectedLang, source };
}

export const parseItems = onCall({ secrets: [ANTHROPIC_API_KEY] }, (request) =>
  parseItemsCore(getFirestore(), requireUid(request), request.data, fastLlm(), new FirestoreCandidateCache()),
);
