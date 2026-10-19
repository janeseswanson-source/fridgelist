import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  type RulesTestContext,
  type RulesTestEnvironment,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import type { Firestore } from "firebase/firestore";

/** Household fixture shared by every rules test. */
export const HID = "h1";
export const LID = "l1";
export const OWNER = "u-owner";
export const ADULT = "u-adult";
export const HELPER = "u-helper";
export const KID = "u-kid";
export const OUTSIDER = "u-out";

export const ROLES = { owner: OWNER, adult: ADULT, helper: HELPER, kid: KID } as const;
export type RoleName = keyof typeof ROLES;

const NOTIFY = { tripHeading: true, tripArrived: false, itemMissing: true, usuals: false };

export async function setupEnv(): Promise<RulesTestEnvironment> {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  if (!host)
    throw new Error("Run rules tests with `npm run test:emulator` (inside firebase emulators:exec).");
  const [hostname, port] = host.split(":");
  return initializeTestEnvironment({
    projectId: "demo-fridgelist-rules",
    firestore: {
      host: hostname ?? "127.0.0.1",
      port: Number(port),
      rules: readFileSync(resolve(import.meta.dirname, "../../../firestore.rules"), "utf8"),
    },
  });
}

/** Writes the household, its members and one list with security rules off. */
export async function seedHousehold(env: RulesTestEnvironment): Promise<void> {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const { doc, setDoc } = await import("firebase/firestore");
    await setDoc(doc(db, `households/${HID}`), {
      name: "Home",
      ownerUid: OWNER,
      defaultLanguage: "ar",
      memberLanguages: ["ar", "tr"],
      plan: "free",
    });
    for (const [role, uid] of Object.entries(ROLES)) {
      await setDoc(doc(db, `households/${HID}/members/${uid}`), {
        role,
        displayName: role,
        language: "ar",
        notify: NOTIFY,
      });
    }
    await setDoc(doc(db, `households/${HID}/lists/${LID}`), {
      name: "Groceries",
      type: "grocery",
      order: 0,
      archived: false,
    });
  });
}

/** Firestore as a signed-in user (or anonymous when uid is null). */
export function as(env: RulesTestEnvironment, uid: string | null): Firestore {
  const ctx: RulesTestContext = uid === null ? env.unauthenticatedContext() : env.authenticatedContext(uid);
  return ctx.firestore() as unknown as Firestore;
}

/** Puts a document in place with rules off (state before the action under test). */
export async function put(
  env: RulesTestEnvironment,
  path: string,
  data: Record<string, unknown>,
): Promise<void> {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const { doc, setDoc } = await import("firebase/firestore");
    await setDoc(doc(ctx.firestore() as unknown as Firestore, path), data);
  });
}

export const itemPath = (iid: string) => `households/${HID}/lists/${LID}/items/${iid}`;
