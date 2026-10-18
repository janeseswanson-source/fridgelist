# Prompt 01 — Backend (run this first)

Open Claude Code in the repo root and paste everything below the line. Milestones B1–B4 unblock both apps.

---

You are the lead backend engineer for FridgeList (working name), a shared household grocery list for Turkey, Saudi Arabia and the UAE. Two native apps — Android in `android/` and iOS in `ios/` — will be built in parallel by other sessions against what you build here, so the data contract must be exact.

## Read first

1. `CLAUDE.md` — rules and project constants.
2. `docs/PRODUCT_SPEC.md` — what we are building and the acceptance criteria.
3. `docs/ARCHITECTURE.md` — §1–6 and §9–12 are yours.
4. `docs/CONTRACT.md` — the data contract. Treat it as law. If something is missing or wrong, propose the change, update CONTRACT.md in the same commit, bump `CONTRACT_VERSION`, and log it in `docs/PROGRESS.md`.
5. `fixtures/parse_cases.json` and `fixtures/catalog_seed.json`.

Then enter plan mode, write a plan for **B1 only**, and wait for my approval. After each milestone: run lint, typecheck and tests and show me the output, update `docs/PROGRESS.md`, commit, and stop for review before the next milestone.

## Stack (ask before substituting anything)

- Firebase: Firestore (Native mode, location from `FIRESTORE_LOCATION`), Auth, Cloud Functions 2nd gen, Cloud Messaging, Hosting, App Check, Remote Config, Cloud Storage.
- Functions: TypeScript (strict), Node 22, `firebase-functions` v2 APIs, `firebase-admin`, `zod`, `vitest`, ESLint + Prettier. Region from `FUNCTIONS_REGION` — verify that 2nd-gen functions deploy there and tell me if you need the fallback.
- Geo: `geofire-common`.
- LLM: `@anthropic-ai/sdk` behind an `LlmProvider` interface. Models come from params `LLM_MODEL_FAST` (default `claude-haiku-5-5`) and `LLM_MODEL_SMART` (default `claude-sonnet-5-5`). Ask for JSON that matches a schema (structured outputs or a forced tool call), validate with zod, retry once, then fall back as ARCHITECTURE §3.5 says.
- Speech-to-text: a `SpeechToText` interface with a Google Cloud Speech-to-Text v2 implementation.
- Secrets with `defineSecret`: `ANTHROPIC_API_KEY` now; `WHATSAPP_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN` in B10.
- Local development runs entirely on the Firebase Emulator Suite. Tests must pass offline with the LLM replayed from recordings.

## Layout you own

```
backend/
  package.json            scripts: emulators, seed, deploy:dev, import:stores
  firebase.json, .firebaserc (aliases dev, prod)
  firestore.rules, firestore.indexes.json, storage.rules
  functions/              src/ as ARCHITECTURE §3.4; test/; test/recordings/
  hosting/                public/ (guest page, join page), build.ts (.well-known files from CLAUDE.md)
  scripts/                emulator-seed.ts, seed-catalog.ts, import-stores.ts
docs/RUNBOOK.md           you create and maintain it
```

## Milestones

### B1 — Scaffold
- Firebase config with `dev`/`prod` aliases (placeholder IDs from CLAUDE.md until I give real ones).
- `functions/` TypeScript project with lint, format, typecheck and vitest; emulator config for Auth, Firestore, Functions, Hosting, Storage, Pub/Sub (scheduler).
- `npm run emulators` starts everything and runs `scripts/emulator-seed.ts`: one household with an owner (item language `ar`), an adult (`tr`) and a helper (`fil`); two lists; 15 items in mixed languages; about 30 stores around Kadıköy (Istanbul), Olaya (Riyadh) and Dubai Marina.
- Done when: lint, typecheck and tests pass; emulators start with seeded data; RUNBOOK.md explains how.

### B2 — Security rules
- Implement `firestore.rules` from CONTRACT §3 (the Writer column and the status-transition table) and ARCHITECTURE §3.3.
- Tests with `@firebase/rules-unit-testing` for every collection: members vs non-members, every role (kids can only create `pending`), every allowed and forbidden status transition, field whitelists, server-only fields, functions-only collections, aliases.
- Done when: every row of the CONTRACT §3 Writer column and §3.6 transition table has at least one passing test.

### B3 — Households, invites, devices
- Callables `createHousehold`, `createInvite`, `joinHousehold` per CONTRACT §4. The default list is named in the household's `defaultLanguage` ("البقالة", "Market listesi", "Groceries").
- Trigger `onMemberWritten` keeps `households/{hid}.memberLanguages` and `users/{uid}.householdIds` up to date.
- Done when: tests cover invite expiry, max uses, joining twice, role assignment and language bookkeeping.

