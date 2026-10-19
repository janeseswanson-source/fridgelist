# Progress log

Newest entries at the top. Each workstream adds an entry when it closes a milestone.

## Contract changes for other platforms

| Date | Version | Change | Backend | Android | iOS |
| --- | --- | --- | --- | --- | --- |
| 2026-10-18 | 1 | Initial contract | — | — | — |
| 2026-10-19 | 2 | §5.1: `trip_heading`, `trip_arrived`, `item_missing` reach **Android** as data-only messages with `title` + `body` data keys (server-localized). Needed for the inline reply. | **to do (B6)** | done (A6) | no change |
| 2026-10-19 | 3 | §4 `startTrip` refuses `kid` (`permission-denied`); §3.2 a user may delete their own device docs (sign-out). | device delete: done (B2); `startTrip`: to do (B6) | done (QA pass) | to do |

## Milestones

| Workstream | Milestone | Status | Date | Notes |
| --- | --- | --- | --- | --- |
| Backend | B1 Scaffold | done | 2026-10-19 | `npm run emulators` builds, starts Auth/Firestore/Functions/Hosting/Storage/Pub/Sub and seeds |
| Backend | B2 Security rules | done | 2026-10-19 | 144 rules tests (full §3.6 matrix); Android offline flow passes the real rules |
| Backend | B3 Households, invites, devices | done | 2026-10-19 | callables + `onMemberWritten`; Android two-user join passes against it |
| Backend | B5 Stores | done, real import awaits owner OK | 2026-10-19 | UAE and Saudi dry runs on live OSM data; `nearbyStores`/`addStore` |
| Backend | B4 Item pipeline | done, LLM/STT recordings pending a key | 2026-10-19 | golden 30/30 on catalog + dialect alone; LLM and STT behind interfaces, untested against the real services |
| Android | A1 Scaffold | done | 2026-10-18 | core:i18n passes 28/28 fixture cases (18/18 split required) |
| Android | A2 Sign-in, onboarding, household | done | 2026-10-18 | two-user join E2E passes against backend B3 (2026-10-19) |
| Android | A3 Lists and items, offline-first | done | 2026-10-19 | server categories/translations now run (B4); | offline add/tick/undo/delete → sync verified (instrumented test + device); categories/translations wait for B4 |
| Android | A4 Voice add | done, real-speech run pending | 2026-10-19 | fallback chain verified on emulator (no mic audio there); `docs/test-voice.md` to run on real phones |
| Android | A5 Shopping Mode | done, server trip pending B6 | 2026-10-19 | tick/undo/missing/finish UI-tested online and offline; local mode verified on device |
| Android | A6 "I'm going", notifications, inline reply, Live Updates | done, cross-phone run pending B6 | 2026-10-19 | contract v2; reply + Live Update verified on emulators via the dev push simulator |
| iOS | I1 Scaffold | not started | | |

## Entries

### 2026-10-19 — Backend B5 Stores
- Shipped:
  - `scripts/import-stores.ts`:
    - dry run by default, with counts per country and chain;
    - Overpass for AE/SA (nodes and ways with centres, the five shop types), retrying on 429/502/503/504;
    - Turkey through Geofabrik + `osmium` GeoJSON (FeatureCollection or geojsonseq);
    - writes are idempotent `osm_<type>_<id>` in batches of 400, into the emulators or `--live <projectId>`;
    - `--prune` for monthly re-runs.
    - Parsing lives in `functions/src/stores/osm.ts` and is unit-tested.
  - Chain mapping (`lib/chains.ts`): the ARCHITECTURE §5.1 table matched on `brand`, then the names; restricted to the countries each chain operates in; CarrefourSA before Carrefour.
    - Unicode-aware whole-word matching: JavaScript `\b` is ASCII-only, so "Yeni Şok" failed with it.
    - Both Turkish and plain lowercasing: Turkish rules turn "MIGROS" into "mıgros".
  - `nearbyStores`:
    - geohash query bounds plus an exact distance filter, nearest first, de-duplicated across cells;
    - defaults 5 km / 60, maxima 10 km / 100;
    - unverified stores show only to the adder's households;
    - **the location is never stored or logged** (rule 4).
  - `addStore`:
    - `user_<id>`, `verified: false`, `addedByHousehold` = the caller's first household, country from coordinates (TR/SA/AE only), a known `chainId` or `other`;
    - the same normalized name within 50 m returns the existing store;
    - 20 per day per user (a new rate-limit bucket; ARCHITECTURE §3 listed none for it).
  - `docs/ATTRIBUTION.md`: the ODbL attribution text in en/ar/tr and where it must appear. It isn't yet shown in the apps or the guest page.
- Live dry runs (public Overpass, read-only):
  - **UAE:** 4,984 shops, 4,478 named stores; chains mapped as listed in RUNBOOK.
  - **Saudi Arabia:** 3,207 / 2,650.
  - Turkey not run (needs the ~600 MB PBF and osmium; steps in RUNBOOK).
