import { randomBytes } from "node:crypto";
import {
  FieldValue,
  Timestamp,
  getFirestore,
  type DocumentData,
  type Firestore,
} from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import { LINK_DOMAIN } from "../config.js";
import { CATEGORIES } from "../lib/categories.js";
import { displayTitle } from "../lib/display.js";
import { writeEvent } from "../lib/events.js";
import { LocalSplitter } from "../lib/localSplit.js";
import { log } from "../lib/log.js";
import { parse, requireMember, requireUid } from "../lib/requests.js";
import { id, itemName } from "../lib/schemas.js";

/** CONTRACT §3.11: 24 random URL-safe characters. */
export const TOKEN = /^[A-Za-z0-9_-]{24}$/;
/** CONTRACT §4 guestAddItem: 20 adds per hour per token. */
export const GUEST_ADDS_PER_HOUR = 20;
/** One guest message adds at most this many items. */
export const GUEST_ITEMS_PER_CALL = 10;

export const CreateShareLinkRequest = z.object({
  householdId: id,
  listId: id,
  scope: z.enum(["view", "add"]),
  kind: z.enum(["nfc", "qr", "link"]),
  expiresInDays: z.number().int().min(1).max(365).optional(),
});

/** Owner or adult makes a fridge tag / QR / link for one list (PRODUCT_SPEC F9). */
export async function createShareLinkCore(
  db: Firestore,
  uid: string,
  data: unknown,
  now: Date = new Date(),
): Promise<{ token: string; url: string }> {
  const input = parse(CreateShareLinkRequest, data);
  await requireMember(db, input.householdId, uid, ["owner", "adult"]);
  const list = await db.doc(`households/${input.householdId}/lists/${input.listId}`).get();
  if (!list.exists) throw new HttpsError("not-found", "No such list.");
  const token = randomBytes(18).toString("base64url");
  await db.doc(`shareLinks/${token}`).create({
    householdId: input.householdId,
    listId: input.listId,
    scope: input.scope,
    kind: input.kind,
    createdBy: uid,
    createdAt: Timestamp.fromDate(now),
    ...(input.expiresInDays
      ? { expiresAt: Timestamp.fromMillis(now.getTime() + input.expiresInDays * 86_400_000) }
      : {}),
    revoked: false,
    guestAddsWindowStart: Timestamp.fromDate(now),
    guestAddsInWindow: 0,
  });
  log.info("share_link_created", { householdId: input.householdId, scope: input.scope, kind: input.kind });
  return { token, url: `https://${LINK_DOMAIN}/k/${token}` };
}

export const RevokeShareLinkRequest = z.object({ householdId: id, token: z.string().regex(TOKEN) });

export async function revokeShareLinkCore(db: Firestore, uid: string, data: unknown): Promise<{ ok: true }> {
  const input = parse(RevokeShareLinkRequest, data);
  await requireMember(db, input.householdId, uid, ["owner", "adult"]);
  const ref = db.doc(`shareLinks/${input.token}`);
  const link = await ref.get();
  if (link.get("householdId") !== input.householdId) throw new HttpsError("not-found", "No such link.");
  await ref.update({ revoked: true });
  return { ok: true };
}

/** A usable link, or `not-found` ("This link no longer works", CONTRACT §8) for anything else. */
async function activeLink(db: Firestore, token: string, now: Date): Promise<DocumentData> {
  const snap = await db.doc(`shareLinks/${token}`).get();
  const link = snap.data();
  const expiresAt = link?.expiresAt as Timestamp | undefined;
  if (!link || link.revoked === true || (expiresAt && expiresAt.toMillis() <= now.getTime())) {
    throw new HttpsError("not-found", "This link no longer works.");
  }
  return link;
}

export const ResolveShareLinkRequest = z.object({
  token: z.string().regex(TOKEN),
  lang: z.string().max(10).optional(),
});

export interface GuestItem {
  id: string;
  title: string;
  subtitle?: string;
  category: string;
  status: "open" | "claimed";
}

const ORDER = new Map<string, number>(CATEGORIES.map((c, i) => [c, i]));
const MEASURED: Record<string, string> = { kg: "kg", g: "g", l: "L", ml: "ml" };

/** "2 kg", "×3": numbers only, so the page can show it in any language. */
function subtitle(qty: unknown, unit: unknown): string | undefined {
  if (typeof qty !== "number") return undefined;
  const symbol = typeof unit === "string" ? MEASURED[unit] : undefined;
  return symbol ? `${String(qty)} ${symbol}` : `×${String(qty)}`;
}

/**
 * Public (App Check): household and list name, and only `open` and `claimed` items, titled in
 * `lang` by the display rule, else the household's default language. No member names, no
 * `addedBy`, nothing else from the household.
 */
/**
 * CONTRACT §4 `resolveShareLink`. A signed-in member of the link's household also gets
 * `member: { householdId, listId }` (v5), so the app opens Quick Add for that list instead of the
 * guest view. Guests learn nothing new.
 */
