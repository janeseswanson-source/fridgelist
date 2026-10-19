import { matchKey } from "./normalize.js";

/** Number words and fractions in Arabic, Turkish and English (port of Android Numbers.kt). */
export type NumberWord =
  | { kind: "value"; value: number }
  | { kind: "fraction"; value: number }
  | { kind: "plus"; value: number }
  | { kind: "dozen" }
  | { kind: "article" };

const WORDS = new Map<string, NumberWord>();
function values(value: number, ...forms: string[]): void {
  for (const form of forms) WORDS.set(matchKey(form), { kind: "value", value });
}
function word(w: NumberWord, ...forms: string[]): void {
  for (const form of forms) WORDS.set(matchKey(form), w);
}

// Arabic (Modern Standard and common dialect spellings).
values(1, "واحد", "واحدة", "وحدة");
values(2, "اثنين", "اثنان", "اتنين", "ثنين");
values(3, "ثلاث", "ثلاثة", "تلات", "تلاتة");
values(4, "أربع", "أربعة");
values(5, "خمس", "خمسة");
values(6, "ست", "ستة");
values(7, "سبع", "سبعة");
values(8, "ثمان", "ثماني", "ثمانية", "تمن", "تمانية");
values(9, "تسع", "تسعة");
values(10, "عشر", "عشرة");
values(11, "احدعش", "حدعش", "إحدى عشر");
values(12, "اثنعش", "اطنعش", "اثنا عشر");
// Turkish ("on" is handled separately: it is also an English word).
values(1, "bir");
values(2, "iki");
values(3, "üç");
values(4, "dört");
values(5, "beş");
values(6, "altı");
values(7, "yedi");
values(8, "sekiz");
values(9, "dokuz");
// English.
values(1, "one");
values(2, "two");
values(3, "three");
values(4, "four");
values(5, "five");
values(6, "six");
values(7, "seven");
values(8, "eight");
values(9, "nine");
values(10, "ten");
values(11, "eleven");
values(12, "twelve");

word({ kind: "fraction", value: 0.5 }, "نص", "نصف", "yarım", "half");
word({ kind: "fraction", value: 0.25 }, "ربع", "çeyrek", "quarter");
word({ kind: "plus", value: 0.5 }, "ونص", "ونصف", "buçuk");
word({ kind: "plus", value: 0.25 }, "وربع");
word({ kind: "dozen" }, "dozen", "دزينة", "درزن", "düzine");
word({ kind: "article" }, "a", "an");

const NUMERAL = /^\d+(\.\d+)?$/;

/** ASCII numerals after matchKey ("2", "1.5"). */
export function parseNumeral(key: string): number | null {
  return NUMERAL.test(key) ? Number(key) : null;
}

/** Looks up one token key. Turkish "on" (10) only counts when `turkish`. */
export function numberWord(key: string, turkish: boolean): NumberWord | null {
  const known = WORDS.get(key);
  if (known) return known;
  if (turkish && key === "on") return { kind: "value", value: 10 };
  const n = parseNumeral(key);
  return n === null ? null : { kind: "value", value: n };
}
