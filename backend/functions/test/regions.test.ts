import { describe, expect, it } from "vitest";
import { REGION } from "../src/config.js";
import * as functions from "../src/index.js";

/** Every deployed function runs in FUNCTIONS_REGION (CLAUDE.md); the apps call that region. */
describe("regions", () => {
  const exported = Object.entries(functions as Record<string, { __endpoint?: { region?: string[] } }>);

  it("exports functions", () => {
    expect(exported.map(([name]) => name)).toEqual(
      expect.arrayContaining(["health", "createHousehold", "createInvite", "joinHousehold", "onMemberWritten"]),
    );
  });

  it.each(exported)("%s runs in the configured region", (_name, fn) => {
    expect(fn.__endpoint?.region).toEqual([REGION]);
  });
});
