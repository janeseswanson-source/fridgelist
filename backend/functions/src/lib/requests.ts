import type { Firestore } from "firebase-admin/firestore";
import { HttpsError, type CallableRequest } from "firebase-functions/v2/https";
import type { z } from "zod";

/** CONTRACT §4: every callable needs Firebase Auth unless marked public. */
export function requireUid(request: Pick<CallableRequest, "auth">): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return uid;
}

/** Validates a request body; the message names fields only, never their values (rule 4). */
export function parse<T extends z.ZodType>(schema: T, data: unknown): z.infer<T> {
  const result = schema.safeParse(data);
  if (!result.success) {
    const fields = [...new Set(result.error.issues.map((issue) => issue.path.join(".") || "body"))];
    throw new HttpsError("invalid-argument", `Invalid: ${fields.join(", ")}`);
  }
  return result.data;
}

export type Role = "owner" | "adult" | "kid" | "helper";

export interface Member {
  role: Role;
  displayName: string;
  language: string;
}

/** The caller's member doc, or `permission-denied` (CONTRACT §8: not a member or wrong role). */
export async function requireMember(
  db: Firestore,
  householdId: string,
  uid: string,
  roles?: readonly Role[],
): Promise<Member> {
  const snap = await db.doc(`households/${householdId}/members/${uid}`).get();
  const member = snap.data() as Member | undefined;
  if (!member) throw new HttpsError("permission-denied", "Not a member of this household.");
  if (roles && !roles.includes(member.role)) throw new HttpsError("permission-denied", "Role not allowed.");
  return member;
}
