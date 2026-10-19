import { FieldValue, Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import { LINK_DOMAIN } from "../config.js";
import { inviteCode, isInviteCode } from "../lib/ids.js";
import { log } from "../lib/log.js";
import { parse, requireMember, requireUid, type Role } from "../lib/requests.js";
import { DEFAULT_NOTIFY, id, invitableRole, itemLanguage, shortName } from "../lib/schemas.js";
import { DAY_SECONDS, unixSeconds } from "../lib/time.js";

/** CONTRACT §3.12: invites live 7 days and allow 10 joins. */
export const INVITE_DAYS = 7;
export const INVITE_MAX_USES = 10;
const JOIN_MAX_ATTEMPTS = 15;

export const CreateInviteRequest = z.object({ householdId: id, role: invitableRole });

export interface CreateInviteResponse {
  code: string;
  url: string;
  /** Unix seconds (CONTRACT §1). */
  expiresAt: number;
}

interface InviteDoc {
  householdId: string;
  role: Exclude<Role, "owner">;
  createdBy: string;
  expiresAt: Timestamp;
  maxUses: number;
  uses: number;
}

/** Owner or adult creates an invite for an adult, kid or helper (CONTRACT §4 `createInvite`). */
export async function createInviteCore(
  db: Firestore,
  uid: string,
  data: unknown,
  now: Date = new Date(),
  newCode: () => string = inviteCode,
): Promise<CreateInviteResponse> {
  const input = parse(CreateInviteRequest, data);
  await requireMember(db, input.householdId, uid, ["owner", "adult"]);
  const expiresAt = unixSeconds(now) + INVITE_DAYS * DAY_SECONDS;
  const invite: InviteDoc = {
    householdId: input.householdId,
    role: input.role,
    createdBy: uid,
    expiresAt: Timestamp.fromMillis(expiresAt * 1000),
    maxUses: INVITE_MAX_USES,
    uses: 0,
  };
  // `create` fails on an existing code, so a (very unlikely) collision never reuses an invite.
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = newCode();
    try {
      await db.doc(`invites/${code}`).create(invite);
      log.info("invite_created", { householdId: input.householdId, role: input.role });
      return { code, url: `https://${LINK_DOMAIN}/join/${code}`, expiresAt };
    } catch (error) {
      if ((error as { code?: number }).code !== 6 /* ALREADY_EXISTS */) throw error;
    }
  }
  throw new HttpsError("unavailable", "Could not create an invite code; try again.");
}

export const JoinHouseholdRequest = z.object({
  code: z.string().max(32),
  displayName: shortName,
  language: itemLanguage,
});

export interface JoinHouseholdResponse {
  householdId: string;
  role: Role;
}

/** Uppercases and drops spaces and dashes, as the apps do ("abcd-2345" → "ABCD2345"). */
export function normalizeCode(code: string): string {
  return code.toUpperCase().replace(/[\s-]/g, "");
}

/**
 * Joins with an invite code (CONTRACT §4 `joinHousehold`). Joining again returns the current role
 * and does not use up the invite. Unknown codes → `not-found`; expired or used up →
 * `failed-precondition` (CONTRACT §8; the apps show "invite expired").
 */
export async function joinHouseholdCore(
  db: Firestore,
  uid: string,
  data: unknown,
  now: Date = new Date(),
): Promise<JoinHouseholdResponse> {
  const input = parse(JoinHouseholdRequest, data);
  const code = normalizeCode(input.code);
  if (!isInviteCode(code)) throw new HttpsError("invalid-argument", "Invalid: code");

  const result = await db.runTransaction(
    async (tx) => {
      const inviteRef = db.doc(`invites/${code}`);
      const inviteSnap = await tx.get(inviteRef);
      const invite = inviteSnap.data() as InviteDoc | undefined;
      if (!invite) throw new HttpsError("not-found", "This invite does not exist.");

      const householdRef = db.doc(`households/${invite.householdId}`);
      const memberRef = householdRef.collection("members").doc(uid);
      const [household, existing] = await Promise.all([tx.get(householdRef), tx.get(memberRef)]);
      if (!household.exists) throw new HttpsError("not-found", "This household no longer exists.");
      if (existing.exists) {
        // Already in: keep the role, keep the invite's uses.
        return {
          householdId: invite.householdId,
          role: (existing.data() as { role: Role }).role,
          joined: false,
        };
      }
      if (invite.expiresAt.toMillis() <= now.getTime())
        throw new HttpsError("failed-precondition", "Invite expired.");
      if (invite.uses >= invite.maxUses) throw new HttpsError("failed-precondition", "Invite used up.");

      tx.create(memberRef, {
        role: invite.role,
        displayName: input.displayName,
        language: input.language,
        notify: DEFAULT_NOTIFY,
        joinedAt: FieldValue.serverTimestamp(),
      });
      tx.update(inviteRef, { uses: FieldValue.increment(1) });
      tx.update(householdRef, { memberLanguages: FieldValue.arrayUnion(input.language) });
      tx.set(
        db.doc(`users/${uid}`),
        {
          displayName: input.displayName,
          language: input.language,
          householdIds: FieldValue.arrayUnion(invite.householdId),
        },
        { merge: true },
      );
      return { householdId: invite.householdId, role: invite.role, joined: true };
      // A family scanning one QR together contends on the invite doc; give retries more room.
    },
    { maxAttempts: JOIN_MAX_ATTEMPTS },
  );

  log.info("household_joined", {
    householdId: result.householdId,
    role: result.role,
    newMember: result.joined,
  });
  return { householdId: result.householdId, role: result.role };
}

export const createInvite = onCall((request) =>
  createInviteCore(getFirestore(), requireUid(request), request.data),
);
export const joinHousehold = onCall((request) =>
  joinHouseholdCore(getFirestore(), requireUid(request), request.data),
);
