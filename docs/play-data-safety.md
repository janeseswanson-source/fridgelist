# Google Play Data safety form (Android, A11)

How to answer Play Console › App content › **Data safety** for FridgeList as built (2026-10-19). Check it again before each release that adds data flows (for example WhatsApp or analytics).

## Overview questions

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (Firebase over TLS) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: in the app (Settings › Delete my account) and at `PRIVACY_POLICY_URL` (to add) |
| Shared with third parties? | **No** for every type below. Google Cloud/Firebase act as our processors, which Play doesn't count as sharing. |

## Data types

| Category › type | Collected | Optional | Processed ephemerally | Purposes | Why |
| --- | --- | --- | --- | --- | --- |
| Personal info › **Name** | Yes | No (needed to use the app) | No | App functionality, Account management | The display name the household sees |
| Personal info › **Email address** | Yes | No | No | Account management | Sign in with Google (Firebase Auth) |
| Location › **Approximate location** | Yes | Yes | **Yes** | App functionality | Rounded to about 1 km, sent only to find nearby stores ("I'm going", store detection); not logged or stored. The precise location never leaves the phone. |
| Location › Precise location | **No** | | | | Used only on the device (geofences) |
| Location (derived) | Covered by "Approximate" | | | | A trip records the store's name and ID, not the user's position |
| Audio › **Voice or sound recordings** | Yes | Yes | **Yes** | App functionality | Only when on-device speech isn't available: up to 15 s are sent to speech-to-text and not stored |
| App activity › **Other user-generated content** | Yes | No | No | App functionality | Shopping lists, items, list names, household name |
| App info and performance › **Crash logs** | Yes (prod) | No | No | Analytics (app stability) | Firebase Crashlytics; never includes item, member or household names (CLAUDE.md rule 4) |
| App info and performance › Diagnostics | No | | | | |
| Device or other IDs › **Device or other IDs** | Yes | No | No | App functionality | A per-install ID and the push token for notifications |
| Contacts, Calendar, Photos, Files, Health, Financial, Messages, Web browsing | No | | | | |
| App activity › App interactions, In-app search | No | | | | No analytics SDK in this version |

## Notes for later versions

- **Analytics** (CONTRACT §9 events): if added, declare App activity › App interactions (Analytics), and keep the rule that events carry no names.
- **WhatsApp** (phase 2): phone numbers are stored only as hashes; declare Personal info › Phone number (collected, App functionality) when it is turned on.
- **Photos** (`app_photo`, phase 2): declare Photos when photo items ship.
