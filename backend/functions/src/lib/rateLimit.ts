import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

/** ARCHITECTURE §3: parseItems 30/min/user, transcribeAudio 20/hour/user (fixed windows). */
export const LIMITS = {
  parseItems: { max: 30, windowSeconds: 60 },
  transcribeAudio: { max: 20, windowSeconds: 3_600 },
  /** B9: a full export is heavy; 3 per day. */
  exportMyData: { max: 3, windowSeconds: 86_400 },
  /** ARCHITECTURE §3.8: per household. */
  suggestMeals: { max: 10, windowSeconds: 3_600 },
  /** Not in ARCHITECTURE §3: added with B5 so one account cannot flood `stores`. */
  addStore: { max: 20, windowSeconds: 86_400 },
} as const;

/**
 * Counts one call in `rateLimits/{bucket}:{subject}` (functions only; the rules close it) and
 * throws `resource-exhausted` past the limit (CONTRACT §8: the apps fall back locally).
 */
export async function consume(
  db: Firestore,
  bucket: keyof typeof LIMITS,
  subject: string,
  now: Date = new Date(),
): Promise<void> {
  const { max, windowSeconds } = LIMITS[bucket];
  const ref = db.doc(`rateLimits/${bucket}:${subject}`);
  const allowed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const start = (snap.get("windowStart") as Timestamp | undefined)?.toMillis() ?? 0;
    const count = (snap.get("count") as number | undefined) ?? 0;
    if (now.getTime() - start >= windowSeconds * 1000) {
      tx.set(ref, { windowStart: Timestamp.fromDate(now), count: 1 });
      return true;
    }
    if (count >= max) return false;
    tx.update(ref, { count: count + 1 });
    return true;
  });
  if (!allowed) throw new HttpsError("resource-exhausted", "Too many requests; try again shortly.");
}
