/* eslint-disable no-control-regex -- VT and FS..US reproduce Kotlin's \s and Char.isWhitespace (CLAUDE.md rule 6). */
import { createHash } from "node:crypto";

/**
 * Text normalization (ARCHITECTURE §4.1), a line-by-line port of the Android reference
 * (core:i18n Normalize.kt). All implementations must pass fixtures/split_cases.json.
 *
 * Java/Kotlin regex semantics are kept on purpose: `\s` is ASCII whitespace only.
 */

/** Kotlin `\s`: [ \t\n\x0B\f\r]. */
const WHITESPACE = /[ \t\n\x0B\f\r]+/;
const TASHKEEL = /[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]/g;
const TATWEEL = /\u0640/g;
const INVISIBLE = /[\u00AD\u200B\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF]/g;
const UNICODE_SPACE = /[\u00A0\u2007\u202F]/g;
const FORMAT = /\p{Cf}/gu;
const DECIMAL_COMMA = /(\d),(\d)/g;

/** Drops bidi marks, zero-width spaces, BOM and soft hyphens; no-break spaces become spaces. */
export function invisible(text: string): string {
  return text.replace(INVISIBLE, "").replace(UNICODE_SPACE, " ");
}

/** Eastern Arabic (٠-٩) and Persian/Urdu (۰-۹) digits → ASCII; Arabic decimal separator ٫ → '.'. */
export function digitsToAscii(text: string): string {
  let out = "";
  for (const c of text) {
    const code = c.charCodeAt(0);
    if (code >= 0x0660 && code <= 0x0669) out += String.fromCharCode(48 + code - 0x0660);
    else if (code >= 0x06f0 && code <= 0x06f9) out += String.fromCharCode(48 + code - 0x06f0);
    else if (c === "٫") out += ".";
    else out += c;
  }
  return out;
}

/** Strips tashkeel and tatweel; unifies أ إ آ ٱ → ا and ى → ي. */
export function arabic(text: string): string {
  return text
    .replace(TASHKEEL, "")
    .replace(TATWEEL, "")
    .replace(/[أإآٱ]/g, "ا")
    .replace(/ى/g, "ي");
}

/** Turkish rules (İ → i, I → ı) when `turkish`, otherwise locale-neutral (Locale.ROOT). */
export function lowercase(text: string, turkish: boolean): string {
  return turkish ? text.toLocaleLowerCase("tr") : text.toLowerCase();
}

export function decimalComma(text: string): string {
  return text.replace(DECIMAL_COMMA, "$1.$2");
}

/** Kotlin String.trim(): strips chars that are Char.isWhitespace (Unicode spaces included). */
function kotlinTrim(text: string): string {
  return text.replace(/^[\s\u001C-\u001F]+|[\s\u001C-\u001F]+$/gu, "");
}

/**
 * Comparison key: invisible and format characters removed, digits to ASCII, Arabic folding,
 * lowercase, decimal comma, collapsed whitespace, final ة treated as ه.
 */
export function matchKey(text: string, turkish = false): string {
  const base = decimalComma(lowercase(arabic(digitsToAscii(invisible(text).replace(FORMAT, ""))), turkish));
  return kotlinTrim(base)
    .split(WHITESPACE)
    .map((word) => (word.endsWith("ة") ? `${word.slice(0, -1)}ه` : word))
    .join(" ");
}

/** True when the locale hints ask for Turkish casing rules. */
export function isTurkish(localeHints: readonly string[]): boolean {
  return localeHints.some((hint) => (hint.toLowerCase().split("-")[0] ?? "") === "tr");
}

/** CONTRACT §3.16: aliasId = first 32 hex characters of the SHA-256 of the normalized text. */
export function aliasId(normalizedText: string): string {
  return createHash("sha256").update(normalizedText, "utf8").digest("hex").slice(0, 32);
}
