import { Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { remoteFlags, type Flags } from "../lib/flags.js";
import { log } from "../lib/log.js";
import { aliasId, matchKey } from "../lib/normalize.js";

const DAY_MS = 86_400_000;
/** Purchases older than this don't count. */
export const USUALS_LOOKBACK_DAYS = 90;
/** Bought on at least this many different days before it's a usual. */
export const USUALS_MIN_PURCHASES = 3;
/** Suggested from one day before it's due. */
export const USUALS_LEAD_DAYS = 1;

export interface Purchase {
  canonicalId?: string;
  name: string;
  at: Date;
}

export interface Usual {
  key: string;
  canonicalId?: string;
  name: string;
  everyDays: number;
  lastBoughtAt: Date;
  dueAt: Date;
  count: number;
}

/** CONTRACT v4 §3.17: the canonicalId, else `n_` + aliasId of the normalized name. */
export function usualKey(p: { canonicalId?: string; name: string }): string {
  return p.canonicalId ?? `n_${aliasId(matchKey(p.name))}`;
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? (sorted[mid] ?? 0) : ((sorted[mid - 1] ?? 0) + (sorted[mid] ?? 0)) / 2;
}

/**
 * Groups purchases by key; with ≥ 3 purchase days, the typical interval is the median gap in
 * days (at least 1). The newest purchase's name is shown.
 */
export function usualsFrom(purchases: readonly Purchase[]): Usual[] {
  const groups = new Map<string, Purchase[]>();
  for (const p of purchases) {
    const key = usualKey(p);
    groups.set(key, [...(groups.get(key) ?? []), p]);
  }
  const out: Usual[] = [];
  for (const [key, group] of groups) {
    const sorted = [...group].sort((a, b) => a.at.getTime() - b.at.getTime());
    const days = [...new Set(sorted.map((p) => Math.floor(p.at.getTime() / DAY_MS)))];
    if (days.length < USUALS_MIN_PURCHASES) continue;
    const gaps = days.slice(1).map((d, i) => d - (days[i] ?? d));
    const everyDays = Math.max(1, Math.round(median(gaps)));
    const last = sorted[sorted.length - 1];
    if (!last) continue;
    out.push({
      key,
      ...(last.canonicalId ? { canonicalId: last.canonicalId } : {}),
      name: last.name,
      everyDays,
      lastBoughtAt: last.at,
      dueAt: new Date(last.at.getTime() + everyDays * DAY_MS),
      count: days.length,
    });
  }
  return out;
}

/** Usuals due by tomorrow whose key isn't on any list (open, claimed, pending or missing). */
export function dueUsuals(usuals: readonly Usual[], onList: ReadonlySet<string>, now: Date): Usual[] {
  const horizon = now.getTime() + USUALS_LEAD_DAYS * DAY_MS;
  return usuals.filter((u) => u.dueAt.getTime() <= horizon && !onList.has(u.key));
}

async function computeFor(db: Firestore, householdId: string, now: Date): Promise<number> {
  const h = db.doc(`households/${householdId}`);
  const [purchases, lists, existing] = await Promise.all([
    h
      .collection("purchases")
      .where("at", ">=", Timestamp.fromMillis(now.getTime() - USUALS_LOOKBACK_DAYS * DAY_MS))
      .get(),
    h.collection("lists").get(),
    h.collection("usuals").get(),
  ]);
  const onList = new Set<string>();
  for (const list of lists.docs) {
    const items = await list.ref
      .collection("items")
      .where("status", "in", ["open", "claimed", "pending", "missing"])
      .get();
    for (const item of items.docs) {
      const canonicalId = item.get("canonicalId") as string | undefined;
      if (canonicalId) onList.add(canonicalId);
      onList.add(usualKey({ name: String(item.get("name") ?? "") }));
    }
  }
  const due = dueUsuals(
    usualsFrom(
      purchases.docs.map((d) => ({
        ...(typeof d.get("canonicalId") === "string" ? { canonicalId: d.get("canonicalId") as string } : {}),
        name: String(d.get("name") ?? ""),
        at: (d.get("at") as Timestamp).toDate(),
      })),
    ),
    onList,
    now,
  );
  const batch = db.batch();
  const keep = new Set(due.map((u) => u.key));
  for (const doc of existing.docs) if (!keep.has(doc.id)) batch.delete(doc.ref);
  for (const u of due) {
    batch.set(h.collection("usuals").doc(u.key), {
      ...(u.canonicalId ? { canonicalId: u.canonicalId } : {}),
      name: u.name,
      everyDays: u.everyDays,
      lastBoughtAt: Timestamp.fromDate(u.lastBoughtAt),
      dueAt: Timestamp.fromDate(u.dueAt),
      count: u.count,
      updatedAt: Timestamp.fromDate(now),
    });
  }
  await batch.commit();
  return due.length;
}

/** Daily, off unless `usuals_enabled`. Logs counts only. */
export async function computeUsualsCore(db: Firestore, flags: Flags, now: Date): Promise<number | null> {
  if (!(await flags.enabled("usuals_enabled"))) return null;
  const households = await db.collection("households").select().get();
  let written = 0;
  for (const h of households.docs) written += await computeFor(db, h.id, now);
  log.info("compute_usuals", { households: households.size, usuals: written });
  return written;
}

export const computeUsuals = onSchedule(
  { schedule: "every day 03:00", timeZone: "Asia/Riyadh" },
  async () => {
    await computeUsualsCore(getFirestore(), remoteFlags, new Date());
  },
);
