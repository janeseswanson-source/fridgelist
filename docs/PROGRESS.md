# Progress log

Newest entries at the top. Each workstream adds an entry when it closes a milestone.

## Contract changes for other platforms

| Date | Version | Change | Backend | Android | iOS |
| --- | --- | --- | --- | --- | --- |
| 2026-10-18 | 1 | Initial contract | — | — | — |

## Milestones

| Workstream | Milestone | Status | Date | Notes |
| --- | --- | --- | --- | --- |
| Backend | B1 Scaffold | not started | | |
| Android | A1 Scaffold | done | 2026-10-18 | core:i18n passes 28/28 fixture cases (18/18 split required) |
| Android | A2 Sign-in, onboarding, household | done, E2E pending B3 | 2026-10-18 | two-user join test written; skips until backend B3 callables run on the emulator |
| Android | A3 Lists and items, offline-first | done, server part pending B4 | 2026-10-19 | offline add/tick/undo/delete → sync verified (instrumented test + device); categories/translations wait for B4 |
| iOS | I1 Scaffold | not started | | |

## Entries

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
