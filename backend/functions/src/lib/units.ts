import type { Catalog } from "./catalog.js";
import { matchKey } from "./normalize.js";

/** CONTRACT §2 `Unit`. */
export const UNITS = ["piece", "kg", "g", "l", "ml", "pack", "can", "bottle", "bag", "box", "bunch", "tray"] as const;
export type Unit = (typeof UNITS)[number];

export interface UnitWord {
  unit: Unit;
  /** Canned food turns "pack"/"box" into `can`; eggs turn "box" into `tray`. */
  container?: "canIfCanned" | "trayIfEggs";
  /** Short forms ("g", "l", "lt") only count right after a number. */
  needsNumber?: boolean;
}

const TABLE = new Map<string, UnitWord>();
function add(word: UnitWord, ...forms: string[]): void {
  for (const form of forms) TABLE.set(matchKey(form), word);
}

// Arabic (port of Android Units.kt, same order: later forms overwrite earlier ones).
add({ unit: "kg" }, "كيلو", "كيلوغرام", "كيلوجرام", "كغ", "كجم");
add({ unit: "g" }, "غرام", "جرام", "غم", "جم");
add({ unit: "l" }, "لتر", "ليتر", "لترات");
add({ unit: "ml" }, "مل");
add({ unit: "pack", container: "canIfCanned" }, "علبة", "علب", "علبه");
add({ unit: "pack" }, "باكيت", "باكو", "باكيتات");
add({ unit: "bag" }, "كيس", "أكياس");
add({ unit: "bottle" }, "قنينة", "قزازة", "إزازة", "زجاجة", "قناني");
add({ unit: "bunch" }, "ربطة", "ربطات", "حزمة");
add({ unit: "box", container: "trayIfEggs" }, "كرتونة", "كرتون", "كراتين");
add({ unit: "piece" }, "حبة", "حبات", "حبه");
// Turkish
add({ unit: "kg" }, "kilo", "kg", "kilogram");
add({ unit: "g" }, "gram", "gr");
add({ unit: "l" }, "litre", "litrelik");
add({ unit: "pack" }, "paket");
add({ unit: "box", container: "canIfCanned" }, "kutu");
add({ unit: "box" }, "koli");
add({ unit: "bottle" }, "şişe");
add({ unit: "bunch" }, "demet");
add({ unit: "piece" }, "tane", "adet");
add({ unit: "bag" }, "poşet");
// English
add({ unit: "kg" }, "kilos", "kilograms");
add({ unit: "g" }, "grams");
add({ unit: "l" }, "liter", "liters", "litres");
add({ unit: "pack" }, "pack", "packs", "packet", "packets");
add({ unit: "can" }, "can", "cans", "tin", "tins");
add({ unit: "bottle" }, "bottle", "bottles");
add({ unit: "bag" }, "bag", "bags");
add({ unit: "box", container: "trayIfEggs" }, "box", "boxes");
add({ unit: "bunch" }, "bunch", "bunches");
add({ unit: "tray" }, "tray", "trays");
add({ unit: "piece" }, "piece", "pieces", "pcs", "pc");
// Short forms that are only units after a number.
add({ unit: "g", needsNumber: true }, "g");
add({ unit: "l", needsNumber: true }, "l", "lt", "ltr");
add({ unit: "ml", needsNumber: true }, "ml");

export function lookupUnit(key: string): UnitWord | null {
  return TABLE.get(key) ?? null;
}

export const EGGS_ID = "eggs";

/** Resolves a container word against the item it describes. */
export function resolveUnit(word: UnitWord, canonicalId: string | null, catalog: Catalog): Unit {
  switch (word.container) {
    case "canIfCanned":
      return catalog.isCanned(canonicalId) ? "can" : word.unit;
    case "trayIfEggs":
      return canonicalId === EGGS_ID ? "tray" : word.unit;
    default:
      return word.unit;
  }
}
