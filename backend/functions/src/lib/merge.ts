import { matchKey } from "./normalize.js";

/** The item fields the merge rule reads (CONTRACT §3.6). */
export interface MergeableItem {
  id: string;
  name: string;
  status: string;
  canonicalId?: string | null;
  qty?: number | null;
  unit?: string | null;
  notes?: string | null;
  urgent?: boolean;
  addedBy: string;
  requestedBy?: string[];
  createdAtMillis: number;
}

export interface MergePatch {
  qty?: number;
  notes?: string;
  urgent?: true;
  requestedBy: string[];
}

const NOTES_MAX = 120;
const QTY_MAX = 999;

/**
 * ARCHITECTURE §4.2: the oldest older `open`/`claimed` item in the same list with the same
 * canonicalId (else the same normalized name). Kids' `pending` items never merge until approved.
 */
export function findMergeTarget(item: MergeableItem, others: readonly MergeableItem[]): MergeableItem | null {
  if (item.status !== "open") return null;
  const key = matchKey(item.name);
  const matches = others.filter(
    (other) =>
      other.id !== item.id &&
      (other.status === "open" || other.status === "claimed") &&
      other.createdAtMillis <= item.createdAtMillis &&
      (item.canonicalId ? other.canonicalId === item.canonicalId : matchKey(other.name) === key),
  );
  return matches.sort((a, b) => a.createdAtMillis - b.createdAtMillis || a.id.localeCompare(b.id))[0] ?? null;
}

function amount(qty: number | null | undefined, unit: string | null | undefined): string {
  return [qty ?? 1, unit].filter((part) => part !== null && part !== undefined).join(" ");
}

/**
 * Same unit (or both without one): quantities add (a missing qty counts as 1, capped at 999).
 * Different units: the new amount is appended to `notes`. The new item's requester joins
 * `requestedBy`; an urgent duplicate makes the merged item urgent.
 */
export function mergePatch(target: MergeableItem, newer: MergeableItem): MergePatch {
  const requestedBy = [...new Set([...(target.requestedBy ?? [target.addedBy]), newer.addedBy])];
  const patch: MergePatch = { requestedBy };
  if ((target.unit ?? null) === (newer.unit ?? null)) {
    if (target.qty != null || newer.qty != null) {
      patch.qty = Math.min(QTY_MAX, (target.qty ?? 1) + (newer.qty ?? 1));
    }
  } else {
    const extra = `+ ${amount(newer.qty, newer.unit)}`;
    patch.notes = (target.notes ? `${target.notes}; ${extra}` : extra).slice(0, NOTES_MAX);
  }
  if (newer.urgent === true && target.urgent !== true) patch.urgent = true;
  return patch;
}
