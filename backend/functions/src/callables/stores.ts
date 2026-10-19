import { FieldValue, getFirestore, type Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { distanceBetween, geohashForLocation, geohashQueryBounds } from "geofire-common";
import { z } from "zod";
import { CHAIN_IDS } from "../lib/chains.js";
import { log } from "../lib/log.js";
import { matchKey } from "../lib/normalize.js";
import { consume } from "../lib/rateLimit.js";
import { parse, requireUid } from "../lib/requests.js";
import { countryOf, type StoreDoc } from "../stores/osm.js";

const lat = z.number().min(-90).max(90);
const lng = z.number().min(-180).max(180);

/** CONTRACT §4 `nearbyStores`: radius default 5000 (max 10000), limit default 60 (max 100). */
export const NearbyStoresRequest = z.object({
  lat,
  lng,
  radiusM: z.number().int().positive().max(10_000).optional(),
  limit: z.number().int().positive().max(100).optional(),
});

export interface NearbyStore {
  storeId: string;
  name: string;
  chainId: string;
  lat: number;
  lng: number;
  distanceM: number;
}

/** The caller's households: unverified user-added stores show up only there (ARCHITECTURE §5.1). */
async function householdsOf(db: Firestore, uid: string): Promise<string[]> {
  const ids: unknown = (await db.doc(`users/${uid}`).get()).get("householdIds");
  return Array.isArray(ids) ? ids.filter((x): x is string => typeof x === "string") : [];
}

/**
 * Geohash range queries around the point, exact distance filter, nearest first. The location is
 * used for this one answer only: never stored, never logged (CLAUDE.md rule 4).
 */
export async function nearbyStoresCore(
  db: Firestore,
  uid: string,
  data: unknown,
): Promise<{ stores: NearbyStore[] }> {
  const input = parse(NearbyStoresRequest, data);
  const radiusM = input.radiusM ?? 5_000;
  const limit = input.limit ?? 60;
  const center: [number, number] = [input.lat, input.lng];
  const [households, snapshots] = await Promise.all([
    householdsOf(db, uid),
    Promise.all(
      geohashQueryBounds(center, radiusM).map(([start, end]) =>
        db.collection("stores").orderBy("geohash").startAt(start).endAt(end).get(),
      ),
    ),
  ]);
  const seen = new Set<string>();
  const stores: NearbyStore[] = [];
  for (const snap of snapshots) {
    for (const doc of snap.docs) {
      if (seen.has(doc.id)) continue;
      seen.add(doc.id);
      const s = doc.data() as StoreDoc;
      if (!s.verified && !(s.addedByHousehold && households.includes(s.addedByHousehold))) continue;
      const distanceM = distanceBetween([s.lat, s.lng], center) * 1000;
      if (distanceM > radiusM) continue;
      stores.push({
        storeId: doc.id,
        name: s.name,
        chainId: s.chainId,
        lat: s.lat,
        lng: s.lng,
        distanceM: Math.round(distanceM),
      });
    }
  }
  stores.sort((a, b) => a.distanceM - b.distanceM || a.storeId.localeCompare(b.storeId));
  log.info("nearby_stores", { results: Math.min(stores.length, limit), radiusM });
  return { stores: stores.slice(0, limit) };
}

export const AddStoreRequest = z.object({
  name: z.string().trim().min(1).max(80),
  lat,
  lng,
  chainId: z.enum(CHAIN_IDS as [string, ...string[]]).optional(),
});

/** Within this distance, a store with the same name is the same store. */
const SAME_STORE_M = 50;

/**
 * CONTRACT §4 `addStore`: `user_<autoId>`, `verified: false`, visible at once to the adder's
 * household (B1–B5: the first of `users.householdIds`). The same name within 50 m returns the
 * existing store instead of a duplicate.
 */
export async function addStoreCore(db: Firestore, uid: string, data: unknown): Promise<{ storeId: string }> {
  const input = parse(AddStoreRequest, data);
  const country = countryOf(input.lat, input.lng);
  if (country === null) throw new HttpsError("invalid-argument", "Invalid: location outside TR, SA, AE");
  const [householdId] = await householdsOf(db, uid);
  if (!householdId) throw new HttpsError("failed-precondition", "Join or create a household first.");
  await consume(db, "addStore", uid);

  const nearby = await nearbyStoresCore(db, uid, {
    lat: input.lat,
    lng: input.lng,
    radiusM: SAME_STORE_M,
    limit: 20,
  });
  const key = matchKey(input.name);
  const same = nearby.stores.find((s) => matchKey(s.name) === key);
  if (same) return { storeId: same.storeId };

  const ref = db.collection("stores").doc();
  const storeId = `user_${ref.id}`;
  const doc: StoreDoc = {
    name: input.name,
    chainId: input.chainId ?? "other",
    lat: input.lat,
    lng: input.lng,
    geohash: geohashForLocation([input.lat, input.lng], 9),
    country,
    source: "user",
    verified: false,
    addedByHousehold: householdId,
  };
  await db.doc(`stores/${storeId}`).set({ ...doc, updatedAt: FieldValue.serverTimestamp() });
  log.info("store_added", { country, chainId: doc.chainId });
  return { storeId };
}

export const nearbyStores = onCall((request) =>
  nearbyStoresCore(getFirestore(), requireUid(request), request.data),
);
export const addStore = onCall((request) => addStoreCore(getFirestore(), requireUid(request), request.data));
