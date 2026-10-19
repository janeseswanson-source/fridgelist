import { assertFails, assertSucceeds, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { ADULT, HELPER, KID, OUTSIDER, OWNER, as, itemPath, put, seedHousehold, setupEnv } from "./setup.js";

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

/** What the apps write on create (Android ItemPatches.create). */
function newItem(uid: string, status = "open", extra: Record<string, unknown> = {}) {
  return {
    name: "süt",
    qty: 2,
    unit: "l",
    urgent: false,
    status,
    addedBy: uid,
    addedVia: "app_text",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...extra,
  };
}

const create = (uid: string, data: Record<string, unknown>, iid = "new") =>
  setDoc(doc(as(env, uid), itemPath(iid)), data);

describe("item create (CONTRACT §3.6 create rows, ARCHITECTURE §3.3)", () => {
  it("owner, adult and helper create open items", async () => {
    await assertSucceeds(create(OWNER, newItem(OWNER), "a"));
    await assertSucceeds(create(ADULT, newItem(ADULT), "b"));
    await assertSucceeds(create(HELPER, newItem(HELPER), "c"));
  });

  it("kids' items are forced to pending", async () => {
    await assertSucceeds(create(KID, newItem(KID, "pending")));
    await assertFails(create(KID, newItem(KID, "open"), "k2"));
  });

  it("adults cannot create pending or any other status", async () => {
    for (const status of ["pending", "claimed", "done", "missing"]) {
      await assertFails(create(ADULT, newItem(ADULT, status), `s-${status}`));
    }
  });

  it("addedBy must be the caller; non-members cannot add", async () => {
    await assertFails(create(ADULT, newItem(OWNER)));
    await assertFails(create(OUTSIDER, newItem(OUTSIDER)));
    await assertFails(setDoc(doc(as(env, null), itemPath("anon")), newItem("x")));
  });

  it("addedVia must be a client channel (guest_web and whatsapp are functions')", async () => {
    await assertSucceeds(create(ADULT, newItem(ADULT, "open", { addedVia: "notification_reply" })));
    await assertFails(create(ADULT, newItem(ADULT, "open", { addedVia: "guest_web" }), "g"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { addedVia: "whatsapp" }), "w"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { addedVia: "carrier_pigeon" }), "p"));
  });

  it("server-owned fields are rejected", async () => {
    const fields: Record<string, unknown> = {
      lang: "tr",
      names: { ar: "حليب" },
      canonicalId: "milk",
      requestedBy: [ADULT],
      recipeRef: "m1",
      claimedBy: ADULT,
      doneBy: ADULT,
    };
    for (const [key, value] of Object.entries(fields)) {
      await assertFails(create(ADULT, newItem(ADULT, "open", { [key]: value }), `f-${key}`));
    }
  });

  it("lengths and ranges: name 1–80, qty 0 < q ≤ 999, notes ≤ 120, enums", async () => {
    await assertSucceeds(
      create(ADULT, newItem(ADULT, "open", { name: "x".repeat(80), notes: "n".repeat(120) })),
    );
    await assertFails(create(ADULT, newItem(ADULT, "open", { name: "x".repeat(81) }), "n81"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { name: "" }), "n0"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { qty: 0 }), "q0"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { qty: 1000 }), "q1000"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { qty: "2" }), "qs"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { notes: "n".repeat(121) }), "notes"));
    await assertFails(create(ADULT, newItem(ADULT, "open", { unit: "gallon" }), "unit"));
  });

  it("items may be created without a category; a client category needs categorySource user", async () => {
    await assertSucceeds(
      create(ADULT, newItem(ADULT, "open", { category: "dairy_eggs", categorySource: "user" })),
    );
    await assertFails(create(ADULT, newItem(ADULT, "open", { category: "dairy_eggs" }), "c1"));
    await assertFails(
      create(ADULT, newItem(ADULT, "open", { category: "dairy_eggs", categorySource: "auto" }), "c2"),
    );
    await assertFails(create(ADULT, newItem(ADULT, "open", { categorySource: "user" }), "c3"));
    await assertFails(
      create(ADULT, newItem(ADULT, "open", { category: "toys", categorySource: "user" }), "c4"),
    );
  });

  it("timestamps are server time", async () => {
    const past = Timestamp.fromMillis(1_000);
    await assertFails(create(ADULT, newItem(ADULT, "open", { createdAt: past })));
    await assertFails(create(ADULT, newItem(ADULT, "open", { updatedAt: past }), "u"));
    const withoutCreatedAt: Record<string, unknown> = newItem(ADULT);
    delete withoutCreatedAt.createdAt;
    await assertFails(create(ADULT, withoutCreatedAt, "missing"));
  });
});