export async function resolveShareLinkCore(
  db: Firestore,
  data: unknown,
  now: Date = new Date(),
  uid?: string,
) {
  const input = parse(ResolveShareLinkRequest, data);
  const link = await activeLink(db, input.token, now);
  const householdId = String(link.householdId);
  const listId = String(link.listId);
  const [household, list, items, member] = await Promise.all([
    db.doc(`households/${householdId}`).get(),
    db.doc(`households/${householdId}/lists/${listId}`).get(),
    db
      .collection(`households/${householdId}/lists/${listId}/items`)
      .where("status", "in", ["open", "claimed"])
      .get(),
    uid ? db.doc(`households/${householdId}/members/${uid}`).get() : Promise.resolve(null),
  ]);
  if (!household.exists || !list.exists) throw new HttpsError("not-found", "This link no longer works.");
  const defaultLanguage = String(household.get("defaultLanguage") ?? "en");
  const viewer = input.lang?.split("-")[0]?.toLowerCase() ?? defaultLanguage;
  const out: GuestItem[] = items.docs
    .map((d) => {
      const i = d.data();
      const sub = subtitle(i.qty, i.unit);
      return {
        id: d.id,
        title: displayTitle(
          {
            name: String(i.name ?? ""),
            lang: i.lang as string | undefined,
            names: i.names as Record<string, string> | undefined,
          },
          viewer,
          defaultLanguage,
        ),
        ...(sub ? { subtitle: sub } : {}),
        category: typeof i.category === "string" ? i.category : "other",
        status: i.status as "open" | "claimed",
      };
    })
    .sort((a, b) => (ORDER.get(a.category) ?? ORDER.size) - (ORDER.get(b.category) ?? ORDER.size));
  return {
    householdName: String(household.get("name") ?? ""),
    listName: String(list.get("name") ?? ""),
    scope: link.scope as "view" | "add",
    items: out,
    ...(member?.exists ? { member: { householdId, listId } } : {}),
  };
}

export const GuestAddItemRequest = z.object({
  token: z.string().regex(TOKEN),
  text: z.string().max(200),
  guestName: z.string().max(30).optional(),
});

/** Guests may not post links: URLs and bare domains are removed (CONTRACT §4). */
export function stripUrls(text: string): string {
  return text
    .replace(/\b(?:https?:\/\/|www\.)\S+/giu, " ")
    .replace(
      /\b[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.(?:com|net|org|io|me|ly|co|app|link|xyz|info|biz|tr|sa|ae)(?:\/\S*)?/giu,
      " ",
    )
    .replace(/\s+/gu, " ")
    .trim();
}

/**
 * Public (App Check), scope `add` only: the text is split like any add (the household's default
 * language as the hint) and written as `guest_web` items by `guest:<token>`; onItemCreated then
 * translates and merges them. At most 20 items per hour per token, counted on the link doc.
 */
export async function guestAddItemCore(
  db: Firestore,
  data: unknown,
  now: Date = new Date(),
): Promise<{ added: number }> {
  const input = parse(GuestAddItemRequest, data);
  const link = await activeLink(db, input.token, now);
  if (link.scope !== "add") throw new HttpsError("permission-denied", "This link is view-only.");
  const text = stripUrls(input.text);
  const guestName = stripUrls(input.guestName ?? "").slice(0, 30);
  if (!text) throw new HttpsError("invalid-argument", "Invalid: text");

  const householdId = String(link.householdId);
  const listId = String(link.listId);
  const household = await db.doc(`households/${householdId}`).get();
  if (!household.exists) throw new HttpsError("not-found", "This link no longer works.");
  const hint = String(household.get("defaultLanguage") ?? "en");
  const items = new LocalSplitter().split(text, [hint]).slice(0, GUEST_ITEMS_PER_CALL);
  if (items.length === 0) throw new HttpsError("invalid-argument", "Invalid: text");

  const linkRef = db.doc(`shareLinks/${input.token}`);
  const listRef = db.collection(`households/${householdId}/lists/${listId}/items`);
  await db.runTransaction(async (tx) => {
    const fresh = (await tx.get(linkRef)).data() ?? {};
    const start = (fresh.guestAddsWindowStart as Timestamp | undefined)?.toMillis() ?? 0;
    const inWindow = now.getTime() - start < 3_600_000;
    const used = inWindow ? Number(fresh.guestAddsInWindow ?? 0) : 0;
    if (used + items.length > GUEST_ADDS_PER_HOUR) {
      throw new HttpsError("resource-exhausted", "Too many guest adds; try again later.");
    }
    tx.update(linkRef, {
      guestAddsWindowStart: inWindow ? Timestamp.fromMillis(start) : Timestamp.fromDate(now),
      guestAddsInWindow: used + items.length,
    });
    for (const item of items) {
      tx.create(listRef.doc(), {
        name: itemName(item.name),
        ...(item.qty != null && item.qty > 0 && item.qty <= 999 ? { qty: item.qty } : {}),
        ...(item.unit ? { unit: item.unit } : {}),
        urgent: false,
        status: "open",
        addedBy: `guest:${input.token}`,
        addedVia: "guest_web",
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  });
  await writeEvent(
    db,
    householdId,
    { type: "guest_added", actorUid: `guest:${input.token}`, actorName: guestName, via: "guest_web" },
    now,
  );
  log.info("guest_added", { householdId, items: items.length });
  return { added: items.length };
}

/**
 * CONTRACT §4 and ARCHITECTURE §3.8: the public callables require App Check everywhere it can
 * exist. The Functions emulator has no App Check, so it would reject every guest call; it is the
 * only place enforcement is off (evaluated when the module loads).
 */
export const PUBLIC = {
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== "true",
  cors: true,
} as const;

export const createShareLink = onCall((request) =>
  createShareLinkCore(getFirestore(), requireUid(request), request.data),
);
export const revokeShareLink = onCall((request) =>
  revokeShareLinkCore(getFirestore(), requireUid(request), request.data),
);
export const resolveShareLink = onCall(PUBLIC, (request) =>
  resolveShareLinkCore(getFirestore(), request.data, new Date(), request.auth?.uid),
);
export const guestAddItem = onCall(PUBLIC, (request) => guestAddItemCore(getFirestore(), request.data));
