import { mkdtempSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ClassifyingLlm } from "../src/llm/provider.js";
import {
  LlmUnavailableError,
  RecordingTransport,
  ReplayTransport,
  type LlmTransport,
} from "../src/llm/transport.js";
import { Catalog } from "../src/lib/catalog.js";
import { detectLanguage } from "../src/lib/detectLang.js";
import { findMergeTarget, mergePatch, type MergeableItem } from "../src/lib/merge.js";
import { aliasId, matchKey } from "../src/lib/normalize.js";
import { numberWord } from "../src/lib/numbers.js";
import { resolveItems } from "../src/lib/resolve.js";
import { lookupUnit, resolveUnit } from "../src/lib/units.js";
import { FakeLlm, MemoryCache } from "./fakes.js";

const catalog = Catalog.bundled;

describe("numbers and units (ARCHITECTURE §4.1)", () => {
  it("number words in three languages; Turkish 'on' needs a Turkish hint", () => {
    expect(numberWord(matchKey("ثلاثة"), false)).toEqual({ kind: "value", value: 3 });
    expect(numberWord("beş", true)).toEqual({ kind: "value", value: 5 });
    expect(numberWord("twelve", false)).toEqual({ kind: "value", value: 12 });
    expect(numberWord("on", true)).toEqual({ kind: "value", value: 10 });
    expect(numberWord("on", false)).toBeNull();
    expect(numberWord(matchKey("ونص"), false)).toEqual({ kind: "plus", value: 0.5 });
    expect(numberWord("yarım", true)).toEqual({ kind: "fraction", value: 0.5 });
  });

  it("units and containers: a box of eggs is a tray, a can of chickpeas a can", () => {
    expect(lookupUnit(matchKey("كيلو"))?.unit).toBe("kg");
    expect(lookupUnit("şişe")?.unit).toBe("bottle");
    expect(lookupUnit("g")?.needsNumber).toBe(true);
    const box = lookupUnit(matchKey("كرتونة"));
    expect(box && resolveUnit(box, "eggs", catalog)).toBe("tray");
    const pack = lookupUnit(matchKey("علبة"));
    expect(
      pack && resolveUnit(pack, catalog.items.find((i) => i.defaultUnit === "can")?.id ?? null, catalog),
    ).toBe("can");
  });

  it("alias ids match Android (SHA-256, 32 hex)", () => {
    expect(aliasId(matchKey("عيش"))).toMatch(/^[0-9a-f]{32}$/);
  });
});

describe("detectLanguage", () => {
  it("by script, Turkish letters, catalog synonym, then hint", () => {
    expect(detectLanguage("بندورة", [], catalog)).toBe("ar");
    expect(detectLanguage("ٹماٹر", [], catalog)).toBe("ur");
    expect(detectLanguage("टमाटर", [], catalog)).toBe("hi");
    expect(detectLanguage("ıspanak", [], catalog)).toBe("tr");
    expect(detectLanguage("domates", ["en"], catalog)).toBe("tr");
    expect(detectLanguage("bananas", ["tr"], catalog)).toBe("en");
    expect(detectLanguage("sabong panlaba", ["fil"], catalog)).toBe("fil");
    expect(detectLanguage("xyz", [], catalog)).toBe("en");
    // Latin text never takes an Arabic-script hint (found in the B4 end-to-end run).
    expect(detectLanguage("ibuprofen", ["ar"], catalog)).toBe("en");
    expect(detectLanguage("ibuprofen", ["ar-SA", "fil"], catalog)).toBe("fil");
  });
});

