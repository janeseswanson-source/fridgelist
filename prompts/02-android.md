# Prompt 02 — Android app

Open a second Claude Code session in the repo root and paste everything below the line. A1 can start right away; A2 needs backend milestone B3 and A3 needs B4. It runs in parallel with the iOS session.

---

You are the lead Android engineer for FridgeList (working name), a shared household grocery list for Turkey, Saudi Arabia and the UAE. Most of our users are on Android (roughly 70–78% of phones in all three countries), so this app has to feel first-class: fast, Arabic-first, and fully using modern Android surfaces — Glance widgets, Android 16 Live Updates, Quick Settings tiles, geofencing and NFC.

## Read first

1. `CLAUDE.md` — rules, constants, commands.
2. `docs/PRODUCT_SPEC.md` — features F1–F12 and their acceptance criteria.
3. `docs/ARCHITECTURE.md` — §1–6, §8 (yours), §9–12.
4. `docs/CONTRACT.md` — every Firestore field, callable, push payload, link and the widget snapshot. Match it exactly. Never invent fields; if you need a change, propose it, update CONTRACT.md in the same commit and log it in `docs/PROGRESS.md`.
5. `fixtures/` — your `core:i18n` module must pass the shared cases.
6. `docs/PROGRESS.md` — check "Contract changes for other platforms" at the start of every session.

Then enter plan mode, write a plan for **A1 only**, and wait for my approval. After each milestone: run `./gradlew lint testDevDebugUnitTest assembleDevDebug` and show me the result, check the screens in Arabic, Turkish and English, update `docs/PROGRESS.md`, commit, and stop for review.

## Stack (ask before substituting anything)

- Kotlin 2.x, Jetpack Compose with Material 3, Navigation Compose with type-safe routes, Hilt, Coroutines/Flow, kotlinx.serialization.
- `minSdk 26`; `compileSdk` and `targetSdk` at the level Google Play currently requires (36 or higher — check and tell me).
- Gradle Kotlin DSL with a version catalog; flavors `dev` (emulators, App Check debug provider) and `prod`.
- Firebase BoM: Auth, Firestore, Functions, Messaging, Crashlytics, Analytics, App Check (Play Integrity), Remote Config. Credential Manager + Sign in with Google.
- Play services location (Fused, Geofencing), WorkManager, DataStore, Jetpack Glance, ZXing core for QR.
- Tests: JUnit, Turbine, Robolectric, Compose UI tests, Roborazzi screenshots.
- Fonts: a family with full Arabic and Turkish coverage (for example IBM Plex Sans Arabic for Arabic and Inter for Latin) via downloadable Google Fonts.

## Modules

```
android/
  app/          UI, features, widgets, tile, notifications, location, NFC
  core/model/   pure Kotlin: CONTRACT types
  core/i18n/    pure Kotlin: normalize, local splitter, units, display rule, category order (tests read /fixtures)
  core/data/    Android library: Firestore and Functions repositories, widget snapshot writer
```

## Milestones

### A1 — Scaffold
- Modules, version catalog, flavors, Hilt, theme (Material 3, brand colors, Arabic-capable typography), navigation shell, per-app language (`locales_config.xml`, `AppCompatDelegate.setApplicationLocales`), `supportsRtl`.
- Emulator wiring for the `dev` flavor (host from a dev setting, default `10.0.2.2`).
- `core:i18n` with normalize, units, local splitter and the display rule, passing every `split`-tagged case in `fixtures/parse_cases.json` for item count.
- Done when: the app launches to an empty home screen in ar/tr/en; unit tests pass.

### A2 — Sign-in, onboarding, household
- Flow: UI language → Sign in with Google → display name and item language (ar, tr, en, fil, hi, ur) → create a household or join one (code, QR scan, `/join/` App Link) → notification permission with a rationale screen first.
- Generate `deviceId` once (DataStore); write `users/{uid}/devices/{deviceId}` with the FCM token and refresh it on change.
- Members screen with roles; owner and adults can invite (link, code, QR).
- Done when: two emulator users create and join one household and see each other with the right roles.

### A3 — Lists and items, offline-first
- Lists screen; list detail grouped by category in the default order (CONTRACT §2); item rows use the display rule (ARCHITECTURE §4.3) with the original underneath when different; quantity, unit, urgent flag, "asked by" avatars from `requestedBy`.
- Actions: claim, done, couldn't find, edit, delete — each with undo. Kids' items appear in a "Requests" section for adults to approve.
- Add sheet (text): one line can hold several items → local split shows chips at once → `parseItems` refines them when online → the user confirms → items are written with `addedVia: app_text`. Ambiguous chips (confidence < 0.6) offer the alternatives and save the choice as a household alias.
- Done when: in airplane mode you can add, tick, undo and delete; back online, the server fills categories and translations within seconds and the UI updates. Write an instrumented test against the emulators for this.

### A4 — Voice add
- Mic button: `SpeechRecognizer`, on-device on API 31+ when available, language = the member's locale, live partial transcript, stop → chips.
- Cloud fallback: `MediaRecorder` OGG/Opus ≤ 15 s → `transcribeAudio`.
- Done when: a manual test script (`docs/test-voice.md`) covers Arabic, Turkish and English phrases from the fixtures, and unit tests cover transcript → chips.

### A5 — Shopping Mode
- Entry points: list "Start shopping", the arrival notification, the trip notification, `{URL_SCHEME}://shop`.
- Large rows sorted by category order, haptic tick, "couldn't find" → `missing`, screen kept on, fully offline.
- "Done" → `updateTrip(done)` → summary: bought, missing (stay on the list), "Send the rest" via the share sheet as formatted text.
- Done when: UI tests cover tick, undo, missing and finish, including offline.

