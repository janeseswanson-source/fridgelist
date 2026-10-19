# Shared contract

This file is the single source of truth for every data shape shared by the backend, the Android app and the iOS app. Field names, enums, payloads and links must match it exactly on all three sides.

**Changing it:** update this file in the same commit as the code, bump `CONTRACT_VERSION`, add a line to the changelog at the bottom, and list the change in `docs/PROGRESS.md` under "Contract changes for other platforms".

```
CONTRACT_VERSION = 2
```

---

## 1. Conventions

- **IDs:** Firestore auto-IDs unless stated. `uid` is the Firebase Auth uid.
- **Time in Firestore:** `Timestamp`. Clients write `serverTimestamp()` (SDK) or a `REQUEST_TIME` transform (REST).
- **Time in JSON** (callables, pushes, widget snapshots): **Unix seconds as a number**, e.g. `1760780000`. Never ISO strings, never Apple reference-date seconds.
- **Enums:** lowercase snake_case strings. Unknown values must be tolerated: map to `other` or ignore. Never crash on an unknown value.
- **Languages:** BCP-47 primary tags. UI languages: `ar`, `tr`, `en`. Item display languages: `ar`, `tr`, `en`, `fil`, `hi`, `ur`.
- **Item names:** store exactly what the user typed in `name`. Never translate in place; translations live in `names`.
- **Quantities:** `qty` is a JSON number (decimals allowed: `0.5`, `1.5`). Clients convert Eastern Arabic digits (`٠-٩`, `۰-۹`) and Turkish decimal commas (`1,5`) before writing. Dozens become pieces (`qty × 12`, unit `piece`).
- **Optional fields:** omit them. Write `null` only where a field is marked nullable.
- **Strings shown to users:** the server localizes push text to the recipient's `uiLanguage`. Clients localize everything else.

---

## 2. Enums

| Enum | Values |
| --- | --- |
| `Role` | `owner`, `adult`, `kid`, `helper` |
| `ItemStatus` | `pending` (kid request awaiting approval), `open`, `claimed`, `done`, `missing` |
| `AddedVia` | `app_text`, `app_voice`, `app_photo`, `widget`, `control`, `siri`, `qs_tile`, `notification_reply`, `nfc`, `guest_web`, `whatsapp`, `meal_plan`, `usuals` |
| `Unit` | `piece`, `kg`, `g`, `l`, `ml`, `pack`, `can`, `bottle`, `bag`, `box`, `bunch`, `tray` |
| `Category` | `produce`, `bakery`, `dairy_eggs`, `meat_poultry`, `seafood`, `pantry`, `spices_condiments`, `breakfast`, `frozen`, `beverages`, `snacks_sweets`, `baby`, `household_cleaning`, `personal_care`, `pharmacy`, `pets`, `other` |
| `CategorySource` | `auto`, `user` |
| `ListType` | `grocery`, `pharmacy`, `household`, `other` |
| `TripStatus` | `heading`, `shopping`, `done`, `cancelled` |
| `ShareScope` | `view`, `add` |
| `ShareKind` | `nfc`, `qr`, `link` |
| `Platform` | `ios`, `android`, `web` |
| `Plan` | `free`, `premium` |
| `Occasion` | `everyday`, `iftar`, `suhoor`, `guests`, `eid` |
| `Cuisine` | `levantine`, `gulf`, `turkish`, `egyptian`, `international` |
| `EventType` | `item_added`, `item_done`, `item_missing`, `item_merged`, `trip_heading`, `trip_arrived`, `trip_done`, `member_joined`, `guest_added` |

Default category display order (used until a store has a learned aisle order): `produce`, `bakery`, `dairy_eggs`, `meat_poultry`, `seafood`, `breakfast`, `pantry`, `spices_condiments`, `snacks_sweets`, `beverages`, `frozen`, `baby`, `household_cleaning`, `personal_care`, `pharmacy`, `pets`, `other`.

---

## 3. Firestore layout

"Writer" says who may write each field. `functions` means Cloud Functions only (security rules deny clients). `self` means the member whose document it is.

### 3.1 `users/{uid}`