- Tests: **38 unit** (chain mapping incl. look-alikes such as "Kibim", "Şokella", "Pandas Toys", "Profile"; country boxes; Overpass and GeoJSON parsing) + **7 emulator** (ordering and distances, radius/limit/validation, cell edges without duplicates, household-only unverified stores, `addStore` creation, de-duplication, validation, rate limit).
- End to end: imported the UAE into the **local** emulators (4,478 stores); `nearbyStores` for Dubai Marina over HTTP returned real OSM stores nearest first (Wmart 21 m, Marina Grocery 52 m, Al Maya 156 m, West Zone 157 m…). The log holds only the count and radius.
- Suggestion for the owner (not done; it changes ARCHITECTURE §5.1's table): large UAE groups currently land in `other`. Sharjah Coop (35), Al Madina (~80 across spellings), West Zone (36), Zoom (~47) and ADNOC Oasis (~100). Adding chain IDs would group them for chips and aisle learning.
- Contract changes: none.
- Owner actions:
  - approve the first **real** import (needs the dev project);
  - for Turkey, run the osmium steps or let me run them once osmium is installed.
- Next: B6 (trips, notifications, live surfaces). It needs FCM, so pushes can be built and snapshot-tested against the emulators, and real delivery waits for the dev project.

### 2026-10-19 — Backend B4 Item pipeline
- The owner chose to build without an API key for now: the LLM and Speech-to-Text sit behind interfaces, are fully tested with fakes, and are **not yet run against the real services**.
- Shipped:
  - **Shared parsing in TypeScript:** `lib/normalize.ts`, `numbers.ts`, `units.ts`, `catalog.ts`, `localSplit.ts` (incl. the alias ID), line-by-line ports of Android `core:i18n`, keeping Kotlin regex semantics (ASCII whitespace class, `Char.isWhitespace`).
  - **New shared fixture `fixtures/split_cases.json` (rule 6):** the exact splitter output (name, qty, unit, canonicalId, ambiguity) for 94 inputs, plus 20 `matchKey`/alias-ID cases.
    - It is generated from the Android reference by `SplitCasesFixtureTest` (verified on every Kotlin run; `-PwriteFixtures=true` regenerates it).
    - TypeScript passes all 114 on the first run. **iOS (FridgeKit) must pass it too.**
  - `scripts/seed-catalog.ts`: idempotent; removes catalog docs no longer in the seed; `--live <project>` only on request. Part of `npm run emulators` (63 items).
  - **LLM** (`llm/`):
    - an `LlmTransport` interface (forced tool call), with an Anthropic implementation (`@anthropic-ai/sdk` 0.135), a replay transport and a recording transport keyed by a request hash;
    - `ClassifyingLlm` validates with zod, retries once, drops canonical IDs the catalog doesn't have, otherwise reports "unavailable";
    - the prompt is in `llm/prompts/classify.md`, with few-shot examples;
    - key: `defineSecret("ANTHROPIC_API_KEY")`; models: params `LLM_MODEL_FAST`/`LLM_MODEL_SMART`.
  - **Resolver** (`lib/resolve.ts`): household alias -> dialect rule with locale hints -> catalog synonym (longest first) -> `catalogCandidates` cache -> one batched LLM call.
    - LLM down -> local result, category `other`; it never throws.
    - The typed name stays as the item's own-language name; catalog names fill the other member languages.
  - `parseItems`: member check, rate limit 30/min/user, local split with the apps' rules, then resolve.
    - Optional fields are omitted; qty is kept only within 0 < q <= 999.
    - An ambiguous dialect word -> best candidate, confidence 0.4, `notes: "alternatives: bread, rice"`.
    - `source` is catalog, llm or mixed.
  - `transcribeAudio` (**assigned to B4 on 2026-10-19**):
    - zod validation (<= 1 MB base64, ogg/mp4/wav, base64 alphabet) and rate limit 20/hour/user;
    - up to 3 recognition languages from the hints (`ar` -> `ar-SA`);
    - `SpeechToText` interface with a Google Speech-to-Text v2 implementation (`@google-cloud/speech` 8.2, the client the stack's "Speech-to-Text v2" requires; flagged as a new dependency);
    - failures -> `unavailable`; audio is never stored or logged.
  - `onItemCreated`:
    - locale hints come from the adder's device locales (e.g. `ar-EG`), then their member language;
    - sets `lang`, `names` (every member language), `canonicalId` and `category`/`categorySource: auto`, unless the member chose `user`;
    - an ambiguous word gets the likely category but no `canonicalId`;
    - then merges per ARCHITECTURE 4.2 in a transaction: same canonicalId, else normalized name; oldest open/claimed; same unit adds (cap 999), a different unit goes to notes; `requestedBy`; urgency carries over; the newer doc is deleted; `item_merged`. Otherwise `item_added`.
    - It is idempotent, with a B6 hook marked.
  - `onItemUpdated`:
    - `done` -> a purchase (`{itemId}_{millis}`, with the trip's `storeId`/`chainId`) and `item_done`. An undo within 30 minutes removes the purchase.
    - `missing` -> `item_missing` (actor = the active trip's shopper) and a push to the adder through a `MissingNotifier`. It respects `notify.itemMissing` and never notifies the shopper. The sender is a stub until B6.
  - Events: `writeEvent` with `expireAt` = 30 days.
- **Golden report** (`backend/functions/test/golden-report.md`): **30/30 (100%)** with no LLM recordings. Every fixture case resolves through the catalog, dialect rules and splitter. It doesn't exercise the LLM, which matters for non-catalog words and fil/hi/ur translations; recording with `RECORD=1` waits for a key.
- Tests: **181 unit** (114 split/normalize parity, 21 split counts, 16 logic tests over normalize/units/merge/LLM/replay/resolve, golden, regions, scaffold) + **168 emulator** (144 rules, 18 households, 19 items/triggers/transcribe).
- End to end on the dev emulators:
  - signed in as seeded Mama: `parseItems` -> tomato 2 kg, "عيش" -> rice for `ar-AE`, basmati in a bag;
  - an item written through the security rules was enriched by `onItemCreated`, and a duplicate merged (1 + 2 = 3, `requestedBy`);
  - Android `OfflineItemsTest` and `TwoUsersJoinHouseholdTest` pass with the triggers active.
- Bugs found and fixed:
  - **Latin-script item names from an Arabic speaker were tagged `lang: ar`** (found end to end with "ibuprofen"). Latin text now only takes Latin-script hints, else `en`; covered by a test.
  - The editor tooling had written literal invisible characters into regexes and test strings in both codebases. All are now `\u` escapes; the Kotlin and TypeScript scans are clean.
- Android (no behaviour change): `SplitCasesFixtureTest` added; invisible characters escaped in five Kotlin files; 348 unit tests pass, lint clean.
- Contract changes: none. New shared fixture: `fixtures/split_cases.json`.
- Owner actions still open:
  - an Anthropic API key (record once; see RUNBOOK);
  - enable Cloud Speech-to-Text on the dev project (needs the project and billing).
- Next: B5 (stores import and `nearbyStores`/`addStore`).

### 2026-10-19 — Backend B3 Households, invites, devices
- Shipped (CONTRACT §4, zod-validated, logic in `*Core` functions with thin `onCall` wrappers):
  - `createHousehold`: one transaction writes the household (`memberLanguages` = owner's language, `plan: free`), the owner member (default `notify`), the default grocery list and the user link (`householdIds` arrayUnion; `createdAt` only for a new user doc).
    - The list is named in `defaultLanguage`: "البقالة", "Market listesi", "Groceries" (`src/i18n/*.json`).
    - Names are trimmed and 1–40 characters.
  - `createInvite`: owner or adult only; roles adult, kid or helper.
    - The code comes from `crypto.randomInt`, and `create()` never overwrites an existing code (retries on collision).
    - 7 days, 10 uses; returns `{code, url, expiresAt}` in Unix seconds.
  - `joinHousehold`: accepts codes as typed (lowercase, spaces, dashes). One transaction:
    - unknown code or deleted household → `not-found`;
    - already a member → current role, invite not used (the owner stays owner);
    - expired or used up → `failed-precondition`;
    - otherwise creates the member, increments uses, and adds the language and the household link.
    - Retry budget raised to 15 so a burst of joins on one QR doesn't fail on contention.
  - `onMemberWritten`: recomputes `memberLanguages` (sorted union, written only when changed) and adds/removes `users/{uid}.householdIds` on member create/delete. Idempotent with the callables; covers language changes and removals (B9).
  - `devices`: client-written by contract; B2 rules already cover them.
- **Bug found and fixed:** functions after `health` were defined in `us-central1`. ES module imports run before `index.ts`'s own `setGlobalOptions`, so they'd have deployed to the wrong region, unreachable by the apps. `src/setup.ts` is now the first import, and `test/regions.test.ts` checks every export (it fails on the old code).
- Tests: 13 unit + 162 emulator tests pass (144 rules + 18 integration).
  - The integration tests cover invite expiry at the exact boundary, max uses, 14 concurrent joins (uses = successes ≤ 10, members = 1 + successes), joining twice, roles, language bookkeeping, validation, and the trigger's change/removal/deleted-household paths.
  - `npm test` = unit + `test:emulator`.
- End to end on the dev emulators: Android `TwoUsersJoinHouseholdTest` (create → invite → join → both see each other with the right roles) and `OfflineItemsTest` both **pass against the real backend and rules**. Function logs show the trigger firing, with IDs and roles only (no names).
- Contract changes: none.
- Next: B4 (catalog seed, normalize/units/localSplit in TypeScript against `fixtures/`, `parseItems`, `transcribeAudio`, `onItemCreated`/`onItemUpdated`, golden test). This needs the Anthropic API key and Google Speech-to-Text for **recording** real responses once; tests replay recordings offline.

### 2026-10-19 — Backend B2 Security rules
- Shipped: `backend/firestore.rules` from CONTRACT §3 and ARCHITECTURE §3.3, replacing the B1 deny-all placeholder.
  - **users:** self reads; self creates/merges only `displayName`, `language`, `uiLanguage` (possibly before any callable, as Android does); `householdIds`/`createdAt` server-only; no client delete.
  - **devices:** self only, whitelisted fields, server `updatedAt`, and self delete (contract v3).
  - **households:** members read; the owner updates `name`/`defaultLanguage` only.
  - **members:** members read; self edits `displayName`/`language`/`notify` only (validated); role, presence and joinedAt are functions'.
  - **lists:** owner/adult create (server `createdAt`, field whitelist) and edit; only the owner deletes.
  - **items:**
    - create forces `open`, or `pending` for kids; `addedBy` must be the caller; `addedVia` is a client channel (not `guest_web`/`whatsapp`); a category needs `categorySource: user`; server timestamps.
    - updates: client fields only, server `updatedAt`, rename by the creator only, the §3.6 transitions (same table as Android `StatusRules`), `claimedBy`/`doneBy` naming only the caller, server `doneAt`.
    - deletes: `pending` by owner/adult, others by any member (matches Android `canDelete`).
  - **aliases:** any member writes, `createdBy` = caller.
  - trips/events/purchases: members read only; mealSuggestions, shareLinks, invites, catalogCandidates: closed; stores/catalog: signed-in read; anything unlisted: closed.
- Tests (`@firebase/rules-unit-testing` 6 + `firebase` 13 JS SDK as its required peer, dev only): **144 passing**.
  - The full transition matrix: 5 × 5 statuses × 4 roles × claimer/not = 100 cases.
  - Every Writer-column row in §3.1–3.16.
  - Validation edges: lengths, qty range, enums, server time.
  - Mutation check: weakening the kid-create rule or the claimer rule makes 4 tests fail.
  - `npm test` now runs unit + rules tests; rules use their own emulator on port 8180.
- Android (test-only changes):
  - `OfflineItemsTest` now signs in as the seeded owner "Mama" and creates its own list in `seed-home`; the app's real offline add/tick/undo/delete → sync **passes the real rules**.
  - `TwoUsersJoinHouseholdTest` now skips until `createHousehold` exists (the B1 functions emulator only has `health`).
- Contract changes: none (implements v3's device delete).
- Next: B3 (`createHousehold`, `createInvite`, `joinHousehold`, `onMemberWritten`).

### 2026-10-19 — Backend B1 Scaffold
- Shipped:
  - `backend/`: `firebase.json`; `.firebaserc` (`dev`/`prod` placeholders from CLAUDE.md; default `demo-fridgelist`); placeholder deny-all `firestore.rules` and `storage.rules` (B2 replaces them); indexes; hosting placeholder.
  - Emulators: Auth 9099, Firestore 8080, Functions 5001 (the ports the Android dev build already uses), Hosting 5000, Storage 9199, Pub/Sub 8085, UI 4000.
  - `functions/`: TypeScript 6.0 strict, ESM, Node 22, `firebase-functions` 7.4 v2 API, `firebase-admin` 14.5, `zod` 4; ESLint 10 + `typescript-eslint` strict type-checked + Prettier; vitest 5. Region and constants in `src/config.ts`.
    - TypeScript 6.0.3, not 7.0, because `typescript-eslint` supports `< 6.1`.
  - `lib/log.ts`: the only logger. It takes IDs, codes and counts and redacts text-like keys; `console` is a lint error (rule 4).
  - `lib/ids.ts` (contract invite codes from `crypto.randomInt`, OSM store IDs) and `lib/time.ts`.
  - `health` callable for diagnostics (not in CONTRACT §4; no auth, no user data).
  - `npm run emulators` (`scripts/emulators.ts`): builds functions, starts the suite, waits for the hub, runs `scripts/emulator-seed.ts`.
    - Seed: household `seed-home`; owner (`ar`), adult (`tr`) and helper (`fil`) linked to the Android dev test sign-in (Mama/Ayse/Maria); 2 lists; 15 items in mixed languages; 30 stores (Kadıköy, Olaya, Dubai Marina) with geohashes.
    - Idempotent (fixed IDs).
  - `docs/RUNBOOK.md`.
  - `tsx` (dev only) approved by the owner for running scripts.
- Decisions: contract v3 (kids can't `startTrip`; self may delete device docs). `transcribeAudio` added to B4 in `prompts/01-backend.md` (it had no owner).
- Tests: functions lint, Prettier, typecheck and 7 vitest tests pass. Scripts typecheck. On the running emulators: `health` returns `{ok, region: me-central2, contractVersion: 3}`; Firestore has 30 stores, 3 members, 13 + 2 items; the "Mama" test sign-in resolves to `seed-owner`; re-seeding leaves 30 stores and 3 users.
- Next: B2 security rules with `@firebase/rules-unit-testing` (every Writer-column row and §3.6 transition; v3 device delete).
- Open: the region check for 2nd-gen functions in `me-central2` happens at the first "deploy dev". Real project IDs, `APPLE_TEAM_ID`, `SUPPORT_EMAIL` and `PRIVACY_POLICY_URL` are still TODO in CLAUDE.md.

### 2026-10-19 — Android QA pass A1–A6
- Scope: code review of all modules, edge-case and fuzz tests, full suite, and a device run (API 36.1 emulator) in Arabic, Turkish and English, light and dark, against the Auth/Firestore emulators plus a **throwaway stand-in** for the CONTRACT §4 callables. That stand-in lived in the session scratchpad and is not in the repo; it is not B3/B6.
- Fixed (each with a test):
  - **Shared normalization (rule 6):** invisible marks (RLM/LRM, bidi embeddings and isolates, ZWSP, BOM, soft hyphen) are removed and no-break spaces become spaces, so `‏حليب` matches milk and `2 süt` splits. ARCHITECTURE §4.1 has a new "All languages" line; `fixtures/parse_cases.json` gains `inv-01` and `inv-02` (30 cases). **Backend and iOS must implement the same rule.**
  - Local chips no longer show out-of-range quantities (`0 milk`, `99999999999 milk`); `validQty` applies before display, as it already did before writes.
  - Length limits cut with `clip()`, which never splits an emoji (surrogate pair).
  - Pushes for a household other than the current one (or while signed out) are not shown (privacy).
  - `trip_update` counts are clamped (no negative or overflowing progress).
  - A last-call countdown shows only for a sane future time.
  - Blank push titles get a localized fallback.
  - Inline reply: says "Not added" when nothing could be written (wrong household, signed out, only a number), and a failure can no longer crash the process.
  - Double taps: create household, join, profile save, sign-in, invite and "I'm going" send once (a second household or a second household-wide push was possible).
  - Shopping Mode:
    - after process death on the summary, no new trip is started;
    - the local-mode cart survives process death;
    - "Done" on the notification while the screen is open stops it re-posting the notification.
  - Kids: no "I'm going" or Shopping Mode (PRODUCT_SPEC roles); a stale saved trip is cleared instead of resumed.
  - Sign-out: deletes this install's `devices` doc (while still signed in) and clears every notification the app posted.
  - The shop link and the join link navigate single-top (no stacked copy of Shopping Mode).
  - The dialect choice chips wrap (three meanings of `لبن` at large font).
  - Denying the microphone now explains how to turn it on, or to type instead. After "don't ask again" it used to do nothing.
  - Voice never crashes on an unexpected transcribe error.
- Verified on device with the stand-in callables:
  - `TwoUsersJoinHouseholdTest` passes (first end-to-end A2 run).
  - Real create household; the offline message and the double tap.
  - Invite link held through sign-in and profile, then prefilled.
  - Kid request lands as `pending` in Requests.
  - Server trip → tick / missing → process death → restore (one trip) → Done → `updateTrip(done)` via WorkManager → missing back to `open`.
  - Inline reply writes `notification_reply` items.
- Tests: 347 unit (model 25, i18n 75, data 23, app 224) + 2 instrumented, 0 failures; lint clean; 132 screenshots verified.
- Open questions, resolved the same day:
  - `startTrip` refuses `kid`, and a user may delete their own device docs: both are now **contract v3** (backend B2/B6 to implement).
  - Pieces now read as a count ("×12 eggs" instead of "12 pieces eggs").
  - The dev test sign-in button was already disabled for an empty name (a misread UI dump, not a bug).
- Not covered (needs real hardware or the backend): real speech, real FCM delivery and the cross-phone 5 s reply, server categories and translations.

### 2026-10-19 — Android A6 "I'm going", notifications, inline reply, Live Updates
- **Contract v2** (see the table at the top): `trip_heading`, `trip_arrived` and `item_missing` reach Android as data-only messages with server-localized `title`/`body` data keys. Android can only attach the inline reply when the app builds the notification itself. **Backend B6 must send them this way**; iOS is unchanged.
- Shipped:
  - **"I'm going" sheet** (list screen):
    - "show stores near me" reads coarse location once, only after a tap, explained in the sheet; ACCESS_COARSE_LOCATION only, background location is A7. Then `nearbyStores` → nearest 5 with distances in the UI language.
    - Otherwise chain chips for the phone's country (TR/SA/AE), and a free-text store.
    - Last call 0/5/10/15 → `startTrip(status: heading)`. Offline: a clear message, nothing started.
  - **Push handling** (`PushRouter`, used by `FirebaseMessagingService`):
    - every CONTRACT §5.1 type
    - unknown or malformed payloads are ignored, never crash
    - `trip_update` drives the shopper's notification; `done`/`cancelled` clear it and end the session
    - `list_changed` is left for A8
  - **Channels** `trip` (low, silent), `last_call` (high), `household` (default), `suggestions` (low), names in the app language.
  - **Inline reply** on `trip_heading` (last-call countdown) and `trip_arrived`:
    - `RemoteInput` key `item_text`, semantic action REPLY
    - `AddFromNotificationReceiver` splits locally (household aliases first) and writes offline-first with `addedVia: notification_reply`; kids' replies become requests
    - the notification turns into "Added ✓" with the reply in its history
  - **Ongoing trip notification / Live Update** (`TripNotifications`):
    - title "Heading to / At {store}"
    - the latest add by someone else, "5 of 12 in the cart", next 3 items
    - `ProgressStyle` with one segment per item (one bar above 30), progress = in the cart
    - `setRequestPromotedOngoing`, short critical text "7 left", last-call chronometer countdown
    - actions Open (`fridgelist://shop?tripId=`) and Done (`TripActionReceiver` → WorkManager `updateTrip(done)`), or "I'm at the store" while heading
    - kept in step by Shopping Mode; arriving on a heading trip sends `updateTrip(shopping)`
  - `POST_PROMOTED_NOTIFICATIONS` and `ACCESS_COARSE_LOCATION` in the manifest.
  - **dev-only push simulator** `DebugPushReceiver` (dev manifest, guarded by `DUMP` so only `adb shell` can call it) feeds §5 payloads to the real router; see `docs/test-notifications.md`.
  - New dependency from the approved stack: Play services location 21.4.0.
- Tests: `./gradlew lint testDevDebugUnitTest assembleDevDebug` → BUILD SUCCESSFUL, lint "No issues found", no compiler warnings.
  - 322 unit tests, 0 failures (app 208), 132 screenshots ("I'm going" sheet added).
  - Robolectric, as prompt A6 asks:
    - channels
    - shopper notification (ongoing, title, progress 5/12, next items, Open/Done, promotion requested)
    - heading countdown and "I'm at the store"
    - inline reply (`item_text`, REPLY), arrived/missing channels, "Added ✓" with reply history
    - router: household post, `trip_update` show and end, unknown or broken ignored
  - The reply handler (`notification_reply`, kids → pending, other household ignored), the "I'm going" ViewModel (nearest 5, request shape, free text, offline) and the Shopping notification sync are unit-tested; push payload parsing is in core:model.
  - Devices:
    - API 36 emulator: `trip_heading` → reply typed in the shade ("2 ayran and jam") → "تمت الإضافة ✓", and Firestore got ayran qty 2 + jam with `addedVia: notification_reply`.
    - Done on the ongoing notification cancelled it and scheduled the WorkManager job. "I'm going" offline message checked.
    - New AVD `fridgelist_api36_1` (Android 16 QPR1): the trip notification is posted with `PROMOTED_ONGOING`, status-bar chip "🛒 باقٍ 7", progress bar, Arabic text, Open/Done.
    - The first Android 16 image has the API but does not promote; `POST_PROMOTED_NOTIFICATIONS` isn't granted there.
- Not done yet: the prompt's cross-phone check (a lock-screen reply reaches another phone within 5 s) needs backend `startTrip` and real pushes. `docs/test-notifications.md` has the checklist.
- Notes for the backend session (B6): follow contract v2 for Android. `trip_update` goes only to the shopper's Android device. `startTrip(heading)` can carry `storeId` from `nearbyStores`, a chain name without `storeId`, or free text.
- Next: A7 (store detection).
- Open questions for the owner: unchanged.
- Contract changes: **v2** (above).

### 2026-10-19 — Android A5 Shopping Mode
- Shipped:
  - Entry points: "Start shopping" on a list (shown when something is left to buy), `{URL_SCHEME}://shop?tripId=` (CONTRACT §6, for A6/A7 notification and Live Update taps), and a "Continue shopping" banner on Home while a session is open.
  - Start: `startTrip(status: shopping, auto: false, listIds, platform: android, deviceId)` with an 8 s cap. If it fails, **local mode** (banner, everything still works). An open session resumes the same trip instead of starting another.
  - Screen:
    - big rows (≥ 72 dp, whole row toggles, haptic tick) in CONTRACT §2 aisle order
    - a "couldn't find" button per row; Undo (long snackbar)
    - sections: to buy, couldn't find, someone else is getting these (dimmed), in the cart (this trip's `doneTripId`, or this session's ticks in local mode)
    - the screen stays on (`keepScreenOn`); moves follow the §3.6 table
  - Ticks write `doneTripId` when there is a trip.
  - Done:
    - summary at once (bought, not found "stays on the list")
    - "Send the rest" shares formatted text in the viewer's language (e.g. `• طماطم — 2 كغ`)
    - with a trip: `updateTrip(done)` goes through WorkManager (unique per trip, network constraint, exponential backoff, gives up after 8 tries; already-ended/not-found count as success) and the server reopens missing items
    - local mode: the app moves this session's missing items back to `open`
  - A stale or unknown shop link shows "This shopping trip has already ended." It never starts a new trip.
  - `UndoQueue` is shared by the list and shopping screens.
  - New dependency from the approved stack: WorkManager 2.11.2 (`work-runtime-ktx`, `work-testing`). The worker gets its dependencies through a Hilt entry point, so no hilt-work library is needed.
- Tests: `./gradlew lint testDevDebugUnitTest assembleDevDebug` → BUILD SUCCESSFUL, lint "No issues found", no compiler warnings.
  - 296 unit tests, 0 failures (app 186), 126 screenshots (Shopping and the summary added).
  - Prompt "done when": `ShoppingUiTest` drives the real screen and ViewModel over fakes: tick → in the cart → Undo → couldn't find → Done → summary (online, `updateTrip` queued), and offline (local banner, tick, Done, no trip call).
  - `ShoppingTest` covers: trip request shape, `doneTripId`, Undo, others' claims, finish with and without a trip, resuming a trip, opening from a link, stale link, worker retry rules.
  - Device (Arabic, Auth + Firestore emulators, no Functions):
    - Start shopping → local mode
    - `KEEP_SCREEN_ON` set on the window
    - ticked bread, tomatoes "couldn't find", Done → summary 1 bought / 1 not found
    - server: bread `done` without `doneTripId`, tomatoes back to `open`
    - "Send the rest" share text checked
    - the `fridgelist://shop?tripId=t-gone` link → "trip has ended"
- Changed along the way: **firebase-messaging 26.0.0 deprecates `getToken()`/`onNewToken`** in favour of `register()` + `FirebaseMessagingService.onRegistered`. The app now calls `register()` and saves tokens from `onRegistered`; the saved token survives sign-out because it belongs to the device. It also still handles `onNewToken` (same code) until rotation is confirmed to reach `onRegistered` on a real project.
- Notes for the backend session (B6):
  - `startTrip` is called with `storeId`/`storeName` absent for manual shopping from a list; please accept that (CONTRACT marks them optional).
  - `updateTrip(done)` may arrive minutes after Done (retry when back online) and possibly twice. The worker treats `failed-precondition` (already ended) as success.
- Next: A6 ("I'm going", notifications, inline reply, Live Updates).
- Open questions for the owner: unchanged. Once real Firebase config exists, I need to confirm FCM token delivery via `onRegistered` on a real device.
- Contract changes: none.

### 2026-10-19 — Android A4 Voice add
- Shipped:
  - Mic button in the add sheet. On first use, a one-line explanation comes before the `RECORD_AUDIO` prompt.
  - A live panel shows "Listening…" with the partial transcript, or recording, transcribing, or a message. Changes are announced by TalkBack.
  - The transcript is appended to any typed text (Arabic comma for Arabic) and goes through the same chips as typing (local split → `parseItems`). Items confirmed after speaking are written with `addedVia: app_voice`.
  - Recognition order (ARCHITECTURE §8.6):
    1. on-device `SpeechRecognizer` (API 31+, when available)
    2. the phone's online recognizer
    3. cloud fallback: `MediaRecorder` ≤ 15 s (auto-stop) → `transcribeAudio`
    - A recognizer moves on to the next one only when it can't do the language or isn't available. Silence, offline and permission each get their own message. Typing always works.
  - Cloud audio: OGG/Opus on API 29+, AAC `audio/mp4` on API 26–28 (both allowed by CONTRACT §4). Base64 is checked against the 1 MB limit before sending, and the cache file is deleted right after it is read.
  - Recognition language: the member's item language with the phone's region when it is the same language (ar-EG on an Egyptian phone), otherwise ar-SA / tr-TR / en-US / fil-PH / hi-IN / ur-PK.
  - Manifest: `RECORD_AUDIO`, and a `<queries>` entry for `android.speech.RecognitionService` (needed on Android 11+ to find the recognizer).
  - `docs/test-voice.md`: manual script with 11 fixture phrases (ar/tr/en), the fallbacks and a privacy check.
- Tests: `./gradlew lint testDevDebugUnitTest assembleDevDebug` → BUILD SUCCESSFUL, lint "No issues found".
  - 272 unit tests, 0 failures (app 163). New tests cover:
    - partials → final → transcript
    - on-device language failure → online recognizer
    - no recognizer → record → `transcribeAudio`
    - 15 s auto-stop, offline, recorder unavailable, too-long audio, silence, Stop, cancel
    - recognition language, audio format per API level, recognizer error mapping
    - transcript → chips → `app_voice`; typed-only stays `app_text`
  - 114 screenshots: adds "listening" with an Arabic partial transcript, and the offline message.
  - Device (API 36 emulator), Arabic:
    - rationale → system prompt → listening started with `ar-SA`
    - the on-device recognizer had no Arabic pack (error 12) → the app fell through to the online recognizer → "no speech" (error 7), because the emulator mic gets no audio
    - closing the sheet stopped the mic (`stopping(CLIENT_REQUESTED)`)
  - Real speech recognition was **not** run; that needs real phones and `docs/test-voice.md`.
- Fixed along the way:
  - Closing the add sheet now stops the mic. The sheet's ViewModel belongs to the list screen and outlived the sheet.
  - The voice panel uses a static mic icon while listening. An endless spinner kept Compose from going idle and stalled screenshot and UI tests for minutes.
- Notes for the backend session: `transcribeAudio` receives `audio/ogg` (Opus, 16 kHz mono) on most phones and `audio/mp4` (AAC) on Android 8–9. `localeHints` starts with the recognition tag (e.g. `ar-SA`).
- Next: A5 (Shopping Mode); needs backend `updateTrip` (B6) for "Done" but works offline otherwise.
- Open questions for the owner:
  - Please run `docs/test-voice.md` on one Android 12+ phone and one older phone and tell me what fails.
  - Others unchanged (application ID, `google-services.json` + Web client ID, brand colours).
- Contract changes: none.

### 2026-10-19 — Android A3 Lists and items, offline-first
- Shipped:
  - Home = the household's lists with open counts (Arabic plurals). Owner and adults can create lists offline: name, type, one-grapheme emoji.
  - List detail, sections in this order:
    - Requests (kids' `pending`, approve/decline for owner/adult)
    - to-buy grouped in the CONTRACT §2 category order
    - Couldn't find
    - Bought (collapsed)
  - Rows use the display rule (§4.3) with the original underneath, localized quantity/unit (counted units use plurals), urgent, "asked by" (members, or "Guest" for `guest:` adds), "X is getting it", and a "not synced yet" marker from Firestore pending writes.
  - Actions:
    - checkbox / swipe toward the end = bought
    - swipe toward the start = couldn't find
    - menu: claim/release, reopen, edit, delete
    - only moves the §3.6 table allows are offered (`StatusRules` in core:model, one test per table row)
    - Undo for every status change, decline and delete. Deletes are hidden at once and written when the snackbar closes, because re-creating someone else's item would break the create rule.
  - Edit sheet: qty (accepts ١٫٥ / 1,5), unit, category (written with `categorySource: user`), notes, urgent. The name is editable by the creator only.
  - Add sheet:
    - local split chips at once, then `parseItems` refines after a 700 ms pause when online; any failure keeps the local chips
    - confirm writes `addedVia: app_text`, `status` open (or pending for kids), server timestamps, and no server-owned fields (no `category`/`lang`/`names`/`canonicalId`)
  - Ambiguous dialect words: chips offer the catalog's candidates (works offline). The pick is saved as a household alias (§3.16 ID = first 32 hex of SHA-256 of the normalized text). The local splitter reads aliases first (ARCHITECTURE §4.1 order).
  - Until the server classifies an item, it is grouped by the local catalog's category guess (display only, never written).
- Tests: `./gradlew lint testDevDebugUnitTest assembleDevDebug` → BUILD SUCCESSFUL, lint "No issues found".
  - 245 unit tests, 0 failures: core:model 19, core:i18n 68 (fixtures still 28/28), core:data 22, app 136. 102 Roborazzi screenshots (17 screens × ar/tr/en × light/dark).
  - `connectedDevDebugAndroidTest` against the Firestore emulator: `OfflineItemsTest` passes (network off → add 2, tick, undo, delete → network on → server state checked). The two-user join test still skips (needs B3).
  - Device, Arabic, airplane mode on the API 36 emulator:
    - added "milk, 6 eggs and bread" → rows appeared at once, grouped, marked not synced
    - ticked and undid offline
    - airplane mode off → synced with no pending markers
    - server shows milk/eggs `done`, bread `open` with the done fields cleared
  - The household was seeded straight into the emulator in place of `createHousehold`. Sign-in then restored it from `users/{uid}.householdIds`, which also exercises the new-phone path.
- Fixed along the way:
  - **Double Back emptied the back stack**, leaving a blank screen (fast double tap, or keyboard Space/Enter). In-app Back now only pops when a screen is underneath.
  - Extended FABs (Add, New list, Invite) didn't expose their label to TalkBack in this Material version; they now have explicit labels. Row checkboxes are labelled with the item title.
  - The Undo snackbar now uses the long duration (it was gone in ~4 s).
  - The household context now waits for the first members snapshot (from cache when offline). A provisional least-privileged role could otherwise have turned an adult's adds into kid requests.
  - The notification step's `finish()` is idempotent.
- Notes for the backend session (B2/B4):
  - Items are created **without** `category`, `categorySource`, `lang`, `names` or `canonicalId`; `onItemCreated` must fill them. Undo/Release send `FieldValue.delete()` for `claimedBy`, `doneBy`, `doneAt` and `doneTripId`.
  - Deletes come from any member for non-pending items, and from owner/adult for `pending` ones (`StatusRules.canDelete`). B2 rules should match, or tell me the intended rule.
  - Aliases are written as `{text, canonicalId, createdBy, createdAt}` with the §3.16 ID; `text` = normalized text as in `Normalize.matchKey` (Kotlin), so the TypeScript normalization must produce the same key.
  - For ambiguous words the app uses the catalog's `dialectRules` candidates. It does not parse `ParsedItem.notes`, so the B4 "alternatives in notes" format is free.
- Next: A4 (voice add).
- Open questions for the owner: unchanged (application ID, `google-services.json` + Web client ID, brand colours).
- Contract changes: none.

### 2026-10-18 — Android A2 Sign-in, onboarding, household
- Shipped:
  - Onboarding flow, resumable after restart (`OnboardingStep` from the stored session): UI language → sign in → display name + item language (ar, tr, en, fil, hi, ur) → create or join a household → notification rationale → home.
  - Sign in with Google via Credential Manager. It turns on when `google.webClientId=…` is set in `android/local.properties`.
  - dev only: an "emulator test user" that signs in to the Auth emulator with an unsigned Google credential.
  - Callables `createHousehold`, `createInvite`, `joinHousehold` with typed CONTRACT §4 shapes and CONTRACT §8 error mapping. "Offline / connection refused" shows a clear message, never a crash.
  - Join by typed code (normalized, validated against the §3.12 alphabet), Google code scanner QR, or the `https://LINK_DOMAIN/join/{code}` App Link (filter in place; verification in A9).
  - Members screen (live, owner first) with role labels. Owner and adults can invite: role → code, QR (ZXing), expiry in the user's locale, Share.
  - `users/{uid}` profile fields written offline-first.
  - `deviceId` UUID created once in DataStore. `users/{uid}/devices/{deviceId}` is written after sign-in, on every start and from `onNewToken` (needs a real FCM project; skipped quietly on the demo project).
  - Settings: sign out (clears the session, keeps the device ID).
  - New dependencies (approved): Credential Manager + googleid, Firebase Messaging, Google code scanner, ZXing core, kotlinx-coroutines-play-services.
- Tests: `./gradlew lint testDevDebugUnitTest assembleDevDebug` → BUILD SUCCESSFUL, lint "No issues found".
  - 172 unit tests, 0 failures: core:model 12, core:i18n 61, core:data 13, app 86.
    - app: 66 Roborazzi screenshots (11 screens × ar/tr/en × light/dark, RTL reviewed) plus ViewModel tests for sign-in, profile, create, join, notifications, members and invites.
  - Count strings use `<plurals>` (Arabic zero/one/two/few/many/other).
  - Device run against the Auth + Firestore emulators (Functions not available yet):
    - Arabic onboarding works end to end up to the callables.
    - `users/{uid}` written with exactly `displayName`, `language`, `uiLanguage`.
    - `createHousehold`/`joinHousehold` without a Functions emulator show the no-connection message.
    - The `/join/abcd2345` link opens Join with `ABCD2345` filled in.
  - `connectedDevDebugAndroidTest` → `TwoUsersJoinHouseholdTest` skips with "Functions emulator not running (needs backend B3)". AGP's XML lists the skipped assumption as a failure; the build passes.
- Acceptance still open: "two emulator users create and join one household and see each other with the right roles". Run once B3 exists:
  - `firebase emulators:start --project demo-fridgelist`
  - then `./gradlew connectedDevDebugAndroidTest`
- Fixed along the way:
  - The demo API key must look like a real one (`AIza` + 35 characters), or Firebase Installations throws before any call.
  - The Functions SDK reports connection failures as `INTERNAL` wrapping an `IOException`; these now map to NETWORK.
- Notes for the backend session (B2/B3):
  - Clients write `users/{uid}` with merge `{displayName, language, uiLanguage}` right after the profile step, possibly before any callable has created the doc. B2 rules must allow the owner to **create** `users/{uid}` with only those self-owned fields.
  - `joinHousehold` carries no `uiLanguage` (CONTRACT §4); the joiner's `uiLanguage` arrives only through that self-write.
  - After sign-in on a new phone, the app reads `users/{uid}.householdIds` from the server to skip create/join.
  - Emulator project ID `demo-fridgelist`; Functions region assumed `me-central2`.
- Next: A3 (lists and items, offline-first); needs backend B4.
- Open questions for the owner: unchanged from A1 (real application ID, `google-services.json` + Web client ID, brand colours).
  - Also: the emulator image's Google Play services (25.26) is older than the latest libraries want (26.12). The code scanner may ask to update Play services on that AVD; real phones are fine.
- Contract changes: none.

### 2026-10-18 — Android A1 Scaffold
- Shipped:
  - `android/` Gradle project (Kotlin DSL, version catalog): AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00, Firebase BoM 35.0.2.
  - SDK levels: `minSdk 26`, `targetSdk 36` (Google Play requirement since 31 Aug 2026), `compileSdk 37` (required by current AndroidX; build-time only).
  - Modules `app`, `core:model`, `core:i18n`, `core:data`; flavors `dev` (`.dev` suffix, emulators) and `prod`.
  - `core:model`: every CONTRACT §2 enum with lenient parsing (unknown → `other`/null, never crash); Firestore document types; `ParsedItem` and widget snapshot JSON. Kotlin type for the `Unit` enum is `ItemUnit` (wire values unchanged).
  - `core:i18n`: `Normalize`, `Numbers`, `Units`, `Catalog` (loaded from `fixtures/catalog_seed.json`, copied into the jar at build time), `LocalSplitter`, `DisplayRule`, `CategoryOrder`.
  - `core:data`: Hilt `FirebaseModule`, `FirebaseBootstrap`, `DevSettings` (emulator host in DataStore, default `10.0.2.2`), Firestore persistent cache.
    - dev runs on the Emulator Suite with project ID **`demo-fridgelist`** and needs no config file.
  - `app`: Material 3 theme with a placeholder green/saffron palette, light and dark.
    - Fonts: IBM Plex Sans Arabic for the Arabic UI, Inter for Turkish/English (downloadable Google Fonts).
    - Type-safe navigation: Home (empty state) and Settings.
    - Per-app language (system/العربية/Türkçe/English) through AppCompat and `locales_config.xml`; `supportsRtl`; all strings in `values`, `values-ar` and `values-tr`.
    - Dev-only emulator host field.
- Tests: `./gradlew lint testDevDebugUnitTest assembleDevDebug` → BUILD SUCCESSFUL, lint "No issues found".
  - 86 unit tests, 0 failures: core:model 5, core:i18n 61, core:data 3, app 17 (including 12 Roborazzi screenshots).
  - Fixture report (local splitter): item count right 28/28; full match (canonicalId + qty + unit) 28/28.
  - Screenshots in `android/app/src/test/screenshots/` (home and settings × ar/tr/en × light/dark); RTL reviewed.
  - Also run on the API 36 emulator: launches; switching language in-app works for ar (RTL) and tr; logcat shows "Firebase emulators at 10.0.2.2"; no crashes.
- Local splitter rules other platforms should mirror (ARCHITECTURE §4.1 left these open):
  - `و` glued to a word splits only when the rest is a known catalog word, unit, number or digit and the whole word is not itself known (`وحليب` splits; `ورق`, `ونص` and `واحد` do not).
  - Unknown `و…` words stay whole.
  - A space-only run of known catalog synonyms with no quantity splits (`domates biber patlıcan` → 3).
  - Trailing quantity is recognized (`yumurta 30 tane`, `sugar 1kg`, `x2`).
  - Short unit words (`g`, `l`, `lt`, `ml`) only count after a number.
  - `علبة`/`kutu` become `can` for catalog items whose `defaultUnit` is `can`; `كرتونة`/`box` become `tray` for eggs.
- Next: A2 (sign-in, onboarding, household); needs backend B3.
- Open questions for the owner:
  - Real `ANDROID_APPLICATION_ID` (placeholder `com.example.fridgelist`, one line in `app/build.gradle.kts`); needed before A9/A11.
  - `google-services.json` for dev/prod and a Web client ID for Sign in with Google; needed for A2 on real projects and for the `prod` flavor at all.
  - Final brand colours and launcher icon (placeholders in place).
- Notes for the backend session:
  - The Android dev build uses Firebase project ID `demo-fridgelist` and calls Functions in region `me-central2`.
  - Please use the same demo project ID for the emulators (`firebase emulators:start --project demo-fridgelist`) and confirm the functions region in B1.
- Toolchain notes:
  - Robolectric needs Java 21 to run SDK 36+, so JVM UI tests run on SDK 35 with JDK 17 (`robolectric.properties`). Installing JDK 21 later lets them move to 36.
  - The repo moved to `C:\dev\Fridgelist` (out of OneDrive, to avoid Gradle file locks).
- Contract changes: none.

<!-- Template:
### YYYY-MM-DD — <Workstream> <Milestone>
- Shipped:
- Tests: <command> → <result>
- Next:
- Open questions for the owner:
- Contract changes:
-->
