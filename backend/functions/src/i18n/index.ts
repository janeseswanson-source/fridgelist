import type { UiLanguage } from "../config.js";
import ar from "./ar.json" with { type: "json" };
import en from "./en.json" with { type: "json" };
import tr from "./tr.json" with { type: "json" };

/** Server-side text (default list names now; push templates in B6). Keys must exist in all three. */
const TEXT = { ar, tr, en } satisfies Record<UiLanguage, typeof en>;

export type TextKey = keyof typeof en;

export function text(language: UiLanguage, key: TextKey): string {
  return TEXT[language][key];
}
