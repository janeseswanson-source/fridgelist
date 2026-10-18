# Start here

This kit sets up Claude Code to build FridgeList: a Firebase backend plus native Android and iOS apps that follow one shared data contract. Three prompts, three Claude Code sessions, one repo.

## What's inside

| File | Purpose |
| --- | --- |
| `CLAUDE.md` | Rules and project constants. Claude Code loads it automatically in every session. |
| `docs/PRODUCT_SPEC.md` | What to build: MVP features with acceptance criteria, the standout features, phase 2 |
| `docs/ARCHITECTURE.md` | How it fits together: backend, item pipeline, store detection, live surfaces, iOS, Android |
| `docs/CONTRACT.md` | Exact data shapes every side must match: Firestore, callables, pushes, links, widget snapshot |
| `docs/PROGRESS.md` | Shared log; each session records milestones and contract changes here |
| `fixtures/parse_cases.json` | 28 Arabic, Turkish, English and mixed test sentences for the item parser |
| `fixtures/catalog_seed.json` | 63 common grocery items with dialect synonyms, plus dialect rules (عيش, لبن, بطاطا) |
| `prompts/01-backend.md` | Paste first |
| `prompts/02-android.md` | Paste in a second session |
| `prompts/03-ios.md` | Paste in a third session, on a Mac |

## Before the first prompt (about an hour)

1. **Accounts**
   - Firebase project ×2 (dev and prod) on the Blaze plan — Cloud Functions need it.
   - Apple Developer Program ($99/year) and Google Play Console ($25 once).
   - An Anthropic API key for parsing, translation and meal ideas.
   - A domain for links, such as `links.yourbrand.com`.
   - **Google Play decision:** a new *personal* developer account must run a closed test with at least 12 testers for 14 straight days before it can publish. An *organization* account (for example under your company) is exempt but needs business verification. Decide now; either path fits the calendar below.
2. **Machines:** iOS needs a Mac with the latest Xcode. Backend and Android work anywhere.
3. **Tools:** Claude Code, Node 22, Firebase CLI, JDK 17, Android Studio; on the Mac also Xcode and XcodeGen (`brew install xcodegen`); `osmium-tool` for the Turkish store import.
4. **Repo:** unzip this kit into an empty folder, fill in the constants table in `CLAUDE.md`, then `git init` and commit.

## Run the three workstreams

1. Open Claude Code in the repo root and paste `prompts/01-backend.md`.
2. Open a second session for Android and paste `prompts/02-android.md`. A1 can start right away; A2 needs backend B3 and A3 needs B4.
3. On the Mac, open a third session and paste `prompts/03-ios.md`. Same dependencies: I2 needs B3, I3 needs B4.

Tips:
- Each prompt asks Claude to plan first and stop after every milestone. Read the plan, approve it, and ask to see the test output before approving the next milestone.
- Clear the session between milestones (`/clear`). `CLAUDE.md`, `docs/PROGRESS.md` and the contract carry everything forward.
- If one session changes the contract, the others pick it up from the "Contract changes" table in `docs/PROGRESS.md`. Tell them to check it.

## The fridge tag needs no hardware

The "magnet" is a blank NFC sticker (NTAG213, 215 or 216, well under $1 each in packs) stuck on any fridge magnet, plus a printed QR code. The app writes the link onto the sticker itself (milestones A9 and I11). Buy a pack of 10–20 for testing.

## Calendar to a pre-Ramadan launch

Ramadan 2027 is expected to start on **8 February 2027** (subject to moon sighting).

| Dates | Backend | Android | iOS |
| --- | --- | --- | --- |
| 19 Oct – 1 Nov | B1–B4 | A1 | I1 |
| 2 – 29 Nov | B5–B7 | A2–A6 | I2–I6 |
| 30 Nov – 20 Dec | B8–B9 | A7–A10 | I7–I12 |
| 21 Dec – 3 Jan | fixes | A11, internal testing | I13, TestFlight (internal) |
| 4 – 18 Jan | B10 (off by default) | Play closed test: 12+ testers, 14 days; field tests in all three countries | TestFlight external beta |
| 19 – 31 Jan | switch on `ramadan_planner_enabled` | production release | App Store release |

## When Claude Code stops to ask you

That is by design. Expect requests for: Firebase project IDs and config files, enabling Blaze billing, App Check keys, uploading the APNs key to Firebase, Apple App IDs and the App Group, signing, the SHA-256 fingerprints for Android App Links, store listings, the privacy policy, and anything that costs money.
