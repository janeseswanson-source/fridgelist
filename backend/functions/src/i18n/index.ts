import { UI_LANGUAGES, type UiLanguage } from "../config.js";
import ar from "./ar.json" with { type: "json" };
import en from "./en.json" with { type: "json" };
import tr from "./tr.json" with { type: "json" };

/**
 * Server-side text: default list names and push templates (CONTRACT §5.1, prompt B6). Every key
 * exists in all three languages (test/push.test.ts checks it). Plural keys hold CLDR categories
 * (Arabic uses all six); `{placeholders}` are filled by `format`, numbers in the reader's locale.
 */
type Plural = Partial<Record<Intl.LDMLPluralRule, string>> & { other: string };
type Template = string | Plural;

const TEXT: Record<UiLanguage, Record<string, Template>> = { ar, tr, en };

export type TextKey = keyof typeof en;

export function uiLanguageOr(value: unknown, fallback: UiLanguage = "en"): UiLanguage {
  return (UI_LANGUAGES as readonly unknown[]).includes(value) ? (value as UiLanguage) : fallback;
}

function fill(template: string, language: UiLanguage, params: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    if (value === undefined) return match;
    return typeof value === "number" ? new Intl.NumberFormat(language).format(value) : value;
  });
}

/** A template in `language`; `count` picks the plural form for plural keys. */
export function format(
  language: UiLanguage,
  key: TextKey,
  params: Record<string, string | number> = {},
  count?: number,
): string {
  const template = TEXT[language][key] ?? TEXT.en[key];
  if (template === undefined) throw new Error(`missing text ${key}`);
  if (typeof template === "string") return fill(template, language, params);
  const category = new Intl.PluralRules(language).select(count ?? 0);
  return fill(template[category] ?? template.other, language, params);
}

/** Plain (non-plural) text, e.g. the default list name. */
export function text(language: UiLanguage, key: TextKey): string {
  return format(language, key);
}

/** For tests: every key of every language. */
export const ALL_TEXT = TEXT;
