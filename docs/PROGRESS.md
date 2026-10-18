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
| iOS | I1 Scaffold | not started | | |

## Entries

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