### B4 — Item pipeline
- `scripts/seed-catalog.ts` loads `fixtures/catalog_seed.json` into `catalog/` (idempotent).
- `lib/normalize.ts`, `lib/units.ts`, `lib/localSplit.ts` exactly as ARCHITECTURE §4.1 (Arabic normalization, Turkish casefolding, Eastern digits, decimal commas, fractions and number words in three languages, units, separators, filler words, dozens → pieces).
- `parseItems` callable: household aliases → dialect rules with locale hints → catalog synonyms → LLM for the rest. Returns `ParsedItem[]` with confidence. When a dialect word stays ambiguous, return the best candidate with confidence below 0.6 and the alternatives in `notes` so the apps can ask. Rate limit 30/min/user.
- `onItemCreated`: normalize → resolve as above (LLM classify/translate only when needed, cached in `catalogCandidates`) → set `lang`, `names` for every `memberLanguages` value, `canonicalId`, `category` (unless `categorySource` is `user`) → merge per ARCHITECTURE §4.2 → `item_added` or `item_merged` event. Leave a clearly marked hook for live fan-out (B6).
- `onItemUpdated`: `done` → purchase record + event; `missing` → push to `addedBy` (respecting `notify.itemMissing`; you may stub the sender until B6) + event.
- Golden test over `fixtures/parse_cases.json`: replay mode in CI; `RECORD=1` records real responses once. Print a per-case report. Target ≥ 90% passing; list every failure with the reason.
- Done when: ≥ 40 unit tests for normalize/units/split including every `split` case, merge tests (same unit, different unit, kid pending), translation-fill tests, and the golden report committed.

### B5 — Stores
- `scripts/import-stores.ts` per ARCHITECTURE §5.1: Overpass for `AE` and `SA`; Geofabrik PBF + `osmium` for `TR`; chain mapping table; dry-run mode with counts per country and chain; batched, idempotent writes. Ask me before the first real import.
- `nearbyStores` (geohash ranges, distance filter, sorted, limit) and `addStore`.
- Done when: emulator tests pass on seeded stores and a UAE dry run prints counts. Add the OpenStreetMap attribution text to `docs/`.

### B6 — Trips, notifications, live surfaces
- `startTrip`, `updateTrip`, `registerLiveActivityToken` per CONTRACT §4 (one active trip per shopper; starting a new one ends the old one).
- Push templates in `src/i18n/{ar,tr,en}.json`; pushes per CONTRACT §5.1 with the recipient's `uiLanguage`, notification preferences, iOS categories and Android channels.
- Live surfaces per CONTRACT §5.2 and §5.3: Live Activity start/update/end through FCM HTTP v1 with `apns.live_activity_token` (check the current Firebase docs; if `firebase-admin` lacks the field, call the REST endpoint with `google-auth-library`); Android `trip_update` data messages; `list_changed` data pushes capped at one per device per minute.
- Throttle live updates to one per trip per 5 s, always sending the newest state. `nextItems` uses the shopper's language and the default category order.
- `closeStaleTrips` every 30 minutes; ending a trip moves its `missing` items back to `open`.
- Done when: snapshot tests cover every push type in ar/tr/en, the throttle and the stale-trip job.

### B7 — Share links, guest page, link hosting
- `createShareLink`, `revokeShareLink`, `resolveShareLink` (public), `guestAddItem` (public; App Check; 20 adds/hour/token; ≤ 200 characters; URLs stripped).
- `hosting/build.ts` generates `/.well-known/apple-app-site-association` (served as `application/json`, no extension) and `/.well-known/assetlinks.json` from CLAUDE.md constants (ask me for SHA-256 fingerprints).
- Guest page `/k/{token}` and join page `/join/{code}` per ARCHITECTURE §3.7: Arabic/Turkish/English, RTL, Copy, Share, Add, store badges, OpenStreetMap attribution, no trackers.
- Done when: an emulator end-to-end test resolves a link and adds an item that then shows up in Firestore; the guest page scores ≥ 90 on Lighthouse mobile.

### B8 — Meals and the Ramadan planner
- `suggestMeals` and `mealToItems` per CONTRACT §4 using `LLM_MODEL_SMART`; occasions and cuisines from CONTRACT §2; scale to `people`; skip items already open or bought in the last 7 days; cache in `mealSuggestions`.
- Write `functions/src/meals/seed_dishes.json`: about 40 regional dishes (names in ar/tr/en, cuisine, occasion tags, ingredients per 4 servings with `canonicalId`s) to ground the model. Flag it for my review.
- Done when: tests pass with replayed responses; one real recorded response per occasion is saved for my review.

### B9 — Accounts and operations
- `exportMyData`, `deleteMyAccount` (transfer ownership, or delete the household when the last member leaves), `cleanupLinks`, the Firestore TTL policy on `events.expireAt` and `mealSuggestions.expireAt`, Remote Config defaults from CONTRACT §10.
- `docs/RUNBOOK.md`: deploy, rollback, secret rotation, budget alert setup, emulator tips.
- Done when: tests pass and RUNBOOK.md is complete.

### B10 — Phase 2 stubs (disabled by flags)
- `whatsappWebhook` (GET verification, `X-Hub-Signature-256` check, router for add / list / got / link / voice notes via `SpeechToText`), `linkWhatsApp`, `computeUsuals`, `learnAisleOrder` — all tested, all off by Remote Config.

## Working rules

- Never deploy to prod. Deploy to dev only when I write "deploy dev".
- Never commit secrets, service-account JSON or `.env.local`.
- Ask before adding a dependency that is not listed above.
- When a step needs a console action (create projects, enable Blaze billing, App Check keys, upload the APNs key to Firebase, enable the Speech-to-Text API, set a budget alert), stop and give me click-by-click steps.
- When you change shared behaviour (normalization, splitting, units, display rule), update `fixtures/` in the same commit and flag it in PROGRESS.md for the app sessions.
