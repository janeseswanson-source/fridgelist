/**
 * Project constants from CLAUDE.md. Values marked TODO are filled in by the owner before the
 * first dev deploy; nothing here is secret (secrets use `defineSecret`).
 */

/**
 * CLAUDE.md FUNCTIONS_REGION: "me-central2 if supported, else me-central1, else europe-west1".
 * Verified at the first `deploy dev`; the apps use the same value (Android FUNCTIONS_REGION).
 */
export const REGION = "me-central2";

/** CONTRACT_VERSION this code implements (docs/CONTRACT.md). */
export const CONTRACT_VERSION = 4;

export const LINK_DOMAIN = "links.example.com";
export const URL_SCHEME = "fridgelist";

/** Item languages the apps offer (CONTRACT §3.1 `language`). */
export const ITEM_LANGUAGES = ["ar", "tr", "en", "fil", "hi", "ur"] as const;
export type ItemLanguage = (typeof ITEM_LANGUAGES)[number];

/** UI languages (CONTRACT §3.1 `uiLanguage`, §3.3 `defaultLanguage`). */
export const UI_LANGUAGES = ["ar", "tr", "en"] as const;
export type UiLanguage = (typeof UI_LANGUAGES)[number];
