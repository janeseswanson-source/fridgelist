import { assertFails, assertSucceeds, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { ADULT, OWNER, as, put, seedHousehold, setupEnv } from "./setup.js";

let env: RulesTestEnvironment;
beforeAll(async () => {
  env = await setupEnv();
});
beforeEach(async () => {
  await seedHousehold(env);
});
afterAll(async () => {
  await env.cleanup();
});

const ref = (uid: string | null, path: string) => doc(as(env, uid), path);

describe("users/{uid} (CONTRACT §3.1)", () => {
  it("self creates the profile with only displayName, language, uiLanguage (before any callable)", async () => {
    await assertSucceeds(
      setDoc(
        ref(ADULT, `users/${ADULT}`),
        { displayName: "Ayşe", language: "tr", uiLanguage: "tr" },
        { merge: true },
      ),
    );
  });

  it("householdIds and createdAt are functions'", async () => {
    await assertFails(setDoc(ref(ADULT, `users/${ADULT}`), { displayName: "Ayşe", householdIds: ["h1"] }));
    await put(env, `users/${ADULT}`, {
      displayName: "Ayşe",
      language: "tr",
      uiLanguage: "tr",
      householdIds: ["h1"],
    });
    await assertFails(updateDoc(ref(ADULT, `users/${ADULT}`), { householdIds: ["h1", "h-other"] }));
    await assertFails(updateDoc(ref(ADULT, `users/${ADULT}`), { createdAt: serverTimestamp() }));
    // A merge write of the self fields leaves householdIds alone and succeeds.
    await assertSucceeds(
      setDoc(
        ref(ADULT, `users/${ADULT}`),
        { displayName: "Ayşe K", language: "tr", uiLanguage: "ar" },
        { merge: true },
      ),
    );
  });

  it("validation", async () => {
    await assertFails(setDoc(ref(ADULT, `users/${ADULT}`), { displayName: "x".repeat(41) }));
    await assertFails(setDoc(ref(ADULT, `users/${ADULT}`), { uiLanguage: "fil" }));
    await assertFails(setDoc(ref(ADULT, `users/${ADULT}`), { language: "de" }));
  });

  it("only self reads or writes; no client delete", async () => {
    await put(env, `users/${OWNER}`, { displayName: "Mama", householdIds: ["h1"] });
    await assertSucceeds(getDoc(ref(OWNER, `users/${OWNER}`)));
    await assertFails(getDoc(ref(ADULT, `users/${OWNER}`)));
    await assertFails(setDoc(ref(ADULT, `users/${OWNER}`), { displayName: "x" }, { merge: true }));
    await assertFails(deleteDoc(ref(OWNER, `users/${OWNER}`)));
  });
});

describe("users/{uid}/devices/{deviceId} (CONTRACT §3.2, v3 delete)", () => {
  const device = (extra: Record<string, unknown> = {}) => ({
    platform: "android",
    fcmToken: "tok",
    appVersion: "0.1.0",
    locale: "ar-SA",
    updatedAt: serverTimestamp(),
    ...extra,
  });
  const path = `users/${ADULT}/devices/d1`;

  it("self registers and refreshes its device", async () => {
    await assertSucceeds(setDoc(ref(ADULT, path), device(), { merge: true }));
    await assertSucceeds(setDoc(ref(ADULT, path), device({ fcmToken: "tok2" }), { merge: true }));
    await assertSucceeds(
      setDoc(
        ref(ADULT, `users/${ADULT}/devices/d2`),
        device({ platform: "ios", apnsPushToStartToken: "ab12" }),
      ),
    );
  });

  it("validation: platform, fields, server time", async () => {
    await assertFails(setDoc(ref(ADULT, path), device({ platform: "web" })));
    await assertFails(setDoc(ref(ADULT, path), device({ admin: true })));
    await assertFails(setDoc(ref(ADULT, path), device({ updatedAt: Timestamp.fromMillis(1) })));
  });

  it("self deletes on sign-out; nobody else reads, writes or deletes", async () => {
    await put(env, path, { platform: "android", fcmToken: "tok" });
    await assertFails(getDoc(ref(OWNER, path)));
    await assertFails(deleteDoc(ref(OWNER, path)));
    await assertFails(setDoc(ref(OWNER, path), device()));
    await assertSucceeds(getDoc(ref(ADULT, path)));
    await assertSucceeds(deleteDoc(ref(ADULT, path)));
  });
});

describe("shared reference data (CONTRACT §3.13, §3.14)", () => {
  it("stores and catalog: signed-in users read, nobody writes", async () => {
    await put(env, "stores/osm_node_1", { name: "Migros", chainId: "migros" });
    await put(env, "catalog/milk", { category: "dairy_eggs" });
    for (const path of ["stores/osm_node_1", "catalog/milk"]) {
      await assertSucceeds(getDoc(ref(ADULT, path)));
      await assertFails(getDoc(ref(null, path)));
      await assertFails(setDoc(ref(OWNER, path), { name: "x" }));
    }
  });
});

describe("functions-only top-level collections (CONTRACT §3.11, §3.12, §3.15)", () => {
  it("shareLinks, invites, catalogCandidates: no client access", async () => {
    for (const path of [
      "shareLinks/tok",
      "invites/ABCD2345",
      "catalogCandidates/hash",
      "whatsappLinkCodes/ABCD2345",
      "whatsappNumbers/abc123",
    ]) {
      await put(env, path, { householdId: "h1" });
      await assertFails(getDoc(ref(OWNER, path)));
      await assertFails(setDoc(ref(OWNER, path), { householdId: "h1" }));
    }
  });
});
