import { FieldValue, getFirestore, type Firestore } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import { text } from "../i18n/index.js";
import { log } from "../lib/log.js";
import { parse, requireUid } from "../lib/requests.js";
import { DEFAULT_NOTIFY, itemLanguage, shortName, uiLanguage } from "../lib/schemas.js";

/** CONTRACT §4 `createHousehold` request. */
export const CreateHouseholdRequest = z.object({
  name: shortName,
  defaultLanguage: uiLanguage,
  displayName: shortName,
  language: itemLanguage,
  uiLanguage,
});

export interface CreateHouseholdResponse {
  householdId: string;
  listId: string;
}

/**
 * Creates the household, its owner and a default grocery list named in `defaultLanguage`, and
 * links the household to the user, in one transaction (the apps open it right away).
 */
export async function createHouseholdCore(
  db: Firestore,
  uid: string,
  data: unknown,
): Promise<CreateHouseholdResponse> {
  const input = parse(CreateHouseholdRequest, data);
  const household = db.collection("households").doc();
  const list = household.collection("lists").doc();
  const user = db.doc(`users/${uid}`);

  await db.runTransaction(async (tx) => {
    const existingUser = await tx.get(user);
    tx.create(household, {
      name: input.name,
      ownerUid: uid,
      defaultLanguage: input.defaultLanguage,
      memberLanguages: [input.language],
      plan: "free",
      createdAt: FieldValue.serverTimestamp(),
    });
    tx.create(household.collection("members").doc(uid), {
      role: "owner",
      displayName: input.displayName,
      language: input.language,
      notify: DEFAULT_NOTIFY,
      joinedAt: FieldValue.serverTimestamp(),
    });
    tx.create(list, {
      name: text(input.defaultLanguage, "defaultListName"),
      type: "grocery",
      order: 0,
      archived: false,
      createdAt: FieldValue.serverTimestamp(),
    });
    tx.set(
      user,
      {
        displayName: input.displayName,
        language: input.language,
        uiLanguage: input.uiLanguage,
        householdIds: FieldValue.arrayUnion(household.id),
        ...(existingUser.exists ? {} : { createdAt: FieldValue.serverTimestamp() }),
      },
      { merge: true },
    );
  });

  log.info("household_created", { householdId: household.id });
  return { householdId: household.id, listId: list.id };
}

export const createHousehold = onCall((request) =>
  createHouseholdCore(getFirestore(), requireUid(request), request.data),
);