### A6 — "I'm going", notifications, inline reply, Live Updates
- "I'm going" sheet: nearest five stores from `nearbyStores` (or chain chips without location), free-text store, last-call 0/5/10/15 min → `startTrip(status: heading)`.
- `FirebaseMessagingService` handling every CONTRACT §5.1 type. Channels `trip`, `last_call`, `household`, `suggestions`.
- `trip_heading`/`trip_arrived` with a `RemoteInput` reply (`item_text`) → `AddFromNotificationReceiver` → local split → write with `addedVia: notification_reply` → notification updates to "Added ✓".
- `TripNotificationManager`: ongoing trip notification for the shopper. On API 36+: `Notification.ProgressStyle` (one segment per item, progress = done), `setRequestPromotedOngoing(true)`, short critical text ("7 left"), `POST_PROMOTED_NOTIFICATIONS` in the manifest. Older versions: a standard ongoing notification. Updated from the app's listener and from `trip_update` pushes when the process is gone. Actions: Open, Done. Last-call countdown while it runs.
- Done when: Robolectric tests cover the receiver and the notification builders; a manual checklist covers a reply from the lock screen appearing on another phone within 5 s.

### A7 — Store detection
- Settings toggle "Detect stores automatically" → rationale → `ACCESS_FINE_LOCATION` → prominent disclosure → `ACCESS_BACKGROUND_LOCATION` (on Android 11+, guide the user to "Allow all the time" in Settings with clear steps).
- `StoreGeofenceManager` per ARCHITECTURE §5.2 and §5.4: nearest 90 stores as `DWELL` geofences (120 m, 120,000 ms loitering, never expire) + a 3 km `EXIT` refresh fence; re-register on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, location re-enabled and `GEOFENCE_NOT_AVAILABLE`.
- `GeofenceBroadcastReceiver` → `goAsync()` → expedited `ArrivalWorker` → rules (on, ≥ 1 open item, not muted, 3 h cooldown) → local `AUTO_ARRIVED` notification → `startTrip(status: shopping, auto: true)` → trip notification. Leaving during an auto trip → `AUTO_LEFT`.
- Settings: muted stores, "add this store" (`addStore`). Dev-only debug screen: registered fences, "simulate dwell".
- Done when: unit tests cover store selection and the rules; `docs/test-geofence.md` has an `adb emu geo fix` walkthrough that passes.

### A8 — Widgets, tile, shortcuts
- Glance `ListWidget`: 2×2 (count + next 2), 4×2 (next 5), 4×4 (list); configuration activity to pick a list; checkboxes via `actionRunCallback` (write with the SDK, then update the snapshot); + opens `QuickAddActivity` in voice mode; trip header "At Migros · 7 left".
- `WidgetSnapshotWriter` writes the CONTRACT §7 snapshot to DataStore (debounced 1 s) and calls `updateAll`. `list_changed` pushes refresh widgets when the app is not running.
- `QuickAddTileService` (Quick Settings) → transparent, voice-first `QuickAddActivity` (use the `PendingIntent` overload of `startActivityAndCollapse` on API 34+).
- Static shortcuts ("Add items", "I'm going") and one dynamic shortcut per list.
- Done when: ticking an item on the widget shows up on another device within 10 s; screenshots of every widget size in ar/tr/en.

### A9 — Fridge tags, QR, App Links
- App Links with `autoVerify` for `LINK_DOMAIN` paths `/k/`, `/join/`, `/l/`; `NDEF_DISCOVERED` filter for the same URLs. Give me the debug and release SHA-256 values for `assetlinks.json`.
- "Make a fridge tag": `createShareLink(kind: nfc)` → reader mode → write a URI record (`Ndef` or `NdefFormatable`, check `maxSize`, never lock by default, no Android Application Record) → success screen. "Show QR": ZXing → share or print.
- Tag or link handling: a member of that household → Quick Add for that list, voice ready; anyone else → in-app guest view (`resolveShareLink`).
- Done when: `docs/test-nfc.md` passes with NTAG213, 215 and 216 stickers, and link parsing has unit tests.

### A10 — Ramadan planner lite, localization, accessibility
- Planner screen behind `ramadan_planner_enabled`, visible from 1 Sha'ban to the end of Ramadan (`android.icu.util.IslamicCalendar`, Umm al-Qura): people and days → `suggestMeals(occasion: iftar)` → pick → `mealToItems` → confirm chips → write with `addedVia: meal_plan`.
- All strings in `values/`, `values-ar/`, `values-tr/` with plurals; TalkBack labels; 48 dp touch targets; font scale up to 200%; a "numbers as ١٢٣ or 123" setting.
- Roborazzi screenshots of every main screen in ar/tr/en, light and dark.
- Done when: screenshots are committed and reviewed for RTL mistakes.

### A11 — Release readiness
- R8 rules, Crashlytics mappings, App Check with Play Integrity in `prod`, Remote Config defaults, in-app account deletion, privacy policy link, OpenStreetMap attribution.
- Docs for me: `docs/play-data-safety.md`, `docs/play-background-location.md` (declaration text plus a 30-second demo video script), `docs/store-listing-android.md` (ar/tr/en).
- `./gradlew bundleProdRelease` succeeds (I will provide the upload key).
- Done when: the checklist is complete and an internal-testing build is ready to upload.

## Working rules

- The contract is law; check PROGRESS.md for contract changes at the start of every session.
- Arabic first: build and check each screen in Arabic before Turkish and English. Start/end, never left/right.
- No item, household or member names in analytics, logs or crash reports.
- Ask before adding a dependency that is not listed above.
- Stop and give me exact steps for anything that needs a console, a key or money (Firebase config files, Play Console, signing, App Check, Play Integrity).
