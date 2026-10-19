# Background location declaration (Android, A11)

Play Console › App content › **Sensitive app permissions › Location permissions**. FridgeList asks for `ACCESS_BACKGROUND_LOCATION` only when the user turns on **Settings › Store detection**.

## The core feature (paste into "Which feature in your app uses location in the background?")

> FridgeList is a shared shopping list for a household. With "Detect stores automatically" turned on, the app notices when the user has spent two minutes inside a supermarket while their list has items. It then opens Shopping Mode and tells the household which store they are shopping at, so others can add last-minute items. It also asks "Done shopping?" when they leave. This works through geofences around nearby supermarkets, which needs location access while the app is closed. The feature is off by default; without background location the user can start shopping by hand.

## Why foreground location isn't enough

> Shoppers walk into a store with the phone in a pocket and the app closed. Geofence dwell and exit events are delivered while the app is in the background, so the feature cannot work with foreground-only access.

## Prominent disclosure (shown in the app before the system prompt)

English, as shipped (`detect_disclosure_text`), followed by the steps to choose "Allow all the time":

> FridgeList collects location data to detect when you arrive at a supermarket, even when the app is closed or not in use. Your exact location stays on this phone; only a rounded area (about 1 km) is sent to find stores near you, and it is not stored.

Arabic and Turkish versions are in `values-ar` and `values-tr` (`strings_detect.xml`).

## Video (30 seconds; Play asks for a link, for example unlisted YouTube)

Record on a phone in English, with captions in Arabic if possible.

| Time | Show | Caption |
| --- | --- | --- |
| 0–4 s | Home with a list of 5 items | "FridgeList: a shared shopping list" |
| 4–9 s | Settings › Store detection › turn on "Detect stores automatically" | "Store detection is off until you turn it on" |
| 9–13 s | The rationale dialog, then the precise-location prompt › Allow | "First we explain, then Android asks" |
| 13–19 s | **The prominent disclosure dialog** (pause on it), then "Allow all the time" in system settings | "Background location: why, and what stays on the phone" |
| 19–24 s | Lock the phone, walk into a supermarket (or the dev debug screen › Dwell on the emulator) | "Two minutes in a supermarket…" |
| 24–28 s | The "At Migros · 5 items" notification › Open list › Shopping Mode | "…opens your list and tells your household" |
| 28–30 s | Settings › turn detection off | "Turn it off any time" |

## Checklist before submitting

- [ ] The video shows the prominent disclosure **before** the system permission prompt.
- [ ] The store listing mentions store detection (see `store-listing-android.md`).
- [ ] The privacy policy (`PRIVACY_POLICY_URL`) describes location use the same way.
- [ ] Data safety: Approximate location declared (see `play-data-safety.md`).
