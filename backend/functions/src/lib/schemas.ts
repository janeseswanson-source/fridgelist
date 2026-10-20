import { z } from "zod";
import { ITEM_LANGUAGES, UI_LANGUAGES } from "../config.js";

/** CONTRACT §2 / §3 building blocks for request validation. */
export const uiLanguage = z.enum(UI_LANGUAGES);
export const itemLanguage = z.enum(ITEM_LANGUAGES);

/** 1–40 characters after trimming (counted like the apps: UTF-16 units). */
export const shortName = z.string().trim().min(1).max(40);

export const id = z.string().min(1).max(128).regex(/^[^/]+$/);

export const invitableRole = z.enum(["adult", "kid", "helper"]);

export const DEFAULT_NOTIFY = { tripHeading: true, tripArrived: false, itemMissing: true, usuals: false } as const;

/** CONTRACT §3.6 / firestore.rules `nameOk(name, 80)`: item names are at most 80 characters. */
export const ITEM_NAME_MAX = 80;

/**
 * An item name as functions write it: trimmed and cut to ITEM_NAME_MAX UTF-16 units (what the rules
 * count) without splitting an emoji. Longer names would leave items no member can tick or edit.
 */
export function itemName(raw: string): string {
  let out = "";
  for (const ch of raw.trim()) {
    if (out.length + ch.length > ITEM_NAME_MAX) break;
    out += ch;
  }
  return out.trim();
}