describe("merge rule (ARCHITECTURE §4.2)", () => {
  const item = (over: Partial<MergeableItem>): MergeableItem => ({
    id: "x",
    name: "süt",
    status: "open",
    canonicalId: "milk",
    addedBy: "u1",
    createdAtMillis: 10,
    ...over,
  });

  it("targets the oldest open or claimed item with the same canonicalId", () => {
    const newer = item({ id: "n", createdAtMillis: 50 });
    const others = [
      item({ id: "c", status: "claimed", createdAtMillis: 20 }),
      item({ id: "a", createdAtMillis: 30 }),
      item({ id: "d", status: "done", createdAtMillis: 5 }),
      item({ id: "p", status: "pending", createdAtMillis: 1 }),
      item({ id: "other", canonicalId: "bread", createdAtMillis: 2 }),
      newer,
    ];
    expect(findMergeTarget(newer, others)?.id).toBe("c");
  });

  it("falls back to the normalized name; kids' pending items never merge", () => {
    const newer = item({ id: "n", name: "SÜT ", canonicalId: null, createdAtMillis: 50 });
    expect(findMergeTarget(newer, [item({ id: "o", canonicalId: null, name: "süt" })])?.id).toBe("o");
    expect(findMergeTarget({ ...newer, status: "pending" }, [item({ id: "o" })])).toBeNull();
  });

  it("same unit: quantities add (missing qty counts as 1, capped at 999)", () => {
    expect(mergePatch(item({ qty: 2, unit: "l" }), item({ qty: 1.5, unit: "l", addedBy: "u2" }))).toEqual({
      qty: 3.5,
      requestedBy: ["u1", "u2"],
    });
    expect(mergePatch(item({}), item({ qty: 2 })).qty).toBe(3);
    expect(mergePatch(item({ qty: 990 }), item({ qty: 20 })).qty).toBe(999);
    expect(mergePatch(item({}), item({})).qty).toBeUndefined();
  });

  it("different unit: the new amount goes to notes; urgency carries over", () => {
    const patch = mergePatch(
      item({ qty: 2, unit: "l", notes: "light" }),
      item({ qty: 6, unit: "bottle", urgent: true, addedBy: "u2" }),
    );
    expect(patch).toEqual({ notes: "light; + 6 bottle", urgent: true, requestedBy: ["u1", "u2"] });
    expect(mergePatch(item({ requestedBy: ["u1", "u3"] }), item({ unit: "kg" })).requestedBy).toEqual([
      "u1",
      "u3",
    ]);
  });
});

describe("LLM provider (ARCHITECTURE §3.5)", () => {
  const ids = catalog.items.map((i) => i.id);
  const ok = {
    items: [
      { canonicalId: "milk", category: "dairy_eggs", lang: "en", names: { ar: "حليب" }, confidence: 0.9 },
    ],
  };

  it("validates, retries once, then reports unavailable", async () => {
    let n = 0;
    const flaky: LlmTransport = { complete: () => Promise.resolve(n++ === 0 ? { items: "nope" } : ok) };
    await expect(
      new ClassifyingLlm(flaky, "m", ids).classify({
        names: ["milk"],
        localeHints: [],
        targetLanguages: ["ar"],
      }),
    ).resolves.toHaveLength(1);
    const broken: LlmTransport = { complete: () => Promise.resolve({ items: [] }) };
    await expect(
      new ClassifyingLlm(broken, "m", ids).classify({
        names: ["milk"],
        localeHints: [],
        targetLanguages: [],
      }),
    ).rejects.toBeInstanceOf(LlmUnavailableError);
  });

  it("drops canonical ids the catalog doesn't have", async () => {
    const invented: LlmTransport = {
      complete: () => Promise.resolve({ items: [{ ...ok.items[0], canonicalId: "unicorn_milk" }] }),
    };
    const [item] = await new ClassifyingLlm(invented, "m", ids).classify({
      names: ["x"],
      localeHints: [],
      targetLanguages: [],
    });
    expect(item?.canonicalId).toBeNull();
  });

  it("records once, then replays offline", async () => {
    const dir = mkdtempSync(join(tmpdir(), "rec-"));
    const real: LlmTransport = { complete: () => Promise.resolve(ok) };
    const request = { names: ["milk"], localeHints: ["en"], targetLanguages: ["ar"] };
    await new ClassifyingLlm(new RecordingTransport(real, dir), "m", ids).classify(request);
    expect(readdirSync(dir)).toHaveLength(1);
    await expect(
      new ClassifyingLlm(new ReplayTransport(dir), "m", ids).classify(request),
    ).resolves.toHaveLength(1);
    await expect(
      new ClassifyingLlm(new ReplayTransport(dir), "m", ids).classify({ ...request, names: ["bread"] }),
    ).rejects.toBeInstanceOf(LlmUnavailableError);
  });
});

