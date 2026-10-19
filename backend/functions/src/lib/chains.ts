/**
 * Chain mapping (ARCHITECTURE §5.1): OSM `brand` / `name` → `chainId`. Text is lowercased with
 * Turkish rules ("BİM" → "bim", "ŞOK" → "şok"); the most specific chain is listed first
 * (CarrefourSA before Carrefour, Macrocenter before Migros). Unmatched → `other`.
 */
export type Country = "TR" | "SA" | "AE";

interface Chain {
  id: string;
  countries: readonly Country[];
  patterns: readonly RegExp[];
}

/**
 * A whole word or phrase. JavaScript's `\b` only knows ASCII letters, so "şok" or "بنده" next to
 * another letter would slip through; this checks Unicode letters and digits instead.
 */
function word(phrase: string): RegExp {
  return new RegExp(`(?<![\\p{L}\\p{N}])${phrase}(?![\\p{L}\\p{N}])`, "u");
}

const CHAINS: readonly Chain[] = [
  // Turkey
  { id: "carrefoursa", countries: ["TR"], patterns: [word("carrefour ?sa")] },
  { id: "macrocenter", countries: ["TR"], patterns: [word("macro ?center")] },
  { id: "migros", countries: ["TR"], patterns: [/migros/u] },
  { id: "a101", countries: ["TR"], patterns: [word("a ?101")] },
  { id: "bim", countries: ["TR"], patterns: [word("bim")] },
  { id: "sok", countries: ["TR"], patterns: [word("şok"), word("sok market")] },
  { id: "file", countries: ["TR"], patterns: [word("file")] },
  { id: "metro", countries: ["TR"], patterns: [word("metro")] },
  { id: "onur", countries: ["TR"], patterns: [word("onur market"), word("onur")] },
  { id: "happy_center", countries: ["TR"], patterns: [word("happy ?center")] },
  // Saudi Arabia (some across the Gulf)
  { id: "panda", countries: ["SA", "AE"], patterns: [word("panda"), word("بنده"), word("باندا")] },
  { id: "danube", countries: ["SA"], patterns: [word("danube"), word("الدانوب"), word("دانوب")] },
  { id: "tamimi", countries: ["SA"], patterns: [/tamimi/u, /التميمي/u] },
  { id: "othaim", countries: ["SA"], patterns: [/othaim/u, /العثيم/u] },
  // UAE (and the Gulf)
  { id: "lulu", countries: ["SA", "AE"], patterns: [word("lulu"), word("لولو")] },
  { id: "carrefour", countries: ["SA", "AE"], patterns: [/carrefour/u, /كارفور/u] },
  { id: "nesto", countries: ["SA", "AE"], patterns: [word("nesto"), word("نستو")] },
  { id: "spinneys", countries: ["AE"], patterns: [/spinneys/u, /سبينس/u, /سبينيس/u] },
  { id: "union_coop", countries: ["AE"], patterns: [/union ?co-?op/u, /تعاونية الاتحاد/u] },
  { id: "waitrose", countries: ["AE"], patterns: [/waitrose/u, /ويتروز/u] },
  { id: "choithrams", countries: ["AE"], patterns: [/choithram/u] },
  { id: "viva", countries: ["AE"], patterns: [word("viva")] },
  { id: "almaya", countries: ["AE"], patterns: [word("al ?maya"), word("الماية")] },
];

/** Every chain ID the backend and apps know (plus `other`). */
export const CHAIN_IDS: readonly string[] = [...CHAINS.map((c) => c.id), "other"];

/** `brand` wins over `name`; the chain must operate in `country`. */
export function chainFor(texts: readonly (string | undefined)[], country: Country): string {
  for (const text of texts) {
    if (!text) continue;
    // Both casings: Turkish rules for "BİM"/"ŞOK", plain rules for "MIGROS"/"VIVA" (Turkish
    // lowering would make those "mıgros"/"vıva").
    const lowered = [text.toLocaleLowerCase("tr"), text.toLowerCase()].map((t) => t.replace(/\s+/gu, " "));
    const chain = CHAINS.find(
      (c) => c.countries.includes(country) && c.patterns.some((p) => lowered.some((t) => p.test(t))),
    );
    if (chain) return chain.id;
  }
  return "other";
}
