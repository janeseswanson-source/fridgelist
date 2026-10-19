/** CONTRACT §2 `Category`, in the default display order. */
export const CATEGORIES = [
  "produce",
  "bakery",
  "dairy_eggs",
  "meat_poultry",
  "seafood",
  "breakfast",
  "pantry",
  "spices_condiments",
  "snacks_sweets",
  "beverages",
  "frozen",
  "baby",
  "household_cleaning",
  "personal_care",
  "pharmacy",
  "pets",
  "other",
] as const;
export type Category = (typeof CATEGORIES)[number];

export function isCategory(value: unknown): value is Category {
  return typeof value === "string" && (CATEGORIES as readonly string[]).includes(value);
}
