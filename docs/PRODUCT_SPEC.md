# Product spec — FridgeList (working name)

## 1. In one line

A shared household grocery list for Turkey, Saudi Arabia and the UAE that anyone at home can add to in seconds — by voice, a fridge tag, a widget or a notification reply — and that opens by itself when someone walks into a supermarket.

## 2. Markets, platforms, languages, launch

- **Markets:** Turkey, Saudi Arabia, UAE. All three are Android-majority (StatCounter, mid-2026: Android ~74% Turkey, ~69% Saudi Arabia, ~78% UAE), so Android quality matters as much as iOS.
- **Platforms:** native Android (Kotlin, Jetpack Compose) and native iOS (Swift, SwiftUI), built in parallel on one Firebase backend and one data contract (`docs/CONTRACT.md`).
- **UI languages:** Arabic (right-to-left), Turkish, English.
- **Item display languages:** Arabic, Turkish, English, plus Filipino, Hindi and Urdu for household helpers (UI falls back to English for those).
- **Launch target:** public launch in the week of **25 Jan 2027**, two weeks before Ramadan (expected to start **8 Feb 2027**, subject to moon sighting).

## 3. Users and roles

| Role | Who | Can |
| --- | --- | --- |
| `owner` | created the household | everything, including members, roles, deleting the household |
| `adult` | partner, older children, relatives | add, edit, shop, approve kids' requests, invite, make fridge tags |
| `kid` | younger children | request items (they wait for approval), see the list |
| `helper` | household staff, drivers, relatives who shop | add, shop, tick off; see items in their own language; no member management |
| guest | anyone with a fridge-tag or QR link, no account | view, copy, share; add if the link allows it |

## 4. The core loop

**Add → Share live → Coordinate ("I'm going") → Remind at the store → Shop → Close out → Learn.**

The MVP covers every step except "Learn", which arrives in phase 2 (usuals and learned aisle order).

## 5. MVP scope — must ship before Ramadan

Each feature lists acceptance criteria the apps and backend are tested against.

### F1 — Households and roles
- Create a household in under a minute: name, default language, your display name and item language.
- Invite by link, code, QR or "tap to join" fridge tag; roles per §3.
- Done when: two people on different platforms join the same household and see each other in the members list with the right roles.

### F2 — Lists and items
- Several lists per household (groceries by default; pharmacy and household optional).
- Items show title in the viewer's item language, the original underneath when different, quantity and unit, urgent flag, who asked.
- Swipe or long-press: claim, done, couldn't find, edit, delete; every destructive action has undo.
- Adding something already on the list merges it (quantities add up) instead of duplicating.
- Kids' items wait in a "Requests" section until an adult approves.
- Done when: all of the above work **offline** and sync within 5 s of reconnecting.

### F3 — Fast add (text and voice)
- One text line can hold several items in any mix of Arabic, Turkish and English: "2 milk, bread و بيض", "yarım kilo beyaz peynir ve süt".
- Mic button: live transcript in the member's language; on stop, items appear as editable chips; confirm saves them.
- Understands Eastern Arabic digits (٢), Turkish decimals (1,5), fractions (نص، ونص، yarım, buçuk, half), units (كيلو، علبة، ربطة، كرتونة، paket, kutu, tane, kg, L) and dialect words (بندورة/طماطم; عيش is bread in Egypt and usually rice in the Gulf; لبن is milk, yogurt or the yogurt drink depending on the country).
- When a word is still ambiguous, the chip offers both meanings; the household's choice is remembered, so it asks only once.
- Offline: falls back to a local splitter and saves; the server fills category and translations later.
- Done when: the backend parser passes ≥ 90% of `fixtures/parse_cases.json`, and add-to-saved takes under 3 s for typed input on Wi-Fi.

### F4 — Everyone reads the list in their own language
- Each member picks an item language. Titles show in that language with the original in small text when different.
- Translations arrive within 5 s of adding (online).
- Done when: an item added in Arabic shows in Turkish to a Turkish member and in Filipino to a helper, original visible.

### F5 — Store detection and Shopping Mode
- With background location allowed, walking into a supermarket from the store database (OpenStreetMap: Migros, A101, BİM, ŞOK, CarrefourSA, Panda, Tamimi, Danube, Othaim, Lulu, Carrefour, Spinneys, Union Coop and others) with at least one open item triggers within about 2 minutes a notification "At Migros · 9 items" that opens Shopping Mode.
- No trigger when driving past (iOS: motion check; Android: 2-minute dwell). At most one trigger per store per 3 hours. Users can mute a store and add a missing one.
- Without background location: a manual "I'm shopping" button with nearby-store suggestions does the same.
- Shopping Mode: sorted by aisle category, big tap targets, screen stays on, works offline, "couldn't find" notifies whoever asked, "Done" shows a summary (bought, missing → stays on the list, share the rest to a delivery app).
- Done when: the field-test script in `docs/` passes in at least one store per country.

### F6 — "I'm going" and last call
- "I'm going" → pick the store (nearest five suggested) and a last-call window (0, 5, 10 or 15 minutes).
- Other members get a notification with an inline reply box; whatever they type lands on the list and on the shopper's lock screen within 5 s.
- Done when: a reply from an Android lock screen appears on an iPhone shopper's Live Activity, and the reverse.

