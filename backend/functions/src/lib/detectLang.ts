import { ITEM_LANGUAGES, type ItemLanguage } from "../config.js";
import type { Catalog } from "./catalog.js";
import { matchKey } from "./normalize.js";

const URDU_LETTERS = /[ٹڈڑںےۓ]/u;
const ARABIC_SCRIPT = /[\u0600-\u06FF]/u;
const DEVANAGARI = /[\u0900-\u097F]/u;
const TURKISH_LETTERS = /[ğĞışŞİ]/u;
const LATIN = /\p{Script=Latin}/u;
const LATIN_LANGUAGES = new Set<string>(["tr", "en", "fil"]);

function isItemLanguage(value: string): value is ItemLanguage {
  return (ITEM_LANGUAGES as readonly string[]).includes(value);
}

/**
 * Detected language of an item name (`items.lang`, `parseItems.detectedLang`): script first,
 * then Turkish letters, then the catalog synonym's language, then the first locale hint.
 */
export function detectLanguage(name: string, localeHints: readonly string[], catalog: Catalog): ItemLanguage {
  if (ARABIC_SCRIPT.test(name)) return URDU_LETTERS.test(name) ? "ur" : "ar";
  if (DEVANAGARI.test(name)) return "hi";
  if (TURKISH_LETTERS.test(name)) return "tr";
  const turkishKey = matchKey(name, true);
  const fromCatalog = catalog.synonymLanguage(matchKey(name)) ?? catalog.synonymLanguage(turkishKey);
  if (fromCatalog !== null && isItemLanguage(fromCatalog)) return fromCatalog;
  // Only hints written in this script: "ibuprofen" from an Arabic speaker is not Arabic.
  const latin = LATIN.test(name);
  for (const hint of localeHints) {
    const primary = hint.toLowerCase().split("-")[0] ?? "";
    if (isItemLanguage(primary) && (!latin || LATIN_LANGUAGES.has(primary))) return primary;
  }
  return "en";
}
