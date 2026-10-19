# Widgets, tile and shortcuts on the Android emulator (A8)

Last passed: 2026-10-19 on `fridgelist_api36_1` (API 36) with the dev emulators and the seed household (Mama, Arabic).

## Setup

```sh
cd backend && npm run emulators                         # seeds household seed-home
cd android && ./gradlew installDevDebug
P=com.example.fridgelist.dev
adb shell appwidget grantbind --package $P               # lets the dev preview host bind widgets
adb shell am start -n $P/com.fridgelist.app.MainActivity # sign in as Mama; the app writes the snapshot
```

**Widget preview host (dev flavor only):** `adb shell am start -n $P/com.fridgelist.app.debug.WidgetPreviewActivity` shows the widget at 2×2, 4×2 and 4×4, the way a launcher hosts it. On a real home screen, long-press › Widgets › FridgeList › "Shopping list" (pick a list, or skip to use the first).

## Steps

| # | Do | Expect |
| --- | --- | --- |
| 1 | Open the preview host | 2×2: count + the next two; 4×2: header + checklist; 4×4: header + up to 12 items, in the app's order (category, urgent first). Arabic is right-to-left; mixed Arabic names and Latin units stay in order ("حليب · 2 L") |
| 2 | Tick the first item in the 4×4 | it leaves all three sizes at once; the item is `done` in Firestore (`doneBy` = Mama) in **0.7 s** (target ≤ 10 s, F8) |
| 3 | As another member, add an item (Firestore), then simulate the push: `adb shell am broadcast -a com.fridgelist.app.debug.PUSH -n $P/com.fridgelist.app.debug.DebugPushReceiver --es type list_changed --es householdId seed-home --es listId groceries` | `WidgetRefreshWorker` runs and the count goes up, without opening the app |
| 4 | Tap **+** on a widget | the transparent Quick Add sheet opens over the home screen, voice first; add "2 yogurt" → item with `addedVia: widget`, `qty: 2`; the widgets update |
| 5 | `adb shell cmd statusbar add-tile $P/com.fridgelist.app.quickadd.QuickAddTileService`, then `click-tile` with the same component | Quick Add opens (`addedVia: qs_tile`) |
| 6 | Long-press the app icon | "Add items" and "I'm going", plus one shortcut per list ("Add to البقالة"). `adb shell am start -a com.fridgelist.app.action.GOING -n $P/com.fridgelist.app.MainActivity` opens the first list with the "I'm going" sheet |
| 7 | Settings › Sign out | the widgets show "Open FridgeList to set up your list"; the per-list shortcuts are gone |

Screenshots (all three sizes, ar/tr/en, light and dark): `docs/screenshots/widget_*.png`. Capture again with `adb shell cmd locale set-app-locales $P --locales <ar|tr|en>` and `adb shell cmd uimode night <yes|no>`, then reopen the preview host.
