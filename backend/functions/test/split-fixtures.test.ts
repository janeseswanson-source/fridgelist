import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { LocalSplitter } from "../src/lib/localSplit.js";
import { aliasId, matchKey } from "../src/lib/normalize.js";

/** CLAUDE.md rule 6: the TypeScript port must reproduce the shared fixtures exactly. */
const fixtures = (name: string): unknown =>
  JSON.parse(readFileSync(resolve(import.meta.dirname, "../../../fixtures", name), "utf8"));

interface SplitCase {
  text: string;
  localeHints: string[];
  aliases?: Record<string, string>;
  expect: unknown[];
}
interface MatchKeyCase {
  text: string;
  turkish: boolean;
  key: string;
  aliasId: string;
}

const splitCases = fixtures("split_cases.json") as { split: SplitCase[]; matchKey: MatchKeyCase[] };
const parseCases = fixtures("parse_cases.json") as {
  cases: { id: string; tags: string[]; text: string; localeHints: string[]; expect: unknown[] }[];
};
const splitter = new LocalSplitter();

describe("fixtures/split_cases.json (exact output of the Android reference)", () => {
  it.each(splitCases.split.map((c, i) => [i, JSON.stringify(c.text).slice(0, 60), c] as const))(
    "#%i %s",
    (_i, _label, c) => {
      const aliases = new Map(Object.entries(c.aliases ?? {}));
      // JSON numbers: Kotlin writes 2.0, JSON.parse gives 2; compare values, not formatting.
      expect(splitter.split(c.text, c.localeHints, aliases)).toEqual(c.expect);
    },
  );

  it.each(splitCases.matchKey.map((c) => [JSON.stringify(c.text), c.turkish, c] as const))(
    "matchKey(%s, turkish=%s)",
    (_label, _turkish, c) => {
      expect(matchKey(c.text, c.turkish)).toBe(c.key);
      expect(aliasId(c.key)).toBe(c.aliasId);
    },
  );
});

describe("fixtures/parse_cases.json: every split case yields the right number of items", () => {
  const cases = parseCases.cases.filter((c) => c.tags.includes("split"));
  it("has at least 18 split cases", () => {
    expect(cases.length).toBeGreaterThanOrEqual(18);
  });
  it.each(cases.map((c) => [c.id, c] as const))("%s", (_id, c) => {
    expect(splitter.split(c.text, c.localeHints)).toHaveLength(c.expect.length);
  });
});
