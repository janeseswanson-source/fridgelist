import { deleteApp, type App } from "firebase-admin/app";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import {
  createInviteCore,
  INVITE_MAX_USES,
  joinHouseholdCore,
  normalizeCode,
} from "../../src/callables/invites.js";
import { memberLanguagesOf, syncMember } from "../../src/triggers/onMemberWritten.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("households"));
});
beforeEach(async () => {
  await clearDb();
});
afterAll(async () => {
  await deleteApp(app);
});

const OWNER = "u-owner";
const request = (extra: Record<string, unknown> = {}) => ({
  name: "بيتنا",
  defaultLanguage: "ar",
  displayName: "Mama",
  language: "ar",
  uiLanguage: "ar",
  ...extra,
});
const joiner = (extra: Record<string, unknown> = {}) => ({ displayName: "Ayşe", language: "tr", ...extra });

async function household(lang = "ar") {
  return createHouseholdCore(db, OWNER, request({ defaultLanguage: lang }));
}

describe("createHousehold", () => {
  it("creates the household, owner, default list and user link", async () => {
    const { householdId, listId } = await household();
    const h = (await db.doc(`households/${householdId}`).get()).data();
    expect(h).toMatchObject({
      name: "بيتنا",
      ownerUid: OWNER,
      defaultLanguage: "ar",
      memberLanguages: ["ar"],
      plan: "free",
    });
    expect(h?.createdAt).toBeInstanceOf(Timestamp);
    const owner = (await db.doc(`households/${householdId}/members/${OWNER}`).get()).data();
    expect(owner).toMatchObject({
      role: "owner",
      displayName: "Mama",
      language: "ar",
      notify: { tripHeading: true, tripArrived: false, itemMissing: true, usuals: false },
    });
    const list = (await db.doc(`households/${householdId}/lists/${listId}`).get()).data();
    expect(list).toMatchObject({ name: "البقالة", type: "grocery", order: 0, archived: false });
    const user = (await db.doc(`users/${OWNER}`).get()).data();
    expect(user).toMatchObject({
      displayName: "Mama",
      language: "ar",
      uiLanguage: "ar",
      householdIds: [householdId],
    });
    expect(user?.createdAt).toBeInstanceOf(Timestamp);
  });

  it("names the default list in the household language", async () => {
    for (const [lang, name] of [
      ["ar", "البقالة"],
      ["tr", "Market listesi"],
      ["en", "Groceries"],
    ] as const) {
      const { householdId, listId } = await household(lang);
      expect((await db.doc(`households/${householdId}/lists/${listId}`).get()).get("name")).toBe(name);
    }
  });

  it("keeps an existing user doc (profile written first by the app) and adds the household", async () => {
    await db
      .doc(`users/${OWNER}`)
      .set({ displayName: "Mama", language: "ar", uiLanguage: "tr", householdIds: ["h-old"] });
    const { householdId } = await household();
    const user = (await db.doc(`users/${OWNER}`).get()).data();
    expect(user?.householdIds).toEqual(["h-old", householdId]);
    expect(user?.createdAt).toBeUndefined();
  });

  it("validates the request", async () => {
    await expectHttpsError(createHouseholdCore(db, OWNER, request({ name: "  " })), "invalid-argument");
    await expectHttpsError(
      createHouseholdCore(db, OWNER, request({ name: "x".repeat(41) })),
      "invalid-argument",
    );
    await expectHttpsError(
      createHouseholdCore(db, OWNER, request({ defaultLanguage: "fil" })),
      "invalid-argument",
    );
    await expectHttpsError(createHouseholdCore(db, OWNER, request({ language: "de" })), "invalid-argument");
    await expectHttpsError(createHouseholdCore(db, OWNER, {}), "invalid-argument");
    expect((await db.collection("households").get()).size).toBe(0);
  });

  it("trims names", async () => {
    const { householdId } = await createHouseholdCore(
      db,
      OWNER,
      request({ name: "  Home  ", displayName: " Mama " }),
    );
    expect((await db.doc(`households/${householdId}`).get()).get("name")).toBe("Home");
    expect((await db.doc(`households/${householdId}/members/${OWNER}`).get()).get("displayName")).toBe(
      "Mama",
    );
  });
});

