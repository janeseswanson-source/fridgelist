import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const backend = resolve(import.meta.dirname, "../..");
const read = (path: string): unknown => JSON.parse(readFileSync(resolve(backend, path), "utf8"));

describe("Remote Config defaults (CONTRACT §10)", () => {
  it("the deployable template has exactly the contract's flags and defaults", () => {
    const contract = readFileSync(resolve(backend, "../docs/CONTRACT.md"), "utf8");
    const section = contract.slice(
      contract.indexOf("## 10. Remote Config flags"),
      contract.indexOf("## 11."),
    );
    const rows = [...section.matchAll(/^\| `(\w+)` \| `([^`]+)` \|/gm)].map((m) => [m[1], m[2]]);
    expect(rows.length).toBeGreaterThanOrEqual(6);
    const template = read("remoteconfig.template.json") as {
      parameters: Record<string, { defaultValue: { value: string }; valueType: string }>;
    };
    expect(Object.keys(template.parameters).sort()).toEqual(rows.map(([k]) => k).sort());
    for (const [key, value] of rows) {
      const p = template.parameters[key ?? ""];
      expect(p?.defaultValue.value, key).toBe(value);
      expect(p?.valueType, key).toBe(value === "true" || value === "false" ? "BOOLEAN" : "NUMBER");
    }
  });

  it("firebase.json deploys the template", () => {
    expect((read("firebase.json") as { remoteconfig: unknown }).remoteconfig).toEqual({
      template: "remoteconfig.template.json",
    });
  });
});

describe("TTL policies (CONTRACT §3.8, §3.10)", () => {
  it("events and mealSuggestions expire on expireAt, without an index on it", () => {
    const { fieldOverrides } = read("firestore.indexes.json") as {
      fieldOverrides: { collectionGroup: string; fieldPath: string; ttl?: boolean; indexes: unknown[] }[];
    };
    for (const group of ["events", "mealSuggestions"]) {
      expect(fieldOverrides).toContainEqual({
        collectionGroup: group,
        fieldPath: "expireAt",
        ttl: true,
        indexes: [],
      });
    }
  });
});
