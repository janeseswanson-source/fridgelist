# Attribution: store data

FridgeList's store database (`stores/`, used for "I'm going", nearby stores and store detection) is built from **OpenStreetMap** data. OpenStreetMap data is © OpenStreetMap contributors and available under the **Open Database License (ODbL) 1.0**: https://www.openstreetmap.org/copyright

## What we must show

ARCHITECTURE §5.1: the attribution appears **in each app's Settings** (an "About" or "Data sources" row) and **on the guest page** (`/k/{token}`, B7), linked to https://www.openstreetmap.org/copyright.

| Language | Text |
| --- | --- |
| English | Store data © OpenStreetMap contributors |
| Arabic | بيانات المتاجر © مساهمو OpenStreetMap |
| Turkish | Mağaza verileri © OpenStreetMap katkıda bulunanlar |

Keep "OpenStreetMap" in Latin letters in every language; it is the project's name.

## Status

| Surface | Status |
| --- | --- |
| Android Settings | to do (add with A10 localization / A11 release) |
| iOS Settings | to do (I12 / I13) |
| Guest page | to do (B7) |

## Obligations under the ODbL (summary, not legal advice)

- Attribute as above wherever the data is shown.
- `stores/` is a derived database: if we ever publish or hand out the database itself, it must be under the ODbL. Serving individual nearby stores inside the app is a "produced work" and needs the attribution only.
- User-added stores (`source: user`) are our users' contributions, not OSM data.
- Re-run the import monthly (`npm run import:stores`) so closed shops disappear (`--prune`).

The owner should confirm the final wording with their legal review before launch (CLAUDE.md rule 8).
