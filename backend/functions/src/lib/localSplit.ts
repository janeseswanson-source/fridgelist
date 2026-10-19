/* eslint-disable no-control-regex -- VT and FS..US reproduce Kotlin's \s and Char.isWhitespace (CLAUDE.md rule 6). */
import { Catalog, type Ambiguity } from "./catalog.js";
import { invisible, isTurkish, matchKey } from "./normalize.js";
import { numberWord, parseNumeral } from "./numbers.js";
import { lookupUnit, resolveUnit, type Unit, type UnitWord } from "./units.js";

/**
 * On-device-style splitter (ARCHITECTURE §4.1), a line-by-line port of the Android reference
 * (core:i18n LocalSplitter.kt). `name` keeps the words exactly as typed. Must reproduce
 * fixtures/split_cases.json exactly.
 */
export interface SplitItem {
  name: string;
  qty: number | null;
  unit: Unit | null;
  canonicalId: string | null;
  ambiguity: Ambiguity | null;
}

interface Token {
  raw: string;
  key: string;
}

interface Quantity {
  qty: number | null;
  unit: UnitWord | null;
  dozen: boolean;
  consumed: number;
}

const TIMES = "×";
// A comma is a separator unless it sits between two digits (Turkish decimal "1,5").
const HARD_SEPARATORS = /(?<!\p{Nd}),|,(?!\p{Nd})|[،؛;&+\n]/u;
/** Kotlin `\s`: ASCII whitespace only. */
const WHITESPACE = /[ \t\n\x0B\f\r]+/;
const GLUED_QTY_UNIT = /^(\p{Nd}+(?:[.,٫]\p{Nd}+)?)(\p{L}+)$/u;
const MULTIPLIER = /^(?:[x×](\p{Nd}+)|(\p{Nd}+)[x×])$/iu;
const DIGIT = /^\p{Nd}/u;

const WORD_SEPARATORS = new Set(["and", "ve", "ile", "و"]);
const LINKING_WORDS = new Set(["of", "من"]);

/** Leading filler phrases as match keys, longest first. */
const FILLERS: string[][] = [
  "we're out of", "were out of", "we are out of", "we need", "don't forget", "dont forget",
  "pick up", "out of", "need", "get", "buy", "grab", "please", "some", "also",
  "almamız lazım", "unutma", "lütfen", "biraz",
  "لا تنسوا", "لا تنسو", "لا تنسى", "لا تنسي", "جيب", "جيبي", "جيبوا", "جيبو", "هات", "هاتي",
  "نبي", "نبغى", "ابي", "ابغى", "بدنا", "بدي", "عايزين", "عايز", "عاوزين", "عاوز",
  "محتاجين", "نحتاج", "اشتري", "اشتروا",
]
  .map((phrase) => matchKey(phrase, true).split(" "))
  // Stable sort, like Kotlin sortedByDescending.
  .map((words, index) => ({ words, index }))
  .sort((a, b) => b.words.length - a.words.length || a.index - b.index)
  .map(({ words }) => words);

/** Kotlin `String.trim()` (Unicode whitespace). */
function trim(text: string): string {
  return text.replace(/^[\s\u001C-\u001F]+|[\s\u001C-\u001F]+$/gu, "");
}

export class LocalSplitter {
  constructor(private readonly catalog: Catalog = Catalog.bundled) {}

  /**
   * @param aliases household aliases (CONTRACT §3.16) as normalized text → canonicalId; they win
   *   over dialect rules and the catalog.
   */
  split(text: string, localeHints: readonly string[] = [], aliases: ReadonlyMap<string, string> = new Map()): SplitItem[] {
    const turkish = isTurkish(localeHints);
    return this.chunks(text)
      .flatMap((chunk) => this.segments(this.tokens(chunk, turkish)))
      .flatMap((segment) => this.coverSplit(segment))
      .map((segment) => this.toItem(segment, localeHints, turkish, aliases))
      .filter((item): item is SplitItem => item !== null);
  }

