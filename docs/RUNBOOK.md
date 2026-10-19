# Runbook — FridgeList backend

How to run, test and (later) deploy the Firebase backend in `backend/`. Sections marked *(B9)* are completed in milestone B9.

## 1. Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| Node.js | 22 | Functions run on Node 22 |
| Java | 21 | The Firestore, Pub/Sub and Storage emulators need a JDK |
| Firebase CLI | 15.x | `npm install -g firebase-tools` |

On Windows, if `java` on the PATH is older than 21, point `JAVA_HOME` at a JDK 21 before starting the emulators.

## 2. Local development (Emulator Suite)

```sh
cd backend
npm install                   # root: seed script tooling
npm --prefix functions install
npm run emulators             # builds functions, starts all emulators, seeds demo data
```

- Project: `demo-fridgelist`. The `demo-` prefix means the emulators never touch a real Google Cloud project.
- Emulator UI: http://127.0.0.1:4000
- Ports: Auth 9099, Firestore 8080, Functions 5001, Hosting 5000, Storage 9199, Pub/Sub 8085, Hub 4400.
- Functions are served at `http://127.0.0.1:5001/demo-fridgelist/me-central2/<name>`. If you change a function's region or options, restart the emulators (hot reload keeps the old registration). The emulator reloads them when `functions/lib` changes: run `npm --prefix functions run build` (or `npx tsc -w -p functions/tsconfig.build.json`) while it runs.
- Re-seed at any time with `npm run seed` while the emulators run. The seed uses fixed IDs, so it overwrites rather than duplicates.

### Seeded data

| What | Details |
| --- | --- |
| Household | `seed-home` "بيت العائلة", default language `ar` |
| Members | `seed-owner` Mama (owner, items in `ar`), `seed-adult` Ayşe (adult, `tr`), `seed-helper` Maria (helper, `fil`) |
| Lists | `groceries` "البقالة" (13 items, mixed `ar`/`tr`/`fil`/`en`, all statuses except `pending`), `pharmacy` "الصيدلية" (2 items) |
| Stores | 30 synthetic stores (`user_seed_*`): 10 around Kadıköy, 10 around Olaya, 10 around Dubai Marina, with chain IDs from ARCHITECTURE §5.1 and precision-9 geohashes |

### Pointing the apps at the emulators

- **Android dev build:** uses `10.0.2.2` from the Android emulator. On a physical phone, set the dev machine's LAN IP in Settings → For developers. Sign in with the "Test user" box: **Mama**, **Ayse** or **Maria** sign straight into the seeded household. Any other name creates a new user.
- **iOS simulator:** `localhost` (I1).

The emulators enforce the real `firestore.rules` (B2) and reload them when the file changes. A dev app signed in as Mama, Ayse or Maria sees the seeded household; any other test user sees nothing until they create or join a household (B3).

### Checks (CLAUDE.md)

```sh
cd backend/functions && npm ci && npm run lint && npm run typecheck && npm test
cd backend && npm run typecheck   # seed and launcher scripts
```

`npm test` = `test:unit` (vitest, no emulator) + `test:emulator` (security rules in `test/rules/` and callable/trigger integration tests in `test/integration/`). `test:emulator` starts its own Firestore emulator on port **8180** (`firebase.rules-test.json`, project `demo-fridgelist-rules`) with `firebase emulators:exec`, so it runs alongside `npm run emulators` and never touches the seeded data. It needs Java 21 and the Firebase CLI.

### Security rules (B2)

- `backend/firestore.rules` implements CONTRACT §3 (Writer columns, §3.6 transitions) and ARCHITECTURE §3.3.
- Tests: `functions/test/rules/`.
  - `items-transitions.test.ts` checks every from → to status pair for every role, as claimer and not, against an independent copy of the §3.6 table.
  - The other files cover every collection: members vs outsiders, field whitelists, server-only fields, validation and functions-only collections.
- When the contract changes a writer or a transition, change the rules and these tests in the same commit.
- Android's `OfflineItemsTest` (instrumented) signs in as the seeded owner and runs the app's real repository code against these rules on the dev emulators.

Health check against a running emulator:

```sh
curl -s -X POST -H "Content-Type: application/json" -d '{"data":{}}' \
  http://127.0.0.1:5001/demo-fridgelist/me-central2/health
# {"result":{"ok":true,"region":"me-central2","contractVersion":3}}
```

### Item pipeline (B4)

- **Catalog:** `npm run seed:catalog` loads `fixtures/catalog_seed.json` into `catalog/` (idempotent; `npm run emulators` runs it). For a real project: `npm run seed:catalog -- --live <projectId>`, only when the owner asks.
  - The functions bundle the same file: `npm run fixtures` copies it to `functions/src/generated/` (git-ignored) before build, typecheck and tests.
- **Shared parsing:** `functions/src/lib/{normalize,numbers,units,catalog,localSplit}.ts` are line-by-line ports of the Android reference and must reproduce `fixtures/split_cases.json` exactly (`test/split-fixtures.test.ts`).
  - When behaviour changes, change Android first, regenerate with `./gradlew :core:i18n:test -PwriteFixtures=true`, then make the TypeScript (and Swift) match.
