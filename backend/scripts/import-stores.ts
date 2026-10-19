/**
 * Builds `stores/` from OpenStreetMap (ARCHITECTURE §5.1). Dry run unless `--write`.
 *
 *   npm run import:stores -- --country AE                    # dry run: counts per chain
 *   npm run import:stores -- --country SA --write            # into the running emulators
 *   npm run import:stores -- --country AE --write --live <projectId> [--prune]   # owner only
 *   npm run import:stores -- --country TR --geojson tr-shops.geojson [--write ...]
 *
 * AE/SA come from the public Overpass API. Turkey is too large for it: see docs/RUNBOOK.md
 * (Geofabrik PBF → osmium tags-filter → osmium export → --geojson).
 * Writes are idempotent (`osm_<type>_<id>`, full overwrite) in batches of 400. `--prune` deletes
 * this country's `osm_` stores that are no longer in the import (monthly re-runs).
 */
import { readFileSync } from "node:fs";
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import type { Country } from "../functions/src/lib/chains.js";
import {
  fromGeoJson,
  fromOverpass,
  overpassQuery,
  type GeoJsonFeature,
  type OverpassElement,
  type StoreRecord,
} from "../functions/src/stores/osm.js";

const OVERPASS = "https://overpass-api.de/api/interpreter";
const USER_AGENT =
  "FridgeList store import (https://github.com/; contact via the app's support email)";

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
}
const flag = (name: string) => process.argv.includes(`--${name}`);

function parseCountry(value: string | undefined): Country {
  if (value === "AE" || value === "SA" || value === "TR") return value;
  throw new Error("--country AE|SA|TR is required");
}
const country: Country = parseCountry(arg("country"));
const geojson = arg("geojson");
const write = flag("write");
const live = arg("live");
const prune = flag("prune");

async function load(): Promise<{ records: StoreRecord[]; seen: number }> {
  if (geojson) {
    const text = readFileSync(geojson, "utf8");
    // `osmium export -f geojson` (one FeatureCollection) or `-f geojsonseq` (one feature per line,
    // optionally prefixed by the RS character, code 30).
    let features: GeoJsonFeature[];
    try {
      features = (JSON.parse(text) as { features: GeoJsonFeature[] }).features;
    } catch {
      const RS = String.fromCharCode(30);
      features = text
        .split("\n")
        .map((line) => line.split(RS).join("").trim())
        .filter((line) => line.length > 0)
        .map((line) => JSON.parse(line) as GeoJsonFeature);
    }
    return {
      records: features
        .map((f) => fromGeoJson(f, country))
        .filter((r): r is StoreRecord => r !== null),
      seen: features.length,
    };
  }
  if (country === "TR")
    throw new Error(
      "Turkey needs --geojson (public Overpass can't serve the whole country).",
    );
  process.stdout.write(`Querying Overpass for ${country}…\n`);
  // The public Overpass instance sheds load with 429/502/503/504: wait and retry (4 tries).
  let response: Response | undefined;
  for (let attempt = 1; attempt <= 4; attempt++) {
    response = await fetch(OVERPASS, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": USER_AGENT,
      },
      body: `data=${encodeURIComponent(overpassQuery(country))}`,
    });
    if (response.ok || ![429, 502, 503, 504].includes(response.status) || attempt === 4) break;
    const waitS = 30 * attempt;
    process.stdout.write(`Overpass HTTP ${String(response.status)}; retrying in ${String(waitS)} s…\n`);
    await new Promise((resolve) => setTimeout(resolve, waitS * 1000));
  }
  if (!response?.ok) throw new Error(`Overpass HTTP ${String(response?.status)}`);
  const elements = ((await response.json()) as { elements: OverpassElement[] })
    .elements;
  return {
    records: elements
      .map((e) => fromOverpass(e, country))
      .filter((r): r is StoreRecord => r !== null),
    seen: elements.length,
  };
}

const { records, seen } = await load();
const byChain = new Map<string, number>();
for (const r of records)
  byChain.set(r.doc.chainId, (byChain.get(r.doc.chainId) ?? 0) + 1);
process.stdout.write(
  `${country}: ${String(seen)} OSM shops, ${String(records.length)} named stores, ${String(seen - records.length)} skipped (unnamed or no position)\n` +
    [...byChain.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([c, n]) => `  ${c.padEnd(14)} ${String(n)}`)
      .join("\n") +
    "\n",
);

if (!write) {
  process.stdout.write(
    "Dry run: nothing written. Add --write (emulators) or --write --live <projectId>.\n",
  );
} else {
  if (live) {
    if (process.env.FIRESTORE_EMULATOR_HOST)
      throw new Error(
        "--live with FIRESTORE_EMULATOR_HOST set: unset it first.",
      );
    initializeApp({ projectId: live });
  } else {
    process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
    initializeApp({ projectId: "demo-fridgelist" });
  }
  const db = getFirestore();
  for (let i = 0; i < records.length; i += 400) {
    const batch = db.batch();
    for (const r of records.slice(i, i + 400)) {
      batch.set(db.doc(`stores/${r.storeId}`), {
        ...r.doc,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
    await batch.commit();
  }
  let pruned = 0;
  if (prune) {
    const keep = new Set(records.map((r) => r.storeId));
    const existing = await db
      .collection("stores")
      .where("country", "==", country)
      .where("source", "==", "osm")
      .select()
      .get();
    const stale = existing.docs.filter((d) => !keep.has(d.id));
    for (let i = 0; i < stale.length; i += 400) {
      const batch = db.batch();
      stale.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
      await batch.commit();
    }
    pruned = stale.length;
  }
  process.stdout.write(
    `Wrote ${String(records.length)} stores${live ? ` to ${live}` : " to the emulators"}; pruned ${String(pruned)}.\n`,
  );
}
