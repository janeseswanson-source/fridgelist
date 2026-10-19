import { FieldValue, Timestamp, type Firestore, type Transaction } from "firebase-admin/firestore";

/** CONTRACT §2 EventType. */
export type EventType =
  | "item_added"
  | "item_done"
  | "item_missing"
  | "item_merged"
  | "trip_heading"
  | "trip_arrived"
  | "trip_done"
  | "member_joined"
  | "guest_added";

export interface EventInput {
  type: EventType;
  actorUid: string;
  actorName: string;
  itemId?: string;
  itemName?: string;
  tripId?: string;
  via?: string;
}

/** Events expire after 30 days (CONTRACT §3.8, TTL policy on `expireAt`, B9). */
export const EVENT_TTL_DAYS = 30;

/** `households/{hid}/events/{auto}`; written in `tx` when given, so it commits with the change. */
export async function writeEvent(
  db: Firestore,
  householdId: string,
  event: EventInput,
  now: Date,
  tx?: Transaction,
): Promise<void> {
  const ref = db.collection(`households/${householdId}/events`).doc();
  const data = {
    ...Object.fromEntries(Object.entries(event).filter(([, v]) => v !== undefined && v !== null)),
    at: FieldValue.serverTimestamp(),
    expireAt: Timestamp.fromMillis(now.getTime() + EVENT_TTL_DAYS * 86_400_000),
  };
  if (tx) tx.create(ref, data);
  else await ref.create(data);
}

/** A member's display name for events (empty for guests and unknown actors). */
export async function memberName(db: Firestore, householdId: string, uid: string): Promise<string> {
  if (!uid || uid.includes(":")) return "";
  const snap = await db.doc(`households/${householdId}/members/${uid}`).get();
  const name: unknown = snap.get("displayName");
  return typeof name === "string" ? name : "";
}
