import { FieldValue, getFirestore, type DocumentData, type Firestore } from "firebase-admin/firestore";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { householdAliases } from "../callables/parseItems.js";
import type { LlmProvider } from "../llm/provider.js";
import { writeEvent, memberName } from "../lib/events.js";
import { log } from "../lib/log.js";
import { findMergeTarget, mergePatch, type MergeableItem } from "../lib/merge.js";
import { resolveItems, type CandidateCache } from "../lib/resolve.js";
import { ANTHROPIC_API_KEY, FirestoreCandidateCache, fastLlm } from "../services.js";

export interface ItemRef {
  householdId: string;
  listId: string;
  itemId: string;
}

export type CreatedOutcome = "enriched" | "merged" | "skipped";

/** Locale hints for an item: the adder's device locales (e.g. ar-EG), their item language, the household's. */
export async function adderHints(db: Firestore, householdId: string, addedBy: string): Promise<string[]> {
  const hints: string[] = [];
  if (addedBy && !addedBy.includes(":")) {
    const devices = await db.collection(`users/${addedBy}/devices`).get();
    for (const d of devices.docs) {
      const locale: unknown = d.get("locale");
      if (typeof locale === "string") hints.push(locale);
    }
    const member = await db.doc(`households/${householdId}/members/${addedBy}`).get();
    const language: unknown = member.get("language");
    if (typeof language === "string") hints.push(language);
  }
  return [...new Set(hints)];
}

function asMergeable(id: string, data: DocumentData): MergeableItem {
  return {
    id,
    name: String(data.name ?? ""),
    status: String(data.status ?? ""),
    canonicalId: (data.canonicalId as string | undefined) ?? null,
    qty: (data.qty as number | undefined) ?? null,
    unit: (data.unit as string | undefined) ?? null,
    notes: (data.notes as string | undefined) ?? null,
    urgent: data.urgent === true,
    addedBy: String(data.addedBy ?? ""),
    requestedBy: data.requestedBy as string[] | undefined,
    createdAtMillis: (data.createdAt as { toMillis?: () => number } | undefined)?.toMillis?.() ?? Date.now(),
  };
}

/**
 * Prompt B4 `onItemCreated`: resolve (alias → dialect → catalog → cache → LLM), set `lang`,
 * `names` for every member language, `canonicalId` and `category` (unless the member chose it),
 * then merge into an older duplicate (ARCHITECTURE §4.2) or log `item_added`.
 * Idempotent: an item that already has `lang` was processed.
 */
export async function onItemCreatedCore(
  db: Firestore,
  ref: ItemRef,
  llm: LlmProvider,
  cache?: CandidateCache,
  now: Date = new Date(),
): Promise<CreatedOutcome> {
  const itemRef = db.doc(`households/${ref.householdId}/lists/${ref.listId}/items/${ref.itemId}`);
  const snap = await itemRef.get();
  const item = snap.data();
  if (!item || typeof item.lang === "string") return "skipped";

  const addedBy = String(item.addedBy ?? "");
  const [household, aliases, localeHints] = await Promise.all([
    db.doc(`households/${ref.householdId}`).get(),
    householdAliases(db, ref.householdId),
    adderHints(db, ref.householdId, addedBy),
  ]);
  if (!household.exists) return "skipped";
  const targetLanguages = (household.get("memberLanguages") as string[] | undefined) ?? [];
  const [resolved] = await resolveItems([{ name: String(item.name) }], {
    localeHints,
    aliases,
    targetLanguages,
    llm,
    cache,
  });
  if (!resolved) return "skipped";

  // An ambiguous dialect word gets the likely category but no canonicalId: the household alias decides later.
  const canonicalId = resolved.source === "ambiguous" ? null : resolved.canonicalId;
  const enrichment: Record<string, unknown> = {
    lang: resolved.lang,
    names: resolved.names,
    ...(canonicalId ? { canonicalId } : {}),
    ...(item.categorySource === "user" ? {} : { category: resolved.category, categorySource: "auto" }),
    updatedAt: FieldValue.serverTimestamp(),
  };

  const actorName = await memberName(db, ref.householdId, addedBy);
  const outcome = await db.runTransaction(async (tx): Promise<CreatedOutcome> => {
    const fresh = await tx.get(itemRef);
    if (!fresh.exists || typeof fresh.get("lang") === "string") return "skipped";
    const list = await tx.get(itemRef.parent.where("status", "in", ["open", "claimed"]));
    const newer = asMergeable(ref.itemId, { ...fresh.data(), canonicalId });
    const target = findMergeTarget(
      newer,
      list.docs.map((d) => asMergeable(d.id, d.data())),
    );
    if (target) {
      const patch = mergePatch(target, newer);
      tx.update(itemRef.parent.doc(target.id), { ...patch, updatedAt: FieldValue.serverTimestamp() });
      tx.delete(itemRef);
      await writeEvent(
        db,
        ref.householdId,
        { type: "item_merged", actorUid: addedBy, actorName, itemId: target.id, itemName: target.name },
        now,
        tx,
      );
      return "merged";
    }
    tx.update(itemRef, enrichment);
    await writeEvent(
      db,
      ref.householdId,
      {
        type: "item_added",
        actorUid: addedBy,
        actorName,
        itemId: ref.itemId,
        itemName: String(item.name),
        via: item.addedVia as string | undefined,
      },
      now,
      tx,
    );
    return "enriched";
  });

  // B6 hook: live fan-out (list_changed pushes, trip_update for an active trip) goes here.
  log.info("item_created", { householdId: ref.householdId, outcome, source: resolved.source });
  return outcome;
}

export const onItemCreated = onDocumentCreated(
  { document: "households/{householdId}/lists/{listId}/items/{itemId}", secrets: [ANTHROPIC_API_KEY] },
  async (event) => {
    await onItemCreatedCore(getFirestore(), event.params, fastLlm(), new FirestoreCandidateCache());
  },
);
