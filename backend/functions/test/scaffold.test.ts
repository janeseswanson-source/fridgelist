import { describe, expect, it } from "vitest";
import { healthResponse } from "../src/callables/health.js";
import { CONTRACT_VERSION, REGION } from "../src/config.js";
import { INVITE_ALPHABET, inviteCode, isInviteCode, osmStoreId } from "../src/lib/ids.js";
import { safeFields } from "../src/lib/log.js";
import { unixSeconds } from "../src/lib/time.js";

describe("ids", () => {
  it("invite codes are 8 characters from the contract alphabet", () => {
    for (let i = 0; i < 200; i++) {
      const code = inviteCode();
      expect(code).toHaveLength(8);
      expect(isInviteCode(code)).toBe(true);
    }
    expect(INVITE_ALPHABET).not.toMatch(/[0O1I]/);
  });

  it("rejects look-alikes, lowercase and wrong lengths", () => {
    expect(isInviteCode("ABCD2345")).toBe(true);
    expect(isInviteCode("ABCD0345")).toBe(false);
    expect(isInviteCode("abcd2345")).toBe(false);
    expect(isInviteCode("ABCD234")).toBe(false);
  });

  it("uses every alphabet position", () => {
    let i = 0;
    expect(inviteCode(() => i++ % INVITE_ALPHABET.length)).toBe("ABCDEFGH");
  });

  it("osm store ids", () => {
    expect(osmStoreId("node", 123)).toBe("osm_node_123");
  });
});

describe("log", () => {
  it("never passes user text through", () => {
    expect(safeFields({ householdId: "h1", itemName: "milk", displayName: "Ayşe", count: 2 })).toEqual({
      householdId: "h1",
      itemName: "[redacted]",
      displayName: "[redacted]",
      count: 2,
    });
  });
});

describe("time", () => {
  it("unix seconds", () => {
    expect(unixSeconds(new Date(1_760_000_000_999))).toBe(1_760_000_000);
  });
});

describe("health", () => {
  it("reports region and contract version only", () => {
    expect(healthResponse()).toEqual({ ok: true, region: REGION, contractVersion: CONTRACT_VERSION });
    expect(CONTRACT_VERSION).toBe(5);
  });
});