  // 1. Hard separators.
  private chunks(text: string): string[] {
    return invisible(text)
      .split(HARD_SEPARATORS)
      .filter((chunk) => trim(chunk).length > 0);
  }

  // 2. Tokens, with glued forms split: "1kg" → "1" "kg", "x2" → "×" "2".
  private tokens(chunk: string, turkish: boolean): Token[] {
    return trim(chunk)
      .split(WHITESPACE)
      .flatMap((raw): Token[] => {
        const glued = GLUED_QTY_UNIT.exec(raw);
        if (glued) {
          const [, number = "", word = ""] = glued;
          const unitKey = matchKey(word, turkish);
          if (lookupUnit(unitKey) !== null) {
            return [
              { raw: number, key: matchKey(number, turkish) },
              { raw: word, key: unitKey },
            ];
          }
        }
        const times = MULTIPLIER.exec(raw);
        if (times) {
          const n = times[1] ?? times[2] ?? "";
          return [
            { raw, key: TIMES },
            { raw: n, key: matchKey(n, turkish) },
          ];
        }
        return [{ raw, key: matchKey(raw, turkish) }];
      });
  }

  // 3. Word separators: and / ve / ile / و, including و glued to the next word.
  private segments(tokens: Token[]): Token[][] {
    const out: Token[][] = [];
    let current: Token[] = [];
    const cut = () => {
      if (current.length > 0) out.push(current);
      current = [];
    };
    for (const token of tokens) {
      if (WORD_SEPARATORS.has(token.key)) {
        cut();
      } else if (this.isWawPrefixed(token)) {
        cut();
        const rest = token.raw.slice(1);
        current.push({ raw: rest, key: matchKey(rest) });
      } else {
        current.push(token);
      }
    }
    cut();
    return out;
  }

  private isWawPrefixed(token: Token): boolean {
    const key = token.key;
    if (key.length < 2 || !key.startsWith("و")) return false;
    if (this.catalog.isKnownWord(key) || lookupUnit(key) !== null || numberWord(key, false) !== null) return false;
    const rest = key.slice(1);
    return (
      this.catalog.isKnownWord(rest) || lookupUnit(rest) !== null || numberWord(rest, false) !== null || DIGIT.test(rest)
    );
  }

  /** Space-only lists ("domates biber patlıcan"): every word covered by synonyms, no quantity. */
  private coverSplit(segment: Token[]): Token[][] {
    if (segment.length < 2) return [segment];
    if (segment.some((t) => numberWord(t.key, false) !== null || lookupUnit(t.key) !== null)) return [segment];
    const parts: Token[][] = [];
    let i = 0;
    while (i < segment.length) {
      let found = 0;
      for (let len = Math.min(this.catalog.longestSynonymWords, segment.length - i); len >= 1; len--) {
        const phrase = segment
          .slice(i, i + len)
          .map((t) => t.key)
          .join(" ");
        if (this.catalog.synonym(phrase) !== null) {
          found = len;
          break;
        }
      }
      if (found === 0) return [segment];
      parts.push(segment.slice(i, i + found));
      i += found;
    }
    return parts.length > 1 ? parts : [segment];
  }

