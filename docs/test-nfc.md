# Fridge tags, QR codes and links (A9)

Two parts:
- **Emulator (no NFC hardware):** QR, links and the NFC intent path. Passed 2026-10-19.
- **Real phone with NTAG213, NTAG215 and NTAG216 stickers:** writing tags. That part needs you (A9's "done when").

## Emulator (passed)

Setup as in `docs/test-widgets.md` (dev emulators, signed in as Mama, Arabic).

| # | Do | Expect |
| --- | --- | --- |
| 1 | List "البقالة" › the QR icon | "ملصق الثلاجة وQR": the QR for a new `kind: qr`, `scope: add` link; Share (PNG via the share sheet) and Print (A4 PDF with the list name). No NFC section on a phone without NFC |
| 2 | Turn off "Guests can add items" | a new `scope: view` QR; turning it back on reuses the first link |
| 3 | `adb shell am start -a android.intent.action.VIEW -d https://links.example.com/k/<that token> <pkg>` | Mama is a member, so Quick Add opens for that list, voice ready (CONTRACT v5 `member`) |
| 4 | Create another household with a share link (`scratchpad/neighbour.py` in the session; any `shareLinks` doc works), then `adb shell am start -a android.nfc.action.NDEF_DISCOVERED -d https://links.example.com/k/<token> <pkg>` | the in-app guest view: "Market · Komşu Evi" with its items; add "2 ayran" with a name → "أُضيف صنف واحد ✓", and the item appears with `addedBy: guest:<token>`, `addedVia: guest_web` |
| 5 | `…/l/seed-home/pharmacy` | the pharmacy list opens |
| 6 | `…/l/<another household>/<list>` | the "This list belongs to another household" toast, nothing opens |

## Real phone (owner)

You'll need an Android phone with NFC and one each of NTAG213, NTAG215 and NTAG216 stickers, blank or previously written (never locked).

| # | Do | Expect |
| --- | --- | --- |
| 1 | List › QR icon › **Write a fridge tag**, hold an **NTAG213** to the back of the phone | "Tag ready. Tapping it opens البقالة." The tag holds one NDEF URI record (check with NXP TagInfo: no Android Application Record, not read-only) |
| 2 | Repeat with **NTAG215** and **NTAG216** | the same |
| 3 | Lock the screen, wake it, tap a tag | FridgeList opens Quick Add for that list (members) |
| 4 | Tap the tag with a phone **without** the app (or another account) | the browser opens the guest page `https://<LINK_DOMAIN>/k/…` |
| 5 | Hold a locked or non-NDEF card (e.g. a transit card) | "This sticker is locked…" or "The phone can't write this kind of tag." The screen stays ready for another sticker |
| 6 | Move the sticker away mid-write | "Keep the sticker still against the phone and try again." |
| 7 | Turn NFC off in system settings | the screen shows "Turn on NFC to write a tag" with Open NFC settings |

## App Links verification (after LINK_DOMAIN is live)

`backend/hosting/signing.json` holds this dev machine's debug SHA-256. Add the release upload key and the Play app-signing key there, deploy hosting, then:

```sh
adb shell pm verify-app-links --re-verify com.example.fridgelist.dev
adb shell pm get-app-links com.example.fridgelist.dev     # LINK_DOMAIN: verified
```