### F7 — Live shopping on the lock screen
- **iOS:** Live Activity and Dynamic Island: store, remaining and done counts, next 3 items, last-call countdown, "✓ next item" and "Done" buttons that work without unlocking. Auto-detected trips start it by push.
- **Android:** ongoing trip notification with progress; on Android 16+ promoted to a Live Update (status-bar chip, lock-screen card). Actions: Open, Done.
- Ends when the trip ends or after 4 hours.

### F8 — Widgets and one-press add
- **iOS:** home-screen widgets (small, medium, large) with tappable checkboxes; lock-screen widgets (count, next 2 items); on iOS 18+ a "Quick add" control for Control Center, the Lock Screen and the Action button.
- **Android:** resizable widget with checkboxes and a + button; Quick Settings tile "Add to list"; long-press app shortcuts.
- Siri / App Shortcuts: "Add to FridgeList" in Arabic, Turkish and English.
- Done when: ticking an item on a widget updates other members' phones within 10 s.

### F9 — Fridge tag and guest page
- "Make a fridge tag": the app writes a link onto any blank NFC sticker (NTAG213/215/216) and shows a printable QR code. No custom hardware.
- A member taps the tag: Quick Add opens, ready for voice. Anyone else: the guest page opens (view, copy, share, add if allowed), no app needed.
- Owners and adults can revoke a tag; guest adds show as "Guest" and are rate-limited.
- Done when: an iPhone and an Android phone both open the right screen from the same tag.

### F10 — Notifications
- Channels/categories: trip in progress, last call, household activity, suggestions. Per-member toggles. Quiet by default: only "heading out", "couldn't find" and your own store arrivals notify.

### F11 — Ramadan planner lite (by 1 Feb 2027, behind `ramadan_planner_enabled`)
- Shown from 1 Sha'ban to the end of Ramadan (Umm al-Qura calendar).
- "Plan iftar": number of people (2–30) and days (1–7) → 6–10 dish suggestions tagged Levantine, Gulf, Turkish or Egyptian (lentil soup, fattoush, kabsa, mansaf, Ramazan pidesi, güllaç, qatayef and so on) → choose → ingredients scaled, minus what's already on the list or bought in the last 7 days → confirm → added.
- Done when: a 6-person, 3-day plan produces a sensible, deduplicated list in Arabic and Turkish.

### F12 — Settings, privacy, accounts
- Item language, UI language (links to system settings where required), notifications, store detection on/off, muted stores, numbers as ١٢٣ or 123.
- In-app account deletion (required by both stores), data export, privacy policy, "Store data © OpenStreetMap contributors".

## 6. What makes it stand out

Checked against AnyList, OurGroceries, Bring!, Listonic, Apple Reminders and Samsung Food in October 2026.

| Feature | Why it matters | What competitors do |
| --- | --- | --- |
| Zero-setup store detection for local chains | Works at any Migros, BİM, Panda or Lulu without pinning locations | AnyList and Buy Me a Pie need locations added by hand; Apple Reminders sets them per reminder |
| "I'm going" + last call with inline reply | Ends the "can you also get…" phone calls and duplicate buys | Not common in list apps checked |
| Lock-screen live shopping (Live Activity, Android 16 Live Updates) | See and tick items without unlocking | Rare in list apps |
| Everyone reads items in their own language | Helpers and relatives buy the right thing | Not found in apps checked |
| Dialect-smart voice and text | How people in the region actually talk and type | Generic speech input |
| DIY fridge tag + guest page | A physical "button" on the fridge for the price of a sticker, works without the app | Hiku sold $49–79 hardware and shut down |
| One-press add (Action button, Control Center, Quick Settings tile) | Capture the moment you notice | Partial |
| Ramadan and occasions planner | The region's biggest grocery season | Not found |

## 7. Phase 2 — after launch

| Feature | Notes |
| --- | --- |
| WhatsApp bridge | "add bread, 2 milk", voice notes, "list", "got milk". Start Meta Business verification early; it takes time. |
| Usuals | Run-out predictions from purchase history; "Running low?" row and widget section. |
| Learned aisle order | Per-branch category order learned from anonymous check-off sequences (minimum 5 trips). |
| Photo to list | Photo of a handwritten list, a WhatsApp screenshot or a shelf → items. |
| Receipt scan and price memory | Spend per trip; especially useful with Turkish inflation. |
| Premium plan | $2.99/month or $19.99/year (Gulf), lower Turkish price; AI meals, usuals, price memory. |
| Watch apps | Apple Watch and Wear OS tick-off. |
| Kitchen display mode | An old tablet as the fridge screen. |

## 8. Not now

Custom hardware, family calendar and chores, in-fridge cameras, nutrition tracking, delivery-app integrations (until there are partner deals).

## 9. UX principles

1. Add an item in under 3 seconds from anywhere: widget, control, tile, tag, notification, Siri.
2. Shopping Mode works one-handed and offline.
3. Arabic first: design and screenshot every screen in Arabic (RTL) before Turkish and English.
4. Never lose or duplicate an item: offline queue, undo everywhere, merge on add.
5. Location stays on the phone; the server only learns "trip started at store X".
6. Quiet by default: notify only when someone can act on it.

## 10. Beta success metrics

| Metric | Target |
| --- | --- |
| Households active weekly after 4 weeks | ≥ 40% |
| Median time from tap to saved item | < 3 s |
| Trips started by detection or "I'm going" | ≥ 60% |
| Detections dismissed as wrong store | < 10% |
| Crash-free sessions | ≥ 99.5% |
