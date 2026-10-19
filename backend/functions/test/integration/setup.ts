import { initializeApp, type App } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { expect } from "vitest";

const PROJECT = "demo-fridgelist-it";

/** Admin Firestore on the test emulator (inside `npm run test:emulator`). */
export function testDb(name: string): { app: App; db: Firestore } {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  if (!host)
    throw new Error("Run integration tests with `npm run test:emulator` (inside firebase emulators:exec).");
  const app = initializeApp({ projectId: PROJECT }, name);
  return { app, db: getFirestore(app) };
}

/** Deletes every document in the test project (emulator REST endpoint). */
export async function clearDb(): Promise<void> {
  const host = process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8180";
  const response = await fetch(
    `http://${host}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,
    {
      method: "DELETE",
    },
  );
  if (!response.ok) throw new Error(`clear failed: ${String(response.status)}`);
}

/** Asserts that `promise` rejects with an HttpsError carrying `code`. */
export async function expectHttpsError(promise: Promise<unknown>, code: HttpsError["code"]): Promise<void> {
  const error: unknown = await promise.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(error, `expected HttpsError ${code}`).toBeInstanceOf(HttpsError);
  expect((error as HttpsError).code).toBe(code);
}