| Field | Type | Writer | Notes |
| --- | --- | --- | --- |
| `displayName` | string (1–40) | self | |
| `uiLanguage` | `ar` \| `tr` \| `en` | self | push text language |
| `language` | item display language | self | mirrors the member doc |
| `householdIds` | string[] | functions | |
| `createdAt` | timestamp | functions | |

### 3.2 `users/{uid}/devices/{deviceId}`

`deviceId` is a UUID the app generates once and keeps in the Keychain (iOS) or DataStore (Android).

| Field | Type | Writer | Notes |
| --- | --- | --- | --- |
| `platform` | `Platform` | self | |
| `fcmToken` | string | self | refresh on every token change |
| `apnsPushToStartToken` | string? | self | iOS 17.2+, hex string |
| `appVersion` | string | self | |
| `locale` | string | self | e.g. `ar-SA` |
| `updatedAt` | timestamp | self | |

### 3.3 `households/{hid}`

| Field | Type | Writer | Notes |
| --- | --- | --- | --- |
| `name` | string (1–40) | owner (update), functions (create) | |
| `ownerUid` | string | functions | |
| `defaultLanguage` | `ar` \| `tr` \| `en` | owner | |
| `memberLanguages` | string[] | functions | union of members' `language` |
| `plan` | `Plan` | functions | |
| `createdAt` | timestamp | functions | |

### 3.4 `households/{hid}/members/{uid}`

| Field | Type | Writer | Notes |
| --- | --- | --- | --- |
| `role` | `Role` | functions | |
| `displayName` | string (1–40) | self | |
| `language` | item display language | self | |
| `notify` | map | self | `{ tripHeading: bool, tripArrived: bool, itemMissing: bool, usuals: bool }` — defaults `true, false, true, false` |
| `presence` | map? | functions | `{ tripId, status: TripStatus, storeName, since: timestamp }`, removed when the trip ends |
| `joinedAt` | timestamp | functions | |

### 3.5 `households/{hid}/lists/{lid}`

| Field | Type | Writer | Notes |
| --- | --- | --- | --- |
| `name` | string (1–40) | owner, adult | |
| `type` | `ListType` | owner, adult | |
| `emoji` | string? | owner, adult | one grapheme |
| `order` | number | owner, adult | |
| `archived` | bool | owner, adult | |
| `createdAt` | timestamp | creator | server time |

### 3.6 `households/{hid}/lists/{lid}/items/{iid}`

| Field | Type | Writer | Notes |
| --- | --- | --- | --- |
| `name` | string (1–80) | creator | exactly as typed |
| `lang` | string? | functions | detected language of `name` |
| `names` | map<lang, string>? | functions | one entry per `memberLanguages` value |
| `canonicalId` | string? | functions | `catalog` id |
| `category` | `Category` | functions, or any member when `categorySource = user` | |
| `categorySource` | `CategorySource` | functions / member | server never overwrites `user` |
| `qty` | number? (0 < qty ≤ 999) | members | |
| `unit` | `Unit`? | members | |
| `notes` | string? (≤ 120) | members | |
| `photoUrl` | string? | members | phase 2 |
| `urgent` | bool | members | default `false` |
| `status` | `ItemStatus` | members (see transitions) | |
| `addedBy` | string | creator | the creator's uid; functions use `guest:<token>` or `wa:<uid>` |
| `addedVia` | `AddedVia` | creator | |
| `requestedBy` | string[] | functions | uids that asked for it (set on merge) |
| `claimedBy` | string? | members | uid |
| `doneBy` | string? | members | uid |
| `doneAt` | timestamp? | members | server time |
| `doneTripId` | string? | members | |
| `sortKey` | number? | members | manual order inside a category |
| `recipeRef` | string? | functions | meal suggestion id |
| `createdAt` | timestamp | creator | server time |
| `updatedAt` | timestamp | everyone | server time |

**Status transitions clients may make** (rules enforce them):

| From | To | Who |
| --- | --- | --- |
| *(create)* | `open` | owner, adult, helper |
| *(create)* | `pending` | kid (forced) |
| `pending` | `open`, or delete | owner, adult |
| `open` | `claimed` (sets `claimedBy` = self), `done`, `missing` | any member |
| `claimed` | `open` (claimer or adult), `done`, `missing` | any member |
| `done` | `open` (undo) | any member |
| `missing` | `open`, `done` | any member |