describe("item update: field whitelist and writers", () => {
  const existing = (extra: Record<string, unknown> = {}) => ({
    name: "süt",
    status: "open",
    addedBy: ADULT,
    addedVia: "app_text",
    urgent: false,
    lang: "tr",
    canonicalId: "milk",
    category: "dairy_eggs",
    categorySource: "auto",
    ...extra,
  });
  const update = (uid: string, data: Record<string, unknown>) =>
    updateDoc(doc(as(env, uid), itemPath("i")), { ...data, updatedAt: serverTimestamp() });

  beforeEach(async () => {
    await put(env, itemPath("i"), existing());
  });

  it("any member edits qty, unit, notes, urgent, sortKey, photoUrl", async () => {
    await assertSucceeds(
      update(HELPER, { qty: 3, unit: "bottle", notes: "light", urgent: true, sortKey: 2 }),
    );
    await assertSucceeds(update(KID, { qty: 1 }));
    await assertSucceeds(update(ADULT, { photoUrl: "https://example.invalid/p.jpg" }));
    await assertFails(update(ADULT, { photoUrl: 42 }));
    await assertFails(update(ADULT, { qty: 1000 }));
  });

  it("only the creator renames (name stays exactly as typed)", async () => {
    await assertSucceeds(update(ADULT, { name: "süt 1,5" }));
    await assertFails(update(OWNER, { name: "milk" }));
  });

  it("server-owned and creator fields are read-only", async () => {
    const fields: Record<string, unknown> = {
      lang: "ar",
      names: { ar: "حليب" },
      canonicalId: "yogurt",
      requestedBy: [OWNER],
      recipeRef: "m1",
      addedBy: OWNER,
      addedVia: "widget",
      createdAt: serverTimestamp(),
    };
    for (const [key, value] of Object.entries(fields)) {
      await assertFails(update(ADULT, { [key]: value }));
    }
  });

  it("category only with categorySource user", async () => {
    await assertSucceeds(update(HELPER, { category: "beverages", categorySource: "user" }));
    await put(env, itemPath("i"), existing());
    await assertFails(update(HELPER, { category: "beverages" }));
    // A member's choice can't be handed back to the server's auto category.
    await put(env, itemPath("i"), existing({ categorySource: "user" }));
    await assertFails(update(HELPER, { categorySource: "auto" }));
    await assertFails(update(HELPER, { category: "beverages", categorySource: "auto" }));
  });

  it("updatedAt must be server time", async () => {
    await assertFails(updateDoc(doc(as(env, ADULT), itemPath("i")), { qty: 2 }));
    await assertFails(
      updateDoc(doc(as(env, ADULT), itemPath("i")), { qty: 2, updatedAt: Timestamp.fromMillis(1) }),
    );
  });

  it("claimedBy and doneBy can only name the caller; doneAt is server time", async () => {
    await assertFails(update(HELPER, { status: "claimed", claimedBy: OWNER }));
    await assertFails(update(HELPER, { status: "done", doneBy: OWNER, doneAt: serverTimestamp() }));
    await assertFails(update(HELPER, { status: "done", doneBy: HELPER, doneAt: Timestamp.fromMillis(1) }));
    await assertSucceeds(
      update(HELPER, { status: "done", doneBy: HELPER, doneAt: serverTimestamp(), doneTripId: "t1" }),
    );
  });

  it("claimedBy cannot be set without claiming", async () => {
    await assertFails(update(HELPER, { claimedBy: HELPER }));
  });

  it("non-members can't read or update", async () => {
    await assertFails(getDoc(doc(as(env, OUTSIDER), itemPath("i"))));
    await assertFails(update(OUTSIDER, { qty: 2 }));
    await assertSucceeds(getDoc(doc(as(env, KID), itemPath("i"))));
  });
});

describe("item delete", () => {
  it("pending items (requests) are deleted by owner or adult only", async () => {
    for (const [uid, ok] of [
      [OWNER, true],
      [ADULT, true],
      [HELPER, false],
      [KID, false],
    ] as const) {
      await put(env, itemPath("p"), {
        name: "chips",
        status: "pending",
        addedBy: KID,
        addedVia: "app_text",
        urgent: false,
      });
      const del = deleteDoc(doc(as(env, uid), itemPath("p")));
      await (ok ? assertSucceeds(del) : assertFails(del));
    }
  });

  it("other items are deleted by any member, never by outsiders", async () => {
    for (const uid of [OWNER, ADULT, HELPER, KID]) {
      await put(env, itemPath("o"), {
        name: "milk",
        status: "done",
        addedBy: ADULT,
        addedVia: "app_text",
        urgent: false,
      });
      await assertSucceeds(deleteDoc(doc(as(env, uid), itemPath("o"))));
    }
    await put(env, itemPath("o"), {
      name: "milk",
      status: "open",
      addedBy: ADULT,
      addedVia: "app_text",
      urgent: false,
    });
    await assertFails(deleteDoc(doc(as(env, OUTSIDER), itemPath("o"))));
  });
});
