import { assertFails, assertSucceeds, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { ADULT, HELPER, HID, KID, LID, OUTSIDER, OWNER, as, put, seedHousehold, setupEnv } from "./setup.js";

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

describe("households/{hid} (CONTRACT §3.3)", () => {
  it("members read, outsiders and anonymous users don't", async () => {
    for (const uid of [OWNER, ADULT, HELPER, KID])
      await assertSucceeds(getDoc(ref(uid, `households/${HID}`)));
    await assertFails(getDoc(ref(OUTSIDER, `households/${HID}`)));
    await assertFails(getDoc(ref(null, `households/${HID}`)));
  });

  it("the owner updates name and defaultLanguage; nobody else does", async () => {
    await assertSucceeds(
      updateDoc(ref(OWNER, `households/${HID}`), { name: "بيتنا", defaultLanguage: "tr" }),
    );
    await assertFails(updateDoc(ref(ADULT, `households/${HID}`), { name: "x" }));
    await assertFails(updateDoc(ref(OWNER, `households/${HID}`), { name: "x".repeat(41) }));
    await assertFails(updateDoc(ref(OWNER, `households/${HID}`), { defaultLanguage: "fil" }));
  });

  it("functions-owned household fields are read-only", async () => {
    for (const [key, value] of Object.entries({
      ownerUid: ADULT,
      memberLanguages: ["en"],
      plan: "premium",
      createdAt: serverTimestamp(),
    })) {
      await assertFails(updateDoc(ref(OWNER, `households/${HID}`), { [key]: value }));
    }
  });

  it("households are created and deleted only by functions", async () => {
    await assertFails(setDoc(ref(OWNER, "households/h2"), { name: "x", ownerUid: OWNER }));
    await assertFails(deleteDoc(ref(OWNER, `households/${HID}`)));
  });
});

describe("members (CONTRACT §3.4)", () => {
  const me = (uid: string) => ref(uid, `households/${HID}/members/${uid}`);

  it("members read each other; outsiders don't", async () => {
    await assertSucceeds(getDoc(ref(KID, `households/${HID}/members/${OWNER}`)));
    await assertFails(getDoc(ref(OUTSIDER, `households/${HID}/members/${OWNER}`)));
  });

  it("self edits displayName, language, notify", async () => {
    await assertSucceeds(
      updateDoc(me(HELPER), {
        displayName: "Maria",
        language: "fil",
        notify: { tripHeading: false, tripArrived: true, itemMissing: true, usuals: false },
      }),
    );
  });

  it("validation: name length, language, notify keys and types", async () => {
    await assertFails(updateDoc(me(HELPER), { displayName: "" }));
    await assertFails(updateDoc(me(HELPER), { language: "de" }));
    await assertFails(updateDoc(me(HELPER), { notify: { tripHeading: "yes" } }));
    await assertFails(updateDoc(me(HELPER), { notify: { spam: true } }));
  });

  it("role, presence and joinedAt are functions'", async () => {
    await assertFails(updateDoc(me(KID), { role: "owner" }));
    await assertFails(updateDoc(me(ADULT), { presence: { tripId: "t", status: "shopping" } }));
    await assertFails(updateDoc(me(ADULT), { joinedAt: serverTimestamp() }));
  });

  it("nobody edits another member, even the owner", async () => {
    await assertFails(updateDoc(ref(OWNER, `households/${HID}/members/${KID}`), { displayName: "x" }));
  });

  it("joining and leaving go through functions", async () => {
    await assertFails(
      setDoc(ref(OUTSIDER, `households/${HID}/members/${OUTSIDER}`), {
        role: "adult",
        displayName: "x",
        language: "en",
      }),
    );
    await assertFails(deleteDoc(me(ADULT)));
  });
});

describe("lists (CONTRACT §3.5)", () => {
  const newList = (extra: Record<string, unknown> = {}) => ({
    name: "Pharmacy",
    type: "pharmacy",
    order: 1,
    archived: false,
    createdAt: serverTimestamp(),
    ...extra,
  });
  const list = (uid: string, lid = "l2") => ref(uid, `households/${HID}/lists/${lid}`);

  it("owner and adult create; helper and kid don't", async () => {
    await assertSucceeds(setDoc(list(OWNER, "a"), newList({ emoji: "💊" })));
    await assertSucceeds(setDoc(list(ADULT, "b"), newList()));
    await assertFails(setDoc(list(HELPER, "c"), newList()));
    await assertFails(setDoc(list(KID, "d"), newList()));
    await assertFails(setDoc(list(OUTSIDER, "e"), newList()));
  });

  it("validation: name 1–40, type enum, server createdAt, no extra fields", async () => {
    await assertFails(setDoc(list(OWNER, "f"), newList({ name: "x".repeat(41) })));
    await assertFails(setDoc(list(OWNER, "g"), newList({ type: "wishlist" })));
    await assertFails(setDoc(list(OWNER, "h"), newList({ createdAt: Timestamp.fromMillis(1) })));
    await assertFails(setDoc(list(OWNER, "i"), newList({ ownerUid: OWNER })));
  });

  it("owner and adult edit; createdAt is fixed", async () => {
    await assertSucceeds(updateDoc(list(ADULT, LID), { name: "Market", archived: true, order: 3 }));
    await assertFails(updateDoc(list(HELPER, LID), { name: "x" }));
    await assertFails(updateDoc(list(OWNER, LID), { createdAt: serverTimestamp() }));
  });

  it("only the owner deletes", async () => {
    await assertFails(deleteDoc(list(ADULT, LID)));
    await assertSucceeds(deleteDoc(list(OWNER, LID)));
  });

  it("every member reads lists", async () => {
    await assertSucceeds(getDoc(list(KID, LID)));
    await assertFails(getDoc(list(OUTSIDER, LID)));
  });
});

describe("aliases (CONTRACT §3.16)", () => {
  const alias = (uid: string, aid = "a1") => ref(uid, `households/${HID}/aliases/${aid}`);
  const data = (uid: string, extra: Record<string, unknown> = {}) => ({
    text: "عيش",
    canonicalId: "rice",
    createdBy: uid,
    createdAt: serverTimestamp(),
    ...extra,
  });

  it("any member creates, updates and deletes; createdBy is the caller", async () => {
    await assertSucceeds(setDoc(alias(KID), data(KID)));
    await assertSucceeds(setDoc(alias(HELPER), data(HELPER, { canonicalId: "arabic_bread" })));
    await assertSucceeds(deleteDoc(alias(ADULT)));
    await assertFails(setDoc(alias(ADULT, "a2"), data(OWNER)));
  });

  it("validation and outsiders", async () => {
    await assertFails(setDoc(alias(ADULT, "a3"), data(ADULT, { extra: 1 })));
    await assertFails(setDoc(alias(ADULT, "a4"), data(ADULT, { createdAt: Timestamp.fromMillis(1) })));
    await assertFails(setDoc(alias(ADULT, "a5"), data(ADULT, { text: "" })));
    await assertFails(setDoc(alias(OUTSIDER, "a6"), data(OUTSIDER)));
    await assertFails(getDoc(alias(OUTSIDER)));
  });
});

describe("functions-only household collections", () => {
  beforeEach(async () => {
    for (const c of ["trips", "events", "purchases", "usuals", "mealSuggestions"]) {
      await put(env, `households/${HID}/${c}/x`, { v: 1 });
    }
  });

  it("trips, events, purchases, usuals: members read, nobody writes (ARCHITECTURE §3.3, CONTRACT v4)", async () => {
    for (const c of ["trips", "events", "purchases", "usuals"]) {
      await assertSucceeds(getDoc(ref(KID, `households/${HID}/${c}/x`)));
      await assertFails(getDoc(ref(OUTSIDER, `households/${HID}/${c}/x`)));
      await assertFails(setDoc(ref(OWNER, `households/${HID}/${c}/y`), { v: 1 }));
      await assertFails(updateDoc(ref(OWNER, `households/${HID}/${c}/x`), { v: 2 }));
      await assertFails(deleteDoc(ref(OWNER, `households/${HID}/${c}/x`)));
    }
  });

  it("mealSuggestions: no client access (the callable returns them)", async () => {
    await assertFails(getDoc(ref(OWNER, `households/${HID}/mealSuggestions/x`)));
    await assertFails(setDoc(ref(OWNER, `households/${HID}/mealSuggestions/y`), { v: 1 }));
  });

  it("unknown subcollections are closed", async () => {
    await assertFails(setDoc(ref(OWNER, `households/${HID}/secrets/x`), { v: 1 }));
  });
});
