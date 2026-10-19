import seed from "../generated/catalog_seed.json" with { type: "json" };
import { matchKey } from "./normalize.js";

/** fixtures/catalog_seed.json shapes (CONTRACT §3.14 plus dialect rules). */
export interface CatalogItem {
  id: string;
  category: string;
  defaultUnit?: string;
  names: Record<string, string>;
  synonyms: Record<string, string[]>;
}

export interface DialectRule {
  word: string;
  candidates: string[];
  hintsByLocale: Record<string, string>;
  note?: string;
}

export interface CatalogSeed {
  dialectRules: DialectRule[];
  items: CatalogItem[];
}

export interface Ambiguity {
  wordKey: string;
  candidates: string[];
}

/** Drops a leading Arabic definite article: "الخبز" → "خبز". */
export function stripArticle(key: string): string {
  return key.length > 3 && key.startsWith("ال") ? key.slice(2) : key;
}

/** The candidate for the first locale hint with an entry, or null when still ambiguous. */
export function resolveDialect(rule: DialectRule, localeHints: readonly string[]): string | null {
  for (const hint of localeHints) {
    const entry = Object.entries(rule.hintsByLocale).find(([locale]) => locale.toLowerCase() === hint.toLowerCase());
    if (entry) return entry[1];
  }
  return null;
}

/** Port of Android Catalog.kt: synonym lookup, longest-match guessing and dialect ambiguity. */
export class Catalog {
  readonly items: CatalogItem[];
  readonly dialectRules: DialectRule[];
  readonly longestSynonymWords: number;
  private readonly byId: Map<string, CatalogItem>;
  private readonly synonyms = new Map<string, string>();
  private readonly knownWords = new Set<string>();
  private readonly dialectByWord = new Map<string, DialectRule>();
  /** synonym key → the language whose synonym list holds it (first wins). */
  private readonly synonymLanguages = new Map<string, string>();

  constructor(data: CatalogSeed) {
    this.items = data.items;
    this.dialectRules = data.dialectRules;
    this.byId = new Map(data.items.map((item) => [item.id, item]));
    for (const rule of data.dialectRules) this.dialectByWord.set(matchKey(rule.word), rule);
    for (const item of data.items) {
      const all = [...Object.values(item.synonyms).flat(), ...Object.values(item.names)];
      for (const synonym of all) {
        const key = matchKey(synonym);
        if (!this.synonyms.has(key)) this.synonyms.set(key, item.id);
        for (const w of key.split(" ")) this.knownWords.add(w);
      }
    }
    for (const item of data.items) {
      for (const [lang, list] of Object.entries(item.synonyms)) {
        for (const synonym of list) {
          const key = matchKey(synonym);
          if (!this.synonymLanguages.has(key)) this.synonymLanguages.set(key, lang);
        }
      }
    }
    for (const word of this.dialectByWord.keys()) this.knownWords.add(word);
    this.longestSynonymWords = Math.max(...[...this.synonyms.keys()].map((k) => k.split(" ").length));
  }

  item(id: string | null | undefined): CatalogItem | undefined {
    return id ? this.byId.get(id) : undefined;
  }

  isCanned(canonicalId: string | null): boolean {
    return this.item(canonicalId)?.defaultUnit === "can";
  }

  isKnownWord(key: string): boolean {
    return this.knownWords.has(key) || this.knownWords.has(stripArticle(key));
  }

  synonym(phraseKey: string): string | null {
    return this.synonyms.get(phraseKey) ?? this.synonyms.get(phraseKey.split(" ").map(stripArticle).join(" ")) ?? null;
  }

  dialectRule(wordKey: string): DialectRule | null {
    return this.dialectByWord.get(wordKey) ?? this.dialectByWord.get(stripArticle(wordKey)) ?? null;
  }

  /**
   * Household alias (whole name, then any word) → longest synonym (skipping dialect words) →
   * dialect word resolved by locale hint. Null when nothing matches or a dialect word stays ambiguous.
   */
  guessCanonical(
    nameKeys: readonly string[],
    localeHints: readonly string[],
    aliases: ReadonlyMap<string, string> = new Map(),
  ): string | null {
    const whole = aliases.get(nameKeys.join(" "));
    if (whole !== undefined) return whole;
    for (const key of nameKeys) {
      const byWord = aliases.get(key) ?? aliases.get(stripArticle(key));
      if (byWord !== undefined) return byWord;
    }
    for (let len = Math.min(this.longestSynonymWords, nameKeys.length); len >= 1; len--) {
      for (let start = 0; start <= nameKeys.length - len; start++) {
        const phrase = nameKeys.slice(start, start + len).join(" ");
        if (this.dialectRule(phrase) !== null) continue;
        const id = this.synonym(phrase);
        if (id !== null) return id;
      }
    }
    for (const key of nameKeys) {
      const rule = this.dialectRule(key);
      if (rule) return resolveDialect(rule, localeHints);
    }
    return null;
  }

  ambiguity(
    nameKeys: readonly string[],
    localeHints: readonly string[],
    aliases: ReadonlyMap<string, string> = new Map(),
  ): Ambiguity | null {
    if (this.guessCanonical(nameKeys, localeHints, aliases) !== null) return null;
    const key = nameKeys.find((k) => this.dialectRule(k) !== null);
    if (key === undefined) return null;
    const rule = this.dialectRule(key);
    return rule ? { wordKey: stripArticle(key), candidates: rule.candidates } : null;
  }

  /** The language of a catalog synonym ("domates" → "tr"), when the phrase is one. */
  synonymLanguage(phraseKey: string): string | null {
    return this.synonymLanguages.get(phraseKey) ?? null;
  }

  displayName(canonicalId: string, lang: string): string | null {
    const names = this.item(canonicalId)?.names;
    return names?.[lang] ?? names?.en ?? null;
  }

  static readonly bundled: Catalog = new Catalog(seed as CatalogSeed);
}