- **LLM:**
  - The key is the `ANTHROPIC_API_KEY` secret (`defineSecret`); models come from the params `LLM_MODEL_FAST` and `LLM_MODEL_SMART`.
  - Without a key everything works on the catalog and fallbacks (category `other`, no machine translation).
  - Locally, put the key in `functions/.secret.local` (git-ignored; `npm run emulators` creates it empty). With it empty, the emulator logs "Unable to access secret ... Secret Manager" on each call; that warning is harmless.
- **Golden test:** `test/golden.test.ts` scores `fixtures/parse_cases.json` and writes `test/golden-report.md`. It replays `test/recordings/` offline. To record real responses once (costs a few cents):
  ```sh
  cd backend/functions
  RECORD=1 ANTHROPIC_API_KEY=sk-ant-... npx vitest run test/golden.test.ts
  ```
  Commit the new files in `test/recordings/`. The >= 90% gate applies once recordings exist.
- **Speech-to-Text (`transcribeAudio`):** uses Google Cloud Speech-to-Text v2 in the functions' own project.
  - **Owner action** before it works on a real project: enable the "Cloud Speech-to-Text API" in Google Cloud Console > APIs & Services for the dev project, with billing on.
  - The emulator has no STT, so it returns `unavailable` locally, and the apps show their offline message.
- **Rate limits:** counters in `rateLimits/{bucket}:{uid}` (functions only; the rules close it). Limits: `parseItems` 30/min/user, `transcribeAudio` 20/hour/user.

### Stores (B5)

- **Callables:**
  - `nearbyStores` (geohash ranges + exact distance, nearest first, radius 5 km default/10 km max, limit 60/100). The caller's location is used for that one answer only: never stored or logged.
  - `addStore`: `user_<id>`, unverified, visible only to the adder's household; the same name within 50 m returns the existing store; 20 per day per user.
- **Import** (`npm run import:stores`): dry run by default; it prints counts per chain.
  ```sh
  npm run import:stores -- --country AE                 # dry run (public Overpass API)
  npm run import:stores -- --country SA --write         # into the running emulators
  npm run import:stores -- --country AE --write --live <projectId> --prune   # real project: owner only
  ```
  - Overpass sometimes answers 429/502/503/504 under load; the script waits and retries 4 times.
  - Writes are idempotent (`osm_<node|way>_<id>`), in batches of 400. `--prune` removes this country's `osm_` stores that disappeared from OSM.
  - Re-run monthly.
- **Turkey** is too large for the public Overpass API. Use Geofabrik and osmium (`osmium-tool`, installable with conda, apt or brew):
  ```sh
  curl -LO https://download.geofabrik.de/europe/turkey-latest.osm.pbf        # ~600 MB
  osmium tags-filter turkey-latest.osm.pbf nw/shop=supermarket,convenience,greengrocer,butcher,bakery -o tr-shops.osm.pbf
  osmium export tr-shops.osm.pbf --attributes type,id -f geojsonseq -o tr-shops.geojsonseq
  npm run import:stores -- --country TR --geojson tr-shops.geojsonseq        # dry run first
  ```
- Dry runs on 2026-10-19:
  - UAE: 4,984 shops, 4,478 named. Carrefour 90, Spinneys 69, LuLu 63, Viva 32, Al Maya 28, Nesto 23, Union Coop 18, Choithrams 17, Waitrose 11, other 4,126.
  - Saudi Arabia: 3,207 shops, 2,650 named. Panda 119, Othaim 88, Tamimi 44, LuLu 31, Danube 30, Carrefour 15, Nesto 6, other 2,317.
- **Attribution:** "Store data © OpenStreetMap contributors" (ODbL), shown in Settings and on the guest page; see `docs/ATTRIBUTION.md`.

## 3. Projects and regions

- `.firebaserc` aliases: `dev` → `fridgelist-dev-TODO`, `prod` → `fridgelist-prod-TODO`. These are placeholders until the owner creates the projects.
- Firestore location `me-central2`. Functions region `me-central2`, with the CLAUDE.md fallback order `me-central1`, then `europe-west1`. **Verify at the first dev deploy** that 2nd-gen functions deploy to `me-central2`; if not, change `REGION` in `functions/src/config.ts` and the apps' `FUNCTIONS_REGION` together.

## 4. Deploy

- **Never deploy to prod from a session.** Deploy to dev only when the owner writes "deploy dev".
- `npm run deploy:dev` (from `backend/`) runs `firebase deploy --project dev`.
- Rollback, secret rotation, budget alerts *(B9)*.

## 5. Function code conventions

- `src/setup.ts` must stay the **first import** of `src/index.ts`: it initializes the Admin SDK and sets the region before any function is defined. `test/regions.test.ts` fails if any export is not in `REGION`.
- Each callable keeps its logic in a `*Core(db, uid, data, now?)` function that the integration tests call directly against the emulator; the `onCall` wrapper only adds auth.

## 6. Privacy in logs

Functions log only through `functions/src/lib/log.ts`. It accepts IDs, codes, counts and flags; keys that look like user text (`name`, `text`, `title`, `body`, `notes`, `email`) are redacted. `console.*` is a lint error (CLAUDE.md rule 4).