  // 4. Filler, quantity and unit, name.
  private toItem(
    segment: Token[],
    localeHints: readonly string[],
    turkish: boolean,
    aliases: ReadonlyMap<string, string>,
  ): SplitItem | null {
    const tokens = this.stripFiller(segment);
    if (tokens.length === 0) return null;

    const lead = this.parseQuantity(tokens, turkish);
    let nameTokens = tokens.slice(lead.consumed);
    let { qty, unit: unitWord, dozen: scale12 } = lead;

    if (lead.consumed === 0) {
      const trail = this.parseTrailingQuantity(nameTokens, turkish);
      if (trail !== null) {
        nameTokens = nameTokens.slice(0, nameTokens.length - trail.consumed);
        qty = trail.qty;
        unitWord = trail.unit;
        scale12 = trail.dozen;
      }
    }
    if (lead.consumed > 0 && nameTokens[0] !== undefined && LINKING_WORDS.has(nameTokens[0].key)) {
      nameTokens = nameTokens.slice(1);
    }
    if (nameTokens.length === 0) return null;

    const name = nameTokens.map((t) => t.raw).join(" ");
    const nameKeys = nameTokens.map((t) => t.key);
    const canonicalId = this.catalog.guessCanonical(nameKeys, localeHints, aliases);
    const unit: Unit | null =
      unitWord !== null ? resolveUnit(unitWord, canonicalId, this.catalog) : scale12 ? "piece" : null;
    const ambiguity = canonicalId === null ? this.catalog.ambiguity(nameKeys, localeHints, aliases) : null;
    return { name, qty, unit, canonicalId, ambiguity };
  }

  private stripFiller(tokens: Token[]): Token[] {
    let rest = tokens;
    for (;;) {
      const phrase = FILLERS.find(
        (filler) => filler.length <= rest.length && filler.every((word, i) => rest[i]?.key === word),
      );
      if (!phrase) return rest;
      rest = rest.slice(phrase.length);
    }
  }

  /** Leading quantity: numbers, number words, fractions, "dozen", then an optional unit and "+½". */
  private parseQuantity(tokens: Token[], turkish: boolean): Quantity {
    let qty: number | null = null;
    let unit: UnitWord | null = null;
    let dozen = false;
    let i = 0;
    while (i < tokens.length) {
      const key = tokens[i]?.key ?? "";
      const next = tokens[i + 1]?.key ?? null;
      const word = numberWord(key, turkish);
      const nextWord = next === null ? null : numberWord(next, true);
      if (turkish && key === "on" && nextWord?.kind === "value") {
        // Turkish "on bir" (11), "on iki" (12)…
        qty = 10 + nextWord.value;
        i += 2;
        continue;
      }
      if (word?.kind === "value" && unit === null) {
        qty = qty === null ? word.value : qty * word.value;
      } else if (word?.kind === "fraction" && unit === null) {
        qty = (qty ?? 1) * word.value;
      } else if (word?.kind === "plus") {
        qty = (qty ?? 1) + word.value;
      } else if (word?.kind === "dozen") {
        qty = (qty ?? 1) * 12;
        dozen = true;
      } else if (
        word?.kind === "article" &&
        next !== null &&
        (numberWord(next, turkish)?.kind === "dozen" || this.isUnit(next, true))
      ) {
        // "a dozen", "a kilo": the article itself counts for nothing.
      } else if (unit === null && !dozen && this.isUnit(key, qty !== null)) {
        unit = lookupUnit(key);
        if (qty === null) qty = 1;
      } else {
        break;
      }
      i++;
    }
    return { qty, unit, dozen, consumed: i };
  }

  /** Trailing quantity: "yumurta 30 tane", "sugar 1kg", "Pampers x2". */
  private parseTrailingQuantity(tokens: Token[], turkish: boolean): Quantity | null {
    if (tokens.length < 2) return null;
    const last = tokens[tokens.length - 1]?.key ?? "";
    const beforeLast = tokens[tokens.length - 2]?.key ?? "";
    const n = parseNumeral(last);
    if (n !== null) {
      // "Pampers size 4": a trailing bare number is part of the name.
      return beforeLast === TIMES ? { qty: n, unit: null, dozen: false, consumed: 2 } : null;
    }
    const unit = lookupUnit(last);
    if (unit === null) return null;
    const word = numberWord(beforeLast, turkish);
    if (word?.kind !== "value") return null;
    return { qty: word.value, unit, dozen: false, consumed: 2 };
  }

  private isUnit(key: string, afterNumber: boolean): boolean {
    const unit = lookupUnit(key);
    return unit !== null && (afterNumber || unit.needsNumber !== true);
  }
}
