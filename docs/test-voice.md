# Voice add: manual test (Android)

Prompt A4 asks for a manual script with Arabic, Turkish and English phrases from `fixtures/parse_cases.json`. Run it on **real phones**: the Android emulator gets no microphone audio, so recognizers there only ever report "no speech".

## Setup

- A dev build signed in to a household (see `docs/PROGRESS.md`, A3 entry, for seeding the emulator backend), or a prod build once Firebase is configured.
- Phone A: Android 12+ (on-device recognition). Phone B: Android 8–11, or a phone without Google's speech service (exercises the online recognizer or the cloud fallback).
- Note the item language in the profile; recognition uses it with the phone's region (`ar` on an Egyptian phone → `ar-EG`, otherwise `ar-SA`, `tr-TR`, `en-US`, `fil-PH`, `hi-IN`, `ur-PK`).

## Each run

1. Open a list → **Add** → tap the mic.
2. First time only: the explanation appears, then the system prompt. Choose "While using the app".
3. Say the phrase. The panel shows the live transcript.
4. Tap **Stop** (or stop talking). The text field fills in and chips appear.
5. Check the chips against "Expect", then **Add** and check the items' quantity and unit in the list.
6. In Firestore (emulator UI or console) check the new items have `addedVia: "app_voice"`.

| # | Item language | Say | Expect (chips) |
| --- | --- | --- | --- |
| 1 | ar (Levant phone) | جيب ٢ كيلو بندورة وعلبة لبنة وحليب | بندورة 2 kg · لبنة 1 pack · حليب |
| 2 | ar (Saudi phone) | نبي طماطم وخيار وخمسة كيلو رز بسمتي | طماطم · خيار · رز بسمتي 5 kg |
| 3 | ar | كيلو ونص لحمة مفرومة | لحمة مفرومة 1.5 kg |
| 4 | ar | كرتونة بيض وربطة بقدونس | بيض 1 tray · بقدونس 1 bunch |
| 5 | ar | نص كيلو جبنة حلوم | جبنة حلوم 0.5 kg |
| 6 | ar (Saudi phone) | عيش | one chip that asks خبز or رز; pick رز, say عيش again → no question |
| 7 | tr | iki ekmek, yarım kilo beyaz peynir ve süt | ekmek 2 · beyaz peynir 0.5 kg · süt |
| 8 | tr | bir buçuk kilo pirinç ve iki paket makarna | pirinç 1.5 kg · makarna 2 pack |
| 9 | tr | üç kutu ton balığı | ton balığı 3 can |
| 10 | en | milk, six eggs and half a dozen lemons | milk · eggs 6 · lemons 6 piece |
| 11 | en | sugar one kilo and olive oil | sugar 1 kg · olive oil |

Recognizers write numbers as digits or words depending on the engine; the splitter accepts both (`٢`, `2`, `two`, `iki`, `اتنين`).

## Fallbacks and errors

| Case | How | Expect |
| --- | --- | --- |
| Language pack missing on device | Phone A with no offline pack for the language | Falls through to the online recognizer silently (logcat `Speech: recognizer error 12 (onDevice=true)`, then the online one listens) |
| Offline, no on-device pack | Airplane mode + no offline pack | Message "Voice needs the internet on this phone. You can type instead."; typing still adds |
| Cloud fallback | Phone without any recognizer | "Recording… tap Stop when you're done." → Stop → "Writing down what you said…" → chips. Needs backend `transcribeAudio`. Auto-stops at 15 s |
| Silence | Tap mic, say nothing | "Didn't catch that. Try again." |
| Permission denied | "Don't allow" in the system prompt | Nothing records; tapping the mic again shows the explanation; typing works |
| Close the sheet while listening | Swipe the sheet down | Listening stops; no audio file left in the app cache |

## Privacy check

- The mic is on only while the panel says "Listening" or "Recording".
- `adb logcat | grep -E "Speech|VoiceRecorder|Callable"` shows codes only, never what was said.
- Cloud-fallback audio is deleted from the cache as soon as it is read.
