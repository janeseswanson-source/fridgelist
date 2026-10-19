# Store detection on the Android emulator (A7)

A walkthrough of the full path: fence registration, a real 2-minute `DWELL`, AUTO_ARRIVED, the auto trip on the server, AUTO_LEFT and the 3 km refresh. It uses `adb emu geo fix` and the seeded stores. Last passed: 2026-10-19 on `fridgelist_api36_1` (API 36).

## Setup

1. Start the backend: `cd backend && npm run emulators` (it seeds household `seed-home` and the stores around Kadıköy, Olaya and Dubai Marina).
2. Start an emulator **with Google Play services** and install the dev build: `cd android && ./gradlew installDevDebug`.
3. Sign in with the dev test sign-in as **Mama** (`mama`). The grocery list needs at least one open item (the seed has 11).
4. Grant the permissions. You can use the in-app flow (Settings › Store detection: rationale → precise → disclosure → "Allow all the time"), or adb:

   ```sh
   P=com.example.fridgelist.dev
   for p in ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $P android.permission.$p; done
   ```

5. Feed GPS continuously, as a real phone would. Geofencing ignores a single fix. Keep this running in a second terminal and change the position by editing the file:

   ```sh
   echo "29.0290 41.0000" > /tmp/geo.txt            # lng lat: ~1 km north of the Kadıköy stores
   while true; do adb emu geo fix $(cat /tmp/geo.txt); sleep 3; done
   ```

6. **Keep GPS active.** On an emulator nothing else asks for location, so the positions never reach the geofencing engine. Android also drops a background app's own GPS request as soon as it leaves the screen. Open **Google Maps** (skip sign-in, allow location) and leave it in the foreground. FridgeList stays in the background, as on a real phone. The debug screen's "Keep GPS on" works only while FridgeList is on screen.

## Steps

| # | Do | Expect |
| --- | --- | --- |
| 1 | Settings › turn on **Detect stores automatically**. In Developer › **Store detection debug**, tap **Refresh fences now** | "Store fences: 10 · refresh fence at 41.0000, 29.0290", "Last result: REGISTERED"; logcat `StoreFences: registered 10 store fences` |
| 2 | Home button, open Maps. `echo "29.0311 40.9915" > /tmp/geo.txt` (Migros) | after about 2 min: logcat `StoreDetection: arrive: Fire`; notifications "في Migros · 11 صنفاً" (Open list / Not shopping) and the ongoing trip card "في Migros"; Firestore `households/seed-home/trips/*` has `auto: true`, `status: shopping`, `storeName: Migros` |
| 3 | `echo "29.0290 41.0000" > /tmp/geo.txt` | within seconds: "انتهيت من التسوق؟ · غادرت Migros." (Done shopping / Still here) |
| 4 | Tap **Done** (on AUTO_LEFT or on the trip card) | trip `status: done` with `endedAt`; AUTO_ARRIVED and AUTO_LEFT are cleared |
| 5 | `echo "29.0273 40.9927" > /tmp/geo.txt` (A101), wait 2 min, then **Not shopping** on AUTO_ARRIVED (expand it first) | new auto trip, then `status: cancelled`; every FridgeList notification is gone |
| 6 | Same store again within 3 h | nothing (`arrive: Skipped(reason=COOLDOWN)`) |
| 7 | `echo "46.6850 24.6956" > /tmp/geo.txt` (Olaya, Riyadh: far outside the 3 km circle) | within seconds: `StoreFences: registered 10 store fences`; Settings lists Panda, Danube, Tamimi… |
| 8 | Settings › **Mute** a store, then Unmute it | muted stores drop out of the fences (the count goes down) and come back after Unmute |
| 9 | `adb reboot`, then start the GPS feed and Maps again | `LocationStateReceiver` re-registers the saved fences (debug screen shows them) |

Without the dwell (fast checks), the debug screen's **Dwell** and **Exit** buttons run the same rules and notifications for any watched store.

## What is never sent

The watched stores, the refresh point and the cooldowns live in the `detection` DataStore on the phone. The server only receives `startTrip(storeId, storeName, auto: true)` (CLAUDE.md rule 4). Logs carry decisions and counts, never names.