When a trip ends, the server moves that trip's `missing` items back to `open`.

### 3.7 `households/{hid}/trips/{tripId}` — functions only

| Field | Type | Notes |
| --- | --- | --- |
| `shopperUid` | string | |
| `shopperName` | string | |
| `shopperPlatform` | `Platform` | |
| `shopperDeviceId` | string | |
| `status` | `TripStatus` | |
| `storeId` | string? | |
| `storeName` | string | |
| `chainId` | string? | |
| `auto` | bool | started by store detection |
| `listIds` | string[] | |
| `lastCallUntil` | timestamp? | |
| `startedAt` | timestamp | |
| `arrivedAt` | timestamp? | |
| `endedAt` | timestamp? | |
| `liveActivityToken` | string? | iOS activity push token (hex) |
| `counts` | map | `{ total, done, missing }` |
| `lastLiveUpdateAt` | timestamp? | throttle marker |

### 3.8 `households/{hid}/events/{eventId}` — functions only

`type: EventType`, `actorUid`, `actorName`, `itemId?`, `itemName?`, `tripId?`, `via?: AddedVia`, `at: timestamp`, `expireAt: timestamp` (TTL policy, 30 days).

### 3.9 `households/{hid}/purchases/{pid}` — functions only

`canonicalId?`, `name`, `qty?`, `unit?`, `storeId?`, `chainId?`, `tripId?`, `by`, `at`.

### 3.10 `households/{hid}/mealSuggestions/{mealId}` — functions only

`title: map<lang, string>`, `description: map<lang, string>`, `servings: number`, `occasion: Occasion`, `cuisine: Cuisine`, `ingredients: Array<{ canonicalId?, name, qty?, unit? }>` (per `servings`), `createdAt`, `expireAt` (TTL 7 days).

### 3.11 `shareLinks/{token}` — functions only

`token`: 24 random URL-safe characters. Fields: `householdId`, `listId`, `scope: ShareScope`, `kind: ShareKind`, `createdBy`, `createdAt`, `expiresAt?`, `revoked: bool`, `guestAddsWindowStart: timestamp`, `guestAddsInWindow: number`.

### 3.12 `invites/{code}` — functions only

