import { FieldValue, getFirestore, type Firestore } from "firebase-admin/firestore";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { log } from "../lib/log.js";

/** Union of the members' item languages, sorted so unchanged sets don't cause writes. */
export function memberLanguagesOf(languages: readonly unknown[]): string[] {
  return [...new Set(languages.filter((l): l is string => typeof l === "string" && l.length > 0))].sort();
}

/**
 * Keeps `households/{hid}.memberLanguages` and `users/{uid}.householdIds` in step with the members
 * collection (prompt B3). Idempotent: callables already write the same values for immediacy; this
 * also covers language changes, removals (B9) and anything the callables didn't touch.
 */
export async function syncMember(
  db: Firestore,
  householdId: string,
  uid: string,
  change: { existedBefore: boolean; existsAfter: boolean },
): Promise<void> {
  const household = db.doc(`households/${householdId}`);
  const members = await household.collection("members").get();
  const languages = memberLanguagesOf(members.docs.map((d): unknown => d.get("language")));

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(household);
    if (!snap.exists) return; // household deleted (B9): nothing to keep in step
    const current = memberLanguagesOf((snap.get("memberLanguages") as unknown[] | undefined) ?? []);
    if (current.join(",") !== languages.join(",")) tx.update(household, { memberLanguages: languages });
  });

  const user = db.doc(`users/${uid}`);
  if (!change.existedBefore && change.existsAfter) {
    await user.set({ householdIds: FieldValue.arrayUnion(householdId) }, { merge: true });
  } else if (change.existedBefore && !change.existsAfter) {
    await user.set({ householdIds: FieldValue.arrayRemove(householdId) }, { merge: true });
  }
}

export const onMemberWritten = onDocumentWritten("households/{householdId}/members/{uid}", async (event) => {
  const { householdId, uid } = event.params;
  const existedBefore = event.data?.before.exists ?? false;
  const existsAfter = event.data?.after.exists ?? false;
  await syncMember(getFirestore(), householdId, uid, { existedBefore, existsAfter });
  log.info("member_synced", {
    householdId,
    created: !existedBefore && existsAfter,
    removed: existedBefore && !existsAfter,
  });
});