describe("createInvite", () => {
  it("owner and adult invite adults, kids and helpers", async () => {
    const { householdId } = await household();
    const now = new Date(1_760_000_000_000);
    const invite = await createInviteCore(db, OWNER, { householdId, role: "kid" }, now);
    expect(invite.code).toMatch(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/);
    expect(invite.url).toBe(`https://links.example.com/join/${invite.code}`);
    expect(invite.expiresAt).toBe(1_760_000_000 + 7 * 86_400);
    expect((await db.doc(`invites/${invite.code}`).get()).data()).toMatchObject({
      householdId,
      role: "kid",
      createdBy: OWNER,
      maxUses: 10,
      uses: 0,
    });
    // An adult who joined can invite too.
    const adultInvite = await createInviteCore(db, OWNER, { householdId, role: "adult" }, now);
    const { role } = await joinHouseholdCore(db, "u-adult", joiner({ code: adultInvite.code }), now);
    expect(role).toBe("adult");
    await expect(
      createInviteCore(db, "u-adult", { householdId, role: "helper" }, now),
    ).resolves.toBeDefined();
  });

  it("helpers, kids and outsiders cannot invite; owner is not an invitable role", async () => {
    const { householdId } = await household();
    for (const role of ["helper", "kid"] as const) {
      const invite = await createInviteCore(db, OWNER, { householdId, role });
      await joinHouseholdCore(db, `u-${role}`, joiner({ code: invite.code }));
      await expectHttpsError(
        createInviteCore(db, `u-${role}`, { householdId, role: "adult" }),
        "permission-denied",
      );
    }
    await expectHttpsError(
      createInviteCore(db, "u-out", { householdId, role: "adult" }),
      "permission-denied",
    );
    await expectHttpsError(createInviteCore(db, OWNER, { householdId, role: "owner" }), "invalid-argument");
  });

  it("never reuses an existing code", async () => {
    const { householdId } = await household();
    const codes = ["ABCD2345", "ABCD2345", "WXYZ6789"];
    const first = await createInviteCore(
      db,
      OWNER,
      { householdId, role: "adult" },
      new Date(),
      () => codes.shift() ?? "",
    );
    const second = await createInviteCore(
      db,
      OWNER,
      { householdId, role: "kid" },
      new Date(),
      () => codes.shift() ?? "",
    );
    expect([first.code, second.code]).toEqual(["ABCD2345", "WXYZ6789"]);
    expect((await db.doc("invites/ABCD2345").get()).get("role")).toBe("adult");
  });
});

describe("joinHousehold", () => {
  async function inviteFor(role: "adult" | "kid" | "helper", now = new Date()) {
    const { householdId } = await household();
    const { code } = await createInviteCore(db, OWNER, { householdId, role }, now);
    return { householdId, code };
  }

  it("assigns the invite's role and does the language bookkeeping", async () => {
    for (const role of ["adult", "kid", "helper"] as const) {
      const { householdId, code } = await inviteFor(role);
      const uid = `u-${role}`;
      expect(await joinHouseholdCore(db, uid, joiner({ code }))).toEqual({ householdId, role });
      expect((await db.doc(`households/${householdId}/members/${uid}`).get()).data()).toMatchObject({
        role,
        displayName: "Ayşe",
        language: "tr",
        notify: { tripHeading: true, tripArrived: false, itemMissing: true, usuals: false },
      });
      expect((await db.doc(`households/${householdId}`).get()).get("memberLanguages")).toEqual(["ar", "tr"]);
      expect((await db.doc(`users/${uid}`).get()).get("householdIds")).toContain(householdId);
      expect((await db.doc(`invites/${code}`).get()).get("uses")).toBe(1);
    }
  });

  it("accepts codes as people type them", async () => {
    const { code } = await inviteFor("adult");
    const typed = `${code.slice(0, 4).toLowerCase()}-${code.slice(4)} `;
    expect(normalizeCode(typed)).toBe(code);
    await expect(joinHouseholdCore(db, "u-1", joiner({ code: typed }))).resolves.toMatchObject({
      role: "adult",
    });
  });

  it("joining twice keeps the role and does not use up the invite", async () => {
    const { householdId, code } = await inviteFor("kid");
    await joinHouseholdCore(db, "u-k", joiner({ code }));
    expect(await joinHouseholdCore(db, "u-k", joiner({ code, displayName: "Other" }))).toEqual({
      householdId,
      role: "kid",
    });
    expect((await db.doc(`invites/${code}`).get()).get("uses")).toBe(1);
    expect((await db.doc(`households/${householdId}/members/u-k`).get()).get("displayName")).toBe("Ayşe");
    // The owner following their own invite stays owner.
    expect(await joinHouseholdCore(db, OWNER, joiner({ code }))).toEqual({ householdId, role: "owner" });
  });

  it("an expired invite fails with failed-precondition", async () => {
    const created = new Date(1_760_000_000_000);
    const { code } = await inviteFor("adult", created);
    const justBefore = new Date(created.getTime() + 7 * 86_400_000 - 1);
    const atExpiry = new Date(created.getTime() + 7 * 86_400_000);
    await expect(joinHouseholdCore(db, "u-1", joiner({ code }), justBefore)).resolves.toMatchObject({
      role: "adult",
    });
    await expectHttpsError(joinHouseholdCore(db, "u-2", joiner({ code }), atExpiry), "failed-precondition");
  });

  it(`max uses: ${String(INVITE_MAX_USES)} joins, then failed-precondition; members can still rejoin`, async () => {
    const { code } = await inviteFor("helper");
    for (let i = 0; i < INVITE_MAX_USES; i++) await joinHouseholdCore(db, `u-${String(i)}`, joiner({ code }));
    await expectHttpsError(joinHouseholdCore(db, "u-late", joiner({ code })), "failed-precondition");
    await expect(joinHouseholdCore(db, "u-3", joiner({ code }))).resolves.toMatchObject({ role: "helper" });
    expect((await db.doc(`invites/${code}`).get()).get("uses")).toBe(INVITE_MAX_USES);
  });

  it("concurrent joins never exceed max uses", { timeout: 120_000 }, async () => {
    const { householdId, code } = await inviteFor("adult");
    const results = await Promise.allSettled(
      Array.from({ length: INVITE_MAX_USES + 4 }, (_, i) =>
        joinHouseholdCore(db, `c-${String(i)}`, joiner({ code })),
      ),
    );
    const joined = results.filter((r) => r.status === "fulfilled").length;
    const uses = (await db.doc(`invites/${code}`).get()).get("uses") as number;
    // Safety: never past max uses, and every success is counted exactly once.
    expect(uses).toBe(joined);
    expect(uses).toBeLessThanOrEqual(INVITE_MAX_USES);
    expect((await db.collection(`households/${householdId}/members`).get()).size).toBe(1 + joined);
    // Every failure is "used up" or a retryable contention error (gRPC ABORTED), never a crash.
    for (const r of results) {
      if (r.status === "rejected") {
        const reason = r.reason as { code?: unknown };
        expect(["failed-precondition", 10]).toContain(reason.code);
      }
    }
    // With the larger retry budget, a burst of 14 gets (nearly) every seat.
    expect(joined).toBeGreaterThanOrEqual(INVITE_MAX_USES - 1);
  });

  it("unknown codes, deleted households and bad input", async () => {
    await expectHttpsError(joinHouseholdCore(db, "u-1", joiner({ code: "ABCD2345" })), "not-found");
    const { householdId, code } = await inviteFor("adult");
    await db.recursiveDelete(db.doc(`households/${householdId}`));
    await expectHttpsError(joinHouseholdCore(db, "u-1", joiner({ code })), "not-found");
    await expectHttpsError(joinHouseholdCore(db, "u-1", joiner({ code: "ABC0" })), "invalid-argument");
    await expectHttpsError(
      joinHouseholdCore(db, "u-1", joiner({ code, displayName: "" })),
      "invalid-argument",
    );
    await expectHttpsError(
      joinHouseholdCore(db, "u-1", joiner({ code, language: "xx" })),
      "invalid-argument",
    );
  });
});

