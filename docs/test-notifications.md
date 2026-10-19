# Notifications, inline reply and Live Updates: manual checklist (Android)

Prompt A6: "a manual checklist covers a reply from the lock screen appearing on another phone within 5 s". Needs the backend (B6 `startTrip`, pushes per CONTRACT §5 **v2**) and two signed-in phones in one household. Until then, the dev-only push simulator below exercises everything on one emulator.

## Phones

- **Shopper**: any Android phone. For the Live Update use **Android 16 QPR1 or newer** (the first Android 16 release has the API but does not promote notifications; the `android-36.1` emulator image does).
- **Member**: a second phone (Android or iPhone) in the same household, notifications allowed, `notify.tripHeading` on (the default).

## Cross-phone checklist

| # | Step | Expect |
| --- | --- | --- |
| 1 | Shopper: list → **I'm going** → pick a nearby store (or a chain chip) → last call 10 min → **Let everyone know** | Shopper: ongoing "Heading to Migros" with a 10:00 countdown and "I'm at the store". Member: "*Name* is heading to Migros / Add anything in the next 10 min" on the `last_call` channel (heads-up), with a countdown |
| 2 | Member: lock the phone. On the lock screen, expand the notification → **Add to the list** → type `2 süt ve labne` → send | Within a second the notification says "Added ✓". Within **5 s** the shopper's list shows süt ×2 and labne with "asked by *member*" and `addedVia: notification_reply` |
| 3 | Shopper: tap **I'm at the store** | Shopping Mode opens; the trip becomes `shopping`; members get "*Name* is at Migros" (if `notify.tripArrived` is on) |
| 4 | Shopper: tick 3 items | The ongoing notification shows "3 of N in the cart", the next items, and on Android 16 QPR1+ a status-bar chip "*N* left" and a progress bar |
| 5 | Shopper: lock the phone, tap **Done** on the lock-screen card | Trip ends (summary on next open); members' "couldn't find" notifications for items marked missing |
| 6 | Shopper offline at step 1 | "Telling the household needs the internet…"; Start shopping still works |
| 7 | Member's phone in Arabic, shopper's in Turkish | Each sees the push text in their own language (server-localized `title`/`body`) |

Pass when step 2 lands on the shopper's phone within 5 s, both directions (Android ↔ Android, and Android ↔ iPhone once the iOS app exists).

## One-emulator simulation (dev builds only)

The dev flavor has `DebugPushReceiver`, guarded by `android.permission.DUMP` (only `adb shell` holds it). It feeds a CONTRACT §5 payload to the same `PushRouter` FCM uses. Quote multi-word values inside the shell command:

```sh
PKG=com.example.fridgelist.dev
# Member side: trip_heading with inline reply (contract v2 title/body)
adb shell "am broadcast -a com.fridgelist.app.debug.PUSH -n $PKG/com.fridgelist.app.debug.DebugPushReceiver \
  --es type trip_heading --es householdId h-seed --es tripId t1 --es listId l1 \
  --es lastCallUntil $(( $(date +%s) + 600 )) \
  --es title 'Ayşe is heading to Migros' --es body 'Add anything in the next 10 min'"

# Shopper side: trip_update (§5.3) → ongoing notification / Live Update
adb shell "am broadcast -a com.fridgelist.app.debug.PUSH -n $PKG/com.fridgelist.app.debug.DebugPushReceiver \
  --es type trip_update --es householdId h-seed --es tripId t-me --es status shopping --es storeName Migros \
  --es remaining 7 --es done 5 --es nextItems '[\"milk\",\"bread\",\"eggs\"]' --es latestAddBy Mama --es latestAddName labneh"

# Is it a promoted Live Update? (Android 16 QPR1+)
adb shell dumpsys notification --noredact | grep -A8 "id=1001" | grep flags=
# expect: ...|PROMOTED_ONGOING|...
```

## Results so far (2026-10-19, emulator)

- API 36 (BE2A.250530): heading push → reply from the shade → "تمت الإضافة ✓"; Firestore got `ayran` qty 2 and `jam` with `addedVia: notification_reply`. Done on the ongoing notification cancelled it and queued `updateTrip(done)` in WorkManager. Not promoted (this build lacks the Live Update UI; `POST_PROMOTED_NOTIFICATIONS` not granted).
- API 36.1 (BE4B.251210): the same `trip_update` is posted with `PROMOTED_ONGOING`; status-bar chip "باقٍ 7", progress 5/12, Arabic text, Open/Done.
- Cross-phone timing (step 2): **not run yet**, needs the backend.