describe("resolveItems: alias → dialect → catalog → cache → LLM", () => {
  const base = {
    localeHints: ["ar"],
    aliases: new Map<string, string>(),
    targetLanguages: ["ar", "tr", "en"],
  };

  it("catalog items get the catalog's names; the typed name stays for its own language", async () => {
    const llm = new FakeLlm();
    const [r] = await resolveItems([{ name: "بندورة" }], { ...base, llm });
    expect(r).toMatchObject({
      canonicalId: "tomato",
      category: "produce",
      lang: "ar",
      source: "catalog",
      confidence: 0.95,
    });
    expect(r?.names).toEqual({ ar: "بندورة", tr: "Domates", en: "Tomatoes" });
    expect(llm.calls).toHaveLength(0);
  });

  it("dialect by locale hint, alias first, ambiguous with alternatives", async () => {
    const llm = new FakeLlm();
    const [egypt] = await resolveItems([{ name: "عيش" }], { ...base, localeHints: ["ar-EG"], llm });
    expect(egypt).toMatchObject({ canonicalId: "bread", source: "dialect", confidence: 0.85 });
    const [alias] = await resolveItems([{ name: "عيش" }], {
      ...base,
      aliases: new Map([[matchKey("عيش"), "rice"]]),
      llm,
    });
    expect(alias).toMatchObject({ canonicalId: "rice", source: "alias", confidence: 1 });
    const [unsure] = await resolveItems([{ name: "عيش" }], { ...base, llm });
    expect(unsure).toMatchObject({
      canonicalId: "bread",
      source: "ambiguous",
      notes: "alternatives: bread, rice",
    });
    expect(unsure?.confidence).toBeLessThan(0.6);
    expect(llm.calls).toHaveLength(0);
  });

  it("unknown names go to the LLM once, in one batch, then come from the cache", async () => {
    const llm = new FakeLlm({
      "sigara böreği yufkası": {
        category: "frozen",
        lang: "tr",
        names: { ar: "رقائق البوريك", en: "Börek sheets" },
      },
    });
    const cache = new MemoryCache();
    const ctx = { ...base, localeHints: ["tr"], llm, cache };
    const first = await resolveItems(
      [{ name: "sigara böreği yufkası" }, { name: "süt" }, { name: "kedi kumu" }],
      ctx,
    );
    expect(llm.calls).toHaveLength(1);
    expect(llm.calls[0]?.names).toEqual(["sigara böreği yufkası", "kedi kumu"]);
    expect(first[0]).toMatchObject({ category: "frozen", source: "llm", lang: "tr" });
    expect(first[0]?.names).toMatchObject({ tr: "sigara böreği yufkası", ar: "رقائق البوريك" });
    expect(first[1]).toMatchObject({ canonicalId: "milk", source: "catalog" });
    await resolveItems([{ name: "sigara böreği yufkası" }], ctx);
    expect(llm.calls).toHaveLength(1);
  });

  it("LLM down: local result, category other, never throws", async () => {
    const [r] = await resolveItems([{ name: "kedi kumu" }], {
      ...base,
      localeHints: ["tr"],
      llm: new FakeLlm({}, true),
    });
    expect(r).toMatchObject({
      canonicalId: null,
      category: "other",
      source: "unresolved",
      lang: "tr",
      names: { tr: "kedi kumu" },
    });
  });
});