`code`: 8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`. Fields: `householdId`, `role` (`adult` \| `kid` \| `helper`), `createdBy`, `expiresAt` (7 days), `maxUses` (10), `uses`.

### 3.13 `stores/{storeId}` — functions and scripts only, readable by signed-in users

`storeId`: `osm_<node|way>_<id>` or `user_<autoId>`. Fields: `name`, `brand?`, `chainId` (see ARCHITECTURE §5.1; `other` when unknown), `lat`, `lng`, `geohash` (precision 9), `country` (`TR` \| `SA` \| `AE`), `source` (`osm` \| `user`), `verified: bool`, `addedByHousehold?`, `updatedAt`.

### 3.14 `catalog/{canonicalId}` — functions and scripts only, readable by signed-in users

`names: { ar, tr, en, fil?, hi?, ur? }`, `synonyms: { ar: string[], tr: string[], en: string[] }`, `category: Category`, `defaultUnit?: Unit`. Seeded from `fixtures/catalog_seed.json`.

### 3.15 `catalogCandidates/{hash}` — functions only

LLM classification cache for names not in the catalog: `normalized`, `lang`, `category`, `names`, `hits`, `createdAt`.

### 3.16 `households/{hid}/aliases/{aliasId}` — members

A household's own meaning for an ambiguous word (for example `عيش` → `rice`), saved the first time the app asks. `aliasId`: the first 32 hex characters of the SHA-256 of the normalized text.

| Field | Type | Writer |
| --- | --- | --- |
| `text` | string (normalized) | members |
| `canonicalId` | string | members |
| `createdBy` | uid | members |
| `createdAt` | timestamp | members (server time) |

`parseItems` and `onItemCreated` check household aliases first, then dialect rules (`fixtures/catalog_seed.json`), then the catalog, then the LLM.

---

## 4. Callable functions

All callables require Firebase Auth and App Check unless marked **public**. Public callables still require App Check. Request and response bodies are JSON. Errors use the codes in §8.

`ParsedItem` (used by several calls):

```json
{
  "name": "بندورة",
  "canonicalId": "tomato",
  "qty": 2,
  "unit": "kg",
  "notes": "",
  "category": "produce",
  "names": { "ar": "بندورة", "tr": "Domates", "en": "Tomatoes" },
  "confidence": 0.93
}
```

| Function | Request | Response | Notes |
| --- | --- | --- | --- |
| `createHousehold` | `{ name, defaultLanguage, displayName, language, uiLanguage }` | `{ householdId, listId }` | creates a default grocery list named in `defaultLanguage` |
| `createInvite` | `{ householdId, role }` | `{ code, url, expiresAt }` | owner or adult |
| `joinHousehold` | `{ code, displayName, language }` | `{ householdId, role }` | |
| `parseItems` | `{ householdId, text (≤ 500), localeHints: string[] }` | `{ items: ParsedItem[], detectedLang, source: "catalog" \| "llm" \| "mixed" }` | no writes; 30 calls/min/user |
| `startTrip` | `{ householdId, status: "heading" \| "shopping", auto, storeId?, storeName?, lastCallMinutes?: 0 \| 5 \| 10 \| 15, listIds?, platform, deviceId }` | `{ tripId }` | one active trip per shopper; a new one ends the old |
| `updateTrip` | `{ householdId, tripId, status: "shopping" \| "done" \| "cancelled" }` | `{ ok: true, summary?: { done, missing } }` | shopper only |
| `registerLiveActivityToken` | `{ householdId, tripId, token }` | `{ ok: true }` | iOS only |
| `nearbyStores` | `{ lat, lng, radiusM? (default 5000, max 10000), limit? (default 60, max 100) }` | `{ stores: [{ storeId, name, chainId, lat, lng, distanceM }] }` | sorted by distance |
| `addStore` | `{ name, lat, lng, chainId? }` | `{ storeId }` | `verified: false` |
| `createShareLink` | `{ householdId, listId, scope, kind, expiresInDays? }` | `{ token, url }` | owner or adult |
| `revokeShareLink` | `{ householdId, token }` | `{ ok: true }` | owner or adult |
| `resolveShareLink` **public** | `{ token, lang? }` | `{ householdName, listName, scope, items: [{ id, title, subtitle?, category, status }] }` | only `open` and `claimed` items; titles in `lang`, else household default |
| `guestAddItem` **public** | `{ token, text (≤ 200), guestName? (≤ 30) }` | `{ added }` | scope `add` only; 20 adds/hour/token; strips URLs |
| `suggestMeals` | `{ householdId, people (1–30), occasion, cuisines?, days? (1–7), exclude?: string[], lang }` | `{ meals: [{ mealId, title, description, servings, cuisine, tags }] }` | 10 calls/hour/household |
| `mealToItems` | `{ householdId, listId, mealIds, people, lang }` | `{ items: ParsedItem[], skipped: [{ name, reason: "on_list" \| "recently_bought" }] }` | no writes; client confirms, then writes with `addedVia: meal_plan` |
| `transcribeAudio` | `{ audioBase64 (≤ 1 MB), mimeType: "audio/ogg" \| "audio/mp4" \| "audio/wav", localeHints }` | `{ text, locale, confidence }` | ≤ 15 s audio |
| `exportMyData` | `{}` | `{ url }` | signed URL, valid 24 h |
| `deleteMyAccount` | `{ transferOwnershipTo? }` | `{ ok: true }` | deletes the household if the user is its last member |
| `linkWhatsApp` | `{ householdId }` | `{ code, botNumber }` | phase 2 |

**Triggers:** `onItemCreated`, `onItemUpdated`, `onMemberWritten`, `onTripWritten`.
**Scheduled:** `closeStaleTrips` (every 30 min), `cleanupLinks` (daily), `computeUsuals` (daily, phase 2), `learnAisleOrder` (nightly, phase 2).
**HTTP:** `whatsappWebhook` (phase 2).

---

## 5. Push payloads

### 5.1 Notification types (FCM)

Server text is localized to the recipient's `uiLanguage`. Every push carries `data.type` and `data.householdId`.

| `data.type` | Sent to | Visible text (example) | Extra `data` keys | iOS category | Android channel |
| --- | --- | --- | --- | --- | --- |
| `trip_heading` | other members with `notify.tripHeading` | "Ali is heading to Migros" / "Add anything in the next 10 min" | `tripId`, `lastCallUntil`, `listId` | `TRIP_HEADING` | `last_call` |
| `trip_arrived` | other members with `notify.tripArrived` | "Ali is at Migros" | `tripId`, `listId` | `TRIP_ARRIVED` | `household` |
| `item_missing` | the item's `addedBy` with `notify.itemMissing` | "Ali couldn't find labneh" / "It stays on the list" | `itemId`, `listId`, `tripId` | `ITEM_MISSING` | `household` |
| `trip_update` | the Android shopper's device, data-only | none | see §5.3 | — | `trip` |
| `list_changed` | other members' devices, data-only (iOS: `content-available`), at most 1 per device per minute | none | `listId` | — | — |

**Android delivery (v2):** to Android devices, `trip_heading`, `trip_arrived` and `item_missing` are sent **data-only** (no FCM `notification` block, Android priority `high`) with two extra data keys, `title` and `body`, holding the server-localized visible text (in the recipient's `uiLanguage`). The app builds the notification itself, which is the only way to attach the inline reply on Android. iOS delivery is unchanged (`aps.alert` + category).

**Inline reply:** `TRIP_HEADING` and `TRIP_ARRIVED` carry a text-input action with identifier `ADD_ITEM`. On Android the `RemoteInput` result key is `item_text`. The reply is split locally and written as items with `addedVia: notification_reply`.

**Local notifications** (created on the device, never pushed):

| Identifier | When | Actions |
| --- | --- | --- |
| `AUTO_ARRIVED` | store detection fired | `OPEN_LIST`, `NOT_SHOPPING` |
| `AUTO_LEFT` | left the store while an auto trip is active | `DONE_SHOPPING`, `STILL_HERE` |

### 5.2 iOS Live Activity

- `ActivityAttributes` type name: **`ShoppingAttributes`** (must match `attributes-type` exactly).
- Static attributes:

```json
{ "tripId": "t_123", "householdId": "h_1", "storeName": "Migros", "chainId": "migros" }
```

- `ContentState`:

```json
{
  "status": "shopping",
  "remaining": 7,
  "done": 5,
  "nextItems": ["حليب", "خبز عربي", "بيض"],
  "lastCallUntil": 1760780600,
  "latestAddBy": "أمي",
  "latestAddName": "لبنة"
}
```

`nextItems`: up to 4 open items not claimed by someone else, in the shopper's display language, in aisle order. `lastCallUntil`, `latestAddBy` and `latestAddName` are optional.

> **Gotcha:** declare `lastCallUntil` as `Double?` (Unix seconds) in Swift and convert with `Date(timeIntervalSince1970:)`. Do not use `Date` inside `ContentState`: ActivityKit decodes pushed JSON with Apple reference-date seconds, which silently shifts times by 31 years.

- Sent through **FCM HTTP v1** with `apns.live_activity_token` and `apns.payload`. Start uses the device's `apnsPushToStartToken` (iOS 17.2+); update and end use the trip's `liveActivityToken`. Payload `aps` fields:

```json
{
  "aps": {
    "timestamp": 1760780000,
    "event": "start",
    "content-state": { "status": "shopping", "remaining": 9, "done": 0, "nextItems": ["حليب", "خبز", "بيض"] },
    "attributes-type": "ShoppingAttributes",
    "attributes": { "tripId": "t_123", "householdId": "h_1", "storeName": "Migros", "chainId": "migros" },
    "alert": { "title": "At Migros", "body": "9 items on your list" }
  }
}
```

`event` is `start`, `update` or `end`. `attributes-type`, `attributes` and `alert` are only for `start`. For `end`, add `"dismissal-date"` (Unix seconds, 15 minutes later). Header `apns-priority`: `10` for start and end, `5` for routine updates.

### 5.3 Android `trip_update` (data-only FCM, all values are strings)

```json
{
  "type": "trip_update",
  "householdId": "h_1",
  "tripId": "t_123",
  "status": "shopping",
  "storeName": "Migros",
  "remaining": "7",
  "done": "5",
  "nextItems": "[\"حليب\",\"خبز عربي\",\"بيض\"]",
  "lastCallUntil": "1760780600",
  "latestAddBy": "أمي",
  "latestAddName": "لبنة"
}
```

`nextItems` is a JSON-encoded array. Optional keys may be absent. Send with Android priority `high`.

---

## 6. Links

| Link | App behaviour | Web behaviour |
| --- | --- | --- |
| `https://{LINK_DOMAIN}/k/{token}` (fridge tag, QR) | member of that household: open Quick Add for that list, voice ready; anyone else: in-app guest view | guest page: view, copy, share, add if scope is `add` |
| `https://{LINK_DOMAIN}/join/{code}` | join flow with the code filled in | instructions + store badges |
| `https://{LINK_DOMAIN}/l/{householdId}/{listId}` | open that list (members only) | store badges |
| `{URL_SCHEME}://add?listId={lid}&mode=voice\|text` | internal: widgets, controls, Quick Settings tile, shortcuts | — |
| `{URL_SCHEME}://shop?tripId={tid}` | internal: Live Activity and Live Update taps | — |