describe("onMemberWritten bookkeeping", () => {
  it("memberLanguages is the sorted, de-duplicated union", () => {
    expect(memberLanguagesOf(["tr", "ar", "tr", "", null, "fil"])).toEqual(["ar", "fil", "tr"]);
  });

  it("follows a language change and a removal", async () => {
    const { householdId } = await household();
    const { code } = await createInviteCore(db, OWNER, { householdId, role: "adult" });
    await joinHouseholdCore(db, "u-a", joiner({ code }));

    await db.doc(`households/${householdId}/members/u-a`).update({ language: "fil" });
    await syncMember(db, householdId, "u-a", { existedBefore: true, existsAfter: true });
    expect((await db.doc(`households/${householdId}`).get()).get("memberLanguages")).toEqual(["ar", "fil"]);

    await db.doc(`households/${householdId}/members/u-a`).delete();
    await syncMember(db, householdId, "u-a", { existedBefore: true, existsAfter: false });
    expect((await db.doc(`households/${householdId}`).get()).get("memberLanguages")).toEqual(["ar"]);
    expect((await db.doc("users/u-a").get()).get("householdIds")).toEqual([]);
  });

  it("adds the household to a member written by other means, and ignores deleted households", async () => {
    const { householdId } = await household();
    await db
      .doc(`households/${householdId}/members/u-x`)
      .set({ role: "adult", displayName: "X", language: "en" });
    await syncMember(db, householdId, "u-x", { existedBefore: false, existsAfter: true });
    expect((await db.doc("users/u-x").get()).get("householdIds")).toEqual([householdId]);
    expect((await db.doc(`households/${householdId}`).get()).get("memberLanguages")).toEqual(["ar", "en"]);

    await db.recursiveDelete(db.doc(`households/${householdId}`));
    await expect(
      syncMember(db, householdId, "u-x", { existedBefore: true, existsAfter: false }),
    ).resolves.toBeUndefined();
    expect((await db.doc(`households/${householdId}`).get()).exists).toBe(false);
  });
});
