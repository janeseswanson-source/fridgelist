# Security probes (dev emulators only)

Run against `npm run emulators` (they use the demo project and unsigned emulator tokens; they create a QA kid and a stranger household):

```sh
node scripts/qa/authz.mjs              # every callable × anonymous / stranger / kid / helper / adult / owner × hostile input
node scripts/qa/rules.mjs              # Firestore rules through the REST API, as a phone would
node scripts/qa/rules-server-time.mjs  # kid and approver paths with real server timestamps
```

Each prints one line per check and exits non-zero when a check fails. Last run 2026-10-20: 63/63, 45/45, 8/8 (the approve write can return a one-off 409 when it races the enrichment trigger; the SDK retries those).