Hosted files on `LINK_DOMAIN`: `/.well-known/apple-app-site-association` (paths `/k/*`, `/join/*`, `/l/*`) and `/.well-known/assetlinks.json` (debug and release SHA-256 fingerprints).

---

## 7. Widget snapshot (local file, same shape on both platforms)

The app writes this after every relevant change (debounced 1 s). Widgets, controls and intents read it. iOS: App Group container `snapshot.json`. Android: DataStore key `widget_snapshot`.

```json
{
  "v": 1,
  "updatedAt": 1760780000,
  "viewerLang": "ar",
  "householdId": "h_1",
  "lists": [
    {
      "id": "l_1",
      "name": "البقالة",
      "openCount": 9,
      "items": [
        { "id": "i_1", "title": "حليب", "subtitle": "2 L", "category": "dairy_eggs", "status": "open", "urgent": false }
      ]
    }
  ],
  "activeTrip": { "id": "t_123", "storeName": "Migros", "remaining": 7, "done": 5, "lastCallUntil": 1760780600 }
}
```

Keep at most 12 items per list, in display order. `activeTrip` is present only while this device's user is shopping.

---

## 8. Errors (Firebase HttpsError codes)

| Code | Meaning | Client behaviour |
| --- | --- | --- |
| `unauthenticated` | no or expired auth | refresh token, else sign-in |
| `permission-denied` | not a member or wrong role | message + refresh membership |
| `invalid-argument` | validation failed | show field error |
| `not-found` | bad token, code or id | "This link no longer works" |
| `failed-precondition` | e.g. invite expired, trip already ended | message |
| `resource-exhausted` | rate limit | retry later; parse falls back to the local splitter |
| `unavailable` | LLM or STT down | local splitter or on-device speech; never block the add |

