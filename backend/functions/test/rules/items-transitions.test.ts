import { assertFails, assertSucceeds, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteField, doc, serverTimestamp, updateDoc } from "firebase/firestore";
import { afterAll, beforeAll, describe, it } from "vitest";
import { ADULT, ROLES, type RoleName, as, itemPath, put, seedHousehold, setupEnv } from "./setup.js";

type Status = "pending" | "open" | "claimed" | "done" | "missing";
const STATUSES: Status[] = ["pending", "open", "claimed", "done", "missing"];

/** CONTRACT §3.6, written out independently of the rules (same table as Android StatusRules). */
function allowed(from: Status, to: Status, role: RoleName, isClaimer: boolean): boolean {
  const approver = role === "owner" || role === "adult";
  switch (from) {
    case "pending":
      return to === "open" && approver;
    case "open":
      return to === "claimed" || to === "done" || to === "missing";
    case "claimed":
      return to === "open" ? isClaimer || approver : to === "done" || to === "missing";
    case "done":
      return to === "open";
    case "missing":
      return to === "open" || to === "done";
  }
}

/** The patch the apps send for each target status (Android ItemPatches.status). */
function patchFor(to: Status, uid: string): Record<string, unknown> {
  const base = { status: to, updatedAt: serverTimestamp() };
  switch (to) {
    case "claimed":
      return { ...base, claimedBy: uid };
    case "done":
      return { ...base, doneBy: uid, doneAt: serverTimestamp() };
    case "open":
      return {
        ...base,
        claimedBy: deleteField(),
        doneBy: deleteField(),
        doneAt: deleteField(),
        doneTripId: deleteField(),
      };
    default:
      return base;
  }
}

let env: RulesTestEnvironment;
beforeAll(async () => {
  env = await setupEnv();
  await seedHousehold(env);
});
afterAll(async () => {
  await env.cleanup();
});

describe("CONTRACT §3.6 status transitions: every from → to, every role, claimer or not", () => {
  let n = 0;
  for (const role of Object.keys(ROLES) as RoleName[]) {
    for (const from of STATUSES) {
      for (const to of STATUSES) {
        if (from === to) continue;
        // The claimer distinction only matters for items that are claimed.
        for (const isClaimer of from === "claimed" ? [true, false] : [false]) {
          const expect = allowed(from, to, role, isClaimer);
          const uid = ROLES[role];
          const iid = `t${String(n++)}`;
          it(`${role}${isClaimer ? " (claimer)" : ""}: ${from} → ${to} is ${expect ? "allowed" : "denied"}`, async () => {
            await put(env, itemPath(iid), {
              name: "milk",
              status: from,
              addedBy: ADULT,
              addedVia: "app_text",
              urgent: false,
              ...(from === "claimed" ? { claimedBy: isClaimer ? uid : `${uid}-other` } : {}),
            });
            const write = updateDoc(doc(as(env, uid), itemPath(iid)), patchFor(to, uid));
            await (expect ? assertSucceeds(write) : assertFails(write));
          });
        }
      }
    }
  }
});
