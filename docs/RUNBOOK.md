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
- Functions are served at `http://127.0.0.1:5001/demo-fridgelist/me-central2/<name>`. The emulator reloads them when `functions/lib` changes: run `npm --prefix functions run build` (or `npx tsc -w -p functions/tsconfig.build.json`) while it runs.
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

`npm test` = `test:unit` (vitest, no emulator) + `test:rules`. `test:rules` starts its own Firestore emulator on port **8180** (`firebase.rules-test.json`, project `demo-fridgelist-rules`) with `firebase emulators:exec`, so it runs alongside `npm run emulators` and never touches the seeded data. It needs Java 21 and the Firebase CLI.

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

## 3. Projects and regions

- `.firebaserc` aliases: `dev` → `fridgelist-dev-TODO`, `prod` → `fridgelist-prod-TODO`. These are placeholders until the owner creates the projects.
- Firestore location `me-central2`. Functions region `me-central2`, with the CLAUDE.md fallback order `me-central1`, then `europe-west1`. **Verify at the first dev deploy** that 2nd-gen functions deploy to `me-central2`; if not, change `REGION` in `functions/src/config.ts` and the apps' `FUNCTIONS_REGION` together.

## 4. Deploy

- **Never deploy to prod from a session.** Deploy to dev only when the owner writes "deploy dev".
- `npm run deploy:dev` (from `backend/`) runs `firebase deploy --project dev`.
- Rollback, secret rotation, budget alerts *(B9)*.

## 5. Privacy in logs

Functions log only through `functions/src/lib/log.ts`. It accepts IDs, codes, counts and flags; keys that look like user text (`name`, `text`, `title`, `body`, `notes`, `email`) are redacted. `console.*` is a lint error (CLAUDE.md rule 4).