---

## 9. Analytics events (no item, household or member names, ever)

`onboarding_completed {ui_lang, item_lang}`, `household_created`, `household_joined {method: code|qr|nfc|link}`, `item_added {via, count, parsed: bool}`, `parse_corrected {fields}`, `trip_started {auto, status}`, `trip_finished {done, missing, minutes}`, `last_call_add {via}`, `widget_toggle`, `control_used {kind}`, `nfc_tag_written`, `live_activity_started {auto}`, `store_detect_dismissed`, `meal_plan_used {occasion, people}`, `guest_item_added` (web).

---

## 10. Remote Config flags

| Key | Default | Purpose |
| --- | --- | --- |
| `store_detection_enabled` | `true` | kill switch |
| `ramadan_planner_enabled` | `false` | on from Jan 2027 |
| `meal_planner_enabled` | `false` | everyday meal ideas |
| `whatsapp_enabled` | `false` | phase 2 |
| `speech_analyzer_enabled` | `false` | iOS 26+ speech API |
| `max_last_call_minutes` | `15` | |

---

## 11. Changelog

- **v1 (2026-10-18):** initial contract.
- **v2 (2026-10-19):** §5.1 Android delivery: `trip_heading`, `trip_arrived`, `item_missing` go to Android as data-only messages with `title` and `body` data keys (server-localized), so the app can build the notification with the inline reply. Backend must change; iOS unaffected.
